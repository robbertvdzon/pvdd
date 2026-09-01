package nl.vdzon.pvdd.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class CsrfProtectionFilter(private val config: AuthConfig) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        config.mode == AuthMode.ACCEPTANCE_BYPASS ||
            !request.requestURI.startsWith("/api/") ||
            request.method.uppercase() in SAFE_METHODS ||
            request.requestURI == "/api/auth/session" ||
            request.requestURI == "/api/auth/tooling-session"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cookieToken = request.cookies.orEmpty()
            .firstOrNull { it.name == UserSessionService.CSRF_COOKIE_NAME }
            ?.value
        val headerToken = request.getHeader(UserSessionService.CSRF_HEADER_NAME)
        if (cookieToken.isNullOrBlank() || headerToken.isNullOrBlank() ||
            !MessageDigest.isEqual(
                cookieToken.toByteArray(StandardCharsets.UTF_8),
                headerToken.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("{\"error\":\"csrf_failed\"}")
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
