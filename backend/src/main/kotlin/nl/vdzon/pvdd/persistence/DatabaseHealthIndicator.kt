package nl.vdzon.pvdd.persistence

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component("pvddDatabase")
class DatabaseHealthIndicator(private val metadataRepository: ApplicationMetadataRepository) : HealthIndicator {
    override fun health(): Health = runCatching {
        check(metadataRepository.get("schema-purpose") == "PvdD technical baseline")
        Health.up().withDetail("schema", "available").build()
    }.getOrElse { Health.down().withDetail("schema", "unavailable").build() }
}
