package nl.vdzon.pvdd.auth

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

data class AuthenticatedUser(val email: String)

@Component
class Authenticator(
    private val config: AuthConfig,
    private val tokenVerifier: GoogleIdTokenVerifier,
) {
    fun authenticate(authorization: String?): AuthenticatedUser {
        if (!config.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google login is not configured")
        }
        val idToken = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "A Google ID token is required")
        val identity = tokenVerifier.verify(idToken)
        if (!identity.emailVerified) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google e-mail is not verified")
        }
        if (!config.isAllowed(identity.email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not allowed")
        }
        return AuthenticatedUser(identity.email.trim().lowercase())
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
