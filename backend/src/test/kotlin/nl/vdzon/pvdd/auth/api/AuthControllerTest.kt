package nl.vdzon.pvdd.auth.api

import jakarta.servlet.http.Cookie
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

class AuthControllerTest {
    private val config = AuthConfig("client-id", "production", "google")
    private val authenticator = Authenticator(config) { GoogleIdentity("robbertvdzon@gmail.com", true) }
    private val sessions = mock(UserSessionService::class.java)
    private val controller = AuthController(authenticator, config, sessions)

    @Test
    fun `Google login creates a long lived secure server session cookie`() {
        `when`(sessions.create("robbertvdzon@gmail.com"))
            .thenReturn(CreatedUserSession("opaque-session", Duration.ofDays(180)))
        val response = MockHttpServletResponse()

        assertEquals(
            "robbertvdzon@gmail.com",
            controller.createSession("Bearer google-token", response).email,
        )

        val cookie = requireNotNull(response.getHeader("Set-Cookie"))
        assertTrue(cookie.contains("pvdd_session=opaque-session"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("Secure"))
        assertTrue(cookie.contains("SameSite=Lax"))
        assertTrue(cookie.contains("Max-Age=15552000"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
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
