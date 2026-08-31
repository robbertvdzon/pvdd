package nl.vdzon.pvdd.analysis

import java.net.URI
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class AnalysisAgendaItem(
    val sourceId: String,
    val category: String,
    val title: String,
    val explanation: String?,
    val treatmentProposal: String?,
)

data class AnalysisSource(
    val sourceId: String,
    val sourceType: CitationSourceType,
    val sourceUrl: URI,
    val pageNumber: Int?,
    val section: String?,
    val text: String,
)

enum class PromptPhaseType { DIRECT_ADVICE, SOURCE_NOTES, SYNTHESIS }

data class PromptPhase(
    val type: PromptPhaseType,
    val sourceIds: List<String>,
    val prompt: String?,
)

data class PromptPlan(val phases: List<PromptPhase>)

@Component
class PromptBuilder(private val mapper: ObjectMapper) {
    fun plan(item: AnalysisAgendaItem, sources: List<AnalysisSource>): PromptPlan {
        require(sources.isNotEmpty()) { "Analysis requires sources." }
        require(sources.any { it.sourceType == CitationSourceType.POLICY_PROGRAMME }) { "Primary policy source is required." }
        val direct = prompt(item, sources)
        if (direct.length <= MAX_DIRECT_PROMPT_CHARACTERS) {
            return PromptPlan(listOf(PromptPhase(PromptPhaseType.DIRECT_ADVICE, sources.map { it.sourceId }, direct)))
        }
        val batches = mutableListOf<List<AnalysisSource>>()
        var current = mutableListOf<AnalysisSource>()
        var characters = 0
        sources.forEach { source ->
            if (current.isNotEmpty() && characters + source.text.length > NOTES_BATCH_CHARACTERS) {
                batches += current
                current = mutableListOf()
                characters = 0
            }
            current += source
            characters += source.text.length
        }
        if (current.isNotEmpty()) batches += current
        val phases = batches.map { batch ->
            PromptPhase(PromptPhaseType.SOURCE_NOTES, batch.map { it.sourceId }, notesPrompt(item, batch))
        } + PromptPhase(PromptPhaseType.SYNTHESIS, sources.map { it.sourceId }, null)
        check(phases.filter { it.type == PromptPhaseType.SOURCE_NOTES }.flatMap { it.sourceIds } == sources.map { it.sourceId })
        return PromptPlan(phases)
    }

    fun schema(category: String): JsonNode {
        val resource = if (category.uppercase() in setOf("A", "B")) "/schemas/ab-advice-v1.json" else "/schemas/c-advice-v1.json"
        return requireNotNull(javaClass.getResourceAsStream(resource)).use(mapper::readTree)
    }

    private fun prompt(item: AnalysisAgendaItem, sources: List<AnalysisSource>): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\nAnalyseer agendapunt ${item.sourceId} in categorie ${item.category}.\n")
        append("BEGIN_UNTRUSTED_SOURCE_DATA\n")
        append(mapper.writeValueAsString(mapOf("agendaItem" to item, "sources" to sources)))
        append("\nEND_UNTRUSTED_SOURCE_DATA")
    }

    private fun notesPrompt(item: AnalysisAgendaItem, sources: List<AnalysisSource>): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\nMaak uitsluitend feitelijke bronnotities voor een latere synthese; behoud alle bron-ID's en paginanummers.")
        append("\nBEGIN_UNTRUSTED_SOURCE_DATA\n")
        append(mapper.writeValueAsString(mapOf("agendaItem" to item, "sources" to sources)))
        append("\nEND_UNTRUSTED_SOURCE_DATA")
    }

    companion object {
        const val PROMPT_VERSION = "pvdd-advice-v1"
        const val SELECTION_VERSION = "policy-selection-v1"
        private const val MAX_DIRECT_PROMPT_CHARACTERS = 80_000
        private const val NOTES_BATCH_CHARACTERS = 35_000
        private val SYSTEM_PROMPT = requireNotNull(PromptBuilder::class.java.getResource("/prompts/advice-system-v1.txt")).readText()
    }
}
