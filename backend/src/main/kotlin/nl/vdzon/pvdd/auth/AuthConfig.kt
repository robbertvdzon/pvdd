package nl.vdzon.pvdd.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

enum class AuthMode { GOOGLE, ACCEPTANCE_BYPASS }

@Component
class AuthConfig(
    @param:Value("\${pvdd.auth.google-client-id:}") val googleClientId: String,
    @param:Value("\${pvdd.environment:local}") val environment: String = "local",
    @param:Value("\${pvdd.auth.mode:google}") mode: String = "google",
    @param:Value("\${pvdd.auth.tooling-enabled:false}") val toolingEnabled: Boolean = false,
    @param:Value("\${pvdd.auth.tooling-token:}") val toolingToken: String = "",
) {
    val mode: AuthMode = when (mode.trim().lowercase()) {
        "google" -> AuthMode.GOOGLE
        "acceptance-bypass" -> AuthMode.ACCEPTANCE_BYPASS
        else -> error("Unsupported authentication mode")
    }
    val enabled: Boolean = this.mode == AuthMode.ACCEPTANCE_BYPASS || googleClientId.isNotBlank()

    init {
        if (this.mode == AuthMode.ACCEPTANCE_BYPASS && environment != "acceptance") {
            error("Acceptance authentication bypass is only permitted in acceptance")
        }
        if (environment != "local" && this.mode == AuthMode.GOOGLE && googleClientId.isBlank()) {
            error("Google authentication must be configured outside local development")
        }
        if (toolingEnabled && (this.mode != AuthMode.GOOGLE || toolingToken.isBlank())) {
            error("Tooling authentication requires Google mode and a configured token")
        }
        if (environment == "acceptance" && (toolingEnabled || toolingToken.isNotBlank())) {
            error("Acceptance must not contain tooling authentication")
        }
    }

    fun isAllowed(email: String): Boolean = email.trim().lowercase() in ALLOWED_EMAILS

    companion object {
        val ALLOWED_EMAILS = setOf("marchanou@gmail.com", "robbertvdzon@gmail.com")
        const val ACCEPTANCE_EMAIL = "acceptance-tester@pvdd.invalid"
    }
}
