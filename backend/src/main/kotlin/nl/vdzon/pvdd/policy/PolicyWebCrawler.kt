package nl.vdzon.pvdd.policy

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.springframework.stereotype.Component

enum class PolicyWebSourceType { PROGRAMME, IDEAL, POLITICAL_WORK, NEWS }

data class CrawledPolicySource(
    val canonicalUrl: URI,
    val sourceType: PolicyWebSourceType,
    val title: String,
    val publicationDate: LocalDate?,
    val fetchedAt: Instant,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val etag: String?,
    val lastModified: String?,
    val extractedText: String,
)

data class PolicyCrawlResult(
    val sources: List<CrawledPolicySource>,
    val unavailableUrls: Set<URI> = emptySet(),
) {
    val complete: Boolean get() = unavailableUrls.isEmpty()
}

@Component
class PolicyWebCrawler(
    private val properties: PolicySyncProperties,
    private val clock: Clock,
) {
    private var lastRequestNanos = 0L
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun crawl(): PolicyCrawlResult {
        properties.validate()
        val queue = ArrayDeque(properties.startUrls.map { canonical(it) to 0 })
        val visited = linkedSetOf<URI>()
        val result = mutableListOf<CrawledPolicySource>()
        val unavailable = linkedSetOf<URI>()
        var totalBytes = 0L
        while (queue.isNotEmpty() && visited.size < properties.maxPages) {
            val (url, depth) = queue.removeFirst()
            if (!visited.add(url)) continue
            properties.validateUrl(url)
            val response = try {
                fetch(url)
            } catch (failure: PolicySourceException) {
                if (!isRetryable(failure.code)) throw failure
                unavailable += url
                continue
            } ?: continue
            totalBytes += response.bytes.size
            if (totalBytes > properties.maxTotalBytes) throw PolicySourceException("POLICY_TOTAL_TOO_LARGE")
            val parsed = parse(url, response)
            if (parsed.source.extractedText.length >= MIN_TEXT_CHARACTERS) result += parsed.source
            if (depth < MAX_DISCOVERY_DEPTH) {
                parsed.links.asSequence()
                    .map(::canonical)
                    .filter(properties::mayDiscover)
                    .filterNot(visited::contains)
                    .forEach { queue.addLast(it to depth + 1) }
            }
        }
        if (result.isEmpty() && unavailable.isEmpty()) throw PolicySourceException("NO_POLICY_SOURCES")
        return PolicyCrawlResult(result.sortedBy { it.canonicalUrl.toString() }, unavailable)
    }

    private fun fetch(initialUrl: URI): FetchResponse? {
        var lastFailure: PolicySourceException? = null
        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            try {
                return fetchOnce(initialUrl)
            } catch (failure: PolicySourceException) {
                if (!isRetryable(failure.code) || attempt == MAX_FETCH_ATTEMPTS - 1) throw failure
                lastFailure = failure
                waitForRetry(attempt + 1)
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun fetchOnce(initialUrl: URI): FetchResponse? {
        var url = initialUrl
        for (redirectCount in 0..MAX_REDIRECTS) {
            properties.validateUrl(url)
            waitForRequestSlot()
            val request = HttpRequest.newBuilder(url)
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html,application/pdf;q=0.9")
                .header("User-Agent", "PvdD-Commissie-Assistent/0.2 (+https://pvdd.vdzonsoftware.nl)")
                .build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (_: HttpTimeoutException) {
                throw PolicySourceException("POLICY_TIMEOUT")
            } catch (_: IOException) {
                throw PolicySourceException("POLICY_HTTP_ERROR")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PolicySourceException("POLICY_INTERRUPTED")
            }
            if (response.statusCode() in 300..399) {
                response.body().close()
                if (redirectCount >= MAX_REDIRECTS) throw PolicySourceException("POLICY_REDIRECT_LIMIT")
                val location = response.headers().firstValue("Location").orElseThrow {
                    PolicySourceException("POLICY_INVALID_REDIRECT")
                }
                url = canonical(url.resolve(location))
                continue
            }
            if (response.statusCode() in setOf(404, 410)) {
                response.body().close()
                return null
            }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                throw PolicySourceException("POLICY_HTTP_${response.statusCode()}")
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
            val limit = if (contentType.substringBefore(';').trim().equals("application/pdf", true) ||
                url.path.lowercase().endsWith(".pdf")
            ) properties.maxPdfBytes else properties.maxHtmlBytes
            val bytes = response.body().use { it.readNBytes(limit + 1) }
            if (bytes.size > limit) throw PolicySourceException("POLICY_SOURCE_TOO_LARGE")
            return FetchResponse(
                bytes,
                contentType,
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null),
            )
        }
        error("Unreachable redirect loop")
    }

    private fun isRetryable(code: String): Boolean = code in setOf("POLICY_TIMEOUT", "POLICY_HTTP_ERROR", "POLICY_HTTP_429") ||
        code.matches(Regex("POLICY_HTTP_5\\d\\d"))

    private fun waitForRetry(attempt: Int) {
        try {
            Thread.sleep((attempt * RETRY_DELAY_MILLIS).toLong())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PolicySourceException("POLICY_INTERRUPTED")
        }
    }

    private fun waitForRequestSlot() {
        if (properties.environment.lowercase() != "production") return
        val elapsed = System.nanoTime() - lastRequestNanos
        val remaining = MIN_REQUEST_INTERVAL_NANOS - elapsed
        if (lastRequestNanos != 0L && remaining > 0) {
            try {
                Thread.sleep(Duration.ofNanos(remaining).toMillis().coerceAtLeast(1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PolicySourceException("POLICY_INTERRUPTED")
            }
        }
        lastRequestNanos = System.nanoTime()
    }

    private fun parse(url: URI, response: FetchResponse): ParsedSource {
        val fetchedAt = clock.instant()
        val isPdf = response.contentType.substringBefore(';').trim().equals("application/pdf", true) ||
            url.path.lowercase().endsWith(".pdf")
        if (isPdf) {
            if (response.bytes.size < PDF_MAGIC.size || PDF_MAGIC.indices.any { response.bytes[it] != PDF_MAGIC[it] }) {
                throw PolicySourceException("INVALID_POLICY_PDF")
            }
            val text = try {
                Loader.loadPDF(response.bytes).use { document ->
                    if (document.isEncrypted || document.numberOfPages !in 1..500) {
                        throw PolicySourceException("INVALID_POLICY_PDF")
                    }
                    (1..document.numberOfPages).joinToString("\n\n") { page ->
                        val pageText = PDFTextStripper().apply {
                            startPage = page
                            endPage = page
                            sortByPosition = true
                        }.getText(document).replace("\u0000", "").trim()
                        "[Pagina $page]\n$pageText"
                    }
                }
            } catch (failure: PolicySourceException) {
                throw failure
            } catch (_: Exception) {
                throw PolicySourceException("INVALID_POLICY_PDF")
            }
            return ParsedSource(
                source(url, response, fetchedAt, PolicyWebSourceType.PROGRAMME, "Verkiezingsprogramma 2023–2027", null, text),
                emptyList(),
            )
        }
        val html = response.bytes.toString(Charsets.UTF_8)
        val document = Jsoup.parse(html, url.toString())
        document.select("script,style,noscript,nav,footer,form,aside,svg").remove()
        val main = document.selectFirst("main") ?: document.selectFirst("article") ?: document.body()
        val title = main.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { document.title().trim() }
        val text = main.text().replace(WHITESPACE, " ").trim()
        val published = document.selectFirst("time[datetime]")?.attr("datetime")?.substringBefore('T')?.let {
            try { LocalDate.parse(it) } catch (_: DateTimeParseException) { null }
        }
        val links = document.select("a[href]").mapNotNull { element ->
            runCatching { URI(element.absUrl("href")) }.getOrNull()?.takeIf { it.isAbsolute }
        }
        return ParsedSource(source(url, response, fetchedAt, type(url), title, published, text), links)
    }

    private fun source(
        url: URI,
        response: FetchResponse,
        fetchedAt: Instant,
        type: PolicyWebSourceType,
        title: String,
        published: LocalDate?,
        text: String,
    ) = CrawledPolicySource(
        canonicalUrl = url,
        sourceType = type,
        title = title.take(500).ifBlank { url.path.substringAfterLast('/').replace('-', ' ') },
        publicationDate = published,
        fetchedAt = fetchedAt,
        contentType = response.contentType.take(160),
        sizeBytes = response.bytes.size.toLong(),
        sha256 = sha256(response.bytes),
        etag = response.etag?.take(1000),
        lastModified = response.lastModified?.take(1000),
        extractedText = text.take(MAX_EXTRACTED_CHARACTERS),
    )

    private fun type(url: URI): PolicyWebSourceType = when {
        url.path.contains("verkiezingsprogramma") -> PolicyWebSourceType.PROGRAMME
        url.path == "/onze-idealen" || url.path.startsWith("/onze-idealen/") ||
            url.path == "/standpunten" || url.path.startsWith("/standpunten/") -> PolicyWebSourceType.IDEAL
        url.path == "/nieuws" || url.path.startsWith("/nieuws/") -> PolicyWebSourceType.NEWS
        else -> PolicyWebSourceType.POLITICAL_WORK
    }

    private fun canonical(url: URI): URI = URI(
        url.scheme.lowercase(),
        null,
        url.host.lowercase(),
        url.port,
        url.path.ifBlank { "/" }.trimEnd('/').ifBlank { "/" },
        null,
        null,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class FetchResponse(val bytes: ByteArray, val contentType: String, val etag: String?, val lastModified: String?)
    private data class ParsedSource(val source: CrawledPolicySource, val links: List<URI>)

    companion object {
        private val PDF_MAGIC = "%PDF-".toByteArray()
        private val WHITESPACE = Regex("\\s+")
        private const val MAX_REDIRECTS = 3
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 250
        private const val MAX_DISCOVERY_DEPTH = 2
        private const val MIN_TEXT_CHARACTERS = 80
        private const val MAX_EXTRACTED_CHARACTERS = 500_000
        private const val MIN_REQUEST_INTERVAL_NANOS = 250_000_000L
    }
}
