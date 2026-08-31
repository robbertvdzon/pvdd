package nl.vdzon.pvdd.documents

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.jsoup.Jsoup
import org.springframework.stereotype.Component

enum class SupportedDocumentType(val mimeType: String) {
    PDF("application/pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    HTML("text/html"),
    TEXT("text/plain"),
}

@Component
class DocumentExtractor(private val properties: DocumentDownloadProperties) {
    fun extract(download: DownloadedDocument, agendaItemId: UUID, fetchedAt: Instant): SourceDocument {
        val hash = sha256(download.bytes)
        val type = detectType(download.bytes)
        if (type == null) {
            return result(download, agendaItemId, fetchedAt, hash, null, ExtractionStatus.UNSUPPORTED, "UNSUPPORTED_MAGIC")
        }
        if (!mimeMatches(download.declaredMimeType, type)) {
            return result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.INVALID_CONTENT, "MIME_MISMATCH")
        }

        return try {
            val sections = when (type) {
                SupportedDocumentType.PDF -> extractPdf(download.bytes)
                SupportedDocumentType.DOCX -> extractDocx(download.bytes)
                SupportedDocumentType.HTML -> extractHtml(download.bytes)
                SupportedDocumentType.TEXT -> extractText(download.bytes)
            }
            val characterCount = sections.sumOf { it.text.length }
            when {
                characterCount > properties.maxExtractedCharacters ->
                    result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.TOO_LARGE, "EXTRACTED_TEXT_LIMIT")
                sections.none { it.text.isNotBlank() } && type == SupportedDocumentType.PDF ->
                    result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.OCR_REQUIRED, "NO_EXTRACTABLE_TEXT", sections)
                sections.none { it.text.isNotBlank() } ->
                    result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.INVALID_CONTENT, "NO_EXTRACTABLE_TEXT")
                else -> result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.EXTRACTED, null, sections)
            }
        } catch (_: DocumentSecurityException) {
            result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.INVALID_CONTENT, "UNSAFE_DOCUMENT")
        } catch (_: Exception) {
            result(download, agendaItemId, fetchedAt, hash, type, ExtractionStatus.INVALID_CONTENT, "EXTRACTION_FAILED")
        }
    }

    private fun extractPdf(bytes: ByteArray): List<ExtractedSection> = Loader.loadPDF(bytes).use { pdf ->
        if (pdf.isEncrypted || pdf.numberOfPages > properties.maxPdfPages) throw DocumentSecurityException()
        (1..pdf.numberOfPages).map { page ->
            val stripper = PDFTextStripper().apply {
                startPage = page
                endPage = page
                sortByPosition = true
            }
            ExtractedSection(page, page, null, normalizeText(stripper.getText(pdf)))
        }
    }

    private fun extractDocx(bytes: ByteArray): List<ExtractedSection> {
        val entries = zipEntries(bytes)
        if ("word/document.xml" !in entries || entries.any { it.equals("word/vbaProject.bin", ignoreCase = true) }) {
            throw DocumentSecurityException()
        }
        return XWPFDocument(ByteArrayInputStream(bytes)).use { document ->
            val sections = mutableListOf<ExtractedSection>()
            var heading: String? = null
            var sequence = 0
            val body = mutableListOf<String>()

            fun flush() {
                val text = normalizeText(body.joinToString("\n"))
                if (text.isNotBlank()) sections += ExtractedSection(++sequence, null, heading, text)
                body.clear()
            }

            document.bodyElements.forEach { element ->
                when (element) {
                    is XWPFParagraph -> {
                        val text = element.text.trim()
                        if (text.isNotBlank() && element.style.orEmpty().startsWith("Heading", ignoreCase = true)) {
                            flush()
                            heading = text
                        } else if (text.isNotBlank()) {
                            body += text
                        }
                    }
                    is XWPFTable -> element.rows.flatMap { it.tableCells }.forEach { cell ->
                        val text = cell.text.trim()
                        if (text.isNotBlank()) body += text
                    }
                }
            }
            flush()
            sections
        }
    }

    private fun extractHtml(bytes: ByteArray): List<ExtractedSection> {
        val document = Jsoup.parse(decodeUtf8(bytes))
        document.select("script,style,noscript,iframe,object,embed,template,svg").remove()
        val sections = mutableListOf<ExtractedSection>()
        var heading: String? = null
        var sequence = 0
        val body = mutableListOf<String>()
        fun flush() {
            val text = normalizeText(body.joinToString("\n"))
            if (text.isNotBlank()) sections += ExtractedSection(++sequence, null, heading, text)
            body.clear()
        }
        document.body().select("h1,h2,h3,h4,h5,h6,p,li,td,th").forEach { element ->
            val text = element.text().trim()
            if (text.isBlank()) return@forEach
            if (element.tagName().startsWith("h")) {
                flush()
                heading = text
            } else {
                body += text
            }
        }
        flush()
        return sections
    }

    private fun extractText(bytes: ByteArray): List<ExtractedSection> = listOf(
        ExtractedSection(1, null, null, normalizeText(decodeUtf8(bytes))),
    )

    private fun detectType(bytes: ByteArray): SupportedDocumentType? = when {
        bytes.beginsWith(PDF_MAGIC) -> SupportedDocumentType.PDF
        bytes.beginsWith(ZIP_MAGIC) && runCatching { "word/document.xml" in zipEntries(bytes) }.getOrDefault(false) -> SupportedDocumentType.DOCX
        looksLikeHtml(bytes) -> SupportedDocumentType.HTML
        runCatching { decodeUtf8(bytes) }.isSuccess && bytes.none { it == 0.toByte() } -> SupportedDocumentType.TEXT
        else -> null
    }

    private fun zipEntries(bytes: ByteArray): Set<String> {
        val entries = linkedSetOf<String>()
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > MAX_ZIP_ENTRIES || entry.name.contains("..") || entry.name.startsWith('/')) {
                    throw DocumentSecurityException()
                }
                entries += entry.name
            }
        }
        return entries
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val prefix = bytes.take(512).toByteArray().toString(StandardCharsets.UTF_8).trimStart().lowercase()
        return HTML_PREFIXES.any { prefix.startsWith(it) }
    }

    private fun ByteArray.beginsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun mimeMatches(declared: String?, detected: SupportedDocumentType): Boolean {
        val normalized = declared?.substringBefore(';')?.trim()?.lowercase()
        return normalized.isNullOrBlank() || normalized == "application/octet-stream" || when (detected) {
            SupportedDocumentType.PDF -> normalized == "application/pdf"
            SupportedDocumentType.DOCX -> normalized == detected.mimeType
            SupportedDocumentType.HTML -> normalized in setOf("text/html", "application/xhtml+xml")
            SupportedDocumentType.TEXT -> normalized == "text/plain"
        }
    }

    private fun result(
        download: DownloadedDocument,
        agendaItemId: UUID,
        fetchedAt: Instant,
        hash: String,
        type: SupportedDocumentType?,
        status: ExtractionStatus,
        errorCode: String?,
        sections: List<ExtractedSection> = emptyList(),
    ) = SourceDocument(
        id = UUID.randomUUID(),
        agendaItemId = agendaItemId,
        sourceId = download.reference.sourceId,
        name = download.reference.name,
        sourceUrl = download.reference.sourceUrl,
        declaredMimeType = download.declaredMimeType,
        detectedMimeType = type?.mimeType,
        sha256 = hash,
        sizeBytes = download.bytes.size.toLong(),
        extractionStatus = status,
        fetchedAt = fetchedAt,
        errorCode = errorCode,
        sections = sections,
    )

    private fun normalizeText(value: String): String = value
        .replace("\u0000", "")
        .lines()
        .joinToString("\n") { it.trim().replace(INLINE_WHITESPACE, " ") }
        .replace(MANY_NEWLINES, "\n\n")
        .trim()

    private class DocumentSecurityException : RuntimeException()

    companion object {
        private val PDF_MAGIC = "%PDF-".toByteArray()
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        private val HTML_PREFIXES = setOf("<!doctype html", "<html", "<head", "<body", "<article", "<main")
        private val INLINE_WHITESPACE = Regex("[\\t \\x0B\\f\\r]+")
        private val MANY_NEWLINES = Regex("\\n{3,}")
        private const val MAX_ZIP_ENTRIES = 2_000

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
