package nl.vdzon.pvdd.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException

@Component
class ApiAuthenticationFilter(private val authenticator: Authenticator) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/") || request.requestURI == "/api/version"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val user = authenticator.authenticate(request.getHeader("Authorization"))
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
