package nl.vdzon.pvdd.policy

import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test

class PolicySourceTest {
    @Test
    fun `environment guard allows official PDF only in production`() {
        PolicySourceProperties(PolicySourceProperties.OFFICIAL_PROGRAMME_URL, "production").validate()
        assertFailsWith<IllegalArgumentException> {
            PolicySourceProperties(URI("https://assets.partijvoordedieren.nl/ander.pdf"), "production").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            PolicySourceProperties(PolicySourceProperties.OFFICIAL_PROGRAMME_URL, "acceptance").validate()
        }
    }

    @Test
    fun `same policy PDF produces stable hash chunks pages and themes`() {
        val bytes = policyPdf()
        val store = MemoryPolicyStore()
        val properties = PolicySourceProperties(chunkCharacters = 500)
        val service = PolicyImportService(
            PolicySourceGateway { PolicyPdf(bytes, "application/pdf") },
            properties,
            store,
            Clock.fixed(Instant.parse("2026-08-31T05:00:00Z"), ZoneOffset.UTC),
        )

        val first = service.ensureImported()
        val second = service.ensureImported()
        assertEquals(first, second)
        assertEquals(first.chunkCount, store.chunks.size)
        assertTrue(store.chunks.map { it.pageNumber }.containsAll(listOf(1, 2, 3, 4)))
        assertTrue(store.chunks.any { PolicyTheme.HOUSING_AND_AFFORDABILITY in it.themes })
        assertTrue(store.chunks.any { PolicyTheme.WALKING_CYCLING_PUBLIC_TRANSPORT in it.themes })
        assertTrue(store.chunks.any { PolicyTheme.ANIMALS_AND_NATURE in it.themes })
    }

    @Test
    fun `selector always includes core and finds topic passages with pages`() {
        val store = MemoryPolicyStore()
        val service = PolicyImportService(
            PolicySourceGateway { PolicyPdf(policyPdf(), "application/pdf") },
            PolicySourceProperties(chunkCharacters = 500),
            store,
            Clock.systemUTC(),
        )
        service.ensureImported()
        val selector = PolicySelector(store)

        listOf(
            "betaalbare woningen binnen bestaande bebouwing" to PolicyTheme.HOUSING_AND_AFFORDABILITY,
            "natuur en biodiversiteit leefgebieden" to PolicyTheme.ANIMALS_AND_NATURE,
            "fiets en openbaar vervoer geen nieuwe wegen" to PolicyTheme.WALKING_CYCLING_PUBLIC_TRANSPORT,
            "gezonde lucht en minder luchtvaart" to PolicyTheme.ROADS_AND_AVIATION,
        ).forEach { (query, theme) ->
            val selection = selector.select(query)
            assertTrue(selection.chunks.any { theme in it.themes }, query)
            assertTrue(selection.chunks.all { it.pageNumber > 0 && it.sourceSha256 == selection.sourceSha256 })
            assertTrue(selection.chunks.any { it.text.contains("draagkracht van de planeet") })
        }
    }

    @Test
    fun `analysis selection fails explicitly without primary source`() {
        assertFailsWith<MissingPolicySourceException> { PolicySelector(MemoryPolicyStore()).select("natuur") }
    }

    private fun policyPdf(): ByteArray {
        val pages = listOf(
            "Onze keuzes blijven binnen de draagkracht van de planeet en beschermen toekomstige generaties.",
            "Natuur en dieren hebben bescherming nodig. Biodiversiteit en leefgebieden worden verbonden.",
            "Betaalbare woningen komen eerst in bestaande bebouwing en worden natuurinclusief en circulair gebouwd.",
            "De fietser en het openbaar vervoer gaan voor. Geen nieuwe wegen en minder luchtvaart voor gezonde lucht.",
        )
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            pages.forEach { text ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 10f)
                    content.newLineAtOffset(50f, 720f)
                    content.showText(text)
                    content.endText()
                }
            }
            document.save(output)
        }
        return output.toByteArray()
    }

    private class MemoryPolicyStore : PolicyStore {
        val chunks = mutableListOf<PolicyChunk>()
        override fun insert(chunk: PolicyChunk): Boolean {
            if (chunks.any { it.sourceSha256 == chunk.sourceSha256 && it.pageNumber == chunk.pageNumber && it.sequence == chunk.sequence }) return false
            chunks += chunk
            return true
        }
        override fun countByHash(sourceSha256: String) = chunks.count { it.sourceSha256 == sourceSha256 }
        override fun latestSource(): String? = chunks.maxByOrNull { it.fetchedAt }?.sourceSha256
        override fun findByHash(sourceSha256: String) = chunks.filter { it.sourceSha256 == sourceSha256 }
            .sortedWith(compareBy(PolicyChunk::pageNumber, PolicyChunk::sequence))
    }
}
