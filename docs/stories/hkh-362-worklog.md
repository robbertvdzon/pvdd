# hkh-362 — worklog

## hkh-363 — development

### Gedaan
- `MeetingDiscoveryGateway` kreeg `commissies()` en `discoverCommissie(commissie, now)` (defaults: alleen Ruimte). `MeetingDiscoveryService` ontdekt per commissie via het agenda-type uit `MeetingSourceProperties`; `discover(now)` blijft Ruimte (en logt de skip-WARN).
- `MeetingCheckWorkflow.check()`: één lock, daarna per commissie een eigen `REQUIRES_NEW`-transactie (`checkCommissie`, geen self-invocation). Een vergadering daarbinnen is een `NESTED`-savepoint, zodat bestaand gedrag (één falende vergadering rolt alleen zichzelf terug) blijft. Een uitzondering of SourceFailure van één commissie wordt gelogd met commissienaam en staat in het resultaat; de andere commissie wordt verwerkt.
- Vergaderingen worden met hun `commissie` opgeslagen; Bestuur gebruikt hetzelfde PREVIEW-mechanisme en dezelfde `AgendaParser` als Ruimte.
- `MeetingCheckResult.commissies: List<CommissieCheckResult>` (additief; velden `status`, `meetingSourceId`, `meetings` blijven die van Ruimte). Geen nieuwe statuswaarden, route of omgevingsvariabele.

### Testvoorzieningen
- `backend/src/test/kotlin/nl/vdzon/pvdd/meetings/CommissieFakeSource.kt`: registrerende nep-bron per commissie (agenda, niet-gepubliceerd, transportfout, fout bij ontdekking/ophalen, fout tijdens documentverwerking = na gedeeltelijke schrijfacties).
- Zaaihelper `seedExistingMeeting` in `DatabaseIntegrationTest`.
- Draaien: `cd backend && mvn -B --no-transfer-progress clean verify`; databasetests met een lege database via `PVDD_TEST_DATABASE_URL` (zie `docs/factory/development.md`). Laatste run met database: 243 tests, 0 failures, 7 skipped (6 `AcceptanceScenarioResetServiceTest` zonder Docker-context, 1 `LiveSourceSpikeTest`).

### Leespadlijst
Nagegaan, niet gewijzigd: `DashboardRepository`/`DashboardController` kennen geen commissie-parameter; overzicht, `next` en archieflijst tonen vergaderingen van alle commissies. Een commissiefilter hoort bij een eigen story.

## hkh-366 — documentation (2026-10-29)

### Doel
Documentatie in lijn brengen met commissiegewijze ontdekking en foutisolatie (hkh-363).

### Bijgewerkt
- `docs/microservice-specificatie.md` §4.2: ontdekking per commissie, transactie per commissie, savepoint per
  vergadering, foutisolatie, PREVIEW voor Bestuur, additief resultaatveld `commissies`; zin dat Bestuur-discovery
  later volgt verwijderd.
- `docs/commissie-bestuur-bron.md`: Bestuur-discovery is nu geïmplementeerd.

### Bewust niet gewijzigd
- `docs/functionele-werking-commissie-assistent.md`, `docs/factory/*`: geen gebruikers- of procesverandering;
  geen frontend-, route-, schedule- of omgevingswijziging.

### Verificatie
- `bash tools/verify-documentation.sh`; alleen `docs/` geraakt.
