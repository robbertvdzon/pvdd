package nl.vdzon.pvdd.meetings

import java.io.File
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.vdzon.pvdd.policy.PolicySyncService
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression

class MeetingCheckSchedulerTest {
    private fun scheduled(type: Class<*>, method: String): Scheduled =
        type.declaredMethods.first { it.name == method }.getAnnotation(Scheduled::class.java)

    @Test
    fun `agenda check runs at 00 00 and standpoints at 01 30 on the first, both before 02 00`() {
        val check = scheduled(MeetingCheckScheduler::class.java, "scheduledCheck")
        val policy = PolicySyncService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(Scheduled::class.java) }
            .first { it.cron.isNotEmpty() }
        assertEquals("0 0 0 * * *", check.cron)
        assertEquals("0 30 1 1 * *", policy.cron)
        assertEquals("Europe/Amsterdam", check.zone)
        assertEquals("Europe/Amsterdam", policy.zone)

        val zone = ZoneId.of(check.zone)
        val checkCron = CronExpression.parse(check.cron)
        val policyCron = CronExpression.parse(policy.cron)
        listOf(
            ZonedDateTime.of(2026, 1, 14, 6, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 28, 6, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 7, 14, 6, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 10, 24, 6, 0, 0, 0, zone),
        ).forEach { previous ->
            val next = requireNotNull(checkCron.next(previous))
            assertEquals(LocalTime.of(0, 0), next.toLocalTime())
            assertEquals(zone, next.zone)
        }
        listOf(
            ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, zone),
        ).forEach { start ->
            // De eerste controle op de 1e valt op 00:00, de standpunten volgen 90 minuten later.
            val agenda = requireNotNull(checkCron.next(start.minusSeconds(1)))
            val standpoints = requireNotNull(policyCron.next(start.minusSeconds(1)))
            assertEquals(1, standpoints.dayOfMonth)
            assertTrue(standpoints.toLocalTime().isBefore(LocalTime.of(2, 0)))
            assertEquals(Duration.ofMinutes(90), Duration.between(agenda, standpoints))
        }
    }

    @Test
    fun `constants match the annotations`() {
        assertEquals("0 0 0 * * *", MeetingCheckScheduler.CRON)
        assertEquals("0 30 1 1 * *", PolicySyncService.MONTHLY_CRON)
    }

    @Test
    fun `old schedule times no longer occur in settings texts, readme, docs and adr`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "docs").isDirectory && File(it, "README.md").isFile }
        val files = listOf(File(root, "README.md")) +
            File(root, "docs").walkTopDown().filter { it.isFile && it.extension == "md" }.toList() +
            File(root, "backend/src/main/kotlin/nl/vdzon/pvdd/settings/SettingsService.kt")
        val old = Regex("""\b0?5:00|\b0?3:30|0 0 5 \* \* \*|0 30 3 1 \* \*""")
        // Alleen docs/stories (worklogs) bevat historische beschrijvingen.
        val offenders = files.filterNot { it.path.contains("docs/stories/") }.flatMap { file ->
            file.readLines().withIndex().filter { old.containsMatchIn(it.value) }
                .map { "${file.relativeTo(root)}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue(offenders.isEmpty(), "Oude plantijden gevonden:\n" + offenders.joinToString("\n"))
    }
}
