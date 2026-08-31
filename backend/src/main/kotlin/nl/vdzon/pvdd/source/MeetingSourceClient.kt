package nl.vdzon.pvdd.source

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

data class SourcePage(
    val url: URI,
    val status: Int,
    val contentType: String?,
    val body: String,
)

enum class SourceTransportCode {
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    DISALLOWED_REDIRECT,
    INVALID_URL,
}

class SourceTransportException(val code: SourceTransportCode) : RuntimeException(code.name)

fun interface MeetingSourceGateway {
    fun fetch(uri: URI): SourcePage
}

@Configuration
class MeetingSourceHttpConfiguration {
    @Bean
    fun meetingSourceHttpClient(properties: MeetingSourceProperties): HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

@Component
class MeetingSourceClient(
    private val properties: MeetingSourceProperties,
    private val client: HttpClient,
) : MeetingSourceGateway {
    override fun fetch(uri: URI): SourcePage {
        val target = validateAndResolve(uri)
        var lastFailure: SourceTransportException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = HttpRequest.newBuilder(target)
                    .GET()
                    .timeout(properties.requestTimeout)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() in 300..399) throw SourceTransportException(SourceTransportCode.DISALLOWED_REDIRECT)
                if (response.statusCode() !in 200..299) {
                    response.body().close()
                    throw SourceTransportException(SourceTransportCode.HTTP_ERROR)
                }
                val bytes = response.body().use { it.readNBytes(properties.maxPageBytes + 1) }
                if (bytes.size > properties.maxPageBytes) throw SourceTransportException(SourceTransportCode.RESPONSE_TOO_LARGE)
                return SourcePage(
                    url = target,
                    status = response.statusCode(),
                    contentType = response.headers().firstValue("Content-Type").orElse(null),
                    body = bytes.toString(Charsets.UTF_8),
                )
            } catch (_: HttpConnectTimeoutException) {
                lastFailure = SourceTransportException(SourceTransportCode.CONNECT_TIMEOUT)
            } catch (_: HttpTimeoutException) {
                lastFailure = SourceTransportException(SourceTransportCode.READ_TIMEOUT)
            } catch (failure: SourceTransportException) {
                lastFailure = failure
                if (failure.code !in RETRYABLE || attempt == MAX_ATTEMPTS - 1) throw failure
            } catch (_: IOException) {
                lastFailure = SourceTransportException(SourceTransportCode.HTTP_ERROR)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SourceTransportException(SourceTransportCode.HTTP_ERROR)
            }
        }
        throw lastFailure ?: SourceTransportException(SourceTransportCode.HTTP_ERROR)
    }

    private fun validateAndResolve(uri: URI): URI {
        val target = if (uri.isAbsolute) uri else properties.baseUrl.resolve(uri)
        val basePort = normalizedPort(properties.baseUrl)
        val targetPort = normalizedPort(target)
        if (
            target.scheme != properties.baseUrl.scheme ||
            target.host != properties.baseUrl.host ||
            targetPort != basePort ||
            target.userInfo != null ||
            target.fragment != null ||
            target.path.isNullOrBlank() ||
            target.path.contains("..")
        ) {
            throw SourceTransportException(SourceTransportCode.INVALID_URL)
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
        private val RETRYABLE = setOf(SourceTransportCode.CONNECT_TIMEOUT, SourceTransportCode.READ_TIMEOUT, SourceTransportCode.HTTP_ERROR)
    }
}
