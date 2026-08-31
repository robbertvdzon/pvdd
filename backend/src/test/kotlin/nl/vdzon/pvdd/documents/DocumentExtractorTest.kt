package nl.vdzon.pvdd.documents

import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test

class DocumentExtractorTest {
    private val extractor = DocumentExtractor(DocumentDownloadProperties())
    private val agendaItemId = UUID.randomUUID()
    private val fetchedAt = Instant.parse("2026-08-31T05:00:00Z")

    @Test
    fun `extracts PDF text per page for citations`() {
        val result = extract(pdf("Eerste pagina", "Tweede pagina"), "application/pdf", "doc.pdf")

        assertEquals(ExtractionStatus.EXTRACTED, result.extractionStatus)
        assertEquals(listOf(1, 2), result.sections.map { it.pageNumber })
        assertTrue(result.sections[0].text.contains("Eerste pagina"))
        assertTrue(result.sections[1].text.contains("Tweede pagina"))
    }

    @Test
    fun `marks a PDF without text as OCR required`() {
        val result = extract(pdf(null), "application/pdf", "scan.pdf")
        assertEquals(ExtractionStatus.OCR_REQUIRED, result.extractionStatus)
        assertFalse(result.sections.any { it.text.isNotBlank() })
    }

    @Test
    fun `extracts DOCX headings and text without executing content`() {
        val output = ByteArrayOutputStream()
        XWPFDocument().use { document ->
            document.createParagraph().apply {
                style = "Heading1"
                createRun().setText("Natuur")
            }
            document.createParagraph().createRun().setText("Verbind de leefgebieden.")
            document.write(output)
        }

        val result = extract(
            output.toByteArray(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "notitie.docx",
        )
        assertEquals(ExtractionStatus.EXTRACTED, result.extractionStatus)
        assertEquals("Natuur", result.sections.single().heading)
        assertTrue(result.sections.single().text.contains("Verbind de leefgebieden"))
    }

    @Test
    fun `sanitizes HTML and extracts plain text fixtures`() {
        val html = extract(fixture("doc-mobility.html"), "text/html", "mobiliteit.html")
        assertEquals(ExtractionStatus.EXTRACTED, html.extractionStatus)
        assertEquals("Mobiliteitsnotitie", html.sections.single().heading)
        assertFalse(html.sections.single().text.contains("script", ignoreCase = true))

        val text = extract(fixture("doc-housing.txt"), "text/plain", "wonen.txt")
        assertEquals(ExtractionStatus.EXTRACTED, text.extractionStatus)
        assertTrue(text.sections.single().text.contains("natuurinclusief"))
    }

    @Test
    fun `rejects MIME spoofing and unsupported binary content`() {
        val fakePdf = extract(fixture("doc-scan.pdf"), "application/pdf", "scan.pdf")
        assertEquals(ExtractionStatus.INVALID_CONTENT, fakePdf.extractionStatus)
        assertEquals("MIME_MISMATCH", fakePdf.errorCode)

        val binary = extract(byteArrayOf(0, 1, 2, 3, 4), "application/octet-stream", "binary.bin")
        assertEquals(ExtractionStatus.UNSUPPORTED, binary.extractionStatus)
    }

    @Test
    fun `content hashes are stable and change with bytes`() {
        val first = extract("dezelfde bytes".toByteArray(), "text/plain", "a.txt")
        val second = extract("dezelfde bytes".toByteArray(), "text/plain", "b.txt")
        val changed = extract("gewijzigde bytes".toByteArray(), "text/plain", "a.txt")
        assertEquals(first.sha256, second.sha256)
        assertNotEquals(first.sha256, changed.sha256)
    }

    private fun extract(bytes: ByteArray, mime: String, name: String): SourceDocument = extractor.extract(
        DownloadedDocument(DocumentReference("fixture-$name", name, URI("https://example.test/$name")), mime, bytes),
        agendaItemId,
        fetchedAt,
    )

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/documents/$name"),
    ).use { it.readAllBytes() }

    private fun pdf(vararg pages: String?): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            pages.forEach { text ->
                val page = PDPage()
                document.addPage(page)
                if (text != null) {
                    PDPageContentStream(document, page).use { content ->
                        content.beginText()
                        content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        content.newLineAtOffset(72f, 720f)
                        content.showText(text)
                        content.endText()
                    }
                }
            }
            document.save(output)
        }
        return output.toByteArray()
    }
}
