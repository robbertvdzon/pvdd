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
}
