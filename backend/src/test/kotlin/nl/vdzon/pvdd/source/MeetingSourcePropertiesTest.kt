package nl.vdzon.pvdd.source

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MeetingSourcePropertiesTest {
    @Test
    fun `acceptance allows only the internal mock service`() {
        MeetingSourceProperties(URI("http://pvdd-meeting-source-mock:8080"), "acceptance").validateEnvironmentBoundary()
        assertFailsWith<IllegalArgumentException> {
            MeetingSourceProperties(URI("https://noordholland.bestuurlijkeinformatie.nl"), "acceptance").validateEnvironmentBoundary()
        }
    }

    @Test
    fun `production allows only the exact approved https origin`() {
        MeetingSourceProperties(URI("https://noordholland.bestuurlijkeinformatie.nl"), "production").validateEnvironmentBoundary()
        listOf(
            "http://noordholland.bestuurlijkeinformatie.nl",
            "https://noordholland.bestuurlijkeinformatie.nl.evil.example",
            "http://pvdd-meeting-source-mock:8080",
            "https://noordholland.bestuurlijkeinformatie.nl/fixture",
        ).forEach { forbidden ->
            assertFailsWith<IllegalArgumentException>(forbidden) {
                MeetingSourceProperties(URI(forbidden), "production").validateEnvironmentBoundary()
            }
        }
    }

    @Test
    fun `unknown environments fail closed`() {
        assertFailsWith<IllegalStateException> {
            MeetingSourceProperties(URI("http://localhost:18091"), "typo").validateEnvironmentBoundary()
        }
    }

    @Test
    fun `the backend and the production deployment know no unconditional scenario route and no mock`() {
        val sources = Files.walk(Path.of("src", "main")).use { it.filter(Files::isRegularFile).toList() }
        assertTrue(sources.isNotEmpty())
        // De route bestaat uitsluitend in de controller die alleen bij pvdd.environment=acceptance wordt geregistreerd.
        sources.filter { Files.readString(it).contains("api/scenarios") }.forEach {
            assertEquals("AcceptanceScenarioController.kt", it.fileName.toString())
            assertTrue(Files.readString(it).contains("havingValue = \"acceptance\""), "$it is niet beperkt tot acceptatie")
        }

        val production = Path.of("..", "deploy", "overlays", "production")
        val files = Files.list(production).use { it.filter { f -> f.toString().endsWith(".yaml") }.toList() }
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val text = Files.readString(file)
            listOf("meeting-source-mock", "MOCKED", "api/scenarios").forEach {
                assertEquals(false, text.contains(it), "$file bevat $it")
            }
        }
        // Productie accepteert bovendien alleen de goedgekeurde bron, nooit de mockservice.
        assertFailsWith<IllegalArgumentException> {
            MeetingSourceProperties(URI("http://pvdd-meeting-source-mock:8080"), "production").validateEnvironmentBoundary()
        }
    }

    private val runner = org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)

    @org.springframework.boot.context.properties.EnableConfigurationProperties(MeetingSourceProperties::class)
    class PropertiesConfig

    private fun bound(vararg values: String, check: (List<CommissieSource>) -> Unit) {
        runner.withPropertyValues("pvdd.meeting-source.environment=local", *values).run { context ->
            check(context.getBean(MeetingSourceProperties::class.java).resolvedCommissies())
        }
    }

    @Test
    fun `binds Ruimte and Bestuur from the list`() = bound(
        "pvdd.meeting-source.commissies[0].commissie=RUIMTE",
        "pvdd.meeting-source.commissies[0].weergavenaam=Ruimte",
        "pvdd.meeting-source.commissies[0].agenda-type-id=1100617069",
        "pvdd.meeting-source.commissies[1].commissie=BESTUUR",
        "pvdd.meeting-source.commissies[1].weergavenaam=Bestuur",
        "pvdd.meeting-source.commissies[1].agenda-type-id=1100617071",
    ) {
        assertEquals(listOf(CommissieSource(Commissie.RUIMTE, "Ruimte", "1100617069"), CommissieSource(Commissie.BESTUUR, "Bestuur", "1100617071")), it)
    }

    @Test
    fun `old property alone is the Ruimte alias`() = bound("pvdd.meeting-source.agenda-type-id=1100617069") {
        assertEquals("1100617069", it.first { c -> c.commissie == Commissie.RUIMTE }.agendaTypeId)
        assertEquals("", it.first { c -> c.commissie == Commissie.BESTUUR }.agendaTypeId)
    }

    @Test
    fun `old alias with another value applies to Ruimte`() = bound("pvdd.meeting-source.agenda-type-id=42") {
        assertEquals("42", it.first { c -> c.commissie == Commissie.RUIMTE }.agendaTypeId)
    }

    @Test
    fun `empty or missing Bestuur id binds as empty`() {
        bound(
            "pvdd.meeting-source.commissies[0].commissie=BESTUUR",
            "pvdd.meeting-source.commissies[0].agenda-type-id=",
        ) { assertEquals("", it.first { c -> c.commissie == Commissie.BESTUUR }.agendaTypeId) }
        bound { assertEquals("", it.first { c -> c.commissie == Commissie.BESTUUR }.agendaTypeId) }
    }

    @Test
    fun `list value wins over the old property`() = bound(
        "pvdd.meeting-source.agenda-type-id=111",
        "pvdd.meeting-source.commissies[0].commissie=RUIMTE",
        "pvdd.meeting-source.commissies[0].agenda-type-id=222",
    ) { assertEquals("222", it.first { c -> c.commissie == Commissie.RUIMTE }.agendaTypeId) }
}
