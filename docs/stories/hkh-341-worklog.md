# Worklog hkh-341 — Acceptatiefixture met meerdere vergaderingen en scenario's

## hkh-342 — development

### Afhankelijkheden gecontroleerd
Aanwezig in de checkout: discovery-lijst (`MeetingDiscoveryService.discover` geeft een lijst),
per-vergadering-workflow met `MeetingCheckResult.meetings`, leesmodel `series`
(`DashboardRepository.series`) en het kader 'Deze vergaderingen horen bij elkaar'. Geen blokkade.

### Gedaan
- `meeting-source-mock/server.py`: zes scenario's, route `/api/scenarios` (kiezen, reset, overzicht,
  documentwijziging), tellen van agenda-ophaalacties per vergadering, datums relatief aan het
  selectiemoment in Europe/Amsterdam. `tzdata` toegevoegd aan de mock-image (alpine heeft geen zoneinfo).
- Productie: geen wijziging; de test `ProductionConfigurationTest` (Python) en
  `MeetingSourcePropertiesTest` (Kotlin) bewijzen dat er geen mock en geen scenarioroute is.
- Backend: `MeetingScenarioSource` en `MeetingScenarioIngestor` (testcode) en 8 scenariotests in
  `DatabaseIntegrationTest` (echte PostgreSQL): aantallen, grensdag 10/11 over de wintertijdovergang
  (klok 20 oktober 2026), niets opgeslagen bij niet-gepubliceerd, mislukte vergadering met HTTP 200,
  herhaalde controle zonder en met documentwijziging, reset.
- Frontend: `test/scenario_ui_test.dart` op 1400 en 390 px.
- Documentatie: sectie in `docs/technical-integrations.md`, verwijzing in README.

### Bewust anders
- AI-mockaanroepen: `check-now` roept zelf geen AI aan; de enige trigger is `MeetingImportedEvent`.
  De tests tellen die gebeurtenissen, `newAnalyses`, `analysis_run` en `meeting_revision`.
- In de test blijft een revisie `REPROCESSING` tot de analyse klaar is; de test rondt die af met
  `markSuccessful`, zoals de orchestrator doet.
- De scenarioroute staat in de Python-mock (er is geen andere mock); `MOCK_SCENARIO_ROUTES=disabled`
  schakelt haar uit.

## hkh-345 — documentation (2026-10-12)

### Doel
Controleren of de documentatie klopt met de volledige storydiff (scenariomock, route, tests).

### Bijgewerkt
- Geen inhoudelijke wijzigingen nodig: `docs/technical-integrations.md` (sectie
  'Meerdere-vergaderingenscenario's') en de verwijzing in `README.md` beschrijven de route
  `/api/scenarios`, de zes scenario's, de reset, de documentwijziging en de tests al correct.
- Deze sectie in de worklog toegevoegd.

### Bewust niet gewijzigd
- `docs/microservice-specificatie.md` en `docs/factory/*`: geen productiegedrag, beleid of limieten
  gewijzigd; alleen mock en testcode.
- `docs/stappenplannen/*`: uitvoeringsplannen, geen beschrijving van huidig gedrag.

### Verificatie
- `bash tools/verify-documentation.sh` uitgevoerd; alleen `docs/` is geraakt.
