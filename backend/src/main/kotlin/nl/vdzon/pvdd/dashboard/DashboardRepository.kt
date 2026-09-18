package nl.vdzon.pvdd.dashboard

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
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
    // Of de vergadering al is geweest. Wordt server-side op databasetijd bepaald
    // (`starts_at < CURRENT_TIMESTAMP`), zodat de markering niet aan de browserklok hangt.
    // Grensgeval: begint de vergadering precies nu, dan is `past` nog false.
    val past: Boolean,
)

data class ProgressDto(val total: Int, val complete: Int, val failed: Int)

/**
 * Eén bewaarde vergadering die al is geweest, zoals het archiefoverzicht haar toont.
 * [substantiveItemCount] en [completedAdviceCount] gebruiken exact hetzelfde filter als de
 * voortgangstelling van de agendaweergave, maar dan gegroepeerd per vergadering.
 */
data class PastMeetingDto(
    val id: UUID,
    val title: String,
    val startsAt: Instant,
    val location: String?,
    val substantiveItemCount: Int,
    val completedAdviceCount: Int,
)

/**
 * Eén pagina voorbije vergaderingen. [nextCursor] is alleen gevuld wanneer er bewijsbaar nog een
 * rij volgt; [total] telt alle bewaarde voorbije vergaderingen, ongeacht cursor en limiet, en is
 * een momentopname per aanvraag.
 */
data class PastMeetingPageDto(val items: List<PastMeetingDto>, val nextCursor: String?, val total: Int)

