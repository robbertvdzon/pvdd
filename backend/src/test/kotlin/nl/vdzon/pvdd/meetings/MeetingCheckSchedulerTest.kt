package nl.vdzon.pvdd.meetings

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression

class MeetingCheckSchedulerTest {
    @Test
    fun `schedule is always 05 clock time in Europe Amsterdam`() {
        val annotation = MeetingCheckScheduler::class.java.getDeclaredMethod("scheduledCheck").getAnnotation(Scheduled::class.java)
        assertEquals("0 0 5 * * *", annotation.cron)
        assertEquals("Europe/Amsterdam", annotation.zone)

        val cron = CronExpression.parse(annotation.cron)
        val zone = ZoneId.of(annotation.zone)
        listOf(
            ZonedDateTime.of(2026, 1, 14, 6, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 28, 6, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 7, 14, 6, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 10, 24, 6, 0, 0, 0, zone),
        ).forEach { previous ->
            val next = requireNotNull(cron.next(previous))
            assertEquals(5, next.hour)
            assertEquals(zone, next.zone)
        }
    }
}
