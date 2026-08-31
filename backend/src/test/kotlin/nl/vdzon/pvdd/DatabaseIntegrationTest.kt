package nl.vdzon.pvdd

import kotlin.test.assertEquals
import nl.vdzon.pvdd.persistence.ApplicationMetadataRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest
class DatabaseIntegrationTest(
    @param:Autowired private val metadataRepository: ApplicationMetadataRepository,
    @param:Autowired private val healthEndpoint: HealthEndpoint,
) {
    @Test
    fun `empty PostgreSQL is migrated and metadata survives writes`() {
        assertEquals("PvdD technical baseline", metadataRepository.get("schema-purpose"))
        metadataRepository.put("integration-test", "works")
        assertEquals("works", metadataRepository.get("integration-test"))
        assertEquals("UP", healthEndpoint.health().status.code)
    }

    companion object {
        @Container @ServiceConnection @JvmField
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
