package nl.vdzon.pvdd.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import kotlin.test.*
import nl.vdzon.pvdd.analysis.ContentResultValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

class AcceptanceRuntimeFixtureTest {
    private val mapper = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val fixtures = linkedMapOf<String, JsonNode>()
    private val jobs = linkedMapOf<String, String>()
    private val calls = mutableListOf<Pair<String, String?>>()
    private var rejectControl = false
    private var loseCreateResponse = false

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            calls += "$method $path" to exchange.requestHeaders.getFirst("Authorization")
            when {
                path == "/v2/test-control/mocks" && rejectControl -> respond(exchange, 403, "{\"privateAdminDetail\":\"do-not-expose\"}")
                path == "/v2/test-control/mocks" && method == "POST" -> {
                    val body = mapper.readTree(exchange.requestBody)
                    val key = body.path("idempotencyKey").asText()
                    if (fixtures.containsKey(key)) respond(exchange, 409, "{}")
                    else { fixtures[key] = body; respond(exchange, 201, "{}") }
                }
                path == "/v2/test-control/mocks" -> respond(exchange, 200,
                    mapper.writeValueAsString(fixtures.values.map { body -> mapOf(
                        "tenantId" to "pvdd", "idempotencyKey" to body.path("idempotencyKey").asText(),
                        // Runtime uses NON_NULL serialization: a successful fixture omits errorCode.
                        "result" to body.path("result"), "outputSequence" to emptyList<String>(),
                    ) }))
                path == "/v2/jobs" && method == "GET" -> respond(exchange, 200,
                    mapper.writeValueAsString(mapOf("items" to jobs.map { (key, id) -> job(id, key) }, "nextCursor" to null)))
                path == "/v2/jobs" && method == "POST" -> {
                    val body = mapper.readTree(exchange.requestBody)
                    val key = body.path("idempotencyKey").asText()
                    if (!fixtures.containsKey(key)) respond(exchange, 422, "{}")
                    else {
                        val id = jobs.getOrPut(key) { UUID.randomUUID().toString() }
                        if (loseCreateResponse) { loseCreateResponse = false; exchange.close() }
                        else respond(exchange, 202, mapper.writeValueAsString(job(id, key)))
                    }
                }
                path.endsWith("/result") -> {
                    val id = path.split('/')[3]
                    val key = jobs.entries.single { it.value == id }.key
                    respond(exchange, 200, mapper.writeValueAsString(mapOf("jobId" to id,
                        "result" to fixtures.getValue(key).path("result"), "completedAt" to "2026-10-14T00:00:00Z")))
                }
                path == "/v2/uploads" -> respond(exchange, 201, """{"uploadId":"upload-1","objectId":"object-1","chunkSizeBytes":1024,"offset":0}""")
                method == "PATCH" -> {
                    val end = exchange.requestHeaders.getFirst("Upload-Offset").toLong() + exchange.requestBody.readAllBytes().size
                    exchange.responseHeaders.add("Upload-Offset", end.toString())
                    exchange.sendResponseHeaders(204, -1); exchange.close()
                }
                path.endsWith("/complete") -> respond(exchange, 200, "{}")
                else -> respond(exchange, 404, "{}")
            }
        }
        server.start()
    }

    @AfterEach fun close() = server.stop(0)

    @Test fun `every typed fixture is registered for its exact v2 job before normal upload and submit`() {
        val client = client()
        AcceptanceRuntimeFixture.entries.forEach { profile ->
            val created = client.create(request(profile.name, profile))
            val result = client.result(created.id).result
            assertEquals("pvdd", fixtures.getValue(profile.name).path("tenantId").asText())
            assertEquals(profile.result(mapper), result)
            when (profile) {
                AcceptanceRuntimeFixture.SELF_TEST -> assertEquals("mock", result.path("message").asText())
                AcceptanceRuntimeFixture.SOURCE_NOTES -> ContentResultValidator().validateNotes(result)
                else -> ContentResultValidator().validateAdvice(result)
            }
        }
        assertTrue(calls.indexOfFirst { it.first == "POST /v2/test-control/mocks" } < calls.indexOfFirst { it.first == "POST /v2/uploads" })
        assertEquals(4, calls.count { it.first == "POST /v2/jobs" })
        assertTrue(calls.filter { it.first.contains("/test-control/") }.all { it.second == "Bearer fixture-control" })
        assertTrue(calls.filterNot { it.first.contains("/test-control/") }.all { it.second == "Bearer consumer" })
    }

    @Test fun `lost job response and repeated submit reuse the same job without consuming another fixture`() {
        loseCreateResponse = true
        val client = client()
        val request = request("same-run", AcceptanceRuntimeFixture.ADVICE_AB)
        val first = client.create(request)
        assertEquals(first.id, client.create(request).id)
        assertEquals(1, fixtures.size)
        assertEquals(1, jobs.size)
        assertEquals(1, calls.count { it.first == "POST /v2/test-control/mocks" })
    }

    @Test fun `fixture registration response recovery accepts only the exact same unconsumed result`() {
        val request = request("preseeded", AcceptanceRuntimeFixture.ADVICE_C)
        fixtures[request.idempotencyKey] = mapper.valueToTree(mapOf("tenantId" to "pvdd",
            "idempotencyKey" to request.idempotencyKey, "result" to requireNotNull(request.acceptanceFixture).result(mapper)))
        assertEquals("SUCCEEDED", client().create(request).status)
        fixtures["wrong"] = mapper.readTree("""{"tenantId":"pvdd","idempotencyKey":"wrong","result":{"message":"different"}}""")
        assertFailsWith<AgentRuntimeException> { client().create(request("wrong", AcceptanceRuntimeFixture.ADVICE_C)) }
        assertFalse(jobs.containsKey("wrong"))
    }

    @Test fun `test-control denial fails before creating an analysis job and hides admin details`() {
        rejectControl = true
        val error = assertFailsWith<AgentRuntimeRejectedException> { client().create(request("denied", AcceptanceRuntimeFixture.ADVICE_AB)) }
        assertEquals(403, error.statusCode)
        assertFalse(error.message.orEmpty().contains("do-not-expose"))
        assertTrue(jobs.isEmpty())
        assertFalse(calls.any { it.first == "POST /v2/uploads" })
    }

    @Test fun `production rejects fixture configuration and all per-request mock overrides before any HTTP`() {
        listOf(
            properties().copy(environment="production", vendorId="anthropic", model="real", mode="API"),
            properties().copy(environment="local"),
            properties().copy(mockControlToken=""),
        ).forEach { assertFailsWith<IllegalArgumentException> { it.validate() } }
        val production = properties().copy(environment="production", vendorId="anthropic", model="real", mode="API",
            acceptanceFixturesEnabled=false, mockControlToken="")
        production.validate()
        val client = AgentRuntimeClient(production, mapper)
        assertFailsWith<IllegalArgumentException> { client.create(request("fixture", AcceptanceRuntimeFixture.ADVICE_AB)) }
        assertFailsWith<IllegalArgumentException> { client.create(RuntimeCreateRequest("override", "test", mapper.createObjectNode(),
            execution=RuntimeExecution("mock","mock","MOCK"))) }
        assertTrue(calls.isEmpty())
        assertFalse(properties().toString().contains("fixture-control"))
    }

    private fun properties() = AgentRuntimeProperties(baseUrl=URI("http://127.0.0.1:${server.address.port}"), token="consumer",
        environment="acceptance", acceptanceFixturesEnabled=true, mockControlToken="fixture-control")
    private fun client() = AgentRuntimeClient(properties().also { it.validate() }, mapper)
    private fun request(key: String, fixture: AcceptanceRuntimeFixture) = RuntimeCreateRequest(key, "synthetic test",
        mapper.readTree("""{"type":"object"}"""), acceptanceFixture=fixture)
    private fun job(id: String, key: String) = mapOf("id" to id, "tenantId" to "pvdd", "idempotencyKey" to key,
        "execution" to mapOf("vendorId" to "mock","model" to "mock","mode" to "MOCK"), "status" to "SUCCEEDED", "phase" to "COMPLETED")
    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(); exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
    }
}
