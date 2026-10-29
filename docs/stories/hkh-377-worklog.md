# Worklog hkh-377 — analyse commissie-onafhankelijk (Bestuur)

Story hkh-376, branch `ai/hkh-376`.

## Bevindingen
- Afhankelijkheid aanwezig: migratie V17 (`meeting.commissie`), `Commissie`, ontdekken per commissie.
- Job-aanmaak, bronnotities, synthese en validatie bevatten geen vaste Ruimte-aanname meer te verwijderen; de
  wachtrij, fingerprint en idempotentie werken al per punt. Het enige ontbrekende was de commissienaam in de prompt.
- Er is geen limiet, budget of groeigrens toegevoegd en model en provider zijn ongewijzigd.

## Wijzigingen
- `PromptBuilder.plan(item, commissie, ...)` en `synthesisPrompt(sourceId, commissie, ...)`: de zin wordt
  `Analyseer agendapunt X van commissie <naam> in categorie <C>.` (ook in notities- en synthese-prompt).
  `PROMPT_VERSION`, systeemprompt en schema's zijn gelijk gebleven.
- `AnalysisOrchestrator` geeft `meeting.commissieName` door. `Meeting.commissieName` bestaat zodat module
  `analysis` niet van `source` hoeft af te hangen (Modulith-regel).
- Het leesmodel toont al `documentStatus` `NO_DOCUMENTS` / `DOCUMENTS_UNREADABLE` voor punten zonder leesbaar stuk;
  de UI-tekst ('Geen stukken') is niet gewijzigd (UI valt buiten scope).

## Testvoorzieningen
- `src/test/.../analysis/CommissieAnalysisFixtures.kt`: `RecordingRuntimeGateway` (registrerende AI-fake),
  `TextDocumentIngestor` (leesbaar/onleesbaar stuk, instelbare tekst), `SingleChunkPolicyStore`, `seededAgenda`.
- `DatabaseIntegrationTest`: `CommissieAnalysisHarness` en 7 tests voor AC1 t/m AC7. Alles draait in een
  teruggedraaide transactie, dus de scheduler ziet niets en er blijft niets achter.
- `ContentResultValidationTest`: promptinhoud per fase met commissienaam.

## Reproduceren
Zonder Docker: zie `docs/factory/development.md` (lege database via `PVDD_TEST_DATABASE_URL`), daarna
`cd backend && mvn -B --no-transfer-progress clean verify`. Resultaat: 253 tests, 0 failures, 7 skipped
(zonder database zijn de databasetests overgeslagen).
Mutatiecontrole: `"Ruimte"` hardcoden in de orchestrator laat drie nieuwe tests falen.

## hkh-380 — documentation (2026-10-29)

### Doel
Documentatie laten aansluiten op de commissie-onafhankelijke analyse voor Bestuur (story hkh-376).

### Bijgewerkt
- `docs/microservice-specificatie.md` §4.4: alinea over commissie-onafhankelijke analyse, de
  commissienaam in de prompt, idempotentie op fingerprint en het ontbreken van limieten voor Bestuur.
- `docs/microservice-specificatie.md` §13.1: testdekking voor de Bestuur-scenario's.

### Bewust niet gewijzigd
- De 00:00/01:30-planningsteksten: die hoorden bij hkh-370 en zijn al bijgewerkt.
- `docs/functionele-werking-commissie-assistent.md`, `docs/factory/*`: leesmodel, UI en gebruikersgedrag
  zijn ongewijzigd; commissiekeuze in de UI valt buiten scope.
- Datumregels bovenaan bestaande docs.

### Verificatie
- `bash tools/verify-documentation.sh`: zie uitkomst in de jobsamenvatting; alleen `docs/` is geraakt.
