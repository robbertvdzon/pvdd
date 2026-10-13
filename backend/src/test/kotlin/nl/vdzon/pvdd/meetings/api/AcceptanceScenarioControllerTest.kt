package nl.vdzon.pvdd.meetings.api

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.AuthenticatedUser
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.CsrfProtectionFilter
import nl.vdzon.pvdd.auth.GoogleIdentity
import nl.vdzon.pvdd.auth.ProductionReadAccessFilter
import nl.vdzon.pvdd.auth.UserSessionService
import nl.vdzon.pvdd.source.MeetingSourceProperties
import nl.vdzon.pvdd.meetings.AcceptanceResetResult
import nl.vdzon.pvdd.meetings.AcceptanceScenarioResetService
import nl.vdzon.pvdd.meetings.MeetingCheckResult
import nl.vdzon.pvdd.meetings.MeetingCheckScheduler
import nl.vdzon.pvdd.meetings.MeetingCheckStatus
import nl.vdzon.pvdd.meetings.MeetingCheckWorkflow
import nl.vdzon.pvdd.meetings.MutationGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException

class AcceptanceScenarioControllerTest {
    private data class Call(val method: String, val path: String, val body: String)

    private val calls = CopyOnWriteArrayList<Call>()
    private lateinit var server: HttpServer
    private var stubStatus = 200
    private var stubBody = """{"scenario":"een-vergadering"}"""
    private lateinit var baseUrl: URI
    private lateinit var workflow: MeetingCheckWorkflow
    private lateinit var resets: AcceptanceScenarioResetService

