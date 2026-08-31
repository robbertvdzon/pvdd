package nl.vdzon.pvdd.meetings

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class MutationGuard(private val clock: Clock) {
    private val results = ConcurrentHashMap<String, Any>()
    private val requests = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    @Synchronized
    fun <T : Any> execute(email: String, action: String, idempotencyKey: String, operation: () -> T): T {
        if (!idempotencyKey.matches(KEY_PATTERN)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key")
        val cacheKey = "$email:$action:$idempotencyKey"
        @Suppress("UNCHECKED_CAST")
        results[cacheKey]?.let { return it as T }
        val now = clock.instant()
        val history = requests.computeIfAbsent("$email:$action") { ArrayDeque() }
        while (history.firstOrNull()?.isBefore(now.minus(WINDOW)) == true) history.removeFirst()
        if (history.size >= MAX_REQUESTS) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit")
        history.addLast(now)
        return operation().also { results[cacheKey] = it }
    }

    companion object {
        private const val MAX_REQUESTS = 6
        private val WINDOW = Duration.ofMinutes(1)
        private val KEY_PATTERN = Regex("[A-Za-z0-9._:-]{8,100}")
    }
}
