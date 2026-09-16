package nl.vdzon.pvdd.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ProductionReadAccessFilterTest {
    private val token = "read-only-token-".repeat(4)
    private val config = AuthConfig("test-client", "production")
    private val filter = ProductionReadAccessFilter(token, AuthConfig.ALLOWED_EMAILS.first(), config)

    @Test fun `only reviewed reads reach the application and no user session is issued`() {
        for (path in listOf("/api/auth/me", "/api/meetings/next", "/api/settings", "/api/agenda-items/00000000-0000-0000-0000-000000000001")) {
            val request = MockHttpServletRequest("GET", path).apply { addHeader(ProductionReadAccessFilter.HEADER, token) }
            val response = MockHttpServletResponse()
            var called = false
            filter.doFilter(request, response) { req, _ ->
                called = true
                assertEquals(true, req.getAttribute(ProductionReadAccessFilter.ATTRIBUTE))
                assertEquals(AuthConfig.ALLOWED_EMAILS.first(), req.getAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE))
            }
            assertTrue(called, path)
            assertNull(response.getHeader("Set-Cookie"))
        }
    }

    @Test fun `all mutations session creation and unreviewed routes fail closed`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            for (path in listOf("/api/auth/session", "/api/auth/agent-session", "/api/auth/tooling-session", "/api/settings/analysis-instructions", "/api/meetings/check-now", "/api/meetings/next")) {
                assertDenied(filter, method, path, token, 403)
            }
        }
        for (path in listOf("/api/new-route", "/api/meetings/next/", "/api/meetings/next;ignored", "/api/../auth/session")) {
            assertDenied(filter, "GET", path, token, 403)
        }
    }

    @Test fun `disabled wrong token and disallowed identity fail closed`() {
        assertDenied(filter, "GET", "/api/meetings/next", "wrong", 401)
        assertDenied(filter, "GET", "/api/meetings/next", "", 401)
        assertDenied(ProductionReadAccessFilter("", AuthConfig.ALLOWED_EMAILS.first(), config), "GET", "/api/meetings/next", token, 401)
        assertDenied(ProductionReadAccessFilter(token, "unknown@example.invalid", config), "GET", "/api/meetings/next", token, 401)
    }

    @Test fun `normal requests retain their existing authentication path`() {
        val request = MockHttpServletRequest("GET", "/api/meetings/next")
        var called = false
        filter.doFilter(request, MockHttpServletResponse()) { req, _ ->
            called = true
            assertNull(req.getAttribute(ProductionReadAccessFilter.ATTRIBUTE))
        }
        assertTrue(called)
    }

    private fun assertDenied(filter: ProductionReadAccessFilter, method: String, path: String, provided: String, status: Int) {
        val request = MockHttpServletRequest(method, path).apply { addHeader(ProductionReadAccessFilter.HEADER, provided) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> fail("Denied request reached the application: $method $path") }
        assertEquals(status, response.status, "$method $path")
        assertNull(response.getHeader("Set-Cookie"))
    }
}
