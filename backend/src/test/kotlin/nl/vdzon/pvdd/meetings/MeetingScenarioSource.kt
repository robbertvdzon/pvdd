package nl.vdzon.pvdd.meetings

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import nl.vdzon.pvdd.documents.DocumentIngestionSummary
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentReference
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.documents.ExtractedSection
import nl.vdzon.pvdd.documents.ExtractionStatus
import nl.vdzon.pvdd.documents.SourceDocument
import nl.vdzon.pvdd.source.MeetingSourceGateway
import nl.vdzon.pvdd.source.SourcePage
import nl.vdzon.pvdd.source.SourceTransportCode
import nl.vdzon.pvdd.source.SourceTransportException

/**
 * Testspiegel van de scenariomock (`meeting-source-mock/server.py`, route `/api/scenarios`): dezelfde
 * scenariotabel, dezelfde fixtures en dezelfde datumregel, maar in-process zodat de echte
 * [MeetingDiscoveryService] en [MeetingCheckWorkflow] zonder netwerk tegen een database draaien.
 * De scenariotabel moet gelijk blijven aan `MEETING_SCENARIOS` in server.py.
 */
class MeetingScenarioSource(
    private val clock: Clock,
    private val fixtures: Path = Path.of("..", "meeting-source-mock", "fixtures"),
    private val suffix: String = UUID.randomUUID().toString().take(8),
) : MeetingSourceGateway {
    data class Meeting(val id: String, val date: LocalDate, val published: Boolean, val fails: Boolean) {
        val documentIds get() = listOf("$id-doc-housing", "$id-doc-mobility")
    }

    var scenario: String = DEFAULT
        private set
    var meetings: List<Meeting> = emptyList()
        private set
    private val versions = mutableMapOf<String, Int>()
    private val fetches = mutableMapOf<String, Int>()
    private var selections = 0

    val agendaFetches: Map<String, Int> get() = fetches.toMap()

    fun select(name: String) {
        val table = requireNotNull(SCENARIOS[name]) { "Onbekend scenario $name" }
        selections++
        scenario = name
        val first = LocalDate.ofInstant(clock.instant(), AMSTERDAM).plusDays(1)
        meetings = table.mapIndexed { index, (offset, published, fails) ->
            Meeting("scn-$name-$suffix-$selections-${index + 1}", first.plusDays(offset.toLong()), published, fails)
        }
        versions.clear()
        fetches.clear()
    }

    fun reset() = select(DEFAULT)

    /** Wijzigt de inhoud van één document (zoals PUT /api/scenarios/documents/{id}). */
    fun changeDocument(documentId: String) {
        versions[documentId] = (versions[documentId] ?: 1) + 1
    }

    /** Alle bronidentifiers van de vergaderingen van dit scenario, ook de standaardvergadering. */
    fun sourceIds(): List<String> = meetings.map { it.id }.ifEmpty { listOf(LEGACY_ID) }

    override fun fetch(uri: URI): SourcePage {
        val path = uri.path
        val body = when {
            path == "/Agenda/RetrieveAgendasForYear" -> yearPage()
            path.startsWith("/Agenda/Index/") -> agenda(path.removePrefix("/Agenda/Index/"))
            path == "/Reports/Item/report-nature" -> text("report-nature.html")
            path.startsWith("/Document/View/") -> document(path.removePrefix("/Document/View/"))
            else -> throw SourceTransportException(SourceTransportCode.HTTP_ERROR)
        }
        return SourcePage(uri, 200, "text/html", body)
    }

    private fun yearPage(): String {
        if (meetings.isEmpty()) return text("year.html")
        val links = meetings.joinToString("") { """<a href="/Agenda/Index/${it.id}">${dutch(it.date)}</a>""" }
        return """<!doctype html><html lang="nl"><body>$links</body></html>"""
    }

    private fun agenda(id: String): String {
        fetches[id] = (fetches[id] ?: 0) + 1
        if (meetings.isEmpty() && id == LEGACY_ID) return text("agenda.html")
        val meeting = meetings.singleOrNull { it.id == id } ?: throw SourceTransportException(SourceTransportCode.HTTP_ERROR)
        if (meeting.fails) throw SourceTransportException(SourceTransportCode.HTTP_ERROR)
        return text(if (meeting.published) "agenda.html" else "agenda-unpublished.html")
            .replace("documentId=doc-", "documentId=${meeting.id}-doc-")
            .replace("data-document-id=\"doc-", "data-document-id=\"${meeting.id}-doc-")
            .replace(LEGACY_DATE, dutch(meeting.date))
            .replace(LEGACY_ID, meeting.id)
    }

    private fun document(id: String): String {
        val name = when {
            id.endsWith("doc-housing") -> "doc-housing.txt"
            id.endsWith("doc-mobility") -> "doc-mobility.html"
            id == "doc-nature" -> "doc-nature.txt" // bijlage van de gedeelde C-agendabrief
            else -> throw SourceTransportException(SourceTransportCode.HTTP_ERROR)
        }
        val version = versions[id] ?: 1
        return text(name) + if (version > 1) "\n<!-- versie $version -->\n" else ""
    }

    private fun text(name: String): String = Files.readString(fixtures.resolve(name))

    private fun dutch(date: LocalDate): String = date.format(DATE_FORMAT).lowercase(DUTCH)

    companion object {
        const val DEFAULT = "een-vergadering"
        const val LEGACY_ID = "acceptance-meeting-v1"
        private const val LEGACY_DATE = "maandag 31 december 2035"
        private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")
        private val DUTCH = Locale.forLanguageTag("nl-NL")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", DUTCH)

        /** (dagen na D, agenda gepubliceerd, agenda geeft bronfout) */
        val SCENARIOS: Map<String, List<Triple<Int, Boolean, Boolean>>> = mapOf(
            "twee-vergaderingen" to listOf(Triple(0, true, false), Triple(3, true, false)),
            "meer-dan-drie" to listOf(Triple(0, true, false), Triple(2, true, false), Triple(5, true, false), Triple(8, true, false)),
            "grens-dag-10-en-11" to listOf(Triple(0, true, false), Triple(10, true, false), Triple(11, true, false)),
            "extra-agenda-niet-gepubliceerd" to listOf(Triple(0, true, false), Triple(3, false, false)),
            "een-vergadering-mislukt" to listOf(Triple(0, true, false), Triple(2, true, true), Triple(4, true, false)),
            DEFAULT to emptyList(),
        )
    }
}

/** Leest de documenten via dezelfde bron en bewaart ze als geëxtraheerd document met hash. */
class MeetingScenarioIngestor(
    private val source: MeetingScenarioSource,
    private val repository: DocumentRepository,
    private val clock: Clock,
) : DocumentIngestor {
    var calls = 0
        private set

    override fun ingest(agendaItemId: UUID, references: List<DocumentReference>): DocumentIngestionSummary {
        calls++
        val documents = references.map { reference ->
            val body = source.fetch(reference.sourceUrl).body
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
                extractionStatus = ExtractionStatus.EXTRACTED,
                fetchedAt = clock.instant(),
                errorCode = null,
                sections = listOf(ExtractedSection(0, null, null, body)),
            ).also(repository::save)
        }
        return DocumentIngestionSummary(documents, true)
    }
}
