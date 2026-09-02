package nl.vdzon.pvdd.analysis

import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class AnalysisGuidance(
    val text: String,
    val updatedAt: Instant,
    val updatedBy: String,
)

@Repository
class AnalysisGuidanceRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    fun current(): AnalysisGuidance = jdbc.query(
        "SELECT setting_value, updated_at, updated_by FROM application_setting WHERE setting_key = ?",
        { rs, _ ->
            AnalysisGuidance(
                text = rs.getString("setting_value"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                updatedBy = rs.getString("updated_by"),
            )
        },
        KEY,
    ).single()

    fun update(text: String, email: String): AnalysisGuidance? = jdbc.query(
        """
        UPDATE application_setting
        SET setting_value = ?, updated_at = ?, updated_by = ?
        WHERE setting_key = ? AND setting_value IS DISTINCT FROM ?
        RETURNING setting_value, updated_at, updated_by
        """.trimIndent(),
        { rs, _ ->
            AnalysisGuidance(
                text = rs.getString("setting_value"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                updatedBy = rs.getString("updated_by"),
            )
        },
        text,
        Timestamp.from(clock.instant()),
        email,
        KEY,
        text,
    ).singleOrNull()

    companion object {
        const val KEY = "analysis.additional-instructions"
    }
}

@Service
class AnalysisGuidanceService(
    private val guidance: AnalysisGuidanceRepository,
    private val analyses: AnalysisRepository,
) {
    fun current(): AnalysisGuidance = guidance.current()

    @Transactional
    fun update(text: String, email: String): AnalysisGuidance {
        val normalized = text.trim()
        require(normalized.length <= MAX_CHARACTERS) { "analysis_guidance_too_long" }
        val changed = guidance.update(normalized, email)
        if (changed != null) analyses.queueFutureMeetingsForPolicyRefresh()
        return changed ?: guidance.current()
    }

    companion object {
        const val MAX_CHARACTERS = 4_000
    }
}
