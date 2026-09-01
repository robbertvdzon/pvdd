package nl.vdzon.pvdd.auth.api

import jakarta.servlet.http.Cookie
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.CreatedUserSession
import nl.vdzon.pvdd.auth.GoogleIdentity
import nl.vdzon.pvdd.auth.UserSessionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException

class AuthControllerTest {
    private val config = AuthConfig("client-id", "production", "google")
    private val authenticator = Authenticator(config) { GoogleIdentity("robbertvdzon@gmail.com", true) }
    private val sessions = mock(UserSessionService::class.java)
    private val tooling = ToolingSessionAuthenticator(config, Clock.systemUTC())
    private val controller = AuthController(authenticator, config, sessions, tooling)

    @Test
    fun `Google login creates a long lived secure server session cookie`() {
        `when`(sessions.create("robbertvdzon@gmail.com"))
            .thenReturn(CreatedUserSession("opaque-session", "csrf-token", Duration.ofDays(180)))
        val response = MockHttpServletResponse()

        assertEquals(
            "robbertvdzon@gmail.com",
            controller.createSession("Bearer google-token", response).email,
        )

        val cookies = response.getHeaders("Set-Cookie")
        val sessionCookie = requireNotNull(cookies.singleOrNull { it.startsWith("pvdd_session=") })
        val csrfCookie = requireNotNull(cookies.singleOrNull { it.startsWith("pvdd_csrf=") })
        assertTrue(sessionCookie.contains("pvdd_session=opaque-session"))
        assertTrue(sessionCookie.contains("HttpOnly"))
        assertTrue(sessionCookie.contains("Secure"))
        assertTrue(sessionCookie.contains("SameSite=Lax"))
        assertTrue(sessionCookie.contains("Max-Age=15552000"))
        assertTrue(csrfCookie.contains("pvdd_csrf=csrf-token"))
        assertTrue(!csrfCookie.contains("HttpOnly"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `tooling token creates the same browser session without Google`() {
        val toolingConfig = AuthConfig("client-id", "production", "google", true, "tooling-secret")
        val toolingController = AuthController(
            authenticator,
            toolingConfig,
            sessions,
            ToolingSessionAuthenticator(
                toolingConfig,
                Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC),
            ),
        )
        `when`(sessions.create("robbertvdzon@gmail.com"))
            .thenReturn(CreatedUserSession("tooling-session", "tooling-csrf", Duration.ofDays(180)))
        val response = MockHttpServletResponse()

        val body = toolingController.createToolingSession(
            "tooling-secret",
            ToolingSessionRequest("robbertvdzon@gmail.com"),
            MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" },
            response,
        )

        assertEquals("robbertvdzon@gmail.com", body.email)
        assertEquals("tooling-csrf", body.csrfToken)
        assertTrue(response.getHeaders("Set-Cookie").any { it.startsWith("pvdd_session=tooling-session") })
        assertTrue(response.getHeaders("Set-Cookie").any { it.startsWith("pvdd_csrf=tooling-csrf") })
    }

    @Test
    fun `tooling bootstrap fails closed for wrong token or identity`() {
        val toolingConfig = AuthConfig("client-id", "production", "google", true, "tooling-secret")
        val toolingAuth = ToolingSessionAuthenticator(
            toolingConfig,
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC),
        )
        assertEquals(401, assertFailsWith<ResponseStatusException> {
            toolingAuth.authenticate("wrong", "robbertvdzon@gmail.com")
        }.statusCode.value())
        assertEquals(401, assertFailsWith<ResponseStatusException> {
            toolingAuth.authenticate("tooling-secret", "unknown@example.com")
        }.statusCode.value())
    }

    @Test
    fun `tooling bootstrap rate limits repeated attempts`() {
        val toolingConfig = AuthConfig("client-id", "production", "google", true, "tooling-secret")
        val toolingAuth = ToolingSessionAuthenticator(
            toolingConfig,
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC),
        )
        repeat(5) {
            assertEquals(401, assertFailsWith<ResponseStatusException> {
                toolingAuth.authenticate("wrong", "robbertvdzon@gmail.com", "192.0.2.10")
            }.statusCode.value())
        }
        assertEquals(429, assertFailsWith<ResponseStatusException> {
            toolingAuth.authenticate("wrong", "robbertvdzon@gmail.com", "192.0.2.10")
        }.statusCode.value())
    }

    @Test
    fun `logout revokes the current session and removes the cookie`() {
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie(UserSessionService.COOKIE_NAME, "opaque-session"))
        }
        val response = controller.deleteSession(request)

        assertEquals(204, response.statusCode.value())
        verify(sessions).revoke("opaque-session")
        val cookie = requireNotNull(response.headers.getFirst("Set-Cookie"))
        assertTrue(cookie.contains("pvdd_session="))
        assertTrue(cookie.contains("Max-Age=0"))
    }
}
