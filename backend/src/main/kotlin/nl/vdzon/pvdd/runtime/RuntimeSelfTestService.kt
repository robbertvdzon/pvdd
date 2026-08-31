package nl.vdzon.pvdd.runtime

import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

data class RuntimeSelfTestResult(val jobId: String, val message: String)

@Service
@ConditionalOnExpression("'\${pvdd.environment:local}' != 'production'")
class RuntimeSelfTestService(
    private val client: AgentRuntimeClient,
    private val properties: AgentRuntimeProperties,
    private val mapper: ObjectMapper,
) {
    fun run(): RuntimeSelfTestResult {
        require(properties.provider == "MOCKED") { "Technical self-test requires the MOCKED provider." }
        val schema = mapper.readTree(
            """{"type":"object","additionalProperties":false,"required":["message"],"properties":{"message":{"const":"pvdd-runtime-ok"}}}"""
        )
        val key = "pvdd-self-test-${UUID.randomUUID()}"
        val job = client.create(RuntimeCreateRequest(key, "Return the exact JSON object required by the response schema.", schema))
        val deadline = Instant.now().plus(properties.selfTestTimeout)
        var current = job
        while (current.status !in TERMINAL && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_INTERVAL.toMillis())
            current = client.status(job.id)
        }
        if (current.status != "SUCCEEDED") throw AgentRuntimeException("Agent Runtime self-test did not succeed (status ${current.status}).")
        val result = client.result(job.id)
        val message = result.result.path("message").asText("")
        if (message != "pvdd-runtime-ok") throw AgentRuntimeInvalidResponseException()
        return RuntimeSelfTestResult(job.id, message)
    }

    companion object {
        private val TERMINAL = setOf("SUCCEEDED", "FAILED", "CANCELLED")
        private val POLL_INTERVAL = Duration.ofMillis(250)
    }
}