    @BeforeEach
    fun startStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += Call(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val bytes = stubBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(stubStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = URI("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun stopStub() = server.stop(0)

    private fun mvc(environment: String, mode: String = "acceptance-bypass", url: URI = baseUrl): MockMvc {
        var controllers: List<Any> = emptyList()
        ApplicationContextRunner()
            .withUserConfiguration(ScenarioOnly::class.java)
            .withPropertyValues("pvdd.environment=$environment", "test.url=$url")
            .run {
                controllers = it.getBeansOfType(AcceptanceScenarioController::class.java).values.toList()
                workflow = it.getBean(MeetingCheckWorkflow::class.java)
                resets = it.getBean(AcceptanceScenarioResetService::class.java)
                if (environment == "acceptance") controllers += MeetingCheckController(workflow, it.getBean(MutationGuard::class.java))
            }
        if (environment == "acceptance") assertEquals(2, controllers.size) else assertEquals(0, controllers.size)
        val config = AuthConfig("client", environment, mode)
        val authenticator = Authenticator(config) { GoogleIdentity("robbertvdzon@gmail.com", true) }
        val sessions = mock(UserSessionService::class.java)
        `when`(sessions.authenticate("valid")).thenReturn(AuthenticatedUser("robbertvdzon@gmail.com"))
        `when`(sessions.authenticate("expired")).thenThrow(ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED))
        return MockMvcBuilders.standaloneSetup(*controllers.toTypedArray())
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                ProductionReadAccessFilter("", "", config),
                ApiAuthenticationFilter(authenticator, config, sessions),
                CsrfProtectionFilter(config),
            ).build()
    }

    @Configuration
    @org.springframework.context.annotation.Import(AcceptanceScenarioController::class)
    class ScenarioOnly {
        @Bean fun properties(@org.springframework.beans.factory.annotation.Value("\${test.url}") url: String) =
            MeetingSourceProperties(URI(url), "local", requestTimeout = Duration.ofSeconds(2))
        @Bean(destroyMethod = "") fun client(): HttpClient = HttpClient.newHttpClient()
        @Bean fun resets(): AcceptanceScenarioResetService = mock(AcceptanceScenarioResetService::class.java).apply {
            `when`(reset()).thenReturn(AcceptanceResetResult(deletedMeetings = 3, preservedMeetings = 1))
        }
        @Bean fun workflow(): MeetingCheckWorkflow = mock(MeetingCheckWorkflow::class.java).apply {
            `when`(check()).thenReturn(MeetingCheckResult(MeetingCheckStatus.IMPORTED))
        }
        @Bean fun scheduler(workflow: MeetingCheckWorkflow) = MeetingCheckScheduler(workflow)
        @Bean fun guard() = MutationGuard(Clock.systemUTC())
    }

    private fun MockHttpServletRequestBuilder.session(): MockHttpServletRequestBuilder =
        cookie(Cookie(UserSessionService.COOKIE_NAME, "valid"))

    private fun MockHttpServletRequestBuilder.csrf(): MockHttpServletRequestBuilder =
        cookie(Cookie(UserSessionService.CSRF_COOKIE_NAME, "csrf-value")).header(UserSessionService.CSRF_HEADER_NAME, "csrf-value")

    private val scenarios = listOf(
        "een-vergadering", "twee-vergaderingen", "meer-dan-drie",
        "grens-dag-10-en-11", "extra-agenda-niet-gepubliceerd", "een-vergadering-mislukt",
        "geen-toekomstige-vergadering", "gehele-broncontrole-mislukt",
    )

    private fun assertForwardedJson(result: MvcResult, status: Int, body: String) {
        assertEquals(status, result.response.status)
        assertEquals(body, result.response.contentAsString)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_JSON_VALUE))
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
    }

    @Test
    fun `GET forwards the overview`() {
        val result = mvc("acceptance").perform(get("/api/scenarios")).andReturn()
        assertForwardedJson(result, 200, stubBody)
        assertEquals(listOf(Call("GET", "/api/scenarios", "")), calls)
    }

    @Test
    fun `POST and PUT select each scenario exactly once`() {
        val mvc = mvc("acceptance")
        scenarios.forEach { name ->
            listOf("POST", "PUT").forEach { method ->
                calls.clear()
                val builder = if (method == "POST") post("/api/scenarios/$name") else put("/api/scenarios/$name")
                assertForwardedJson(mvc.perform(builder.content("{\"ignored\":true}")).andReturn(), 200, stubBody)
                assertEquals(listOf(Call(method, "/api/scenarios/$name", "")), calls)
            }
        }
    }

    @Test
    fun `an unknown scenario name passes the mock error through`() {
        stubStatus = 400
        stubBody = """{"error":"unknown_scenario","allowed":["een-vergadering"]}"""
        val result = mvc("acceptance").perform(post("/api/scenarios/bestaat-niet")).andReturn()
        assertForwardedJson(result, 400, stubBody)
        assertEquals(listOf(Call("POST", "/api/scenarios/bestaat-niet", "")), calls)
    }

    @Test
    fun `reset and document touch forward with and without a version`() {
        val mvc = mvc("acceptance")
        assertForwardedJson(mvc.perform(post("/api/scenarios/reset")).andReturn(), 200, stubBody)
        assertForwardedJson(mvc.perform(put("/api/scenarios/documents/scn-a_1.b-doc-housing")).andReturn(), 200, stubBody)
        assertForwardedJson(
            mvc.perform(put("/api/scenarios/documents/doc-x").contentType(MediaType.APPLICATION_JSON).content("""{ "version": 3 }""")).andReturn(),
            200, stubBody,
        )
        assertEquals(
            listOf(
                Call("POST", "/api/scenarios/reset", ""),
                Call("PUT", "/api/scenarios/documents/scn-a_1.b-doc-housing", ""),
                Call("PUT", "/api/scenarios/documents/doc-x", """{"version":3}"""),
            ),
            calls,
        )
    }

    @Test
    fun `reset data is authenticated csrf protected and returns a bounded cleanup receipt`() {
        val mvc = mvc("acceptance", mode = "google")
        assertEquals(401, mvc.perform(post("/api/scenarios/reset-data").header("Idempotency-Key", "reset-data-1")).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/api/scenarios/reset-data").session().header("Idempotency-Key", "reset-data-1")).andReturn().response.status)
        verifyNoInteractions(resets)
        val result = mvc.perform(post("/api/scenarios/reset-data").session().csrf().header("Idempotency-Key", "reset-data-1")).andReturn()
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("\"mode\":\"synthetic-only\""))
        assertTrue(result.response.contentAsString.contains("\"deletedMeetings\":3"))
        assertTrue(result.response.contentAsString.contains("\"preservedMeetings\":1"))
        mvc.perform(post("/api/scenarios/reset-data").session().csrf().header("Idempotency-Key", "reset-data-1"))
        verify(resets, times(1)).reset()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `scheduled check calls the real scheduler and shares check-now idempotency and rate limit`() {
        val mvc = mvc("acceptance", mode = "google")
        assertEquals(401, mvc.perform(post("/api/scenarios/scheduled-check").header("Idempotency-Key", "scheduled-1")).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/api/scenarios/scheduled-check").session().header("Idempotency-Key", "scheduled-1")).andReturn().response.status)
        verifyNoInteractions(workflow)
        val result = mvc.perform(post("/api/scenarios/scheduled-check").session().csrf().header("Idempotency-Key", "scheduled-1")).andReturn()
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("\"status\":\"IMPORTED\""))
        assertEquals(MeetingCheckScheduler.CRON, result.response.getHeader("X-Meeting-Check-Cron"))
        assertEquals(MeetingCheckScheduler.ZONE, result.response.getHeader("X-Meeting-Check-Zone"))
        mvc.perform(post("/api/meetings/check-now").session().csrf().header("Idempotency-Key", "scheduled-1"))
        verify(workflow, times(1)).check()
        (2..6).forEach { index ->
            assertEquals(200, mvc.perform(post("/api/meetings/check-now").session().csrf().header("Idempotency-Key", "scheduled-$index")).andReturn().response.status)
        }
        assertEquals(429, mvc.perform(post("/api/scenarios/scheduled-check").session().csrf().header("Idempotency-Key", "scheduled-7")).andReturn().response.status)
        verify(workflow, times(6)).check()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `requests outside the allowlist are never forwarded`() {
        val mvc = mvc("acceptance")
        val results = listOf(
            get("/api/scenarios/../x"),
            get("/api/scenarios/een-vergadering/extra"),
            post("/api/scenarios/documents"),
            post("/api/scenarios/.."),
            delete("/api/scenarios/een-vergadering"),
            delete("/api/scenarios"),
            post("/api/scenarios"),
            get("/api/scenarios/reset"),
            put("/api/scenarios/documents/" + "a".repeat(101)),
            put("/api/scenarios/documents/bad%20id"),
            put("/api/scenarios/documents/bad:id"),
            put("/api/scenarios/documents/ok").content("x".repeat(1025)),
            put("/api/scenarios/documents/ok").content("""{"version":"drie"}"""),
            put("/api/scenarios/documents/ok").content("""{"version":3,"extra":1}"""),
            put("/api/scenarios/documents/ok").content("[1]"),
        ).map { mvc.perform(it).andReturn().response.status }
        results.forEach { assertTrue(it in setOf(400, 404, 405), "status $it") }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `without a session the request is refused like other api routes`() {
        val mvc = mvc("acceptance", mode = "google")
        assertEquals(401, mvc.perform(get("/api/scenarios")).andReturn().response.status)
        assertEquals(401, mvc.perform(get("/api/scenarios").cookie(Cookie(UserSessionService.COOKIE_NAME, "expired"))).andReturn().response.status)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `in google mode writes need the csrf pair and reads do not`() {
        val mvc = mvc("acceptance", mode = "google")
        assertEquals(200, mvc.perform(get("/api/scenarios").session()).andReturn().response.status)
        calls.clear()
        val rejected = mvc.perform(post("/api/scenarios/meer-dan-drie").session()).andReturn().response
        assertEquals(403, rejected.status)
        assertEquals("""{"error":"csrf_failed"}""", rejected.contentAsString)
        assertEquals(403, mvc.perform(put("/api/scenarios/reset").session().cookie(Cookie(UserSessionService.CSRF_COOKIE_NAME, "a")).header(UserSessionService.CSRF_HEADER_NAME, "b")).andReturn().response.status)
        assertEquals(emptyList(), calls)
        assertEquals(200, mvc.perform(post("/api/scenarios/meer-dan-drie").session().csrf()).andReturn().response.status)
        assertEquals(listOf(Call("POST", "/api/scenarios/meer-dan-drie", "")), calls)
    }

    @Test
    fun `acceptance bypass needs no csrf`() {
        assertEquals(200, mvc("acceptance").perform(post("/api/scenarios/reset")).andReturn().response.status)
        assertEquals(1, calls.size)
    }

    @Test
    fun `production and local have no scenario route`() {
        listOf("production", "local").forEach { environment ->
            val mvc = mvc(environment, mode = "google")
            listOf(
                get("/api/scenarios").session(),
                post("/api/scenarios/reset").session().csrf(),
                post("/api/scenarios/reset-data").session().csrf().header("Idempotency-Key", "reset-data-1"),
                post("/api/scenarios/scheduled-check").session().csrf().header("Idempotency-Key", "scheduled-1"),
                post("/api/scenarios/meer-dan-drie").session().csrf(),
                get("/api/scenarios").header(ProductionReadAccessFilter.HEADER, "x".repeat(40)),
            ).forEach { request ->
                val status = mvc.perform(request).andReturn().response.status
                assertTrue(status in setOf(401, 404), "$environment status $status")
            }
            assertEquals(404, mvc.perform(get("/api/scenarios").session()).andReturn().response.status)
            assertEquals(404, mvc.perform(post("/api/scenarios/x").session().csrf()).andReturn().response.status)
        }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `controller maps connection failure and timeout to 502`() {
        val closed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).let { val p = it.address.port; it.stop(0); p }
        val dead = testController(MeetingSourceProperties(URI("http://127.0.0.1:$closed"), "local"))
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { Thread.sleep(1500) }
            start()
        }
        try {
            val timing = testController(
                MeetingSourceProperties(URI("http://127.0.0.1:${slow.address.port}"), "local", requestTimeout = Duration.ofMillis(200)),
            )
            listOf(dead, timing).forEach { controller ->
                val mvc = MockMvcBuilders.standaloneSetup(controller, HealthStub()).build()
                assertForwardedJson(mvc.perform(get("/api/scenarios")).andReturn(), 502, """{"error":"scenario_mock_unreachable"}""")
                assertForwardedJson(mvc.perform(post("/api/scenarios/reset")).andReturn(), 502, """{"error":"scenario_mock_unreachable"}""")
                assertEquals("ok", mvc.perform(get("/api/other")).andReturn().response.contentAsString)
            }
        } finally {
            slow.stop(0)
        }
    }

    private fun testController(properties: MeetingSourceProperties) = AcceptanceScenarioController(
        properties, HttpClient.newHttpClient(), mock(AcceptanceScenarioResetService::class.java),
        MeetingCheckScheduler(mock(MeetingCheckWorkflow::class.java)), MutationGuard(Clock.systemUTC()),
    )

    @org.springframework.web.bind.annotation.RestController
    class HealthStub {
        @org.springframework.web.bind.annotation.GetMapping("/api/other")
        fun other() = "ok"
    }
}
