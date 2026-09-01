package nl.vdzon.pvdd.auth.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.AuthMode
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.UserSessionService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory

data class AuthenticatedUserResponse(val email: String, val csrfToken: String? = null)
data class ToolingSessionRequest(val email: String?)

@Component
class ToolingSessionAuthenticator(
    private val config: AuthConfig,
    private val clock: Clock,
) {
    private val attempts = ConcurrentHashMap<String, AttemptWindow>()

    fun authenticate(providedToken: String?, requestedEmail: String?, remoteAddress: String = "unknown"): String {
        val key = "${remoteAddress.take(80)}|${requestedEmail?.trim()?.lowercase().orEmpty().take(320)}"
        enforceRateLimit(key)
        if (!config.toolingEnabled || providedToken.isNullOrBlank() ||
            !constantTimeEquals(providedToken, config.toolingToken)
        ) {
            log.warn("Tooling session rejected")
            throw rejected()
        }
        val email = requestedEmail?.trim()?.lowercase().orEmpty()
        if (email.isBlank() || !config.isAllowed(email)) {
            log.warn("Tooling session rejected for non-allowlisted identity")
            throw rejected()
        }
        attempts.remove(key)
        log.info("Tooling session created for allowed identity {}", email)
        return email
    }

    private fun enforceRateLimit(key: String) {
        val now = clock.instant()
        val current = attempts.compute(key) { _, previous ->
            if (previous == null || Duration.between(previous.startedAt, now) >= WINDOW) {
                AttemptWindow(now, 1)
            } else {
                previous.copy(count = previous.count + 1)
            }
        } ?: return
        if (current.count > MAX_ATTEMPTS) {
            log.warn("Tooling session rate limited")
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "authentication_failed")
        }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

    private fun rejected() = ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_failed")

    private data class AttemptWindow(val startedAt: java.time.Instant, val count: Int)

    companion object {
        private val log = LoggerFactory.getLogger(ToolingSessionAuthenticator::class.java)
        private val WINDOW = Duration.ofMinutes(15)
        private const val MAX_ATTEMPTS = 5
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticator: Authenticator,
    private val config: AuthConfig,
    private val sessions: UserSessionService,
    private val tooling: ToolingSessionAuthenticator,
) {
    @GetMapping("/me")
    fun me(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
    ): AuthenticatedUserResponse = AuthenticatedUserResponse(email)

    @PostMapping("/session")
    fun createSession(
        @RequestHeader("Authorization", required = false) authorization: String?,
        response: HttpServletResponse,
    ): AuthenticatedUserResponse {
        val user = authenticator.authenticate(authorization)
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if (config.mode == AuthMode.GOOGLE) {
            val session = sessions.create(user.email)
            setSession(response, session)
            return AuthenticatedUserResponse(user.email, session.csrfToken)
        }
        return AuthenticatedUserResponse(user.email)
    }

    @PostMapping("/tooling-session")
    fun createToolingSession(
        @RequestHeader("X-PVDD-Tooling-Token", required = false) token: String?,
        @RequestBody body: ToolingSessionRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): AuthenticatedUserResponse {
        val email = tooling.authenticate(token, body.email, request.remoteAddr)
        val session = sessions.create(email)
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        setSession(response, session)
        return AuthenticatedUserResponse(email, session.csrfToken)
    }

    @DeleteMapping("/session")
    fun deleteSession(request: HttpServletRequest): ResponseEntity<Void> {
        val token = request.cookies.orEmpty().firstOrNull { it.name == UserSessionService.COOKIE_NAME }?.value
        sessions.revoke(token)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .header(HttpHeaders.SET_COOKIE, csrfCookie("", Duration.ZERO).toString())
            .build()
    }

    private fun setSession(response: HttpServletResponse, session: nl.vdzon.pvdd.auth.CreatedUserSession) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(session.token, session.expiresIn).toString())
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie(session.csrfToken, session.expiresIn).toString())
    }

    private fun sessionCookie(value: String, maxAge: Duration): ResponseCookie = ResponseCookie
        .from(UserSessionService.COOKIE_NAME, value)
        .httpOnly(true)
        .secure(config.environment != "local")
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()

    private fun csrfCookie(value: String, maxAge: Duration): ResponseCookie = ResponseCookie
        .from(UserSessionService.CSRF_COOKIE_NAME, value)
        .httpOnly(false)
        .secure(config.environment != "local")
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
}
