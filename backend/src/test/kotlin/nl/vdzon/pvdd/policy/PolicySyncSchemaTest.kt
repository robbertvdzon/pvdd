package nl.vdzon.pvdd.policy

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

class PolicySyncSchemaTest {
    @Test
    fun `strict response schema requires every declared object property`() {
        assertStrictObjects(PolicySyncService.positionResponseSchema(jacksonObjectMapper()))
    }

    private fun assertStrictObjects(node: JsonNode) {
        if (node.isObject) {
            val properties = node.path("properties")
            if (node.path("additionalProperties").isBoolean && !node.path("additionalProperties").booleanValue()) {
                val declared: Set<String> = properties.properties().map { it.key }.toSet()
                val required = mutableSetOf<String>()
                for (requiredNode in node.path("required")) required += requiredNode.stringValue()
                assertEquals(declared, required)
            }
            properties.properties().forEach { assertStrictObjects(it.value) }
            node.path("items").takeUnless(JsonNode::isMissingNode)?.let(::assertStrictObjects)
        } else if (node.isArray) {
            for (child in node) assertStrictObjects(child)
        }
    }
}
