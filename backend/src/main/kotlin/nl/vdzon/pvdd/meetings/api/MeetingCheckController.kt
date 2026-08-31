package nl.vdzon.pvdd.meetings.api

import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.meetings.MeetingCheckResult
import nl.vdzon.pvdd.meetings.MeetingCheckStatus
import nl.vdzon.pvdd.meetings.MeetingCheckWorkflow
import nl.vdzon.pvdd.meetings.MutationGuard
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings")
class MeetingCheckController(private val workflow: MeetingCheckWorkflow, private val guard: MutationGuard) {
    @PostMapping("/check-now")
    fun checkNow(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<MeetingCheckResult> {
        val result = guard.execute(email, "check-now", idempotencyKey, workflow::check)
        return ResponseEntity.status(
            if (result.status == MeetingCheckStatus.ALREADY_RUNNING) HttpStatus.CONFLICT else HttpStatus.OK,
        ).body(result)
    }
}
