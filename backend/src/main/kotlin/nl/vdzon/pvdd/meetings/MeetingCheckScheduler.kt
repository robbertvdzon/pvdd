package nl.vdzon.pvdd.meetings

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
class SchedulingConfiguration

@Component
class MeetingCheckScheduler(private val workflow: MeetingCheckWorkflow) {
    @Scheduled(cron = CRON, zone = ZONE)
    fun scheduledCheck() {
        workflow.check()
    }

    companion object {
        const val CRON = "0 0 5 * * *"
        const val ZONE = "Europe/Amsterdam"
    }
}
