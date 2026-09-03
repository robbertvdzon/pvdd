package nl.vdzon.pvdd.dashboard

import java.net.URI
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class MeetingOverviewDto(
    val status: String,
    val meeting: MeetingDto?,
    val lastCheckedAt: Instant?,
    val progress: ProgressDto,
)

data class MeetingDto(
    val id: UUID,
    val sourceId: String,
    val title: String,
    val committee: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val location: String?,
    val sourceUrl: URI,
    val status: String,
    val publicationStatus: String,
    val revisionNumber: Int,
    val canonicalFingerprint: String?,
    val revisionStatus: String?,
)

data class ProgressDto(val total: Int, val complete: Int, val failed: Int)

data class AgendaItemSummaryDto(
    val id: UUID,
    val sequence: Int,
    val displayNumber: String?,
    val category: String,
    val title: String,
    val substantive: Boolean,
    val importStatus: String,
    val analysisStatus: String?,
    val sourceState: String,
    val currentFingerprint: String?,
    val adviceActuality: String?,
    val changeTypes: List<String>,
    val lastDetectedChangeAt: Instant?,
    val displayTitle: String?,
    val shortConclusion: String?,
    val lastAnalysisRun: AnalysisRunDto?,
    val canRetryAnalysis: Boolean,
)

data class SourceLinkDto(val name: String, val url: URI, val status: String)

data class AgendaItemDetailDto(
    val item: AgendaItemSummaryDto,
    val explanation: String?,
    val treatmentProposal: String?,
    val sourceUrl: URI,
    val advice: JsonNode?,
    val adviceActuality: String?,
    val sources: List<SourceLinkDto>,
    val warning: String = "AI-concept — controleer bronnen en formulering vóór gebruik",
)

