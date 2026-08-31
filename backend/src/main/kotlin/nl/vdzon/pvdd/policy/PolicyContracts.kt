package nl.vdzon.pvdd.policy

import java.net.URI
import java.time.Instant
import java.util.UUID

data class PolicyChunk(
    val id: UUID,
    val sourceUrl: URI,
    val sourceSha256: String,
    val fetchedAt: Instant,
    val pageNumber: Int,
    val sequence: Int,
    val heading: String?,
    val text: String,
    val themes: Set<PolicyTheme>,
)

enum class PolicyTheme {
    ANIMALS_AND_NATURE,
    BIODIVERSITY,
    CLIMATE_AND_RESOURCES,
    HEALTHY_ENVIRONMENT,
    ECOLOGY_OVER_SHORT_TERM_ECONOMY,
    CIRCULAR_BUILDING,
    HOUSING_AND_AFFORDABILITY,
    WALKING_CYCLING_PUBLIC_TRANSPORT,
    ROADS_AND_AVIATION,
    TRANSPARENCY_PRIVACY_PARTICIPATION,
    FAIR_DISTRIBUTION_AND_FUTURE_GENERATIONS,
}
