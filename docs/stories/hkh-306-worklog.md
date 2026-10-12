# hkh-306 / hkh-307 — zoeklaag geeft geordende lijst

- `MeetingDiscoveryGateway.discover(now)` en `MeetingDiscoveryService.discover` geven `List<DiscoveryOutcome>` (nooit leeg).
- Eerstvolgende = eerste kandidaat met `startsAt > now` (ongewijzigde scan). Venster: Amsterdamse `LocalDate` + 10 dagen; vaste private constanten `WINDOW_DAYS = 10`, `MAX_MEETINGS = 3`. Afkappen op de jaarpagina-datum vóór het ophalen van extra agenda's.
- Extra kandidaten: `Found` / `AgendaUnpublished` / `SourceFailure` per kandidaat; er wordt niets geschreven.
- Aanroepers (`MeetingCheckWorkflow.check()`, `LiveSourceSpikeTest`) gebruiken `.first()`.
- Tests: `MeetingDiscoveryServiceTest` (AC1–AC8, met `SyntheticSource`, `amsterdam(...)`, registratie `fetches`).
- Afwijking: `discover` heeft geen schrijfpoort (PREVIEW-import zit in de workflow); "niets opgeslagen" is bewezen via één fetch per kandidaat en geen rapportverrijking.
- Commando: `cd backend && mvn -B --no-transfer-progress clean verify` (168 tests, 0 failures, 27 skipped zonder database).
