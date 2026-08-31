package nl.vdzon.pvdd.policy

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import org.springframework.stereotype.Component

data class PolicyPdf(val bytes: ByteArray, val contentType: String?)

class PolicySourceException(val code: String) : RuntimeException(code)

fun interface PolicySourceGateway {
    fun fetch(): PolicyPdf
}

@Component
class PolicySourceClient(private val properties: PolicySourceProperties) : PolicySourceGateway {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(): PolicyPdf {
        properties.validate()
        val request = HttpRequest.newBuilder(properties.url)
            .GET()
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/pdf")
            .header("User-Agent", "PvdD-Commissie-Assistent/0.1 (+https://pvdd.vdzonsoftware.nl)")
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() in 300..399) {
                response.body().close()
                throw PolicySourceException("REDIRECT")
            }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                throw PolicySourceException("HTTP_ERROR")
            }
            val bytes = response.body().use { it.readNBytes(properties.maxBytes + 1) }
            if (bytes.size > properties.maxBytes) throw PolicySourceException("TOO_LARGE")
            PolicyPdf(bytes, response.headers().firstValue("Content-Type").orElse(null))
        } catch (_: HttpTimeoutException) {
            throw PolicySourceException("TIMEOUT")
        } catch (failure: PolicySourceException) {
            throw failure
        } catch (_: IOException) {
            throw PolicySourceException("HTTP_ERROR")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PolicySourceException("INTERRUPTED")
        }
    }
}
