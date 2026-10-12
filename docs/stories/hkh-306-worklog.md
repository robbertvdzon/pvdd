# hkh-306 / hkh-307 — zoeklaag geeft geordende lijst

- `MeetingDiscoveryGateway.discover(now)` en `MeetingDiscoveryService.discover` geven `List<DiscoveryOutcome>` (nooit leeg).
- Eerstvolgende = eerste kandidaat met `startsAt > now` (ongewijzigde scan). Venster: Amsterdamse `LocalDate` + 10 dagen; vaste private constanten `WINDOW_DAYS = 10`, `MAX_MEETINGS = 3`. Afkappen op de jaarpagina-datum vóór het ophalen van extra agenda's.
- Extra kandidaten: `Found` / `AgendaUnpublished` / `SourceFailure` per kandidaat; er wordt niets geschreven.
- Aanroepers (`MeetingCheckWorkflow.check()`, `LiveSourceSpikeTest`) gebruiken `.first()`.
- Tests: `MeetingDiscoveryServiceTest` (AC1–AC8, met `SyntheticSource`, `amsterdam(...)`, registratie `fetches`).
- Afwijking: `discover` heeft geen schrijfpoort (PREVIEW-import zit in de workflow); "niets opgeslagen" is bewezen via één fetch per kandidaat en geen rapportverrijking.
- Commando: `cd backend && mvn -B --no-transfer-progress clean verify` (168 tests, 0 failures, 27 skipped zonder database).

## hkh-310 — documentation (2026-10-12)

### Doel
Documentatie in lijn brengen met de geordende lijst die de zoeklaag teruggeeft (hkh-306/hkh-307).

### Bijgewerkt
- `docs/microservice-specificatie.md` §4.2: lijstvorm, venster van tien kalenderdagen, maximum van
  drie vóór het ophalen van agenda's, uitkomst per kandidaat, foutisolatie en tijdelijk gebruik van
  alleen het eerste element.

### Bewust niet gewijzigd
- `docs/functionele-werking-commissie-assistent.md`, `docs/factory/*`, README en `docs/agent-access.md`:
  eindgebruikersgedrag, API, routes en configuratie zijn in deze story niet veranderd.
- `docs/stappenplannen/*`: uitvoeringsplannen, geen beschrijving van huidig gedrag.

### Verificatie
- `bash tools/verify-documentation.sh` → zie uitkomst in de samenvatting.
- Alleen bestanden onder `docs/` gewijzigd.