data class AnalysisRunDto(
    val id: UUID,
    val agendaItemId: UUID,
    val status: String,
    val errorCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

@Repository
class DashboardRepository(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun overview(): MeetingOverviewDto {
        val meeting = jdbc.query(
            """
            SELECT m.id, m.source_id, m.title, m.committee, m.starts_at, m.ends_at, m.location,
                   m.source_url, m.status, m.checked_at, m.publication_status,
                   m.current_revision_number, m.canonical_fingerprint, latest.revision_status
            FROM meeting m
            LEFT JOIN LATERAL (
                SELECT revision_status FROM meeting_revision mr
                WHERE mr.meeting_id = m.id ORDER BY revision_number DESC LIMIT 1
            ) latest ON TRUE
            ORDER BY CASE WHEN starts_at >= CURRENT_TIMESTAMP THEN 0 ELSE 1 END, starts_at ASC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                MeetingDto(
                    rs.getObject("id", UUID::class.java), rs.getString("source_id"), rs.getString("title"),
                    rs.getString("committee"), rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("ends_at")?.toInstant(), rs.getString("location"),
                    URI(rs.getString("source_url")), rs.getString("status"),
                    rs.getString("publication_status"), rs.getInt("current_revision_number"),
                    rs.getString("canonical_fingerprint"), rs.getString("revision_status"),
                ) to rs.getTimestamp("checked_at").toInstant()
            },
        ).singleOrNull() ?: return MeetingOverviewDto("NO_MEETING", null, null, ProgressDto(0, 0, 0))
        val progress = progress(meeting.first.id)
        return MeetingOverviewDto(meeting.first.status, meeting.first, meeting.second, progress)
    }

    fun agendaItems(meetingId: UUID): List<AgendaItemSummaryDto>? {
        if (!exists("meeting", meetingId)) return null
        return jdbc.query(
            """
            SELECT ai.id, ai.sequence_number, ai.display_number, ai.category, ai.title, ai.substantive,
                   ai.import_status, ai.source_state, ai.current_fingerprint,
                   latest.status AS analysis_status, advice.actuality AS advice_actuality,
                   revision.difference_types,
                   CASE WHEN cardinality(revision.difference_types) > 0 THEN revision.created_at END last_detected_change_at,
                   advice.advice->>'displayTitle' display_title,
                   advice.advice->>'shortConclusion' short_conclusion,
                   latest.id latest_run_id, latest.error_code latest_error_code,
                   latest.created_at latest_created_at, latest.updated_at latest_updated_at,
                   latest.completed_at latest_completed_at,
                   (m.starts_at > CURRENT_TIMESTAMP AND latest.status = 'FAILED'
                    AND ai.source_state <> 'WITHDRAWN' AND ai.substantive AND ai.category IN ('A', 'B', 'C')
                    AND NOT EXISTS (SELECT 1 FROM analysis_run retry WHERE retry.retry_of_run_id = latest.id))
                       can_retry_analysis
            FROM agenda_item ai
            LEFT JOIN LATERAL (
                SELECT id, status, error_code, created_at, updated_at, completed_at
                FROM analysis_run ar WHERE ar.agenda_item_id = ai.id AND ar.run_type = 'FINAL_ADVICE'
                ORDER BY created_at DESC LIMIT 1
            ) latest ON TRUE
            LEFT JOIN LATERAL (
                SELECT actuality, advice FROM agenda_item_advice aia
                JOIN analysis_run ar ON ar.id = aia.analysis_run_id
                WHERE aia.agenda_item_id = ai.id AND ar.status = 'SUCCEEDED'
                ORDER BY CASE aia.actuality
                    WHEN 'CURRENT' THEN 0
                    WHEN 'STALE' THEN 1
                    ELSE 2
                END, ar.created_at DESC, ar.id DESC LIMIT 1
            ) advice ON TRUE
            LEFT JOIN meeting m ON m.id = ai.meeting_id
            LEFT JOIN meeting_revision mr ON mr.meeting_id = m.id AND mr.revision_number = m.current_revision_number
            LEFT JOIN agenda_item_revision revision ON revision.meeting_revision_id = mr.id AND revision.source_id = ai.source_id
            WHERE ai.meeting_id = ? ORDER BY ai.sequence_number
            """.trimIndent(),
            { rs, _ -> summary(rs) },
            meetingId,
        )
    }

    fun item(itemId: UUID): AgendaItemDetailDto? {
        val row = jdbc.query(
            """
            SELECT ai.*, latest.status AS analysis_status, advice.advice::text AS advice_json,
                   advice.actuality AS advice_actuality, revision.difference_types,
                   CASE WHEN cardinality(revision.difference_types) > 0 THEN revision.created_at END last_detected_change_at,
                   advice.advice->>'displayTitle' display_title,
                   advice.advice->>'shortConclusion' short_conclusion,
                   latest.id latest_run_id, latest.error_code latest_error_code,
                   latest.created_at latest_created_at, latest.updated_at latest_updated_at,
                   latest.completed_at latest_completed_at,
                   (m.starts_at > CURRENT_TIMESTAMP AND latest.status = 'FAILED'
                    AND ai.source_state <> 'WITHDRAWN' AND ai.substantive AND ai.category IN ('A', 'B', 'C')
                    AND NOT EXISTS (SELECT 1 FROM analysis_run retry WHERE retry.retry_of_run_id = latest.id))
                       can_retry_analysis
            FROM agenda_item ai
            LEFT JOIN LATERAL (
                SELECT id, status, error_code, created_at, updated_at, completed_at
                FROM analysis_run ar WHERE ar.agenda_item_id = ai.id AND ar.run_type = 'FINAL_ADVICE'
                ORDER BY created_at DESC LIMIT 1
            ) latest ON TRUE
            LEFT JOIN LATERAL (
                SELECT aia.advice, aia.actuality FROM agenda_item_advice aia
                JOIN analysis_run ar ON ar.id = aia.analysis_run_id
                WHERE aia.agenda_item_id = ai.id AND ar.status = 'SUCCEEDED'
                ORDER BY CASE aia.actuality
                    WHEN 'CURRENT' THEN 0
                    WHEN 'STALE' THEN 1
                    ELSE 2
                END, ar.created_at DESC, ar.id DESC LIMIT 1
            ) advice ON TRUE
            LEFT JOIN meeting m ON m.id = ai.meeting_id
            LEFT JOIN meeting_revision mr ON mr.meeting_id = m.id AND mr.revision_number = m.current_revision_number
            LEFT JOIN agenda_item_revision revision ON revision.meeting_revision_id = mr.id AND revision.source_id = ai.source_id
            WHERE ai.id = ?
            """.trimIndent(),
            { rs, _ ->
                AgendaItemDetailDto(
                    item = summary(rs),
                    explanation = rs.getString("explanation"),
                    treatmentProposal = rs.getString("treatment_proposal"),
                    sourceUrl = URI(rs.getString("source_url")),
                    advice = rs.getString("advice_json")?.let(mapper::readTree),
                    adviceActuality = rs.getString("advice_actuality"),
                    sources = sources(itemId),
                )
            },
            itemId,
        )
        return row.singleOrNull()
    }

    fun run(runId: UUID): AnalysisRunDto? = jdbc.query(
        """
        SELECT id, agenda_item_id, status, error_code, created_at, updated_at, completed_at
        FROM analysis_run WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            AnalysisRunDto(
                rs.getObject("id", UUID::class.java), rs.getObject("agenda_item_id", UUID::class.java),
                rs.getString("status"), rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("completed_at")?.toInstant(),
            )
        },
        runId,
    ).singleOrNull()

    private fun progress(meetingId: UUID): ProgressDto = jdbc.queryForObject(
        """
        SELECT COUNT(*) AS total,
               COUNT(*) FILTER (WHERE latest.status = 'SUCCEEDED') AS complete,
               COUNT(*) FILTER (WHERE latest.status IN ('FAILED', 'CANCELLED')) AS failed
        FROM agenda_item ai
        LEFT JOIN LATERAL (
            SELECT status FROM analysis_run ar WHERE ar.agenda_item_id = ai.id AND ar.run_type = 'FINAL_ADVICE' ORDER BY created_at DESC LIMIT 1
        ) latest ON TRUE
        WHERE ai.meeting_id = ? AND ai.source_state <> 'WITHDRAWN'
          AND ai.substantive AND ai.category IN ('A', 'B', 'C')
        """.trimIndent(),
        { rs, _ -> ProgressDto(rs.getInt("total"), rs.getInt("complete"), rs.getInt("failed")) },
        meetingId,
    ) ?: ProgressDto(0, 0, 0)

    private fun sources(itemId: UUID): List<SourceLinkDto> = jdbc.query(
        """
        SELECT name, source_url, extraction_status FROM (
            SELECT DISTINCT ON (source_id) source_id, name, source_url, extraction_status, created_at
            FROM source_document WHERE agenda_item_id = ?
            ORDER BY source_id, created_at DESC
        ) latest ORDER BY name
        """.trimIndent(),
        { rs, _ -> SourceLinkDto(rs.getString("name"), URI(rs.getString("source_url")), rs.getString("extraction_status")) },
        itemId,
    )

    private fun exists(table: String, id: UUID): Boolean = jdbc.queryForObject(
        "SELECT COUNT(*) > 0 FROM $table WHERE id = ?",
        Boolean::class.java,
        id,
    ) == true

    private fun summary(rs: java.sql.ResultSet) = AgendaItemSummaryDto(
        id = rs.getObject("id", UUID::class.java),
        sequence = rs.getInt("sequence_number"),
        displayNumber = rs.getString("display_number"),
        category = rs.getString("category"),
        title = rs.getString("title"),
        substantive = rs.getBoolean("substantive"),
        importStatus = rs.getString("import_status"),
        analysisStatus = rs.getString("analysis_status"),
        sourceState = rs.getString("source_state"),
        currentFingerprint = rs.getString("current_fingerprint"),
        adviceActuality = rs.getString("advice_actuality"),
        changeTypes = (rs.getArray("difference_types")?.array as? Array<*>)?.map(Any?::toString) ?: emptyList(),
        lastDetectedChangeAt = rs.getTimestamp("last_detected_change_at")?.toInstant(),
        displayTitle = rs.getString("display_title"),
        shortConclusion = rs.getString("short_conclusion"),
        lastAnalysisRun = rs.getObject("latest_run_id", UUID::class.java)?.let { runId ->
            AnalysisRunDto(
                runId,
                rs.getObject("id", UUID::class.java),
                rs.getString("analysis_status"),
                rs.getString("latest_error_code"),
                rs.getTimestamp("latest_created_at").toInstant(),
                rs.getTimestamp("latest_updated_at").toInstant(),
                rs.getTimestamp("latest_completed_at")?.toInstant(),
            )
        },
        canRetryAnalysis = rs.getBoolean("can_retry_analysis"),
    )
}
