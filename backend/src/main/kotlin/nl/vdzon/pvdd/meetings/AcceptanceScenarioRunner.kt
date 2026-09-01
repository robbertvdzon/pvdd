package nl.vdzon.pvdd.meetings

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** Runs the real workflow once after an acceptance deployment; never available in production. */
@Component
class AcceptanceScenarioRunner(
    private val workflow: MeetingCheckWorkflow,
    @Value("\${pvdd.environment:local}") environment: String,
    @Value("\${pvdd.acceptance-run-on-startup:false}") private val enabled: Boolean,
) {
    init {
        require(!enabled || environment.equals("acceptance", ignoreCase = true)) {
            "The startup acceptance scenario may only run in acceptance."
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun runAfterStartup() {
        if (!enabled) return
        val result = workflow.check()
        log.info("Acceptance startup scenario finished with status {}", result.status)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AcceptanceScenarioRunner::class.java)
    }
}
