package nl.vdzon.pvdd.meetings

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class MutationGuardTest {
    private val guard = MutationGuard(Clock.fixed(Instant.parse("2026-08-31T10:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `same idempotency key executes operation once`() {
        val calls = AtomicInteger()
        repeat(2) {
            assertEquals("result", guard.execute("user@example.test", "check", "stable-key-123") {
                calls.incrementAndGet()
                "result"
            })
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `rate limit and malformed keys fail safely`() {
        assertEquals(
            HttpStatus.BAD_REQUEST,
            assertFailsWith<ResponseStatusException> { guard.execute("user@example.test", "invalid", "x") { "x" } }.statusCode,
        )
        repeat(6) { index -> guard.execute("user@example.test", "limited", "request-key-$index") { index } }
        assertEquals(
            HttpStatus.TOO_MANY_REQUESTS,
            assertFailsWith<ResponseStatusException> {
                guard.execute("user@example.test", "limited", "request-key-overflow") { 7 }
            }.statusCode,
        )
    }
}
