package nl.vdzon.pvdd.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CsrfProtectionFilterTest {
    private val filter = CsrfProtectionFilter(AuthConfig("client", "production", "google"))

    @Test
    fun `writing api calls require matching csrf cookie and header`() {
        val rejectedRequest = MockHttpServletRequest("POST", "/api/meetings/check-now")
        val rejectedResponse = MockHttpServletResponse()
        filter.doFilter(rejectedRequest, rejectedResponse, mock(FilterChain::class.java))
        assertEquals(403, rejectedResponse.status)
        assertEquals("{\"error\":\"csrf_failed\"}", rejectedResponse.contentAsString)

        val acceptedRequest = MockHttpServletRequest("POST", "/api/meetings/check-now").apply {
            setCookies(Cookie(UserSessionService.CSRF_COOKIE_NAME, "csrf-value"))
            addHeader(UserSessionService.CSRF_HEADER_NAME, "csrf-value")
        }
        val acceptedResponse = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        filter.doFilter(acceptedRequest, acceptedResponse, chain)
        verify(chain).doFilter(acceptedRequest, acceptedResponse)
    }

    @Test
    fun `session bootstrap and safe reads do not require csrf`() {
        listOf(
            MockHttpServletRequest("POST", "/api/auth/session"),
            MockHttpServletRequest("POST", "/api/auth/tooling-session"),
            MockHttpServletRequest("GET", "/api/meetings/next"),
        ).forEach { request ->
            val response = MockHttpServletResponse()
            val chain = mock(FilterChain::class.java)
            filter.doFilter(request, response, chain)
            verify(chain).doFilter(request, response)
        }
    }
}
