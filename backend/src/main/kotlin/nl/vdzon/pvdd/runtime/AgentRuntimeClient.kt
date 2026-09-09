package nl.vdzon.pvdd.runtime

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class RuntimeExecution(val vendorId: String, val model: String, val mode: String)

data class RuntimeJob(
    val id: String,
    val tenantId: String,
    val idempotencyKey: String,
    val execution: RuntimeExecution,
    val status: String,
    val phase: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class RuntimeResult(
    val jobId: String,
    val result: JsonNode,
    val completedAt: Instant,
    val artifacts: List<JsonNode> = emptyList(),
    val usageSummary: JsonNode? = null,
)

data class RuntimeCreateRequest(
    val idempotencyKey: String,
    val prompt: String,
    val responseSchema: JsonNode,
    val environmentKeys: List<String> = emptyList(),
    val executionTimeoutSeconds: Int = 300,
)

interface AgentRuntimeGateway {
    fun create(request: RuntimeCreateRequest): RuntimeJob
    fun status(jobId: String): RuntimeJob
    fun result(jobId: String): RuntimeResult
    fun cancel(jobId: String): RuntimeJob
}

open class AgentRuntimeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AgentRuntimeRejectedException(val statusCode: Int) : AgentRuntimeException("Agent Runtime rejected the request (HTTP $statusCode).")
class AgentRuntimeUnavailableException(cause: Throwable) : AgentRuntimeException("Agent Runtime is temporarily unavailable.", cause)
class AgentRuntimeInvalidResponseException(cause: Throwable? = null) : AgentRuntimeException("Agent Runtime returned an invalid response.", cause)

private data class RuntimeJobPage(val items: List<RuntimeJob>, val nextCursor: String? = null)
private data class RuntimeUpload(val uploadId: String, val objectId: String, val chunkSizeBytes: Long, val offset: Long)

class AgentRuntimeClient(
    private val properties: AgentRuntimeProperties,
    private val mapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
) : AgentRuntimeGateway {
    override fun create(request: RuntimeCreateRequest): RuntimeJob {
        findByIdempotencyKey(request.idempotencyKey)?.let { return it }

        val promptBytes = request.prompt.toByteArray(StandardCharsets.UTF_8)
        val upload = reservePrompt(promptBytes)
        uploadPrompt(upload, promptBytes)
        completeUpload(upload.uploadId)
        val body = mapper.writeValueAsString(
            mapOf(
                "idempotencyKey" to request.idempotencyKey,
                "jobKind" to "APPLICATION_WORK",
                "taskType" to "STRUCTURED_GENERATION",
                "execution" to mapOf("vendorId" to properties.vendorId, "model" to properties.model, "mode" to properties.mode),
                "input" to mapOf(
                    "instruction" to PROMPT_INSTRUCTION,
                    "objects" to listOf(mapOf("objectId" to upload.objectId, "name" to "prompt", "role" to "PROMPT")),
                ),
                "output" to mapOf("resultSchema" to request.responseSchema, "artifacts" to emptyList<Any>()),
                "environmentKeys" to request.environmentKeys,
                "executionTimeoutSeconds" to request.executionTimeoutSeconds,
            )
        )
        return try {
            createJob(body)
        } catch (first: AgentRuntimeUnavailableException) {
            findByIdempotencyKey(request.idempotencyKey) ?: createJob(body)
        } catch (rejected: AgentRuntimeRejectedException) {
            val existing = findByIdempotencyKey(request.idempotencyKey)
            bestEffortDeleteUpload(upload.uploadId)
            existing ?: throw rejected
        }
    }

    override fun status(jobId: String): RuntimeJob = jsonExchange("GET", "/v2/jobs/${safeId(jobId)}", null, RuntimeJob::class.java)
    override fun result(jobId: String): RuntimeResult = jsonExchange("GET", "/v2/jobs/${safeId(jobId)}/result", null, RuntimeResult::class.java)
    override fun cancel(jobId: String): RuntimeJob = jsonExchange("POST", "/v2/jobs/${safeId(jobId)}/cancel", "{}", RuntimeJob::class.java)

    private fun reservePrompt(bytes: ByteArray): RuntimeUpload {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val body = mapper.writeValueAsString(
            mapOf(
                "filename" to "prompt-${sha256.take(12)}.md",
                "mimeType" to "text/markdown",
                "sizeBytes" to bytes.size,
                "sha256" to sha256,
            )
        )
        return jsonExchange("POST", "/v2/uploads", body, RuntimeUpload::class.java, properties.uploadTimeout)
    }

    private fun uploadPrompt(upload: RuntimeUpload, bytes: ByteArray) {
        var offset = upload.offset
        var recoveriesWithoutProgress = 0
        val chunkSize = upload.chunkSizeBytes.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        while (offset < bytes.size) {
            val end = minOf(bytes.size.toLong(), offset + chunkSize).toInt()
            val chunk = bytes.copyOfRange(offset.toInt(), end)
            offset = try {
                appendChunk(upload.uploadId, offset, chunk).also { recoveriesWithoutProgress = 0 }
            } catch (first: AgentRuntimeUnavailableException) {
                val recoveredOffset = currentUploadOffset(upload.uploadId)
                if (recoveredOffset == offset && ++recoveriesWithoutProgress >= MAX_UPLOAD_RECOVERIES) throw first
                if (recoveredOffset > offset) recoveriesWithoutProgress = 0
                recoveredOffset
            }
        }
    }

    private fun appendChunk(uploadId: String, offset: Long, chunk: ByteArray): Long {
        val response = send(
            HttpRequest.newBuilder(resolve("/v2/uploads/${safeId(uploadId)}"))
                .timeout(properties.uploadTimeout)
                .header("Authorization", "Bearer ${properties.token}")
                .header("Content-Type", "application/offset+octet-stream")
                .header("Upload-Offset", offset.toString())
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        requireSuccess(response.statusCode())
        return response.headers().firstValue("Upload-Offset").orElseThrow { AgentRuntimeInvalidResponseException() }.toLongOrNull()
            ?: throw AgentRuntimeInvalidResponseException()
    }

    private fun currentUploadOffset(uploadId: String): Long {
        val response = send(
            HttpRequest.newBuilder(resolve("/v2/uploads/${safeId(uploadId)}"))
                .timeout(properties.uploadTimeout)
                .header("Authorization", "Bearer ${properties.token}")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        requireSuccess(response.statusCode())
        return response.headers().firstValue("Upload-Offset").orElseThrow { AgentRuntimeInvalidResponseException() }.toLongOrNull()
            ?: throw AgentRuntimeInvalidResponseException()
    }

    private fun completeUpload(uploadId: String) {
        jsonExchange("POST", "/v2/uploads/${safeId(uploadId)}/complete", "{}", JsonNode::class.java, properties.uploadTimeout)
    }

    private fun createJob(body: String): RuntimeJob = jsonExchange("POST", "/v2/jobs", body, RuntimeJob::class.java)

    private fun findByIdempotencyKey(key: String): RuntimeJob? {
        var cursor: String? = null
        repeat(MAX_JOB_PAGES) {
            val suffix = cursor?.let { "&cursor=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }.orEmpty()
            val page = jsonExchange("GET", "/v2/jobs?limit=$JOB_PAGE_SIZE$suffix", null, RuntimeJobPage::class.java)
            page.items.firstOrNull { it.idempotencyKey == key }?.let { return it }
            cursor = page.nextCursor ?: return null
        }
        return null
    }

    private fun bestEffortDeleteUpload(uploadId: String) {
        runCatching { noContentExchange("DELETE", "/v2/uploads/${safeId(uploadId)}") }
    }

    private fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid Runtime ID." }
        return id
    }

    private fun <T> jsonExchange(method: String, path: String, body: String?, responseType: Class<T>, timeout: Duration = properties.requestTimeout): T {
        val builder = HttpRequest.newBuilder(resolve(path))
            .timeout(timeout)
            .header("Authorization", "Bearer ${properties.token}")
            .header("Accept", "application/json")
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        val response = send(builder.build(), HttpResponse.BodyHandlers.ofString())
        requireSuccess(response.statusCode())
        return try {
            mapper.readValue(response.body(), responseType)
        } catch (exception: Exception) {
            throw AgentRuntimeInvalidResponseException(exception)
        }
    }

    private fun noContentExchange(method: String, path: String) {
        val request = HttpRequest.newBuilder(resolve(path))
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer ${properties.token}")
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        requireSuccess(send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    private fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = try {
        httpClient.send(request, handler)
    } catch (exception: HttpTimeoutException) {
        throw AgentRuntimeUnavailableException(exception)
    } catch (exception: IOException) {
        throw AgentRuntimeUnavailableException(exception)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw AgentRuntimeUnavailableException(exception)
    }

    private fun requireSuccess(statusCode: Int) {
        if (statusCode !in 200..299) throw AgentRuntimeRejectedException(statusCode)
    }

    private fun resolve(path: String): URI = URI(properties.baseUrl.toString().trimEnd('/') + path)

    companion object {
        private const val JOB_PAGE_SIZE = 100
        private const val MAX_JOB_PAGES = 100
        private const val MAX_UPLOAD_RECOVERIES = 3
        private const val PROMPT_INSTRUCTION =
            "Lees de volledige opdracht uit het invoerobject 'prompt'. Voer die exact uit en geef uitsluitend JSON terug dat aan het opgegeven resultSchema voldoet."
    }
}
