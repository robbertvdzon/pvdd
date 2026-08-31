package nl.vdzon.pvdd.runtime

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class RuntimeJob(
    val id: String,
    val tenantId: String,
    val idempotencyKey: String,
    val provider: String,
    val model: String,
    val status: String,
    val phase: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class RuntimeResult(
    val jobId: String,
    val result: JsonNode,
    val completedAt: Instant,
)

data class RuntimeCreateRequest(
    val idempotencyKey: String,
    val prompt: String,
    val responseSchema: JsonNode,
    val environmentKeys: List<String> = emptyList(),
    val executionTimeoutSeconds: Int = 300,
)

open class AgentRuntimeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AgentRuntimeRejectedException(val statusCode: Int) : AgentRuntimeException("Agent Runtime rejected the request (HTTP $statusCode).")
class AgentRuntimeUnavailableException(cause: Throwable) : AgentRuntimeException("Agent Runtime is temporarily unavailable.", cause)
class AgentRuntimeInvalidResponseException(cause: Throwable? = null) : AgentRuntimeException("Agent Runtime returned an invalid response.", cause)

class AgentRuntimeClient(
    private val properties: AgentRuntimeProperties,
    private val mapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
) {
    fun create(request: RuntimeCreateRequest): RuntimeJob {
        val payload = mapOf(
            "jobKind" to "APPLICATION_WORK",
            "idempotencyKey" to request.idempotencyKey,
            "provider" to properties.provider,
            "model" to properties.model,
            "prompt" to request.prompt,
            "responseSchema" to request.responseSchema,
            "environmentKeys" to request.environmentKeys,
            "executionTimeoutSeconds" to request.executionTimeoutSeconds,
        )
        val body = mapper.writeValueAsString(payload)
        return try {
            exchange("POST", "/v1/jobs", body, RuntimeJob::class.java)
        } catch (first: AgentRuntimeUnavailableException) {
            // The first request may have reached the server. Retrying the exact body with the same
            // key is safe because idempotency is enforced per Runtime tenant.
            exchange("POST", "/v1/jobs", body, RuntimeJob::class.java)
        }
    }

    fun status(jobId: String): RuntimeJob = exchange("GET", "/v1/jobs/${safeId(jobId)}", null, RuntimeJob::class.java)

    fun result(jobId: String): RuntimeResult = exchange("GET", "/v1/jobs/${safeId(jobId)}/result", null, RuntimeResult::class.java)

    fun cancel(jobId: String): RuntimeJob = exchange("POST", "/v1/jobs/${safeId(jobId)}/cancel", "{}", RuntimeJob::class.java)

    private fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid Runtime job ID." }
        return id
    }

    private fun <T> exchange(method: String, path: String, body: String?, responseType: Class<T>): T {
        val builder = HttpRequest.newBuilder(resolve(path))
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer ${properties.token}")
            .header("Accept", "application/json")
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        val response = try {
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw AgentRuntimeUnavailableException(exception)
        } catch (exception: IOException) {
            throw AgentRuntimeUnavailableException(exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRuntimeUnavailableException(exception)
        }
        if (response.statusCode() !in 200..299) throw AgentRuntimeRejectedException(response.statusCode())
        return try {
            mapper.readValue(response.body(), responseType)
        } catch (exception: Exception) {
            throw AgentRuntimeInvalidResponseException(exception)
        }
    }

    private fun resolve(path: String): URI = URI(properties.baseUrl.toString().trimEnd('/') + path)
}
