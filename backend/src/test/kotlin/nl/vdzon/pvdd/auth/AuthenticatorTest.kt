package nl.vdzon.pvdd.auth

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class AuthenticatorTest {
    private val config = AuthConfig("client-id")

    @Test
    fun `allows exactly both configured users`() {
        AuthConfig.ALLOWED_EMAILS.forEach { email ->
            val authenticator = Authenticator(config) { GoogleIdentity(email.uppercase(), true) }
            assertEquals(email, authenticator.authenticate("Bearer valid").email)
        }
    }

    @Test
    fun `rejects missing token unverified mail and unknown user`() {
        assertStatus(HttpStatus.UNAUTHORIZED) {
            Authenticator(config) { error("not called") }.authenticate(null)
        }
        assertStatus(HttpStatus.UNAUTHORIZED) {
            Authenticator(config) { GoogleIdentity("robbertvdzon@gmail.com", false) }.authenticate("Bearer x")
        }
        assertStatus(HttpStatus.FORBIDDEN) {
            Authenticator(config) { GoogleIdentity("other@example.com", true) }.authenticate("Bearer x")
        }
    }

    @Test
    fun `missing client id fails closed`() {
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE) {
            Authenticator(AuthConfig("")) { error("not called") }.authenticate("Bearer x")
        }
    }

    @Test
    fun `acceptance bypass has one fixed identity and needs no token`() {
        val authenticator = Authenticator(AuthConfig("", "acceptance", "acceptance-bypass")) { error("not called") }
        assertEquals(AuthConfig.ACCEPTANCE_EMAIL, authenticator.authenticate(null).email)
        assertEquals(AuthConfig.ACCEPTANCE_EMAIL, authenticator.authenticate("Bearer ignored").email)
    }

    @Test
    fun `acceptance bypass cannot start outside acceptance and non-local Google fails without config`() {
        assertFailsWith<IllegalStateException> { AuthConfig("client-id", "production", "acceptance-bypass") }
        assertFailsWith<IllegalStateException> { AuthConfig("client-id", "unknown", "acceptance-bypass") }
        assertFailsWith<IllegalStateException> { AuthConfig("", "production", "google") }
        assertFailsWith<IllegalStateException> { AuthConfig("", "unknown", "google") }
        assertFailsWith<IllegalStateException> { AuthConfig("client-id", "production", "google", true, "") }
        assertFailsWith<IllegalStateException> {
            AuthConfig("", "acceptance", "acceptance-bypass", false, "production-secret")
        }
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        val failure = assertFailsWith<ResponseStatusException> { block() }
        assertEquals(status, failure.statusCode)
    }
}
