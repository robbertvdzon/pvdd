package nl.vdzon.pvdd.meetings

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

data class ParsedMeetingAgenda(
    val sourceId: String,
    val committee: String,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val location: String?,
    val sourceUrl: URI,
    val sourceHash: String,
    val published: Boolean,
    val agendaDocuments: List<ParsedDocumentLink>,
    val items: List<ParsedAgendaItem>,
)

data class ParsedAgendaItem(
    val sourceId: String,
    val parentSourceId: String?,
    val sequence: Int,
    val displayNumber: String?,
    val category: AgendaCategory,
    val title: String,
    val explanation: String?,
    val treatmentProposal: String?,
    val sourceUrl: URI,
    val sourceHash: String,
    val substantive: Boolean,
    val section: Boolean,
    val documents: List<ParsedDocumentLink>,
)

data class ParsedDocumentLink(
    val sourceId: String,
    val name: String,
    val sourceUrl: URI,
)

class AgendaParseException(val code: SourceErrorCode) : RuntimeException(code.name)

@Component
class AgendaParser {
    fun parse(html: String, sourceUrl: URI): ParsedMeetingAgenda {
        val document = Jsoup.parse(html, sourceUrl.toString())
        val committee = document.selectFirst("main h1, h1")?.text()?.trim().orEmpty()
        val dateText = document.selectFirst("main h2, h2")?.text()?.trim().orEmpty()
        val timeText = document.selectFirst(".heading3")?.text()?.trim().orEmpty()
        if (committee.isBlank() || dateText.isBlank() || timeText.isBlank() || document.selectFirst("#agendaitems") == null) {
            throw AgendaParseException(SourceErrorCode.UNKNOWN_HTML)
        }
        val date = parseDate(dateText)
        val (start, end) = parseTimes(date, timeText)
        val sourceId = sourceUrl.path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: throw AgendaParseException(SourceErrorCode.UNKNOWN_HTML)
        val items = parseItems(document.select("#agendaitems .agenda-item").toList(), sourceUrl)
        val agendaDocuments = document.select("dl .list-attachments a[data-document-id]").map { documentLink(it, sourceUrl) }
        val location = document.select("dl dt").firstOrNull { it.text().trim().equals("Locatie", ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
        return ParsedMeetingAgenda(
            sourceId = sourceId,
            committee = committee,
            title = "$committee $dateText",
            startsAt = start,
            endsAt = end,
            location = location,
            sourceUrl = sourceUrl,
            sourceHash = sha256(html),
            published = items.any { it.substantive },
            agendaDocuments = agendaDocuments,
            items = items,
        )
    }

    fun parseReportItem(html: String, sourceUrl: URI, sequence: Int, parentSourceId: String): ParsedAgendaItem {
        val document = Jsoup.parse(html, sourceUrl.toString())
        val main = document.selectFirst("main") ?: throw AgendaParseException(SourceErrorCode.UNKNOWN_HTML)
        val sourceId = main.attr("data-report-id").ifBlank { sourceUrl.path.substringAfterLast('/') }
        val title = main.selectFirst("h1")?.text()?.trim().orEmpty()
        if (sourceId.isBlank() || title.isBlank()) throw AgendaParseException(SourceErrorCode.UNKNOWN_HTML)
        val explanation = main.select("p").joinToString("\n") { it.text().trim() }.takeIf { it.isNotBlank() }
        return ParsedAgendaItem(
            sourceId = sourceId,
            parentSourceId = parentSourceId,
            sequence = sequence,
            displayNumber = null,
            category = AgendaCategory.C,
            title = title,
            explanation = explanation,
            treatmentProposal = null,
            sourceUrl = sourceUrl,
            sourceHash = sha256(html),
            substantive = true,
            section = false,
            documents = main.select(".list-attachments a[data-document-id]").map { documentLink(it, sourceUrl) },
        )
    }

    private fun parseItems(elements: List<Element>, meetingUrl: URI): List<ParsedAgendaItem> {
        val result = mutableListOf<ParsedAgendaItem>()
        var category = AgendaCategory.OTHER
        var sectionId: String? = null
        var sequence = 0
        elements.forEach { element ->
            val sourceId = element.id().trim()
            val title = element.selectFirst(".panel-heading .panel-title-label")?.text()?.trim().orEmpty()
            if (sourceId.isBlank() || title.isBlank()) return@forEach
            val sectionCategory = categoryFromSection(title)
            if (sectionCategory != null) {
                category = sectionCategory
                sectionId = sourceId
                result += parsedElement(element, meetingUrl, ++sequence, category, null, substantive = false, section = true)
                if (category == AgendaCategory.C) {
                    element.select("tr[data-entry-id][data-url]").forEach { row ->
                        val reportId = row.attr("data-entry-id").trim()
                        val reportTitle = row.selectFirst("td")?.text()?.trim().orEmpty()
                        if (reportId.isNotBlank() && reportTitle.isNotBlank()) {
                            result += ParsedAgendaItem(
                                sourceId = reportId,
                                parentSourceId = sectionId,
                                sequence = ++sequence,
                                displayNumber = null,
                                category = AgendaCategory.C,
                                title = reportTitle,
                                explanation = null,
                                treatmentProposal = null,
                                sourceUrl = meetingUrl.resolve(row.attr("data-url")),
                                sourceHash = sha256(row.outerHtml()),
                                substantive = true,
                                section = false,
                                documents = emptyList(),
                            )
                        }
                    }
                }
            } else if (element.closest(".agenda-item") == element && element.parents().none { it.hasClass("agenda-item") }) {
                result += parsedElement(
                    element,
                    meetingUrl,
                    ++sequence,
                    category,
                    sectionId,
                    substantive = !isFunctionallyEmpty(title),
                    section = false,
                )
            }
        }
        return result
    }

    private fun parsedElement(
        element: Element,
        meetingUrl: URI,
        sequence: Int,
        category: AgendaCategory,
        parentSourceId: String?,
        substantive: Boolean,
        section: Boolean,
    ): ParsedAgendaItem {
        val sourceId = element.id().trim()
        val title = element.selectFirst(".panel-heading .panel-title-label")!!.text().trim()
        val paragraphs = element.select(".panel-body p").map { it.text().trim() }
        return ParsedAgendaItem(
            sourceId = sourceId,
            parentSourceId = parentSourceId,
            sequence = sequence,
            displayNumber = element.selectFirst(".panel-heading .panel-id")?.text()?.trim()?.takeIf { it.isNotBlank() },
            category = category,
            title = title,
            explanation = labeledText(paragraphs, "Toelichting:"),
            treatmentProposal = labeledText(paragraphs, "Behandelvoorstel:"),
            sourceUrl = URI.create("${meetingUrl}#$sourceId"),
            sourceHash = sha256(element.outerHtml()),
            substantive = substantive,
            section = section,
            documents = element.select(".list-attachments a[data-document-id]").map { documentLink(it, meetingUrl) },
        )
    }

    private fun documentLink(element: Element, baseUrl: URI): ParsedDocumentLink {
        val nameNode = element.clone().apply { select(".badge, .icon").remove() }
        return ParsedDocumentLink(
            sourceId = element.attr("data-document-id").trim(),
            name = nameNode.text().trim(),
            sourceUrl = baseUrl.resolve(element.attr("href")),
        )
    }

    private fun categoryFromSection(title: String): AgendaCategory? = SECTION_PATTERN.find(title)?.groupValues?.get(1)?.uppercase()?.let {
        AgendaCategory.valueOf(it)
    }

    private fun labeledText(paragraphs: List<String>, label: String): String? = paragraphs
        .firstOrNull { it.startsWith(label, ignoreCase = true) }
        ?.substring(label.length)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun isFunctionallyEmpty(title: String): Boolean = EMPTY_TITLES.any { it.containsMatchIn(title) }

    private fun parseDate(text: String): LocalDate = try {
        LocalDate.parse(text.lowercase(DUTCH), DATE_FORMAT)
    } catch (_: Exception) {
        throw AgendaParseException(SourceErrorCode.UNKNOWN_HTML)
    }

    private fun parseTimes(date: LocalDate, text: String): Pair<Instant, Instant?> {
        val match = TIME_PATTERN.find(text) ?: throw AgendaParseException(SourceErrorCode.UNKNOWN_HTML)
        val startTime = LocalTime.parse(match.groupValues[1])
        val endTime = LocalTime.parse(match.groupValues[2])
        val start = ZonedDateTime.of(date, startTime, AMSTERDAM)
        var end = ZonedDateTime.of(date, endTime, AMSTERDAM)
        if (end.isBefore(start)) end = end.plusDays(1)
        return start.toInstant() to end.toInstant()
    }

    companion object {
        private val DUTCH = Locale.forLanguageTag("nl-NL")
        private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", DUTCH)
        private val TIME_PATTERN = Regex("(\\d{2}:\\d{2})\\s*-\\s*(\\d{2}:\\d{2})")
        private val SECTION_PATTERN = Regex("(?:^|\\b)([ABC])-agenda\\b", RegexOption.IGNORE_CASE)
        private val EMPTY_TITLES = listOf(
            Regex("^opening\\b", RegexOption.IGNORE_CASE),
            Regex("^pauze\\b", RegexOption.IGNORE_CASE),
            Regex("^sluiting\\b", RegexOption.IGNORE_CASE),
        )

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
