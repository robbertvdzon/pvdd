package nl.vdzon.pvdd.analysis

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class SourceNotesValidator {
    fun validate(expectedAgendaItemSourceId: String, output: JsonNode, allowedSources: List<AnalysisSource>) {
        val errors = mutableListOf<String>()
        if (!output.isObject || output.propertyNames().toSet() != ROOT_PROPERTIES) errors += "invalid_root"
        if (output.path("agendaItemSourceId").takeIf { it.isString }?.stringValue() != expectedAgendaItemSourceId) {
            errors += "agenda_item_mismatch"
        }
        val notes = output.path("notes")
        if (!notes.isArray || notes.size() !in 1..100) errors += "invalid_notes"
        val sourceMap = allowedSources.associateBy(AnalysisSource::sourceId)
        if (sourceMap.size != allowedSources.size) errors += "duplicate_allowed_source_id"
        if (notes.isArray) notes.forEachIndexed { index, note -> validateNote(note, index, sourceMap, errors) }
        if (errors.isNotEmpty()) throw AdviceValidationException(errors)
    }

    private fun validateNote(
        note: JsonNode,
        index: Int,
        sources: Map<String, AnalysisSource>,
        errors: MutableList<String>,
    ) {
        if (!note.isObject || note.propertyNames().toSet() != NOTE_PROPERTIES) {
            errors += "note_${index}_invalid"
            return
        }
        val text = note.path("text")
        if (!text.isString || text.stringValue().trim().length !in 1..2_000) errors += "note_${index}_invalid_text"
        val citation = note.path("citation")
        val citationProperties = citation.takeIf { it.isObject }?.propertyNames()?.toSet().orEmpty()
        if (!citation.isObject || !citationProperties.containsAll(REQUIRED_CITATION_PROPERTIES) ||
            (citationProperties - CITATION_PROPERTIES).isNotEmpty()
        ) {
            errors += "note_${index}_invalid_citation"
            return
        }
        val sourceId = citation.path("sourceId").takeIf { it.isString }?.stringValue()
        val source = sourceId?.let(sources::get)
        if (source == null) {
            errors += "note_${index}_unknown_source"
            return
        }
        if (citation.path("sourceType").takeIf { it.isString }?.stringValue() != source.sourceType.name) {
            errors += "note_${index}_source_type_mismatch"
        }
        val page = citation.get("pageNumber")?.takeUnless { it.isNull }?.takeIf { it.isIntegralNumber }?.intValue()
        if (page != source.pageNumber) errors += "note_${index}_page_mismatch"
        val section = citation.get("section")?.takeUnless { it.isNull }?.takeIf { it.isString }?.stringValue()
        if (source.section != null && section != source.section) errors += "note_${index}_section_mismatch"
        val quote = citation.path("quote").takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        if (quote.length !in 1..500 || !normalize(source.text).contains(normalize(quote))) {
            errors += "note_${index}_invalid_quote"
        }
    }

    private fun normalize(value: String): String = value.lowercase().replace(WHITESPACE, " ").trim()

    companion object {
        private val ROOT_PROPERTIES = setOf("agendaItemSourceId", "notes")
        private val NOTE_PROPERTIES = setOf("text", "citation")
        private val REQUIRED_CITATION_PROPERTIES = setOf("sourceId", "sourceType", "quote")
        private val CITATION_PROPERTIES = setOf("sourceId", "sourceType", "pageNumber", "section", "quote")
        private val WHITESPACE = Regex("\\s+")
    }
}
