package nl.vdzon.pvdd.analysis

import java.time.Instant
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import nl.vdzon.pvdd.runtime.AgentRuntimeProperties
import nl.vdzon.pvdd.runtime.RuntimeExecution
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/** Leverancier, model en uitvoeringswijze; de analysemodule toont dit zonder runtime-types. */
data class AnalysisExecution(val vendorId: String, val model: String, val mode: String)

/** De twee AI-taken waarvoor een beheerder afzonderlijk een model kiest. */
enum class AnalysisModelTask(val settingKey: String) {
    SOURCE_NOTES("analysis.model.source-notes"),
    FINAL_ADVICE("analysis.model.final-advice"),
}

/** De actieve keuze voor een taak: door een beheerder gekozen of de geconfigureerde standaard. */
data class AnalysisModelChoice(
    val task: AnalysisModelTask,
    val execution: AnalysisExecution,
    val fromSetting: Boolean,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

data class AnalysisModelOption(val execution: AnalysisExecution, val available: Boolean, val onlineWorkers: Int)

data class AnalysisModelOverview(
    val choices: List<AnalysisModelChoice>,
    val options: List<AnalysisModelOption>,
    /** Gevuld als de catalogus van de runtime niet kon worden opgehaald. */
    val catalogUnavailable: Boolean,
)

class UnknownAnalysisModelException : RuntimeException("Dit model staat niet in de catalogus van de runtime.")
class AnalysisModelCatalogUnavailableException(cause: Throwable) : RuntimeException("De catalogus van de runtime is niet bereikbaar.", cause)

/**
 * Het model per taak is zonder uitrol te wisselen. De keuze staat in `application_setting`; zonder
 * keuze geldt de configuratie (`pvdd.agent-runtime.*`). Een nieuwe job leest de keuze steeds opnieuw.
 */
@Service
class AnalysisModelSettings(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val properties: AgentRuntimeProperties,
    private val runtime: AgentRuntimeGateway,
) {
    fun current(task: AnalysisModelTask): AnalysisModelChoice {
        val row = jdbc.query(
            "SELECT setting_value, updated_at, updated_by FROM application_setting WHERE setting_key = ?",
            { rs, _ -> Triple(rs.getString("setting_value"), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by")) },
            task.settingKey,
        ).singleOrNull()
        val stored = row?.let { (value, _, _) ->
            runCatching {
                val node = mapper.readTree(value)
                AnalysisExecution(node.path("vendorId").asText(), node.path("model").asText(), node.path("mode").asText())
            }.getOrNull()
        }?.takeIf { it.vendorId.isNotBlank() && it.model.isNotBlank() && it.mode.isNotBlank() }
        return if (stored != null) AnalysisModelChoice(task, stored, true, row.second, row.third)
        else AnalysisModelChoice(task, configured(), false, null, null)
    }

    /** De uitvoering voor een nieuwe job van deze taak, zonder cache. */
    fun execution(task: AnalysisModelTask): RuntimeExecution =
        current(task).execution.let { RuntimeExecution(it.vendorId, it.model, it.mode) }

    fun overview(): AnalysisModelOverview {
        val catalog = runCatching { runtime.executionOptions() }
        return AnalysisModelOverview(
            choices = AnalysisModelTask.entries.map(::current),
            options = catalog.getOrDefault(emptyList()).map { AnalysisModelOption(it.execution.toAnalysis(), it.available, it.onlineWorkers) },
            catalogUnavailable = catalog.isFailure,
        )
    }

    fun select(task: AnalysisModelTask, requested: AnalysisExecution, email: String) {
        val chosen = RuntimeExecution(requested.vendorId, requested.model, requested.mode)
        val options = try {
            runtime.executionOptions()
        } catch (failure: Exception) {
            throw AnalysisModelCatalogUnavailableException(failure)
        }
        if (options.none { it.execution == chosen }) throw UnknownAnalysisModelException()
        val value = mapper.writeValueAsString(
            mapOf("vendorId" to chosen.vendorId, "model" to chosen.model, "mode" to chosen.mode),
        )
        jdbc.update(
            """
            INSERT INTO application_setting (setting_key, setting_value, updated_at, updated_by)
            VALUES (?, ?, CURRENT_TIMESTAMP, ?)
            ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value,
                updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by
            """.trimIndent(),
            task.settingKey,
            value,
            email.take(320),
        )
    }

    /** Terug naar de configuratie. */
    fun reset(task: AnalysisModelTask) {
        jdbc.update("DELETE FROM application_setting WHERE setting_key = ?", task.settingKey)
    }

    private fun configured() = AnalysisExecution(properties.vendorId, properties.model, properties.mode)

    private fun RuntimeExecution.toAnalysis() = AnalysisExecution(vendorId, model, mode)
}
