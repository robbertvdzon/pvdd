package nl.vdzon.pvdd.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

data class CreatedUserSession(val token: String, val csrfToken: String, val expiresIn: Duration)

@Component
class UserSessionService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val authConfig: AuthConfig,
    @param:Value("\${pvdd.auth.session-days:180}") sessionDays: Long,
) {
    private val lifetime = Duration.ofDays(sessionDays).also {
        require(!it.isZero && !it.isNegative && it <= Duration.ofDays(365)) {
            "Session lifetime must be between 1 and 365 days"
        }
    }

    fun create(email: String): CreatedUserSession {
        val token = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)
        val csrfToken = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)
        if (!authConfig.isAllowed(email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Session account is no longer allowed")
        }
        jdbc.update(
            """
            INSERT INTO user_session(token_hash, email, expires_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
            hash(token),
            email,
            Timestamp.from(clock.instant().plus(lifetime)),
        )
        return CreatedUserSession(token, csrfToken, lifetime)
    }

    fun authenticate(token: String): AuthenticatedUser {
        val email = jdbc.query(
            "SELECT email FROM user_session WHERE token_hash = ? AND expires_at > ?",
            { rs, _ -> rs.getString("email") },
            hash(token),
            Timestamp.from(clock.instant()),
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is missing or expired")
        if (!authConfig.isAllowed(email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Session account is no longer allowed")
        }
        jdbc.update(
            "UPDATE user_session SET last_seen_at = ? WHERE token_hash = ?",
            Timestamp.from(clock.instant()),
            hash(token),
        )
        return AuthenticatedUser(email)
    }

    fun revoke(token: String?) {
        token?.takeIf(String::isNotBlank)?.let { jdbc.update("DELETE FROM user_session WHERE token_hash = ?", hash(it)) }
    }

    private fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val COOKIE_NAME = "pvdd_session"
        const val CSRF_COOKIE_NAME = "pvdd_csrf"
        const val CSRF_HEADER_NAME = "X-CSRF-Token"
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
