package nl.vdzon.pvdd.documents

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocumentDownloaderTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: URI

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/document") { it.respond(200, "text/plain", "veilig".toByteArray()) }
        server.createContext("/large") { it.respond(200, "application/octet-stream", ByteArray(2048)) }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/document")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        baseUrl = URI("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    @Test
    fun `downloads only from configured origin`() {
        val properties = limits()
        val result = downloader(properties).download(reference("/document"), DownloadBudget(properties))
        assertContentEquals("veilig".toByteArray(), result.bytes)
        assertEquals("text/plain", result.declaredMimeType)

        val failure = assertFailsWith<DocumentDownloadException> {
            downloader(properties).download(reference("https://example.org/document"), DownloadBudget(properties))
        }
        assertEquals(DocumentDownloadError.INVALID_URL, failure.code)
    }

    @Test
    fun `rejects redirect and oversized response`() {
        val properties = limits(maxBytes = 1024)
        val redirect = assertFailsWith<DocumentDownloadException> {
            downloader(properties).download(reference("/redirect"), DownloadBudget(properties))
        }
        assertEquals(DocumentDownloadError.REDIRECT, redirect.code)

        val large = assertFailsWith<DocumentDownloadException> {
            downloader(properties).download(reference("/large"), DownloadBudget(properties))
        }
        assertEquals(DocumentDownloadError.TOO_LARGE, large.code)
    }

    @Test
    fun `enforces per-meeting document count`() {
        val properties = limits(maxDocuments = 1)
        val budget = DownloadBudget(properties)
        downloader(properties).download(reference("/document"), budget)
        val failure = assertFailsWith<DocumentDownloadException> {
            downloader(properties).download(reference("/document"), budget)
        }
        assertEquals(DocumentDownloadError.DOCUMENT_LIMIT, failure.code)
    }

    private fun limits(maxBytes: Int = 4096, maxDocuments: Int = 5) = DocumentDownloadProperties(
        maxDocumentsPerMeeting = maxDocuments,
        maxDocumentBytes = maxBytes,
        maxTotalBytes = 16_384,
    )

    private fun downloader(limits: DocumentDownloadProperties): DocumentDownloader {
        val source = MeetingSourceProperties(
            baseUrl = baseUrl,
            environment = "local",
            connectTimeout = Duration.ofSeconds(1),
            requestTimeout = Duration.ofSeconds(1),
        )
        return DocumentDownloader(
            source,
            limits,
            HttpClient.newBuilder().connectTimeout(source.connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build(),
        )
    }

    private fun reference(path: String) = DocumentReference("doc", "Document", URI(path))

    private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }
}
