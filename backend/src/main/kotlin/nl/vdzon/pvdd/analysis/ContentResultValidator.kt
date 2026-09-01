package nl.vdzon.pvdd.analysis

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

class ContentResultValidationException : RuntimeException("INVALID_AI_RESULT")

/**
 * The backend deliberately validates only what it needs to store and display the result.
 * Political structure, citations and completeness belong in the prompt, not in a hard gate.
 */
@Component
class ContentResultValidator {
    fun validateAdvice(output: JsonNode): String {
        if (!output.isObject || output.propertyNames().toSet() != setOf("displayTitle", "shortConclusion", "content")) {
            throw ContentResultValidationException()
        }
        text(output, "displayTitle", 160)
        text(output, "shortConclusion", 280)
        return text(output, "content", MAX_CONTENT_CHARACTERS)
    }

    fun validateNotes(output: JsonNode): String {
        if (!output.isObject || output.propertyNames().toSet() != setOf("content")) {
            throw ContentResultValidationException()
        }
        return text(output, "content", MAX_CONTENT_CHARACTERS)
    }

    /** Compatibility helper for tests and older callers: final advice is now the strict default. */
    fun validate(output: JsonNode): String = validateAdvice(output)

    private fun text(output: JsonNode, field: String, max: Int): String {
        val content = output.path(field).takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        if (content.isEmpty() || content.length > max) {
            throw ContentResultValidationException()
        }
        return content
    }

    companion object {
        const val MAX_CONTENT_CHARACTERS = 50_000
    }
}