data class AgendaItemSummaryDto(
    val id: UUID,
    val sequence: Int,
    val displayNumber: String?,
    val category: String,
    val title: String,
    val substantive: Boolean,
    val importStatus: String,
    val analysisStatus: String?,
    val documentStatus: String,
    val documentCount: Int,
    val readableDocumentCount: Int,
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

/**
 * Eén bewaarde adviesversie van een agendapunt, met de adviesinhoud in dezelfde vorm en met dezelfde
 * veldnamen als [DashboardRepository.item] die voor het laatste advies teruggeeft.
 *
 * Bewust géén citaten en géén bronlijst: bij een eerdere versie is niet bewaard welke stukken toen
 * zijn gebruikt, dus `agenda_item_advice.citations` wordt niet gelezen en er wordt niets uit de
 * revisiehistorie afgeleid.
 *
 * [latest] is uitsluitend `true` voor de eerste rij in de ordening van [DashboardRepository.item]
 * (CURRENT vóór STALE vóór overig, daarna `created_at DESC, id DESC`). Dat is dus de versie die de
 * detailweergave als 'laatste advies' toont, ook wanneer een WITHDRAWN-rij chronologisch nieuwer is.
 * [analysisGuidance] is `null` wanneer er niets of alleen witruimte is bewaard.
 */
data class AdviceVersionDto(
    val adviceId: UUID,
    val analysisRunId: UUID,
    val createdAt: Instant,
    val actuality: String,
    val latest: Boolean,
    val advice: JsonNode,
    val displayTitle: String?,
    val shortConclusion: String?,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val analysisGuidance: String?,
    val refreshReason: String,
)

/** Alle bewaarde adviesversies van één agendapunt, nieuwste eerst. Geen paginering, geen limiet. */
data class AdviceVersionsDto(val versions: List<AdviceVersionDto>)

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
    /**
     * De vergadering die de startweergave toont: het dichtstbijzijnde toekomstige begintijdstip, en
     * bestaat dat niet, dan het meest recente begintijdstip uit het verleden.
     *
     * De eerste sorteersleutel scheidt toekomst van verleden, de tweede kiest binnen de toekomst de
     * vroegste (voor voorbije rijen is zij NULL en dus zonder betekenis), en de derde kiest binnen
     * het verleden de laatste. `starts_at == CURRENT_TIMESTAMP` valt in de toekomsttak, gelijk aan
     * de `past`-berekening in [MEETING_SELECT].
     */
    fun overview(): MeetingOverviewDto {
        val meeting = jdbc.query(
            """
            $MEETING_SELECT
            ORDER BY CASE WHEN m.starts_at >= CURRENT_TIMESTAMP THEN 0 ELSE 1 END,
                     CASE WHEN m.starts_at >= CURRENT_TIMESTAMP THEN m.starts_at END ASC,
                     m.starts_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> meetingRow(rs) },
        ).singleOrNull() ?: return MeetingOverviewDto("NO_MEETING", null, null, ProgressDto(0, 0, 0))
        return overviewOf(meeting)
    }

    // Dezelfde SELECT en hetzelfde antwoordmodel als `overview()`, maar voor één bekende
    // vergadering. Een onbekende id levert null, zodat de route er 404 van maakt.
    fun meeting(id: UUID): MeetingOverviewDto? {
        val meeting = jdbc.query(
            """
            $MEETING_SELECT
            WHERE m.id = ?
            """.trimIndent(),
            { rs, _ -> meetingRow(rs) },
            id,
        ).singleOrNull() ?: return null
        return overviewOf(meeting)
    }

    private fun overviewOf(meeting: Pair<MeetingDto, Instant>) =
        MeetingOverviewDto(meeting.first.status, meeting.first, meeting.second, progress(meeting.first.id))

    private fun meetingRow(rs: java.sql.ResultSet): Pair<MeetingDto, Instant> = MeetingDto(
        rs.getObject("id", UUID::class.java), rs.getString("source_id"), rs.getString("title"),
        rs.getString("committee"), rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at")?.toInstant(), rs.getString("location"),
        URI(rs.getString("source_url")), rs.getString("status"),
        rs.getString("publication_status"), rs.getInt("current_revision_number"),
        rs.getString("canonical_fingerprint"), rs.getString("revision_status"),
        rs.getBoolean("past"),
    ) to rs.getTimestamp("checked_at").toInstant()

    /**
     * Een pagina bewaarde vergaderingen die al zijn geweest, de meest recente bovenaan.
     *
     * De parameters komen ongewijzigd van de route binnen en worden hier gevalideerd, vóór elke
     * databasetoegang: een ontbrekende of afwijkende [state], een [limit] buiten 1..50 of niet
     * numeriek, en een onleesbare [cursor] leveren [IllegalArgumentException]. De controller
     * vertaalt die naar HTTP 400 met foutcode `invalid_meeting_query`, net zoals bij de AI-runlijst.
     */
    fun pastMeetings(state: String?, limit: String?, cursor: String?): PastMeetingPageDto {
        require(state == "past")
        val size = if (limit == null) DEFAULT_PAST_MEETING_LIMIT else limit.toIntOrNull()
        require(size != null && size in 1..50)
        val after = cursor?.let(KeysetCursor::decode)

        // Eén rij meer ophalen dan gevraagd: die extra rij valt buiten `items` en bewijst alleen dat
        // er nog een volgende pagina is. Zo levert de laatste pagina nooit een cursor, ook niet als
        // zij precies `limit` items bevat.
        val rows = jdbc.query(
            """
            WITH page AS (
                SELECT m.id, m.title, m.starts_at, m.location
                FROM meeting m
                WHERE m.starts_at < CURRENT_TIMESTAMP
                  ${if (after == null) "" else "AND (m.starts_at, m.id) < (?::timestamptz, ?::uuid)"}
                ORDER BY m.starts_at DESC, m.id DESC
                LIMIT ?
            )
            SELECT page.id, page.title, page.starts_at, page.location,
                   counts.substantive_count, counts.completed_count
            FROM page
            JOIN LATERAL (
                SELECT COUNT(*) substantive_count,
                       COUNT(*) FILTER (WHERE latest.status = 'SUCCEEDED') completed_count
                FROM agenda_item ai
                JOIN agenda_item_document_status documents ON documents.agenda_item_id = ai.id
                LEFT JOIN LATERAL (
                    SELECT status FROM analysis_run ar
                    WHERE ar.agenda_item_id = ai.id AND ar.run_type = 'FINAL_ADVICE'
                    ORDER BY created_at DESC LIMIT 1
                ) latest ON TRUE
                WHERE ai.meeting_id = page.id AND ai.source_state <> 'WITHDRAWN'
                  AND ai.substantive AND ai.category IN ('A', 'B', 'C')
                  AND documents.readable_document_count > 0
            ) counts ON TRUE
            ORDER BY page.starts_at DESC, page.id DESC
            """.trimIndent(),
            { rs, _ ->
                PastMeetingDto(
                    rs.getObject("id", UUID::class.java), rs.getString("title"),
                    rs.getTimestamp("starts_at").toInstant(), rs.getString("location"),
                    rs.getInt("substantive_count"), rs.getInt("completed_count"),
                )
            },
            // De cursorwaarde gaat als expliciete timestamptz mee (zie de cast hierboven), zodat de
            // keysetvergelijking niet van de sessietijdzone afhangt.
            *listOfNotNull(
                after?.let { it.first.atOffset(ZoneOffset.UTC) }, after?.second, size + 1,
            ).toTypedArray(),
        )
        val items = rows.take(size)
        // De telling is een aggregaat en levert altijd precies één rij; de LIMIT legt die grens
        // ook letterlijk vast, zodat geen enkele nieuwe query onbegrensd is.
        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM meeting m WHERE m.starts_at < CURRENT_TIMESTAMP LIMIT 1",
            Int::class.java,
        ) ?: 0
        val next = if (rows.size > size) items.lastOrNull()?.let { KeysetCursor.encode(it.startsAt, it.id) } else null
        return PastMeetingPageDto(items, next, total)
    }

    fun agendaItems(meetingId: UUID): List<AgendaItemSummaryDto>? {
        if (!exists("meeting", meetingId)) return null
        return jdbc.query(
            """
            SELECT ai.id, ai.sequence_number, ai.display_number, ai.category, ai.title, ai.substantive,
                   ai.import_status, ai.source_state, ai.current_fingerprint,
                   latest.status AS analysis_status, advice.actuality AS advice_actuality,
                   documents.document_count, documents.readable_document_count,
                   revision.difference_types,
                   CASE WHEN cardinality(revision.difference_types) > 0 THEN revision.created_at END last_detected_change_at,
                   advice.advice->>'displayTitle' display_title,
                   advice.advice->>'shortConclusion' short_conclusion,
                   latest.id latest_run_id, latest.error_code latest_error_code,
                   latest.created_at latest_created_at, latest.updated_at latest_updated_at,
                   latest.completed_at latest_completed_at,
                   (m.starts_at > CURRENT_TIMESTAMP AND latest.status = 'FAILED'
                    AND ai.source_state <> 'WITHDRAWN' AND ai.substantive AND ai.category IN ('A', 'B', 'C')
                    AND documents.readable_document_count > 0
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
            JOIN agenda_item_document_status documents ON documents.agenda_item_id = ai.id
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
                   documents.document_count, documents.readable_document_count,
                   CASE WHEN cardinality(revision.difference_types) > 0 THEN revision.created_at END last_detected_change_at,
                   advice.advice->>'displayTitle' display_title,
                   advice.advice->>'shortConclusion' short_conclusion,
                   latest.id latest_run_id, latest.error_code latest_error_code,
                   latest.created_at latest_created_at, latest.updated_at latest_updated_at,
                   latest.completed_at latest_completed_at,
                   (m.starts_at > CURRENT_TIMESTAMP AND latest.status = 'FAILED'
                    AND ai.source_state <> 'WITHDRAWN' AND ai.substantive AND ai.category IN ('A', 'B', 'C')
                    AND documents.readable_document_count > 0
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
            JOIN agenda_item_document_status documents ON documents.agenda_item_id = ai.id
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

    /**
     * Alle bewaarde adviesversies van één agendapunt, in exact de ordening die [item] gebruikt om
     * het laatste advies te kiezen: `actuality` CURRENT vóór STALE vóór overig, daarna
     * `ar.created_at DESC, ar.id DESC`. Geen paginering en geen limiet — de lijst is per agendapunt
     * en groeit alleen met het aantal geslaagde analyses.
     *
     * Een onbekend agendapunt levert `null`, zodat de route er 404 van maakt. Een bestaand
     * agendapunt zonder geslaagde adviesrun levert een lege lijst, geen 404.
     *
     * Uitsluitend leesverkeer: alleen `agenda_item_advice` en `analysis_run` worden gelezen,
     * `citations` blijft buiten de SELECT en de revisietabellen worden niet geraakt.
     */
    fun adviceVersions(itemId: UUID): AdviceVersionsDto? {
        if (!exists("agenda_item", itemId)) return null
        val rows = jdbc.query(
            """
            SELECT aia.id advice_id, ar.id analysis_run_id, ar.created_at, aia.actuality,
                   aia.advice::text advice_json,
                   aia.advice->>'displayTitle' display_title,
                   aia.advice->>'shortConclusion' short_conclusion,
                   aia.provider, aia.model, aia.prompt_version, ar.analysis_guidance,
                   ar.retry_of_run_id,
                   ${AdviceRefreshReason.reanalysisPredicate("ar")} ${AdviceRefreshReason.REANALYSIS_COLUMN}
            FROM agenda_item_advice aia
            JOIN analysis_run ar ON ar.id = aia.analysis_run_id
            WHERE aia.agenda_item_id = ? AND ar.status = 'SUCCEEDED'
            ORDER BY CASE aia.actuality
                WHEN 'CURRENT' THEN 0
                WHEN 'STALE' THEN 1
                ELSE 2
            END, ar.created_at DESC, ar.id DESC
            """.trimIndent(),
            { rs, rowNumber ->
                AdviceVersionDto(
                    adviceId = rs.getObject("advice_id", UUID::class.java),
                    analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    actuality = rs.getString("actuality"),
                    // Alleen de eerste rij in bovenstaande ordening is het 'laatste advies'.
                    latest = rowNumber == 0,
                    advice = mapper.readTree(rs.getString("advice_json")),
                    displayTitle = rs.getString("display_title"),
                    shortConclusion = rs.getString("short_conclusion"),
                    provider = rs.getString("provider"),
                    model = rs.getString("model"),
                    promptVersion = rs.getString("prompt_version"),
                    // `analysis_guidance` is NOT NULL met standaard ''; niets of alleen witruimte
                    // betekent dat er geen aanvullende instructie is bewaard.
                    analysisGuidance = rs.getString("analysis_guidance")?.takeIf { it.isNotBlank() },
                    refreshReason = AdviceRefreshReason.of(rs).name,
                )
            },
            itemId,
        )
        return AdviceVersionsDto(rows)
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
        JOIN agenda_item_document_status documents ON documents.agenda_item_id = ai.id
        LEFT JOIN LATERAL (
            SELECT status FROM analysis_run ar WHERE ar.agenda_item_id = ai.id AND ar.run_type = 'FINAL_ADVICE' ORDER BY created_at DESC LIMIT 1
        ) latest ON TRUE
        WHERE ai.meeting_id = ? AND ai.source_state <> 'WITHDRAWN'
          AND ai.substantive AND ai.category IN ('A', 'B', 'C')
          AND documents.readable_document_count > 0
        """.trimIndent(),
        { rs, _ -> ProgressDto(rs.getInt("total"), rs.getInt("complete"), rs.getInt("failed")) },
        meetingId,
    ) ?: ProgressDto(0, 0, 0)

    private fun sources(itemId: UUID): List<SourceLinkDto> = jdbc.query(
        """
        SELECT document_revision.name, document_revision.source_url,
               COALESCE(document_version.extraction_status, 'DOWNLOAD_FAILED') extraction_status
        FROM agenda_item item
        JOIN meeting ON meeting.id = item.meeting_id
        JOIN meeting_revision
          ON meeting_revision.meeting_id = meeting.id
         AND meeting_revision.revision_number = meeting.current_revision_number
        JOIN agenda_item_revision
          ON agenda_item_revision.meeting_revision_id = meeting_revision.id
         AND agenda_item_revision.agenda_item_id = item.id
         AND agenda_item_revision.source_state <> 'WITHDRAWN'
        JOIN document_revision
          ON document_revision.agenda_item_revision_id = agenda_item_revision.id
         AND document_revision.source_state = 'CURRENT'
        LEFT JOIN LATERAL (
            SELECT source_document.extraction_status
            FROM source_document
            WHERE source_document.agenda_item_id = item.id
              AND source_document.source_id = document_revision.source_id
              AND source_document.sha256 IS NOT DISTINCT FROM document_revision.sha256
            ORDER BY source_document.created_at DESC
            LIMIT 1
        ) document_version ON TRUE
        WHERE item.id = ?
        ORDER BY document_revision.name
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
        documentStatus = documentStatus(rs.getInt("document_count"), rs.getInt("readable_document_count")),
        documentCount = rs.getInt("document_count"),
        readableDocumentCount = rs.getInt("readable_document_count"),
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

    private fun documentStatus(documentCount: Int, readableDocumentCount: Int): String = when {
        documentCount == 0 -> "NO_DOCUMENTS"
        readableDocumentCount == 0 -> "DOCUMENTS_UNREADABLE"
        readableDocumentCount < documentCount -> "DOCUMENTS_PARTIALLY_READABLE"
        else -> "DOCUMENTS_READY"
    }

    private companion object {
        // De standaardpaginagrootte van het archiefoverzicht; de webapp vraagt dezelfde 20.
        private const val DEFAULT_PAST_MEETING_LIMIT = 20

        // Gedeeld tussen `overview()` en `meeting(id)`: beide leveren exact hetzelfde antwoordmodel
        // en dus ook hetzelfde `past`-veld, berekend op databasetijd.
        private val MEETING_SELECT = """
            SELECT m.id, m.source_id, m.title, m.committee, m.starts_at, m.ends_at, m.location,
                   m.source_url, m.status, m.checked_at, m.publication_status,
                   m.current_revision_number, m.canonical_fingerprint, latest.revision_status,
                   m.starts_at < CURRENT_TIMESTAMP AS past
            FROM meeting m
            LEFT JOIN LATERAL (
                SELECT revision_status FROM meeting_revision mr
                WHERE mr.meeting_id = m.id ORDER BY revision_number DESC LIMIT 1
            ) latest ON TRUE
        """.trimIndent()
    }
}
