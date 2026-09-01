package nl.vdzon.pvdd.meetings

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.vdzon.pvdd.source.MeetingSourceGateway
import nl.vdzon.pvdd.source.MeetingSourceProperties
import nl.vdzon.pvdd.source.SourceTransportCode
import nl.vdzon.pvdd.source.SourceTransportException
import org.slf4j.LoggerFactory
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class MeetingDiscoveryService(
    private val source: MeetingSourceGateway,
    private val properties: MeetingSourceProperties,
    private val parser: AgendaParser,
    private val clock: Clock,
) : MeetingDiscoveryGateway {
    fun discover(): DiscoveryOutcome = discover(clock.instant())

    override fun discover(now: Instant): DiscoveryOutcome {
        return try {
            val today = LocalDate.ofInstant(now, AMSTERDAM)
            val candidates = (today.year..today.year + 1)
                .flatMap { year -> parseYearPage(source.fetch(yearUri(year)).body) }
                .filter { !it.date.isBefore(today) }
                .distinctBy { it.url }
                .sortedBy { it.date }

            for (candidate in candidates) {
                val agenda = fetchAgenda(candidate.url, enrichReports = false)
                if (agenda.startsAt <= now) continue
                val meeting = DiscoveredMeeting(agenda.sourceId, agenda.startsAt, agenda.sourceUrl)
                return if (agenda.published) DiscoveryOutcome.Found(meeting) else DiscoveryOutcome.AgendaUnpublished(meeting)
            }
            DiscoveryOutcome.NoFutureMeeting
        } catch (failure: SourceTransportException) {
            DiscoveryOutcome.SourceFailure(failure.code.toErrorCode())
        } catch (failure: AgendaParseException) {
            DiscoveryOutcome.SourceFailure(failure.code)
        }
    }

    override fun fetchAgenda(sourceUrl: URI, enrichReports: Boolean): ParsedMeetingAgenda {
        val agenda = parser.parse(source.fetch(sourceUrl).body, sourceUrl)
        if (!enrichReports) return agenda
        val enrichedItems = agenda.items.map { item ->
            if (item.category == AgendaCategory.C && item.substantive && item.documents.isEmpty() && item.sourceUrl.fragment == null) {
                try {
                    parser.parseReportItem(
                        source.fetch(item.sourceUrl).body,
                        item.sourceUrl,
                        item.sequence,
                        item.parentSourceId ?: item.sourceId,
                    )
                } catch (failure: SourceTransportException) {
                    log.warn("Report enrichment for {} failed with {}", item.sourceId, failure.code)
                    item
                } catch (failure: AgendaParseException) {
                    log.warn("Report enrichment for {} failed with {}", item.sourceId, failure.code)
                    item
                }
            } else {
                item
            }
        }
        return agenda.copy(items = enrichedItems)
    }

    private fun yearUri(year: Int): URI {
        val agendaType = URLEncoder.encode(properties.agendaTypeId, StandardCharsets.UTF_8)
        return URI.create("/Agenda/RetrieveAgendasForYear?agendatypeId=$agendaType&year=$year")
    }

    private fun parseYearPage(html: String): List<MeetingCandidate> {
        val document = Jsoup.parse(html, properties.baseUrl.toString())
        return document.select("a[href*=/Agenda/Index/]").mapNotNull { link ->
            val date = parseCandidateDate(link.text()) ?: return@mapNotNull null
            val href = link.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MeetingCandidate(date, properties.baseUrl.resolve(href))
        }
    }

    private fun parseCandidateDate(text: String): LocalDate? {
        val normalized = text.trim().lowercase(DUTCH).replace(WHITESPACE, " ")
        return DATE_PATTERN.find(normalized)?.value?.let { value ->
            runCatching { LocalDate.parse(value, DATE_FORMAT) }.getOrNull()
        }
    }

    private fun SourceTransportCode.toErrorCode(): SourceErrorCode = when (this) {
        SourceTransportCode.CONNECT_TIMEOUT -> SourceErrorCode.CONNECT_TIMEOUT
        SourceTransportCode.READ_TIMEOUT -> SourceErrorCode.READ_TIMEOUT
        SourceTransportCode.DISALLOWED_REDIRECT -> SourceErrorCode.DISALLOWED_REDIRECT
        SourceTransportCode.HTTP_ERROR,
        SourceTransportCode.RESPONSE_TOO_LARGE,
        SourceTransportCode.INVALID_URL,
        -> SourceErrorCode.HTTP_ERROR
    }

    private data class MeetingCandidate(val date: LocalDate, val url: URI)

    companion object {
        private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")
        private val DUTCH = Locale.forLanguageTag("nl-NL")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", DUTCH)
        private val DATE_PATTERN = Regex("(?:maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag) \\d{1,2} [a-z]+ \\d{4}")
        private val WHITESPACE = Regex("\\s+")
        private val log = LoggerFactory.getLogger(MeetingDiscoveryService::class.java)
    }
}

interface MeetingDiscoveryGateway {
    fun discover(now: Instant): DiscoveryOutcome
    fun fetchAgenda(sourceUrl: URI, enrichReports: Boolean = true): ParsedMeetingAgenda
}
