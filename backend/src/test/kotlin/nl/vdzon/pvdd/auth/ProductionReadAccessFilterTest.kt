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
        for (path in listOf("/api/auth/me", "/api/meetings/next", "/api/settings", "/api/agenda-items/00000000-0000-0000-0000-000000000001", "/api/meetings", "/api/meetings/$ARCHIVE_UUID", "/api/agenda-items/$ARCHIVE_UUID/advice-versions")) {
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
        assertDenied(filter, "GET", "/api/meetings", "wrong", 401)
        assertDenied(filter, "GET", "/api/meetings", "", 401)
        assertDenied(filter, "GET", "/api/meetings/$ARCHIVE_UUID", "x".repeat(4097), 401)
        assertDenied(filter, "GET", "/api/agenda-items/$ARCHIVE_UUID/advice-versions", "wrong", 401)
        assertDenied(ProductionReadAccessFilter("", AuthConfig.ALLOWED_EMAILS.first(), config), "GET", "/api/meetings", token, 401)
        assertDenied(ProductionReadAccessFilter(token, "unknown@example.invalid", config), "GET", "/api/meetings", token, 401)
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

    @Test fun `the archive reads are allowed for GET and HEAD and stay uncacheable`() {
        for (path in ARCHIVE_PATHS + "/api/meetings/next" + "/api/meetings/$ARCHIVE_UUID/agenda-items") {
            for (method in listOf("GET", "HEAD")) assertAllowed(MockHttpServletRequest(method, path))
        }
    }

    @Test fun `the meeting list stays allowed when the archive query parameters are supplied`() {
        val request = MockHttpServletRequest("GET", "/api/meetings").apply {
            queryString = "state=past&limit=20&cursor=$CURSOR"
            setParameter("state", "past")
            setParameter("limit", "20")
            setParameter("cursor", CURSOR)
        }
        assertEquals("/api/meetings", request.requestURI)
        assertAllowed(request)
    }

    @Test fun `no method other than GET or HEAD reaches the archive reads`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            for (path in ARCHIVE_PATHS) assertDenied(filter, method, path, token, 403)
        }
    }

    @Test fun `only the exact archive paths are allowed`() {
        val variants = listOf(
            "/api/meetings/", "/api/meetings;a=b", "/api/meetings/$ARCHIVE_UUID/", "/api/meetings/not-a-uuid",
            "/api/meetings/$ARCHIVE_UUID/extra", "/api/agenda-items/$ARCHIVE_UUID/advice-versions/",
            "/api/agenda-items/$ARCHIVE_UUID/advice-versions;a=b", "/api/agenda-items/$ARCHIVE_UUID/advice-versions/1",
        )
        for (path in variants) assertDenied(filter, "GET", path, token, 403)
    }

    @Test fun `a refused archive request never repeats the read token`() {
        assertNoTokenLeak("GET", "/api/meetings", "wrong", 401)
        assertNoTokenLeak("POST", "/api/meetings", token, 403)
        assertNoTokenLeak("GET", "/api/agenda-items/$ARCHIVE_UUID/advice-versions/", token, 403)
    }

    @Test fun `without the read header the archive paths keep their existing authentication path`() {
        for (path in ARCHIVE_PATHS) {
            val request = MockHttpServletRequest("GET", path)
            val response = MockHttpServletResponse()
            var called = false
            filter.doFilter(request, response) { req, _ ->
                called = true
                assertNull(req.getAttribute(ProductionReadAccessFilter.ATTRIBUTE))
                assertNull(req.getAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE))
            }
            assertTrue(called, path)
            assertNull(response.getHeader("Cache-Control"), path)
            assertNull(response.getHeader("Set-Cookie"), path)
        }
    }

    private fun assertAllowed(request: MockHttpServletRequest) {
        request.addHeader(ProductionReadAccessFilter.HEADER, token)
        val where = "${request.method} ${request.requestURI}"
        val response = MockHttpServletResponse()
        var called = false
        filter.doFilter(request, response) { req, _ ->
            called = true
            assertEquals(true, req.getAttribute(ProductionReadAccessFilter.ATTRIBUTE), where)
            assertEquals(AuthConfig.ALLOWED_EMAILS.first(), req.getAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE), where)
        }
        assertTrue(called, where)
        assertNotEquals(401, response.status, where)
        assertNotEquals(403, response.status, where)
        assertEquals("no-store", response.getHeader("Cache-Control"), where)
        assertNull(response.getHeader("Set-Cookie"), where)
    }

    private fun assertNoTokenLeak(method: String, path: String, provided: String, status: Int) {
        val request = MockHttpServletRequest(method, path).apply { addHeader(ProductionReadAccessFilter.HEADER, provided) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> fail("Denied request reached the application: $method $path") }
        val where = "$method $path"
        assertEquals(status, response.status, where)
        assertEquals("no-store", response.getHeader("Cache-Control"), where)
        assertNull(response.getHeader("Set-Cookie"), where)
        assertFalse(response.contentAsString.contains(token), where)
        assertFalse(response.errorMessage.orEmpty().contains(token), where)
        for (name in response.headerNames) {
            for (value in response.getHeaders(name)) assertFalse(value.toString().contains(token), "$name: $where")
        }
    }

    private fun assertDenied(filter: ProductionReadAccessFilter, method: String, path: String, provided: String, status: Int) {
        val request = MockHttpServletRequest(method, path).apply { addHeader(ProductionReadAccessFilter.HEADER, provided) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> fail("Denied request reached the application: $method $path") }
        assertEquals(status, response.status, "$method $path")
        assertNull(response.getHeader("Set-Cookie"))
    }

    private companion object {
        const val ARCHIVE_UUID = "0e5e7720-1e0a-4a9c-8f2b-0000000000a1"
        const val CURSOR = "MjAyNi0wOS0xN1QxMDowMDowMFp8MGU1ZTc3MjAtMWUwYS00YTljLThmMmItMDAwMDAwMDAwMGEx"
        val ARCHIVE_PATHS = listOf("/api/meetings", "/api/meetings/$ARCHIVE_UUID", "/api/agenda-items/$ARCHIVE_UUID/advice-versions")
    }
}
