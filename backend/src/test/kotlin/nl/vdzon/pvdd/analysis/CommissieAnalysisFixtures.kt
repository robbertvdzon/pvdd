package nl.vdzon.pvdd.analysis

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID
import nl.vdzon.pvdd.documents.DocumentIngestionSummary
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentReference
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.documents.ExtractedSection
import nl.vdzon.pvdd.documents.ExtractionStatus
import nl.vdzon.pvdd.documents.SourceDocument
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.AgendaParser
import nl.vdzon.pvdd.meetings.ParsedAgendaItem
import nl.vdzon.pvdd.meetings.ParsedDocumentLink
import nl.vdzon.pvdd.meetings.ParsedMeetingAgenda
import nl.vdzon.pvdd.policy.PolicyChunk
import nl.vdzon.pvdd.policy.PolicyStore
import nl.vdzon.pvdd.policy.PolicyTheme
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import nl.vdzon.pvdd.runtime.RuntimeCreateRequest
import nl.vdzon.pvdd.runtime.RuntimeExecution
import nl.vdzon.pvdd.runtime.RuntimeExecutionOption
import nl.vdzon.pvdd.runtime.RuntimeJob
import nl.vdzon.pvdd.runtime.RuntimeResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Testvoorziening: registrerende AI-fake. Elke `create` is een AI-aanroep en wordt bewaard met de
 * volledige aanvraag (prompt, schema, uitvoering). Het resultaat is een geldig antwoord waarin ieder
 * onderdeel naar de bron-ID's uit de prompt verwijst; [resultFor] kan dat vervangen, bijvoorbeeld
 * door ongeldige uitvoer. Er wordt niets naar een echte runtime gestuurd.
 */
class RecordingRuntimeGateway(private val mapper: ObjectMapper) : AgentRuntimeGateway {
    val calls = mutableListOf<RuntimeCreateRequest>()
    var resultFor: (RuntimeCreateRequest) -> JsonNode = ::validResult
    private val requests = mutableMapOf<String, RuntimeCreateRequest>()

    /** Aanroepen waarvan de prompt [marker] bevat; zo telt een test alleen zijn eigen vergaderingen. */
    fun callsWith(marker: String): List<RuntimeCreateRequest> = calls.filter { marker in it.prompt }

    override fun create(request: RuntimeCreateRequest): RuntimeJob {
        calls += request
        val id = "fake-job-${calls.size}"
        requests[id] = request
        return job(id, request.idempotencyKey, "QUEUED")
    }

    override fun status(jobId: String): RuntimeJob = job(jobId, requests[jobId]?.idempotencyKey ?: jobId, "SUCCEEDED")

    override fun result(jobId: String): RuntimeResult =
        RuntimeResult(jobId, resultFor(requireNotNull(requests[jobId]) { "Onbekende fake job $jobId" }), Instant.now())

    override fun cancel(jobId: String): RuntimeJob = job(jobId, jobId, "CANCELLED")

    override fun executionOptions(): List<RuntimeExecutionOption> = emptyList()

    private fun job(id: String, key: String, status: String) =
        RuntimeJob(id, "test", key, RuntimeExecution("fake", "fake-model", "SUBSCRIPTION"), status, status)

    private fun validResult(request: RuntimeCreateRequest): JsonNode {
        val sourceIds = Regex("\"sourceId\":\"(doc-[^\"]+)\"").findAll(request.prompt).map { it.groupValues[1] }.distinct().toList()
        val cite = sourceIds.joinToString(", ") { "[bron: $it]" }.ifEmpty { "[bron: agenda]" }
        val isNotes = request.responseSchema.path("required").toString().contains("\"content\"") &&
            !request.responseSchema.path("properties").has("displayTitle")
        if (isNotes) return mapper.valueToTree(mapOf("content" to "Feitelijke notitie $cite"))
        val content = if ("in categorie C." in request.prompt) {
            "## Beoordeling B-agenda\n**Voldoende argumenten** $cite\n\n## Doorslaggevende argumenten\nOnderbouwd $cite"
        } else {
            "## Feitelijke samenvatting\nSamenvatting $cite\n\n## Wat sluit aan bij de PvdD?\n1. Aansluiting $cite\n\n" +
                "## Wat moet beter?\n1. Aandachtspunt $cite\n\n## Inzet en vragen voor de commissie\nVraag $cite"
        }
        return mapper.valueToTree(mapOf("displayTitle" to "Titel", "shortConclusion" to "Conclusie", "content" to content))
    }
}

