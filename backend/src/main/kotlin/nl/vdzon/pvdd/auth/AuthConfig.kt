package nl.vdzon.pvdd.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class AuthConfig(
    @param:Value("\${pvdd.auth.google-client-id:}") val googleClientId: String,
) {
    val enabled: Boolean = googleClientId.isNotBlank()

    fun isAllowed(email: String): Boolean = email.trim().lowercase() in ALLOWED_EMAILS

    companion object {
        val ALLOWED_EMAILS = setOf("marchanou@gmail.com", "robbertvdzon@gmail.com")
    }
}
