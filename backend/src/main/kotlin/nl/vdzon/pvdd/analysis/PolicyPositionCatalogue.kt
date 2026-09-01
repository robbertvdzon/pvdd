package nl.vdzon.pvdd.analysis

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.vdzon.pvdd.policy.PolicyPositionDto
import nl.vdzon.pvdd.policy.PolicySnapshotDto
import nl.vdzon.pvdd.policy.PolicySyncRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private data class PolicyPositionCataloguePayload(
    val snapshotVersion: Int,
    val snapshotActivatedAt: Instant,
    val positions: List<PolicyPositionCatalogueEntry>,
)

private data class PolicyPositionCatalogueEntry(
    val id: UUID,
    val title: String,
    val summary: String,
    val themes: List<String>,
    val direction: String,
    val status: String,
    val sourceDate: LocalDate?,
    val references: List<PolicyPositionReferenceEntry>,
)

private data class PolicyPositionReferenceEntry(
    val url: URI,
    val sourceType: String,
    val title: String,
    val pageNumber: Int?,
    val section: String?,
)

@Component
class PolicyPositionCatalogue(
    private val repository: PolicySyncRepository,
    private val mapper: ObjectMapper,
) {
    fun currentSource(): AnalysisSource? {
        val snapshot = repository.activeSnapshot() ?: return null
        return policyPositionCatalogueSource(snapshot, repository.positions(snapshot.id), mapper)
    }
}

internal fun policyPositionCatalogueSource(
    snapshot: PolicySnapshotDto,
    positions: List<PolicyPositionDto>,
    mapper: ObjectMapper,
): AnalysisSource? {
    if (positions.isEmpty()) return null
    val entries = positions.map { position ->
        PolicyPositionCatalogueEntry(
            id = position.id,
            title = position.title,
            summary = position.summary,
            themes = position.themes,
            direction = position.direction,
            status = position.status,
            sourceDate = position.sourceDate,
            references = position.references.map { reference ->
                PolicyPositionReferenceEntry(
                    url = reference.url,
                    sourceType = reference.sourceType,
                    title = reference.title,
                    pageNumber = reference.pageNumber,
                    section = reference.section,
                )
            },
        )
    }
    val sourceUrl = entries.asSequence().flatMap { it.references.asSequence() }.map { it.url }.firstOrNull()
        ?: URI("https://noordholland.partijvoordedieren.nl/onze-idealen")
    return AnalysisSource(
        sourceId = "policy-positions-snapshot-${snapshot.id}",
        sourceType = CitationSourceType.POLICY_POSITIONS,
        sourceUrl = sourceUrl,
        pageNumber = null,
        section = "Volledig actueel standpuntenoverzicht (${entries.size})",
        text = mapper.writeValueAsString(
            PolicyPositionCataloguePayload(
                snapshotVersion = snapshot.version,
                snapshotActivatedAt = snapshot.activatedAt,
                positions = entries,
            ),
        ),
    )
}
