package nl.vdzon.pvdd.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MeetingSourceClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: URI

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { it.respond(200, "<html>ok</html>") }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/large") { it.respond(200, "x".repeat(2048)) }
        server.start()
        baseUrl = URI("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    @Test
    fun `fetches a bounded same-origin response`() {
        val page = client().fetch(URI("/ok"))
        assertEquals("<html>ok</html>", page.body)
    }

    @Test
    fun `does not follow redirects`() {
        val failure = assertFailsWith<SourceTransportException> { client().fetch(URI("/redirect")) }
        assertEquals(SourceTransportCode.DISALLOWED_REDIRECT, failure.code)
    }

    @Test
    fun `rejects oversized and foreign responses`() {
        val oversized = assertFailsWith<SourceTransportException> { client(maxBytes = 1024).fetch(URI("/large")) }
        assertEquals(SourceTransportCode.RESPONSE_TOO_LARGE, oversized.code)

        val foreign = assertFailsWith<SourceTransportException> { client().fetch(URI("https://example.org/agenda")) }
        assertEquals(SourceTransportCode.INVALID_URL, foreign.code)
    }

    private fun client(maxBytes: Int = 4096): MeetingSourceClient {
        val properties = MeetingSourceProperties(
            baseUrl = baseUrl,
            connectTimeout = Duration.ofSeconds(1),
            requestTimeout = Duration.ofSeconds(1),
            maxPageBytes = maxBytes,
        )
        return MeetingSourceClient(
            properties,
            HttpClient.newBuilder().connectTimeout(properties.connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build(),
        )
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
