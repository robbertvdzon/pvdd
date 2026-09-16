package nl.vdzon.pvdd.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Separate capability: never creates a user session and never authorizes a mutation. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ProductionReadAccessFilter(
    @param:Value("\${AI_READ_ACCESS_TOKEN:}") private val token: String,
    @param:Value("\${AI_READ_ACCESS_EMAIL:}") private val email: String,
    private val config: AuthConfig,
) : OncePerRequestFilter() {
    init { require(token.isEmpty() || token.length >= 32) { "AI_READ_ACCESS_TOKEN must contain at least 32 characters" } }

    override fun shouldNotFilter(request: HttpServletRequest) = request.getHeader(HEADER) == null

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        val supplied = request.getHeader(HEADER).orEmpty()
        if (token.isBlank() || supplied.length > 4096 || !MessageDigest.isEqual(token.toByteArray(), supplied.toByteArray()) ||
            email.isBlank() || !config.isAllowed(email)) {
            response.sendError(401)
            return
        }
        // Explicit paths keep future GET endpoints out until their side effects have been reviewed.
        if (request.method !in setOf("GET", "HEAD") || !allowedPath(request.requestURI)) {
            response.sendError(403)
            return
        }
        request.setAttribute(ATTRIBUTE, true)
        request.setAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE, email)
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-AI-Read-Token"
        const val ATTRIBUTE = "pvdd.productionReadAccess"
        private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val paths = listOf(
            Regex("/api/(version|auth/me|meetings/next|ai-runs|settings|policy/overview|policy/sync-runs/current)"),
            Regex("/api/meetings/$UUID/agenda-items"),
            Regex("/api/(agenda-items|analysis-runs|ai-runs)/$UUID"),
            Regex("/api/policy/positions/$UUID"),
        )
        fun allowedPath(path: String) = paths.any { it.matches(path) }
    }
}
