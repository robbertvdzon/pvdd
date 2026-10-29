package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Instant
import nl.vdzon.pvdd.documents.DocumentIngestionSummary
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.source.Commissie
import nl.vdzon.pvdd.source.CommissieSource

/**
 * Testvoorziening: configureerbare, registrerende nep-bron per commissie (agenda-type).
 *
 * Per commissie kan de bron een of meer agenda's leveren (gepubliceerd of niet gepubliceerd), een
 * transportfout ([SourceErrorCode]) of een onverwachte uitzondering. Een fout ná gedeeltelijke
 * schrijfacties ontstaat met [ingestor] of [failOnFetch]: de vergadering en eerdere agendapunten zijn
 * dan al geschreven. Alle aanroepen worden per commissie geregistreerd.
 *
 * Gebruik de eerstvolgende-regel van de productiecode niet na: de bron kiest hier wel zelf de vroegste
 * agenda met `startsAt > nu` (strikt), zodat `startsAt == nu` afvalt.
 */
class CommissieFakeSource(
    private val configured: List<CommissieSource> = listOf(
        CommissieSource(Commissie.RUIMTE, "Ruimte", "1100617069"),
        CommissieSource(Commissie.BESTUUR, "Bestuur", "1100617071"),
    ),
) : MeetingDiscoveryGateway {
    private val agendas = mutableMapOf<Commissie, List<ParsedMeetingAgenda>>()
    private val transportFailures = mutableMapOf<Commissie, SourceErrorCode>()
    private val discoveryFailures = mutableMapOf<Commissie, RuntimeException>()
    private val fetchFailures = mutableMapOf<Commissie, RuntimeException>()
    private val ingestFailures = mutableMapOf<Commissie, RuntimeException>()
    private var currentCommissie: Commissie? = null

    /** Commissies waarvoor [discoverCommissie] is aangeroepen, in aanroepvolgorde. */
    val discoverCalls = mutableListOf<Commissie>()

    /** Opgehaalde agenda-URL's per commissie. */
    val fetchCalls = mutableMapOf<Commissie, MutableList<URI>>()

    /** Aanroepen van [ingestor] per commissie. */
    val ingestCalls = mutableMapOf<Commissie, Int>()

    fun agendas(commissie: Commissie, vararg values: ParsedMeetingAgenda) = apply { agendas[commissie] = values.toList() }

    fun transportFailure(commissie: Commissie, code: SourceErrorCode = SourceErrorCode.HTTP_ERROR) =
        apply { transportFailures[commissie] = code }

    /** Laat de ontdekking zelf een onverwachte uitzondering gooien. */
    fun failOnDiscovery(commissie: Commissie, failure: RuntimeException) = apply { discoveryFailures[commissie] = failure }

    /** Laat het ophalen van de agenda falen (na de ontdekking, vóór enige schrijfactie). */
    fun failOnFetch(commissie: Commissie, failure: RuntimeException) = apply { fetchFailures[commissie] = failure }

    /** Laat de documentverwerking falen nadat de vergadering en eerdere agendapunten zijn geschreven. */
    fun failDuringIngest(commissie: Commissie, failure: RuntimeException) = apply { ingestFailures[commissie] = failure }

    fun fetchesOf(commissie: Commissie): List<URI> = fetchCalls[commissie].orEmpty()

    /** Documentverwerking die samen met deze bron faalt voor commissies uit [failDuringIngest]. */
    val ingestor = DocumentIngestor { _, _ ->
        val commissie = currentCommissie
        if (commissie != null) {
            ingestCalls.merge(commissie, 1, Int::plus)
            ingestFailures[commissie]?.let { throw it }
        }
        DocumentIngestionSummary(emptyList(), true)
    }

    override fun commissies(): List<CommissieSource> = configured

    override fun discover(now: Instant): List<DiscoveryOutcome> = discoverCommissie(Commissie.RUIMTE, now)

    override fun discoverCommissie(commissie: Commissie, now: Instant): List<DiscoveryOutcome> {
        discoverCalls += commissie
        discoveryFailures[commissie]?.let { throw it }
        transportFailures[commissie]?.let { return listOf(DiscoveryOutcome.SourceFailure(it)) }
        val next = agendas[commissie].orEmpty().filter { it.startsAt > now }.minByOrNull { it.startsAt }
            ?: return listOf(DiscoveryOutcome.NoFutureMeeting)
        val meeting = DiscoveredMeeting(next.sourceId, next.startsAt, next.sourceUrl)
        return listOf(if (next.published) DiscoveryOutcome.Found(meeting) else DiscoveryOutcome.AgendaUnpublished(meeting))
    }

    override fun fetchAgenda(sourceUrl: URI, enrichReports: Boolean): ParsedMeetingAgenda {
        val (commissie, agenda) = agendas.entries.firstNotNullOf { (key, list) ->
            list.firstOrNull { it.sourceUrl == sourceUrl }?.let { key to it }
        }
        fetchCalls.getOrPut(commissie) { mutableListOf() } += sourceUrl
        fetchFailures[commissie]?.let { throw it }
        currentCommissie = commissie
        return agenda
    }
}
