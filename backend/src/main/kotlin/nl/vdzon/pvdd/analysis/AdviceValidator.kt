package nl.vdzon.pvdd.analysis

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

class AdviceValidationException(val errors: List<String>) : RuntimeException("INVALID_AI_ADVICE")

sealed interface ValidatedAdvice {
    data class Ab(val advice: AbAdvice) : ValidatedAdvice
    data class C(val advice: CAdvice) : ValidatedAdvice
}

@Component
class AdviceValidator {
    fun validate(
        category: String,
        expectedAgendaItemSourceId: String,
        output: JsonNode,
        allowedSources: List<AnalysisSource>,
    ): ValidatedAdvice {
        val errors = mutableListOf<String>()
        if (output.toString().length > MAX_RESULT_CHARACTERS) errors += "result_too_large"
        if (!output.isObject) errors += "root_must_be_object"
        if (errors.isNotEmpty()) throw AdviceValidationException(errors)
        val sourceMap = allowedSources.associateBy { it.sourceId }
        if (sourceMap.size != allowedSources.size) errors += "duplicate_allowed_source_id"
        val result = if (category.uppercase() in setOf("A", "B")) {
            validateAb(output, expectedAgendaItemSourceId, sourceMap, errors)
        } else if (category.uppercase() == "C") {
            validateC(output, expectedAgendaItemSourceId, sourceMap, errors)
        } else {
            errors += "unsupported_category"
            null
        }
        if (errors.isNotEmpty() || result == null) throw AdviceValidationException(errors)
        return result
    }

    private fun validateAb(
        node: JsonNode,
        itemId: String,
        sources: Map<String, AnalysisSource>,
        errors: MutableList<String>,
    ): ValidatedAdvice.Ab? {
        exactProperties(node, AB_PROPERTIES, "root", errors)
        validateItemId(node, itemId, errors)
        val subject = section(node, "waarGaatHetOver", sources, errors)
        val position = section(node, "watVindenWeErvan", sources, errors, requirePolicy = true)
        val action = section(node, "commissieInzet", sources, errors)
        val points = section(node, "puntenVoorGedeputeerde", sources, errors)
        val questions = section(node, "technischeVragen", sources, errors)
        if (listOf(subject, position, action, points, questions).any { it == null }) return null
        return ValidatedAdvice.Ab(
            AbAdvice(itemId, subject!!, position!!, action!!, points!!, questions!!),
        )
    }

    private fun validateC(
        node: JsonNode,
        itemId: String,
        sources: Map<String, AnalysisSource>,
        errors: MutableList<String>,
    ): ValidatedAdvice.C? {
        exactProperties(node, C_PROPERTIES, "root", errors)
        validateItemId(node, itemId, errors)
        val moveNode = node.get("besprekenEnNaarB")
        if (moveNode == null || !moveNode.isBoolean) errors += "besprekenEnNaarB_must_be_boolean"
        val move = moveNode?.takeIf { it.isBoolean }?.booleanValue() ?: false
        // A placeholder or otherwise contentless C item can legitimately remain on C without
        // inventing a policy link. Moving an item to B must always be grounded in the programme.
        val motivation = section(node, "motivering", sources, errors, requirePolicy = move)
        val goal = section(node, "commissieDoel", sources, errors)
        val urgency = when (string(node, "urgentie", 10, errors)) {
            "LAAG" -> Urgency.LOW
            "MIDDEL" -> Urgency.MEDIUM
            "HOOG" -> Urgency.HIGH
            else -> { errors += "invalid_urgentie"; null }
        }
        val keyNode = node.get("kernvraag")
        val keyQuestion = if (keyNode == null || keyNode.isNull) null else sectionNode(keyNode, "kernvraag", sources, errors)
        if (move && keyQuestion == null) errors += "kernvraag_required_when_moving"
        if (motivation == null || goal == null || urgency == null) return null
        return ValidatedAdvice.C(CAdvice(itemId, move, motivation, urgency, goal, keyQuestion))
    }

    private fun section(
        parent: JsonNode,
        field: String,
        sources: Map<String, AnalysisSource>,
        errors: MutableList<String>,
        requirePolicy: Boolean = false,
    ): AdviceSection? {
        val node = parent.get(field)
        if (node == null) {
            errors += "missing_$field"
            return null
        }
        val result = sectionNode(node, field, sources, errors) ?: return null
        if (requirePolicy && result.citations.none { it.sourceType == CitationSourceType.POLICY_PROGRAMME }) {
            errors += "${field}_requires_policy_citation"
        }
        return result
    }

