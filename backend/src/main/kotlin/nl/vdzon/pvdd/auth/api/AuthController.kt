package nl.vdzon.pvdd.auth.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.AuthMode
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.UserSessionService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AuthenticatedUserResponse(val email: String)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticator: Authenticator,
    private val config: AuthConfig,
    private val sessions: UserSessionService,
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
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.token, session.expiresIn).toString())
        }
        return AuthenticatedUserResponse(user.email)
    }

    @DeleteMapping("/session")
    fun deleteSession(request: HttpServletRequest): ResponseEntity<Void> {
        val token = request.cookies.orEmpty().firstOrNull { it.name == UserSessionService.COOKIE_NAME }?.value
        sessions.revoke(token)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookie("", java.time.Duration.ZERO).toString())
            .build()
    }

    private fun cookie(value: String, maxAge: java.time.Duration): ResponseCookie = ResponseCookie
        .from(UserSessionService.COOKIE_NAME, value)
        .httpOnly(true)
        .secure(config.environment != "local")
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
}
