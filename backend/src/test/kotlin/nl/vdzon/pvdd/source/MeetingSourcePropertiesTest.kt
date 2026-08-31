package nl.vdzon.pvdd.source

import java.net.URI
import kotlin.test.assertFailsWith
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
}
