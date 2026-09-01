package nl.vdzon.pvdd

import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import nl.vdzon.pvdd.documents.DocumentDownloadProperties
import nl.vdzon.pvdd.documents.DocumentDownloadException
import nl.vdzon.pvdd.documents.DocumentDownloader
import nl.vdzon.pvdd.documents.DocumentExtractor
import nl.vdzon.pvdd.documents.DocumentReference
import nl.vdzon.pvdd.documents.DownloadBudget
import nl.vdzon.pvdd.meetings.AgendaParser
import nl.vdzon.pvdd.meetings.DiscoveryOutcome
import nl.vdzon.pvdd.meetings.MeetingDiscoveryService
import nl.vdzon.pvdd.source.MeetingSourceClient
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Read-only production-source probe. It never writes a database or starts an AI job. */
@EnabledIfEnvironmentVariable(named = "PVDD_LIVE_SOURCE_SPIKE", matches = "true")
class LiveSourceSpikeTest {
    @Test
    fun `inventory the current public committee source without persistence or AI`() {
        val sourceProperties = MeetingSourceProperties(
            baseUrl = URI("https://noordholland.bestuurlijkeinformatie.nl"),
            environment = "production",
        )
        sourceProperties.validateEnvironmentBoundary()
        val client = HttpClient.newBuilder()
            .connectTimeout(sourceProperties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val discovery = MeetingDiscoveryService(
            MeetingSourceClient(sourceProperties, client),
            sourceProperties,
            AgendaParser(),
            Clock.systemUTC(),
        )
        val outcome = discovery.discover()
        val meeting = when (outcome) {
            is DiscoveryOutcome.Found -> outcome.meeting
            is DiscoveryOutcome.AgendaUnpublished -> outcome.meeting
            else -> error("No inspectable future meeting: $outcome")
        }
        val agenda = discovery.fetchAgenda(meeting.sourceUrl, enrichReports = true)
        check(agenda.committee == "Commissie Ruimte")

        val documentProperties = DocumentDownloadProperties()
        val downloader = DocumentDownloader(sourceProperties, documentProperties, client)
        val extractor = DocumentExtractor(documentProperties)
        val budget = DownloadBudget(documentProperties)
        val documents = agenda.items.filter { it.substantive }.flatMap { item ->
            item.documents.map { document ->
                try {
                    val downloaded = downloader.download(
                        DocumentReference(document.sourceId, document.name, document.sourceUrl),
                        budget,
                    )
                    val extracted = extractor.extract(downloaded, java.util.UUID.randomUUID(), Clock.systemUTC().instant())
                    mapOf(
                        "agendaItemSourceId" to item.sourceId,
                        "sourceId" to document.sourceId,
                        "name" to document.name,
                        "declaredMimeType" to downloaded.declaredMimeType,
                        "detectedMimeType" to extracted.detectedMimeType,
                        "sizeBytes" to downloaded.bytes.size,
                        "extractionStatus" to extracted.extractionStatus.name,
                        "sections" to extracted.sections.size,
                        "characters" to extracted.sections.sumOf { it.text.length },
                    )
                } catch (failure: DocumentDownloadException) {
                    mapOf(
                        "agendaItemSourceId" to item.sourceId,
                        "sourceId" to document.sourceId,
                        "name" to document.name,
                        "extractionStatus" to "DOWNLOAD_FAILED",
                        "errorCode" to failure.code.name,
                    )
                }
            }
        }
        val report = linkedMapOf<String, Any>(
            "outcome" to outcome::class.simpleName.orEmpty(),
            "sourceId" to agenda.sourceId,
            "sourceUrl" to agenda.sourceUrl.toString(),
            "startsAt" to agenda.startsAt.toString(),
            "published" to agenda.published,
            "agendaItems" to agenda.items.count { it.substantive },
            "categories" to agenda.items.filter { it.substantive }.groupingBy { it.category.name }.eachCount(),
            "documents" to documents,
            "ocrRequired" to documents.count { it["extractionStatus"] == "OCR_REQUIRED" },
        )
        println("LIVE_SOURCE_SPIKE_JSON=${jacksonObjectMapper().writeValueAsString(report)}")
    }
}
