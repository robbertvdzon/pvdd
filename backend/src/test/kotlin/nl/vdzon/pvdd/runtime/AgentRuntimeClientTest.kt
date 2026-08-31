package nl.vdzon.pvdd.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `environment policy rejects real AI in acceptance and mocked AI in production`() {
        assertFailsWith<IllegalArgumentException> {
            AgentRuntimeProperties(provider = "CODEX", model = "gpt-5.6-sol", environment = "acceptance").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            AgentRuntimeProperties(provider = "MOCKED", model = "mock-model", environment = "production").validate()
        }
        AgentRuntimeProperties(provider = "MOCKED", model = "mock-model", environment = "acceptance").validate()
        AgentRuntimeProperties(provider = "CODEX", model = "gpt-5.6-sol", environment = "production").validate()
    }

    @Test
    fun `create status result and cancel use bearer authenticated v1 contract`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/") { exchange ->
            calls += "${exchange.requestMethod} ${exchange.requestURI.path}"
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            when (exchange.requestURI.path) {
                "/v1/jobs" -> {
                    val body = mapper.readTree(exchange.requestBody)
                    assertEquals("APPLICATION_WORK", body.path("jobKind").asText())
                    assertEquals("PVDD_TECHNICAL", body.path("environmentKeys").get(0).asText())
                    respond(exchange, 202, jobJson("QUEUED"))
                }
                "/v1/jobs/job-1" -> respond(exchange, 200, jobJson("RUNNING"))
                "/v1/jobs/job-1/result" -> respond(exchange, 200, resultJson())
                "/v1/jobs/job-1/cancel" -> respond(exchange, 200, jobJson("CANCELLED"))
                else -> respond(exchange, 404, "{}")
            }
        }
        server.start()
        val client = client()
        val schema = mapper.readTree("""{"type":"object"}""")

        assertEquals("QUEUED", client.create(RuntimeCreateRequest("same-key", "test", schema, listOf("PVDD_TECHNICAL"))).status)
        assertEquals("RUNNING", client.status("job-1").status)
        assertEquals("ok", client.result("job-1").result.path("message").asText())
        assertEquals("CANCELLED", client.cancel("job-1").status)
        assertEquals(listOf("POST /v1/jobs", "GET /v1/jobs/job-1", "GET /v1/jobs/job-1/result", "POST /v1/jobs/job-1/cancel"), calls)
    }

    @Test
    fun `client safely translates 4xx and 5xx without response content`() {
        val status = AtomicInteger(400)
        server.createContext("/v1/jobs") { exchange -> respond(exchange, status.get(), """{"message":"must not leak"}""") }
        server.start()
        val client = client()
        val request = request("rejected")

        assertEquals(400, assertFailsWith<AgentRuntimeRejectedException> { client.create(request) }.statusCode)
        status.set(503)
        assertEquals(503, assertFailsWith<AgentRuntimeRejectedException> { client.create(request.copy(idempotencyKey = "unavailable")) }.statusCode)
    }

    @Test
    fun `timeout is translated and submit is retried only with identical idempotency key`() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/jobs") { exchange ->
            bodies += exchange.requestBody.bufferedReader().readText()
            Thread.sleep(250)
            respond(exchange, 202, jobJson("QUEUED"))
        }
        server.start()

        assertFailsWith<AgentRuntimeUnavailableException> {
            client(requestTimeout = Duration.ofMillis(75)).create(request("timeout-key"))
        }
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { mapper.readTree(it).path("idempotencyKey").asText() == "timeout-key" })
    }

    @Test
    fun `lost submit response recovers through Runtime idempotency without a new job`() {
        val attempts = AtomicInteger()
        val keys = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/jobs") { exchange ->
            keys += mapper.readTree(exchange.requestBody).path("idempotencyKey").asText()
            if (attempts.incrementAndGet() == 1) exchange.close()
            else respond(exchange, 202, jobJson("QUEUED"))
        }
        server.start()

        val job = client().create(request("lost-response-key"))
        assertEquals("job-1", job.id)
        assertEquals(listOf("lost-response-key", "lost-response-key"), keys)
    }

    @Test
    fun `repeating a create uses the caller supplied idempotency key`() {
        val keys = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/jobs") { exchange ->
            keys += mapper.readTree(exchange.requestBody).path("idempotencyKey").asText()
            respond(exchange, 202, jobJson("QUEUED"))
        }
        server.start()
        val client = client()
        val request = request("stable-key")

        assertEquals(client.create(request).id, client.create(request).id)
        assertEquals(listOf("stable-key", "stable-key"), keys)
    }

    private fun client(requestTimeout: Duration = Duration.ofSeconds(1)): AgentRuntimeClient = AgentRuntimeClient(
        AgentRuntimeProperties(
            baseUrl = java.net.URI("http://127.0.0.1:${server.address.port}"),
            token = "test-token",
            provider = "MOCKED",
            model = "mock-model",
            requestTimeout = requestTimeout,
        ),
        mapper,
    )

    private fun request(key: String) = RuntimeCreateRequest(key, "technical test", mapper.readTree("""{"type":"object"}"""))

    private fun jobJson(status: String) = """{
      "id":"job-1","tenantId":"pvdd","idempotencyKey":"same-key","provider":"MOCKED",
      "model":"mock-model","status":"$status","phase":"technical"
    }""".trimIndent()

    private fun resultJson() = """{
      "jobId":"job-1","result":{"message":"ok"},"completedAt":"2026-08-31T12:00:00Z"
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
}
