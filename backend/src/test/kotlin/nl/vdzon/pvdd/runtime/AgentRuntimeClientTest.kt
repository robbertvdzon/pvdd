package nl.vdzon.pvdd.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class AgentRuntimeClientTest {
    private lateinit var server: HttpServer
    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    @Test
    fun `environment policy requires an explicit mock execution in acceptance and real execution in production`() {
        assertFailsWith<IllegalArgumentException> {
            AgentRuntimeProperties(vendorId = "openai", model = "gpt-5.6-sol", mode = "SUBSCRIPTION", environment = "acceptance").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            AgentRuntimeProperties(vendorId = "mock", model = "mock", mode = "MOCK", environment = "production").validate()
        }
        AgentRuntimeProperties(vendorId = "mock", model = "mock", mode = "MOCK", environment = "acceptance").validate()
        AgentRuntimeProperties(vendorId = "openai", model = "gpt-5.6-sol", mode = "SUBSCRIPTION", environment = "production").validate()
    }

    @Test
    fun `large prompt is uploaded in chunks and v2 job has explicit execution`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val uploaded = StringBuilder()
        server.createContext("/") { exchange ->
            calls += "${exchange.requestMethod} ${exchange.requestURI.path}"
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/jobs" -> respond(exchange, 200, pageJson())
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/uploads" -> {
                    val body = mapper.readTree(exchange.requestBody)
                    assertEquals("text/markdown", body.path("mimeType").asText())
                    assertEquals(10, body.path("sizeBytes").asInt())
                    respond(exchange, 201, uploadJson(chunkSize = 4))
                }
                exchange.requestMethod == "PATCH" && exchange.requestURI.path == "/v2/uploads/upload-1" -> {
                    val offset = exchange.requestHeaders.getFirst("Upload-Offset").toLong()
                    assertEquals(uploaded.length.toLong(), offset)
                    uploaded.append(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                    respondEmpty(exchange, 204, mapOf("Upload-Offset" to uploaded.length.toString()))
                }
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/uploads/upload-1/complete" -> respond(exchange, 200, "{}")
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/jobs" -> {
                    val body = mapper.readTree(exchange.requestBody)
                    assertEquals("STRUCTURED_GENERATION", body.path("taskType").asText())
                    assertEquals("openai", body.path("execution").path("vendorId").asText())
                    assertEquals("gpt-5.6-sol", body.path("execution").path("model").asText())
                    assertEquals("SUBSCRIPTION", body.path("execution").path("mode").asText())
                    assertEquals("object-1", body.path("input").path("objects").get(0).path("objectId").asText())
                    assertFalse(body.path("input").path("instruction").asText().contains("0123456789"))
                    assertEquals("PVDD__TECHNICAL", body.path("environmentKeys").get(0).asText())
                    respond(exchange, 202, jobJson("QUEUED", "same-key"))
                }
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/jobs/job-1" -> respond(exchange, 200, jobJson("RUNNING", "same-key"))
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/jobs/job-1/result" -> respond(exchange, 200, resultJson())
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/jobs/job-1/cancel" -> respond(exchange, 200, jobJson("CANCELLED", "same-key"))
                else -> respond(exchange, 404, "{}")
            }
        }
        server.start()
        val client = client()
        val schema = mapper.readTree("""{"type":"object"}""")

        assertEquals("QUEUED", client.create(RuntimeCreateRequest("same-key", "0123456789", schema, listOf("PVDD__TECHNICAL"))).status)
        assertEquals("0123456789", uploaded.toString())
        assertEquals("RUNNING", client.status("job-1").status)
        assertEquals("ok", client.result("job-1").result.path("message").asText())
        assertEquals("CANCELLED", client.cancel("job-1").status)
        assertEquals(3, calls.count { it == "PATCH /v2/uploads/upload-1" })
    }

    @Test
    fun `lost create response is recovered by idempotency lookup without a second upload`() {
        val jobExists = AtomicBoolean(false)
        val uploadReservations = AtomicInteger()
        val jobSubmits = AtomicInteger()
        server.createContext("/") { exchange ->
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/jobs" -> {
                    respond(exchange, 200, if (jobExists.get()) pageJson(jobJson("QUEUED", "lost-key")) else pageJson())
                }
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/uploads" -> {
                    uploadReservations.incrementAndGet()
                    respond(exchange, 201, uploadJson())
                }
                exchange.requestMethod == "PATCH" -> respondEmpty(exchange, 204, mapOf("Upload-Offset" to "6"))
                exchange.requestURI.path.endsWith("/complete") -> respond(exchange, 200, "{}")
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/jobs" -> {
                    jobSubmits.incrementAndGet()
                    jobExists.set(true)
                    exchange.close()
                }
                else -> respond(exchange, 404, "{}")
            }
        }
        server.start()

        assertEquals("job-1", client().create(request("lost-key")).id)
        assertEquals(1, uploadReservations.get())
        assertEquals(1, jobSubmits.get())
    }

    @Test
    fun `repeated create returns existing job without creating another upload`() {
        val jobExists = AtomicBoolean(false)
        val uploads = AtomicInteger()
        server.createContext("/") { exchange ->
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/jobs" ->
                    respond(exchange, 200, if (jobExists.get()) pageJson(jobJson("QUEUED", "stable-key")) else pageJson())
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/uploads" -> {
                    uploads.incrementAndGet()
                    respond(exchange, 201, uploadJson())
                }
                exchange.requestMethod == "PATCH" -> respondEmpty(exchange, 204, mapOf("Upload-Offset" to "6"))
                exchange.requestURI.path.endsWith("/complete") -> respond(exchange, 200, "{}")
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/jobs" -> {
                    jobExists.set(true)
                    respond(exchange, 202, jobJson("QUEUED", "stable-key"))
                }
                else -> respond(exchange, 404, "{}")
            }
        }
        server.start()
        val client = client()

        assertEquals(client.create(request("stable-key")).id, client.create(request("stable-key")).id)
        assertEquals(1, uploads.get())
    }

    private fun client(): AgentRuntimeClient = AgentRuntimeClient(
        AgentRuntimeProperties(
            baseUrl = java.net.URI("http://127.0.0.1:${server.address.port}"),
            token = "test-token",
            vendorId = "openai",
            model = "gpt-5.6-sol",
            mode = "SUBSCRIPTION",
            requestTimeout = Duration.ofSeconds(1),
        ),
        mapper,
    )

    private fun request(key: String) = RuntimeCreateRequest(key, "prompt", mapper.readTree("""{"type":"object"}"""))

    private fun uploadJson(chunkSize: Int = 1024) = """{
      "uploadId":"upload-1","objectId":"object-1","chunkSizeBytes":$chunkSize,"offset":0
    }""".trimIndent()

    private fun jobJson(status: String, key: String) = """{
      "id":"job-1","tenantId":"pvdd","idempotencyKey":"$key",
      "execution":{"vendorId":"openai","model":"gpt-5.6-sol","mode":"SUBSCRIPTION"},
      "status":"$status","phase":"technical"
    }""".trimIndent()

    private fun pageJson(job: String? = null) = if (job == null) """{"items":[],"nextCursor":null}""" else """{"items":[$job],"nextCursor":null}"""

    private fun resultJson() = """{
      "jobId":"job-1","result":{"message":"ok"},"artifacts":[],"usageSummary":{},"completedAt":"2026-08-31T12:00:00Z"
    }""".trimIndent()

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        try {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: IOException) {
            exchange.close()
        }
    }

    private fun respondEmpty(exchange: HttpExchange, status: Int, headers: Map<String, String>) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }
}
