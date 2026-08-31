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
)

data class SourceLinkDto(val name: String, val url: URI, val status: String)

data class AgendaItemDetailDto(
    val item: AgendaItemSummaryDto,
    val explanation: String?,
    val treatmentProposal: String?,
    val sourceUrl: URI,
    val advice: JsonNode?,
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
            SELECT id, source_id, title, committee, starts_at, ends_at, location, source_url, status, checked_at
            FROM meeting
            ORDER BY CASE WHEN starts_at >= CURRENT_TIMESTAMP THEN 0 ELSE 1 END, starts_at ASC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                MeetingDto(
                    rs.getObject("id", UUID::class.java), rs.getString("source_id"), rs.getString("title"),
                    rs.getString("committee"), rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("ends_at")?.toInstant(), rs.getString("location"),
                    URI(rs.getString("source_url")), rs.getString("status"),
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
                   ai.import_status, latest.status AS analysis_status
            FROM agenda_item ai
            LEFT JOIN LATERAL (
                SELECT status FROM analysis_run ar WHERE ar.agenda_item_id = ai.id ORDER BY created_at DESC LIMIT 1
            ) latest ON TRUE
            WHERE ai.meeting_id = ? ORDER BY ai.sequence_number
            """.trimIndent(),
            { rs, _ -> summary(rs) },
            meetingId,
        )
    }

    fun item(itemId: UUID): AgendaItemDetailDto? {
        val row = jdbc.query(
            """
            SELECT ai.*, latest.status AS analysis_status, advice.advice::text AS advice_json
            FROM agenda_item ai
            LEFT JOIN LATERAL (
                SELECT id, status FROM analysis_run ar WHERE ar.agenda_item_id = ai.id ORDER BY created_at DESC LIMIT 1
            ) latest ON TRUE
            LEFT JOIN agenda_item_advice advice ON advice.analysis_run_id = latest.id
            WHERE ai.id = ?
            """.trimIndent(),
            { rs, _ ->
                AgendaItemDetailDto(
                    item = summary(rs),
                    explanation = rs.getString("explanation"),
                    treatmentProposal = rs.getString("treatment_proposal"),
                    sourceUrl = URI(rs.getString("source_url")),
                    advice = rs.getString("advice_json")?.let(mapper::readTree),
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
            SELECT status FROM analysis_run ar WHERE ar.agenda_item_id = ai.id ORDER BY created_at DESC LIMIT 1
        ) latest ON TRUE
        WHERE ai.meeting_id = ? AND ai.substantive AND ai.category IN ('A', 'B', 'C')
        """.trimIndent(),
        { rs, _ -> ProgressDto(rs.getInt("total"), rs.getInt("complete"), rs.getInt("failed")) },
        meetingId,
    ) ?: ProgressDto(0, 0, 0)

    private fun sources(itemId: UUID): List<SourceLinkDto> = jdbc.query(
        """
        SELECT DISTINCT name, source_url, extraction_status FROM source_document
        WHERE agenda_item_id = ? ORDER BY name
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
    )
}
