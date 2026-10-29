package nl.vdzon.pvdd.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.ContentResultValidator
import tools.jackson.module.kotlin.jacksonObjectMapper

/** AI-mock voor Bestuur: gevalideerde, uitsluitend synthetische uitvoer. */
class BestuurAcceptanceFixtureTest {
    private val mapper = jacksonObjectMapper()
    private val bestuur = listOf(AcceptanceRuntimeFixture.BESTUUR_ADVICE_AB, AcceptanceRuntimeFixture.BESTUUR_ADVICE_C)

    @Test
    fun `Bestuur analysis and assessment output passes the existing advice validator`() {
        bestuur.forEach { fixture ->
            val content = ContentResultValidator().validateAdvice(fixture.result(mapper))
            assertTrue(content.isNotBlank(), fixture.name)
        }
    }

    @Test
    fun `an A or B analysis has the analysis sections and a C assessment has the assessment sections`() {
        val analysis = AcceptanceRuntimeFixture.BESTUUR_ADVICE_AB.result(mapper).path("content").stringValue()
        val assessment = AcceptanceRuntimeFixture.BESTUUR_ADVICE_C.result(mapper).path("content").stringValue()
        assertTrue("## Feitelijke samenvatting" in analysis && "## Inzet en vragen voor de commissie" in analysis)
        assertTrue("## Beoordeling B-agenda" in assessment && "## Doorslaggevende argumenten" in assessment)
        assertFalse("## Beoordeling B-agenda" in analysis)
    }

    @Test
    fun `every Bestuur fixture is marked synthetic and refers to no real source content`() {
        bestuur.forEach { fixture ->
            val text = fixture.result(mapper).toString()
            assertTrue("Synthetisch" in text || "synthetisch" in text, fixture.name)
            assertEquals(setOf("displayTitle", "shortConclusion", "content"), fixture.result(mapper).propertyNames().toSet())
            listOf("bestuurlijkeinformatie", "http://", "https://", "@").forEach {
                assertFalse(it in text.lowercase(), "${fixture.name} bevat $it")
            }
        }
    }

    @Test
    fun `the existing Ruimte fixtures are unchanged in name and still valid`() {
        assertEquals(
            listOf("ADVICE_AB", "ADVICE_C", "SOURCE_NOTES", "SELF_TEST"),
            AcceptanceRuntimeFixture.entries.take(4).map { it.name },
        )
        ContentResultValidator().validateAdvice(AcceptanceRuntimeFixture.ADVICE_AB.result(mapper))
        ContentResultValidator().validateAdvice(AcceptanceRuntimeFixture.ADVICE_C.result(mapper))
    }
}