/** Testvoorziening: documentverwerking met instelbare tekst; een [unreadable] stuk heeft geen secties. */
class TextDocumentIngestor(private val repository: DocumentRepository, private val clock: Clock) : DocumentIngestor {
    val texts = mutableMapOf<String, String>()
    val unreadable = mutableSetOf<String>()

    override fun ingest(agendaItemId: UUID, references: List<DocumentReference>): DocumentIngestionSummary {
        val documents = references.map { reference ->
            val body = texts[reference.sourceId] ?: "Synthetische tekst over wonen en natuur voor ${reference.sourceId}."
            val readable = reference.sourceId !in unreadable
            SourceDocument(
                id = UUID.randomUUID(),
                agendaItemId = agendaItemId,
                sourceId = reference.sourceId,
                name = reference.name,
                sourceUrl = reference.sourceUrl,
                declaredMimeType = "text/plain",
                detectedMimeType = "text/plain",
                sha256 = AgendaParser.sha256(body),
                sizeBytes = body.length.toLong(),
                extractionStatus = if (readable) ExtractionStatus.EXTRACTED else ExtractionStatus.UNSUPPORTED,
                fetchedAt = clock.instant(),
                errorCode = null,
                sections = if (readable) listOf(ExtractedSection(1, 1, null, body)) else emptyList(),
            ).also(repository::save)
        }
        return DocumentIngestionSummary(documents, true)
    }
}

/** Testvoorziening: één beleidsbron zonder database of download. */
class SingleChunkPolicyStore : PolicyStore {
    private val chunk = PolicyChunk(
        id = UUID.nameUUIDFromBytes("test-policy".toByteArray()),
        sourceUrl = URI("https://example.invalid/verkiezingsprogramma.pdf"),
        sourceSha256 = "d".repeat(64),
        fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
        pageNumber = 1,
        sequence = 1,
        heading = "Dieren en natuur",
        text = "De Partij voor de Dieren kiest voor dieren, natuur en betaalbaar wonen.",
        themes = setOf(PolicyTheme.ANIMALS_AND_NATURE),
    )

    override fun insert(chunk: PolicyChunk) = false
    override fun countByHash(sourceSha256: String) = 1
    override fun latestSource(): String = chunk.sourceSha256
    override fun findByHash(sourceSha256: String) = listOf(chunk)
}

/** Testvoorziening: zaaihelper voor een agenda van een commissie met per punt categorie en stukken. */
data class SeedItem(
    val key: String,
    val category: AgendaCategory,
    val documents: List<String> = listOf("$key-doc"),
    val explanation: String? = "Toelichting bij $key",
)

fun seededAgenda(sourceId: String, startsAt: Instant, items: List<SeedItem>, title: String = "Synthetische vergadering"): ParsedMeetingAgenda {
    val url = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/$sourceId")
    return ParsedMeetingAgenda(
        sourceId = sourceId,
        committee = "Commissie",
        title = title,
        startsAt = startsAt,
        endsAt = startsAt.plusSeconds(3600),
        location = "Statenzaal",
        sourceUrl = url,
        sourceHash = AgendaParser.sha256(sourceId + title + items.joinToString("|") { it.key + it.explanation }),
        published = true,
        agendaDocuments = emptyList(),
        items = items.mapIndexed { index, item ->
            ParsedAgendaItem(
                sourceId = item.key,
                parentSourceId = null,
                sequence = index + 1,
                displayNumber = "${index + 1}",
                category = item.category,
                title = "Titel ${item.key}",
                explanation = item.explanation,
                treatmentProposal = "Bespreken",
                sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Item/${item.key}"),
                sourceHash = AgendaParser.sha256(item.key + item.category + item.explanation + item.documents),
                substantive = true,
                section = false,
                documents = item.documents.map {
                    ParsedDocumentLink(it, "Stuk $it", URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Document/$it"))
                },
            )
        },
    )
}
