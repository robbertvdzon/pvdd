# hkh-384 — Commissie-parameter, leesmodel en check-now per commissie

Story hkh-383, branch `ai/hkh-383`.

## Controle vooraf
- Kolom `meeting.commissie` (default `RUIMTE`, CHECK op RUIMTE/BESTUUR) staat in `V17__meeting_commissie.sql`.
- Discovery per commissie en `MeetingCheckResult.commissies` bestaan (hkh-363).

## Gedaan
- Leesroutes `GET /api/meetings/next`, `/api/meetings` (archief), `/api/meetings/{id}`,
  `/api/meetings/{id}/agenda-items`, `/api/agenda-items/{id}` en `/api/agenda-items/{id}/advice-versions`
  nemen `commissie` (ontbrekend = RUIMTE, exact `RUIMTE`/`BESTUUR`, anders 400 `invalid_commissie` vóór
  databasetoegang).
- `DashboardRepository` filtert op `meeting.commissie` (overzicht, serie, detail, punten, punt, versies,
  archieflijst met tellingen, `total` en paginering). Een id van de andere commissie geeft 404.
- Additief: `commissie`/`commissieNaam` op `MeetingDto` en `AgendaItemSummaryDto`; `agendaStatus`
  (`PUBLISHED`/`UNPUBLISHED`/`NONE`) op `MeetingOverviewDto`; `melding` per `CommissieCheckResult`.
- Module `dashboard` mag nu van `source` afhangen (enkel de enum `Commissie`).
- Geen nieuwe routes, mutaties, config, sessie/CSRF-wijziging. `ProductionReadAccessFilter` ongewijzigd:
  de querystring zit niet in `requestURI`.

## Tests
- `DashboardCommissieParameterTest` (400 zonder repositoryaanroep, doorgeven, 404, velden, 401).
- `ProductionReadAccessFilterTest`: querystring, padvarianten, POST, no-store, geen Set-Cookie.
- `DatabaseIntegrationTest`: scheiding Ruimte/Bestuur met gezaaide rijen, niet-gepubliceerde agenda,
  `melding` in de bestaande check-now-tests. Bestaande hkh-377-tests lezen Bestuur nu met `Commissie.BESTUUR`.
- Commando: `cd backend && PVDD_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/<verse-db> mvn -B --no-transfer-progress clean verify`
  (261 tests, 0 failures, 7 skipped).