    private fun sectionNode(
        node: JsonNode,
        path: String,
        sources: Map<String, AnalysisSource>,
        errors: MutableList<String>,
    ): AdviceSection? {
        if (!node.isObject) {
            errors += "${path}_must_be_object"
            return null
        }
        exactProperties(node, SECTION_PROPERTIES, path, errors)
        val text = string(node, "text", MAX_SECTION_CHARACTERS, errors, path) ?: return null
        val citationNodes = node.get("citations")
        if (citationNodes == null || !citationNodes.isArray || citationNodes.size() !in 1..20) {
            errors += "${path}_invalid_citations"
            return null
        }
        val citations = citationNodes.mapIndexedNotNull { index, citation ->
            parseCitation(citation, "$path.citations[$index]", sources, errors)
        }
        return AdviceSection(text, citations)
    }

    private fun parseCitation(
        node: JsonNode,
        path: String,
        sources: Map<String, AnalysisSource>,
        errors: MutableList<String>,
    ): Citation? {
        if (!node.isObject) {
            errors += "${path}_must_be_object"
            return null
        }
        exactProperties(node, CITATION_PROPERTIES, path, errors, optional = setOf("pageNumber", "section"))
        val sourceId = string(node, "sourceId", 160, errors, path) ?: return null
        val sourceTypeText = string(node, "sourceType", 40, errors, path) ?: return null
        val quote = string(node, "quote", 500, errors, path) ?: return null
        val source = sources[sourceId]
        if (source == null) {
            errors += "${path}_unknown_source"
            return null
        }
        if (source.sourceType.name != sourceTypeText) errors += "${path}_source_type_mismatch"
        val pageNode = node.get("pageNumber")
        val page = pageNode?.takeIf { it.isIntegralNumber }?.intValue()
        if (pageNode != null && !pageNode.isNull && !pageNode.isIntegralNumber) errors += "${path}_invalid_page"
        if (source.pageNumber != page) errors += "${path}_page_mismatch"
        val sectionNode = node.get("section")
        val section = sectionNode?.takeIf { it.isString }?.stringValue()
        if (sectionNode != null && !sectionNode.isNull && !sectionNode.isString) errors += "${path}_invalid_section"
        if (source.section != null && source.section != section) errors += "${path}_section_mismatch"
        if (!normalizeCitationText(source.text).contains(normalizeCitationText(quote))) {
            errors += "${path}_quote_not_in_source"
        }
        return Citation(sourceId, source.sourceType, source.sourceUrl, page, section, quote)
    }

    private fun validateItemId(node: JsonNode, expected: String, errors: MutableList<String>) {
        val actual = string(node, "agendaItemSourceId", 160, errors)
        if (actual != expected) errors += "agenda_item_mismatch"
    }

    private fun exactProperties(
        node: JsonNode,
        required: Set<String>,
        path: String,
        errors: MutableList<String>,
        optional: Set<String> = emptySet(),
    ) {
        val present = node.propertyNames().toSet()
        (required - present).forEach { errors += "${path}_missing_$it" }
        (present - required - optional).forEach { errors += "${path}_unknown_$it" }
    }

    private fun string(
        node: JsonNode,
        field: String,
        maxLength: Int,
        errors: MutableList<String>,
        path: String = "root",
    ): String? {
        val value = node.get(field)
        if (value == null || !value.isString) {
            errors += "${path}_${field}_must_be_string"
            return null
        }
        val text = value.stringValue().trim()
        if (text.isEmpty() || text.length > maxLength) errors += "${path}_${field}_invalid_length"
        return text
    }

    companion object {
        private const val MAX_RESULT_CHARACTERS = 50_000
        private const val MAX_SECTION_CHARACTERS = 6_000
        private val AB_PROPERTIES = setOf(
            "agendaItemSourceId", "waarGaatHetOver", "watVindenWeErvan", "commissieInzet",
            "puntenVoorGedeputeerde", "technischeVragen",
        )
        private val C_PROPERTIES = setOf(
            "agendaItemSourceId", "besprekenEnNaarB", "motivering", "urgentie", "commissieDoel", "kernvraag",
        )
        private val SECTION_PROPERTIES = setOf("text", "citations")
        private val CITATION_PROPERTIES = setOf("sourceId", "sourceType", "quote")
    }
}

/**
 * PDF extraction may insert control characters or split a printed word across whitespace
 * (for example `D e` instead of `De`). Citations still have to be a literal sequence of
 * letters and numbers from the source; only presentation artefacts are ignored.
 */
internal fun normalizeCitationText(value: String): String =
    value.lowercase().replace(NON_ALPHANUMERIC, "")

private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]")
