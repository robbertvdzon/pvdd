package nl.vdzon.pvdd.meetings.api

import jakarta.servlet.http.HttpServletRequest
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.meetings.AcceptanceResetResult
import nl.vdzon.pvdd.meetings.AcceptanceScenarioResetService
import nl.vdzon.pvdd.meetings.MeetingCheckResult
import nl.vdzon.pvdd.meetings.MeetingCheckScheduler
import nl.vdzon.pvdd.meetings.MeetingCheckStatus
import nl.vdzon.pvdd.meetings.MutationGuard
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Forwards the scenario control of the acceptance source mock through the backend, so it is reachable on the
 * acceptance origin with the existing session. Only registered in acceptance; strict allowlist, no free path forwarding.
 */
@RestController
@RequestMapping("/api/scenarios")
@ConditionalOnProperty(name = ["pvdd.environment"], havingValue = "acceptance")
class AcceptanceScenarioController(
    private val properties: MeetingSourceProperties,
    private val client: HttpClient,
    private val resets: AcceptanceScenarioResetService,
    private val scheduler: MeetingCheckScheduler,
    private val guard: MutationGuard,
) {
    @GetMapping
    fun overview(): ResponseEntity<String> = forward("GET", "/api/scenarios", null)

    @PostMapping("/reset")
    fun reset(): ResponseEntity<String> = forward("POST", "/api/scenarios/reset", null)

    @PostMapping("/reset-data")
    fun resetData(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<AcceptanceResetResult> = ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(guard.execute(email, "acceptance-reset-data", idempotencyKey, resets::reset))

    @PostMapping("/scheduled-check")
    fun scheduledCheck(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<MeetingCheckResult> {
        val result = guard.execute(email, "check-now", idempotencyKey, scheduler::scheduledCheck)
        return ResponseEntity.status(if (result.status == MeetingCheckStatus.ALREADY_RUNNING) HttpStatus.CONFLICT else HttpStatus.OK)
            .header("Cache-Control", "no-store")
            .header("X-Meeting-Check-Cron", MeetingCheckScheduler.CRON)
            .header("X-Meeting-Check-Zone", MeetingCheckScheduler.ZONE)
            .body(result)
    }

    @PostMapping("/{name}")
    fun selectPost(@PathVariable name: String): ResponseEntity<String> = select("POST", name)

    @PutMapping("/{name}")
    fun selectPut(@PathVariable name: String): ResponseEntity<String> = select("PUT", name)

    @PutMapping("/documents/{documentId}")
    fun touchDocument(@PathVariable documentId: String, request: HttpServletRequest): ResponseEntity<String> {
        if (!DOCUMENT_ID.matches(documentId)) return error(HttpStatus.BAD_REQUEST, "invalid_document_id")
        val raw = request.inputStream.use { it.readNBytes(MAX_BODY_BYTES + 1) }
        if (raw.size > MAX_BODY_BYTES) return error(HttpStatus.BAD_REQUEST, "invalid_body")
        val text = raw.toString(Charsets.UTF_8)
        val body = if (text.isBlank()) {
            null
        } else {
            val match = BODY.matchEntire(text) ?: return error(HttpStatus.BAD_REQUEST, "invalid_body")
            match.groupValues[1].takeIf { it.isNotEmpty() }?.let { "{\"version\":$it}" }
        }
        return forward("PUT", "/api/scenarios/documents/$documentId", body)
    }

    private fun select(method: String, name: String): ResponseEntity<String> =
        if (!NAME.matches(name) || name in setOf("documents", "reset-data", "scheduled-check")) error(HttpStatus.NOT_FOUND, "not_found")
        else forward(method, "/api/scenarios/$name", null)

    private fun forward(method: String, path: String, body: String?): ResponseEntity<String> {
        val target = URI.create(properties.baseUrl.toString().trimEnd('/') + path)
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val builder = HttpRequest.newBuilder(target).method(method, publisher).timeout(properties.requestTimeout)
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json")
        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            json(HttpStatus.valueOf(response.statusCode()), response.body())
        } catch (_: IOException) {
            unreachable()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unreachable()
        } catch (_: IllegalArgumentException) {
            unreachable()
        }
    }

    private fun unreachable() = error(HttpStatus.BAD_GATEWAY, "scenario_mock_unreachable")

    private fun error(status: HttpStatus, code: String) = json(status, "{\"error\":\"$code\"}")

    private fun json(status: HttpStatus, body: String): ResponseEntity<String> = ResponseEntity.status(status)
        .header("Content-Type", "application/json")
        .header("Cache-Control", "no-store")
        .body(body)

    companion object {
        private const val MAX_BODY_BYTES = 1024
        private val NAME = Regex("[a-z0-9-]{1,64}")
        private val DOCUMENT_ID = Regex("[A-Za-z0-9._-]{1,100}")
        private val BODY = Regex("""\s*\{\s*(?:"version"\s*:\s*(-?[0-9]{1,9})\s*)?}\s*""")
    }
}
