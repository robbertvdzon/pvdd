package nl.vdzon.pvdd.runtime

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Trusted application metadata selects a fixture; prompt/source text never controls test administration. */
enum class AcceptanceRuntimeFixture {
    ADVICE_AB, ADVICE_C, SOURCE_NOTES, SELF_TEST, BESTUUR_ADVICE_AB, BESTUUR_ADVICE_C;

    fun result(mapper: ObjectMapper): JsonNode = when (this) {
        SELF_TEST -> mapper.valueToTree(mapOf("message" to "mock"))
        SOURCE_NOTES -> mapper.valueToTree(mapOf("content" to
            "Deterministische acceptatiefixture: de aangeleverde bronnotities zijn verwerkt voor de synthetische scenariotest. Dit is geen inhoudelijke AI-beoordeling."))
        ADVICE_AB, ADVICE_C -> mapper.valueToTree(mapOf(
            "displayTitle" to "Advies voor de acceptatietest",
            "shortConclusion" to "Deterministische acceptatiefixture: de analyse is afgerond voor dit synthetische agendapunt.",
            "content" to if (this == ADVICE_C) """
                ## Beoordeling B-agenda

                Dit is een deterministische acceptatiefixture, geen inhoudelijke AI-beoordeling.

                ## Doorslaggevende argumenten

                De synthetische bron is verwerkt; de scenariotest controleert het analyseproces en de schermweergave.

                ## Mogelijke inzet bij verplaatsing

                Bespreek de uitkomst van het synthetische scenario tijdens de acceptatietest.
            """.trimIndent() else """
                ## Feitelijke samenvatting

                Dit is een deterministische acceptatiefixture, geen inhoudelijke AI-beoordeling.

                ## Wat sluit aan bij de PvdD?

                De synthetische bron is verwerkt voor de acceptatietest.

                ## Wat moet beter?

                Deze fixture controleert het analyseproces en de schermweergave; zij geeft geen werkelijk politiek advies.

                ## Inzet en vragen voor de commissie

                Controleer de uitkomst van het synthetische scenario tijdens de acceptatietest.
            """.trimIndent(),
        ))
        BESTUUR_ADVICE_AB, BESTUUR_ADVICE_C -> mapper.valueToTree(mapOf(
            "displayTitle" to "Synthetisch Bestuur-advies voor de acceptatietest",
            "shortConclusion" to "Deterministische acceptatiefixture: synthetische Bestuur-analyse afgerond voor dit verzonnen agendapunt.",
            "content" to if (this == BESTUUR_ADVICE_C) """
                ## Beoordeling B-agenda

                Synthetische Bestuur-fixture: voldoende argumenten voor bespreking van dit verzonnen ingekomen stuk. Geen inhoudelijke AI-beoordeling.

                ## Doorslaggevende argumenten

                Het verzonnen stuk is verwerkt; de scenariotest controleert het analyseproces voor commissie Bestuur.

                ## Mogelijke inzet bij verplaatsing

                Bespreek de uitkomst van het synthetische Bestuur-scenario tijdens de acceptatietest.
            """.trimIndent() else """
                ## Feitelijke samenvatting

                Synthetische Bestuur-fixture voor een verzonnen agendapunt, geen inhoudelijke AI-analyse.

                ## Wat sluit aan bij de PvdD?

                Het verzonnen stuk is verwerkt voor de acceptatietest van commissie Bestuur.

                ## Wat moet beter?

                Deze fixture controleert het analyseproces en de schermweergave; zij geeft geen werkelijk politiek advies.

                ## Inzet en vragen voor de commissie

                Controleer de uitkomst van het synthetische Bestuur-scenario tijdens de acceptatietest.
            """.trimIndent(),
        ))
    }
}
