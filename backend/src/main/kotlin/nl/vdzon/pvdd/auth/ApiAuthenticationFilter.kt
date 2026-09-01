package nl.vdzon.pvdd.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException

@Component
class ApiAuthenticationFilter(
    private val authenticator: Authenticator,
    private val config: AuthConfig,
    private val sessions: UserSessionService,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/") || request.requestURI == "/api/version" ||
            request.requestURI == "/api/auth/session" || request.requestURI == "/api/auth/tooling-session"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val user = if (config.mode == AuthMode.ACCEPTANCE_BYPASS) {
                authenticator.authenticate(request.getHeader("Authorization"))
            } else {
                val sessionToken = request.cookies.orEmpty()
                    .firstOrNull { it.name == UserSessionService.COOKIE_NAME }
                    ?.value
                if (sessionToken.isNullOrBlank()) {
                    // Keep bearer support during a rolling deployment of the new frontend.
                    authenticator.authenticate(request.getHeader("Authorization"))
                } else {
                    sessions.authenticate(sessionToken)
                }
            }
            request.setAttribute(AUTHENTICATED_EMAIL_ATTRIBUTE, user.email)
            filterChain.doFilter(request, response)
        } catch (failure: ResponseStatusException) {
            response.status = failure.statusCode.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("{\"error\":\"authentication_failed\"}")
        }
    }

    companion object {
        const val AUTHENTICATED_EMAIL_ATTRIBUTE = "pvdd.authenticatedEmail"
    }
}
