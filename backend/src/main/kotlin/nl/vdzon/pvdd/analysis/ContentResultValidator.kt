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
    fun validate(output: JsonNode): String {
        if (!output.isObject || output.propertyNames().toSet() != setOf("content")) {
            throw ContentResultValidationException()
        }
        val content = output.path("content").takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        if (content.isEmpty() || content.length > MAX_CONTENT_CHARACTERS) {
            throw ContentResultValidationException()
        }
        return content
    }

    companion object {
        const val MAX_CONTENT_CHARACTERS = 50_000
    }
}
