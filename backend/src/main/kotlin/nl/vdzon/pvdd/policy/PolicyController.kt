package nl.vdzon.pvdd.policy

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/policy")
class PolicyController(private val service: PolicySyncService) {
    @GetMapping("/overview")
    fun overview(): PolicyOverviewDto = service.overview()

    @GetMapping("/positions/{id}")
    fun position(@PathVariable id: UUID): PolicyPositionDto = service.position(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping("/sync-runs/current")
    fun currentRun(): ResponseEntity<PolicyRunDto> {
        val run = service.currentRun() ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(run)
    }

    @PostMapping("/refresh")
    fun refresh(@RequestHeader("Idempotency-Key") idempotencyKey: String): ResponseEntity<PolicyRunDto> {
        val result = service.startManual(idempotencyKey)
        val run = result.run
        val dto = PolicyRunDto(
            run.id, run.trigger.name, run.status.name, run.createdAt, run.startedAt, run.completedAt,
            run.updatedAt, run.sourceCount, run.newCount, run.changedCount, run.unchangedCount,
            run.disappearedCount, run.errorCode,
        )
        return ResponseEntity.status(if (result.started) HttpStatus.ACCEPTED else HttpStatus.CONFLICT).body(dto)
    }
}
