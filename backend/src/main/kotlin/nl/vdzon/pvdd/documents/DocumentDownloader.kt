package nl.vdzon.pvdd.documents

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.springframework.stereotype.Component

data class DocumentReference(
    val sourceId: String,
    val name: String,
    val sourceUrl: URI,
)

data class DownloadedDocument(
    val reference: DocumentReference,
    val declaredMimeType: String?,
    val bytes: ByteArray,
)

enum class DocumentDownloadError {
    INVALID_URL,
    REDIRECT,
    TIMEOUT,
    HTTP_ERROR,
    TOO_LARGE,
    DOCUMENT_LIMIT,
    TOTAL_LIMIT,
}

class DocumentDownloadException(val code: DocumentDownloadError) : RuntimeException(code.name)

class DownloadBudget(private val properties: DocumentDownloadProperties) {
    private var documents = 0
    private var bytes = 0L

    @Synchronized
    fun startDocument() {
        if (documents + 1 > properties.maxDocumentsPerMeeting) throw DocumentDownloadException(DocumentDownloadError.DOCUMENT_LIMIT)
        documents++
    }

    @Synchronized
    fun addBytes(size: Long) {
        if (bytes + size > properties.maxTotalBytes) throw DocumentDownloadException(DocumentDownloadError.TOTAL_LIMIT)
        bytes += size
    }
}

@Component
class DocumentDownloader(
    private val sourceProperties: MeetingSourceProperties,
    private val properties: DocumentDownloadProperties,
    private val httpClient: HttpClient,
) {
    fun download(reference: DocumentReference, budget: DownloadBudget): DownloadedDocument {
        val uri = validate(reference.sourceUrl)
        budget.startDocument()
        var lastError = DocumentDownloadError.HTTP_ERROR
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(sourceProperties.requestTimeout)
                    .header("Accept", ACCEPT)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() in 300..399) {
                    response.body().close()
                    throw DocumentDownloadException(DocumentDownloadError.REDIRECT)
                }
                if (response.statusCode() !in 200..299) {
                    response.body().close()
                    throw DocumentDownloadException(DocumentDownloadError.HTTP_ERROR)
                }
                val declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1)
                if (declaredLength > properties.maxDocumentBytes) {
                    response.body().close()
                    throw DocumentDownloadException(DocumentDownloadError.TOO_LARGE)
                }
                val bytes = response.body().use { it.readNBytes(properties.maxDocumentBytes + 1) }
                if (bytes.size > properties.maxDocumentBytes) throw DocumentDownloadException(DocumentDownloadError.TOO_LARGE)
                budget.addBytes(bytes.size.toLong())
                return DownloadedDocument(
                    reference = reference,
                    declaredMimeType = response.headers().firstValue("Content-Type").orElse(null),
                    bytes = bytes,
                )
            } catch (_: HttpTimeoutException) {
                lastError = DocumentDownloadError.TIMEOUT
            } catch (failure: DocumentDownloadException) {
                lastError = failure.code
                if (failure.code !in RETRYABLE || attempt == MAX_ATTEMPTS - 1) throw failure
            } catch (_: IOException) {
                lastError = DocumentDownloadError.HTTP_ERROR
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DocumentDownloadException(DocumentDownloadError.HTTP_ERROR)
            }
        }
        throw DocumentDownloadException(lastError)
    }

    private fun validate(candidate: URI): URI {
        val target = if (candidate.isAbsolute) candidate else sourceProperties.baseUrl.resolve(candidate)
        val base = sourceProperties.baseUrl
        val expectedScheme = if (sourceProperties.environment.equals("production", ignoreCase = true)) "https" else base.scheme
        if (
            target.scheme != expectedScheme || target.host != base.host || normalizedPort(target) != normalizedPort(base) ||
            target.userInfo != null || target.fragment != null || target.path.isNullOrBlank() || target.path.contains("..")
        ) {
            throw DocumentDownloadException(DocumentDownloadError.INVALID_URL)
        }
        return target
    }

    private fun normalizedPort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme == "https" -> 443
        else -> 80
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val USER_AGENT = "PvdD-Commissie-Assistent/0.1 (+https://pvdd.vdzonsoftware.nl)"
        private const val ACCEPT = "application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/html"
        private val RETRYABLE = setOf(DocumentDownloadError.TIMEOUT, DocumentDownloadError.HTTP_ERROR)
    }
}
