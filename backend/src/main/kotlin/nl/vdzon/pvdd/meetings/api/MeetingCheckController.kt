package nl.vdzon.pvdd.meetings.api

import nl.vdzon.pvdd.meetings.MeetingCheckResult
import nl.vdzon.pvdd.meetings.MeetingCheckStatus
import nl.vdzon.pvdd.meetings.MeetingCheckWorkflow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings")
class MeetingCheckController(private val workflow: MeetingCheckWorkflow) {
    @PostMapping("/check-now")
    fun checkNow(): ResponseEntity<MeetingCheckResult> {
        val result = workflow.check()
        return ResponseEntity.status(
            if (result.status == MeetingCheckStatus.ALREADY_RUNNING) HttpStatus.CONFLICT else HttpStatus.OK,
        ).body(result)
    }
}
