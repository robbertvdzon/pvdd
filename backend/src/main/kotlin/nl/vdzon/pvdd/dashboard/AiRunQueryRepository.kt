package nl.vdzon.pvdd.dashboard

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class LogicalAiRunDto(
    val id: UUID,
    val type: String,
    val title: String,
    val explanation: String,
    val linkedEntityId: UUID?,
    val status: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val provider: String?,
    val model: String?,
    val promptVersion: String?,
    val phaseCount: Int,
    val completedPhases: Int,
    val errorCode: String?,
)

data class LogicalAiRunPageDto(val items: List<LogicalAiRunDto>, val nextCursor: String?)
data class AiRunPhaseDto(val id: UUID?, val title: String, val status: String, val startedAt: Instant?, val completedAt: Instant?)
data class LogicalAiRunDetailDto(val run: LogicalAiRunDto, val phases: List<AiRunPhaseDto>)

@Repository
class AiRunQueryRepository(private val jdbc: JdbcTemplate) {
    fun page(state: String, limit: Int, cursor: String?): LogicalAiRunPageDto {
        require(state in setOf("active", "finished"))
        require(limit in 1..50)
        val statuses = if (state == "active") ACTIVE else FINISHED
        val all = (agendaRuns(statuses) + policyRuns(statuses)).sortedWith(
            compareByDescending<LogicalAiRunDto> { it.completedAt ?: it.createdAt }.thenByDescending { it.id },
        )
        val after = cursor?.let(::decodeCursor)
        val filtered = if (after == null) all else all.dropWhile { run ->
            val marker = run.completedAt ?: run.createdAt
            marker > after.first || (marker == after.first && run.id >= after.second)
        }
        val page = filtered.take(limit)
        val next = if (filtered.size > limit) page.lastOrNull()?.let(::encodeCursor) else null
        return LogicalAiRunPageDto(page, next)
    }

    fun detail(id: UUID): LogicalAiRunDetailDto? {
        val run = (agendaRuns(ACTIVE + FINISHED, id) + policyRuns(ACTIVE + FINISHED, id)).singleOrNull() ?: return null
        val phases = if (run.type.startsWith("POLICY")) {
            listOf(
                AiRunPhaseDto(null, "Officiële bronnen ophalen en vergelijken", if (run.status == "PENDING") "PENDING" else "SUCCEEDED", run.startedAt, run.startedAt),
                AiRunPhaseDto(null, "Standpunten met AI afleiden en valideren", run.status, run.startedAt, run.completedAt),
            )
        } else {
            jdbc.query(
                """
                SELECT id, phase_index, status, submitted_at, completed_at FROM analysis_run
                WHERE parent_run_id = ? ORDER BY phase_index
                """.trimIndent(),
                { rs, _ -> AiRunPhaseDto(
                    rs.getObject("id", UUID::class.java), "Bronnotities fase ${rs.getInt("phase_index")}",
                    rs.getString("status"), rs.getTimestamp("submitted_at")?.toInstant(),
                    rs.getTimestamp("completed_at")?.toInstant(),
                ) },
                id,
            ) + AiRunPhaseDto(id, "Definitief agenda-advies", run.status, run.startedAt, run.completedAt)
        }
        return LogicalAiRunDetailDto(run, phases)
    }

    private fun agendaRuns(statuses: Set<String>, id: UUID? = null): List<LogicalAiRunDto> {
        val placeholders = statuses.joinToString(",") { "?" }
        val sql = """
            SELECT run.id, run.agenda_item_id, run.status, run.created_at, run.submitted_at,
                   run.updated_at, run.completed_at, run.error_code, run.prompt_version,
                   item.title item_title, item.category,
                   EXISTS (SELECT 1 FROM analysis_run older WHERE older.agenda_item_id = run.agenda_item_id
                       AND older.run_type = 'FINAL_ADVICE' AND older.created_at < run.created_at) reanalysis,
                   advice.provider, advice.model,
                   (SELECT COUNT(*) FROM analysis_run phase WHERE phase.parent_run_id = run.id) + 1 phase_count,
                   (SELECT COUNT(*) FROM analysis_run phase WHERE phase.parent_run_id = run.id AND phase.status = 'SUCCEEDED')
                       + CASE WHEN run.status = 'SUCCEEDED' THEN 1 ELSE 0 END completed_phases
            FROM analysis_run run
            JOIN agenda_item item ON item.id = run.agenda_item_id
            LEFT JOIN agenda_item_advice advice ON advice.analysis_run_id = run.id
            WHERE run.run_type = 'FINAL_ADVICE' AND run.status IN ($placeholders)
              ${if (id == null) "" else "AND run.id = ?"}
            ORDER BY run.created_at DESC LIMIT 1000
        """.trimIndent()
        val args = mutableListOf<Any>(*statuses.toTypedArray()).apply { id?.let(::add) }
        return jdbc.query(sql, { rs, _ ->
            val reanalysis = rs.getBoolean("reanalysis")
            val itemTitle = rs.getString("item_title")
            LogicalAiRunDto(
                rs.getObject("id", UUID::class.java),
                if (reanalysis) "AGENDA_REANALYSIS" else "AGENDA_ADVICE",
                if (reanalysis) "$itemTitle opnieuw analyseren" else itemTitle,
                if (reanalysis) "Opnieuw gestart omdat de bron- of beleidscontext veranderde" else "Eerste analyse van een ${rs.getString("category")}-agendapunt",
                rs.getObject("agenda_item_id", UUID::class.java), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("submitted_at")?.toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("completed_at")?.toInstant(),
                rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"),
                rs.getInt("phase_count"), rs.getInt("completed_phases"), rs.getString("error_code"),
            )
        }, *args.toTypedArray())
    }

    private fun policyRuns(statuses: Set<String>, id: UUID? = null): List<LogicalAiRunDto> {
        val placeholders = statuses.joinToString(",") { "?" }
        val sql = """
            SELECT * FROM policy_sync_run WHERE status IN ($placeholders)
            ${if (id == null) "" else "AND id = ?"}
            ORDER BY created_at DESC LIMIT 1000
        """.trimIndent()
        val args = mutableListOf<Any>(*statuses.toTypedArray()).apply { id?.let(::add) }
        return jdbc.query(sql, { rs, _ ->
            val trigger = rs.getString("trigger_type")
            LogicalAiRunDto(
                rs.getObject("id", UUID::class.java), "POLICY_SYNC",
                if (trigger == "MONTHLY") "Maandelijkse controle van PvdD-standpunten" else "PvdD-standpunten actualiseren",
                "Officiële website en verkiezingsprogramma controleren en gewijzigde standpunten afleiden",
                null, rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at")?.toInstant(), null, null, "policy-position-v1", 2,
                when (rs.getString("status")) { "PENDING" -> 0; "RUNNING", "QUEUED", "WAITING_FOR_WORKER" -> 1; else -> 2 },
                rs.getString("error_code"),
            )
        }, *args.toTypedArray())
    }

    private fun encodeCursor(run: LogicalAiRunDto): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "${run.completedAt ?: run.createdAt}|${run.id}".toByteArray(StandardCharsets.UTF_8),
    )

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> = try {
        val value = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split('|')
        Instant.parse(value[0]) to UUID.fromString(value[1])
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor")
    }

    companion object {
        private val ACTIVE = setOf("PENDING", "QUEUED", "WAITING_FOR_WORKER", "RUNNING")
        private val FINISHED = setOf("SUCCEEDED", "FAILED", "CANCELLED")
    }
}
