package nl.vdzon.pvdd.dashboard

import java.sql.ResultSet

/**
 * Waarom een `FINAL_ADVICE`-run is gedaan.
 *
 * De afleiding stond eerst alleen inline in [AiRunQueryRepository] en wordt sinds de adviesversies
 * door beide weergaven gedeeld, zodat de AI-runslijst en `GET /api/agenda-items/{id}/advice-versions`
 * niet uiteen kunnen lopen: één SQL-fragment ([reanalysisPredicate]) en één beslissing ([of]).
 *
 * Voor déze afleiding bestonden nog geen codewaarden. `AGENDA_RETRY`, `AGENDA_REANALYSIS` en
 * `AGENDA_ADVICE` zijn de *soort* logische AI-run — dezelfde reeks bevat ook `POLICY_SYNC` — en niet
 * de reden van vernieuwing; de reden krijgt daarom eigen codes. De AI-runslijst blijft haar
 * bestaande soortcodes, titels en uitleg letterlijk teruggeven, nu afgeleid uit deze enum.
 */
enum class AdviceRefreshReason {
    /** `retry_of_run_id` is gevuld: handmatig opnieuw gestart na een technische fout. */
    MANUAL_RETRY,

    /** Geen retry, maar er bestond al een oudere `FINAL_ADVICE`-run: de bron- of beleidscontext veranderde. */
    CONTEXT_CHANGED,

    /** De eerste analyse van dit agendapunt. */
    FIRST_ANALYSIS,
    ;

    companion object {
        /** De kolomnaam waaronder [reanalysisPredicate] in beide queries wordt geselecteerd. */
        const val REANALYSIS_COLUMN = "reanalysis"

        /**
         * Het SQL-fragment dat bewijst dat er voor hetzelfde agendapunt al een oudere
         * `FINAL_ADVICE`-run bestond, met [runAlias] als alias van de rij waarover het gaat.
         */
        fun reanalysisPredicate(runAlias: String): String =
            "EXISTS (SELECT 1 FROM analysis_run older WHERE older.agenda_item_id = $runAlias.agenda_item_id " +
                "AND older.run_type = 'FINAL_ADVICE' AND older.created_at < $runAlias.created_at)"

        /**
         * Leidt de reden af uit een rij die `retry_of_run_id` en [reanalysisPredicate] als
         * [REANALYSIS_COLUMN] selecteert.
         */
        fun of(rs: ResultSet): AdviceRefreshReason = of(
            manualRetry = rs.getObject("retry_of_run_id") != null,
            reanalysis = rs.getBoolean(REANALYSIS_COLUMN),
        )

        fun of(manualRetry: Boolean, reanalysis: Boolean): AdviceRefreshReason = when {
            manualRetry -> MANUAL_RETRY
            reanalysis -> CONTEXT_CHANGED
            else -> FIRST_ANALYSIS
        }
    }
}
