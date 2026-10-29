# Acceptatiemock: synthetische Bestuur-reeks

Uitsluitend voor de acceptatieomgeving. De bronmock (`meeting-source-mock/server.py`) serveert naast de Ruimte-reeks een
tweede, volledig verzonnen agendatype voor commissie Bestuur. Er is geen echte bron, geen echte AI en geen echte
persoonsgegevens of brontekst in gebruik; alle fixtures zijn als "synthetisch" gemarkeerd. Productie kent geen mock, geen
scenarioroute en geen Bestuur-agenda-type (`deploy/overlays/production` is ongewijzigd).

## Agenda-type en vergadering

- Agenda-type: `1100617071` (het vastgelegde bron-id uit `docs/commissie-bestuur-bron.md`; de bron was bereikbaar, dus het
  synthetische alternatief `9000000001` is niet nodig). Het staat in `deploy/overlays/acceptance/configmap.yaml` als
  `PVDD_MEETING_BESTUUR_AGENDA_TYPE_ID`. Elk ander of ontbrekend `agendatypeId` geeft ongewijzigd de Ruimte-reeks.
- De jaarlijst (`/Agenda/RetrieveAgendasForYear?agendatypeId=1100617071&year=2026`) bevat precies één vergadering:
  `bestuur-meeting-20261130`, maandag 30 november 2026, 13:00–17:00 Europe/Amsterdam (CET), dus 12:00–16:00 UTC. Andere
  jaren zijn leeg. Standaard (en na `reset`) is de agenda niet gepubliceerd.

## Scenario's

De scenario's lopen via de **bestaande scenariobediening** (geen nieuwe route, geen filterwijziging). In acceptatie stuurt
de backend ze door via `AcceptanceScenarioController`; rechtstreeks op de mock kan hetzelfde zonder sessie.

Aanroep: `POST /api/scenarios/<naam>` (of `PUT`) op de acceptatie-origin, met de gewone sessiecookie én, omdat het een
muterende aanroep is, de CSRF-controle (cookie `pvdd_csrf` en dezelfde waarde als header `X-CSRF-Token`). Daarna
"Nu controleren": `POST /api/meetings/check-now` met een unieke `Idempotency-Key`. Een Bestuur-scenario verandert de
Ruimte-toestand niet; een Ruimte-scenario laat de Bestuur-toestand ongemoeid. `POST /api/scenarios/reset` zet beide terug.
Een onbekende naam geeft HTTP 400 `unknown_scenario` met `allowed` (Ruimte) en `allowedBestuur`.

| Scenario | Inhoud van de mock | Verwacht resultaat van "Nu controleren" |
|----------|--------------------|-----------------------------------------|
| `bestuur-niet-gepubliceerd` (standaard) | Vergadering zichtbaar, agenda zonder punten ("wordt later gepubliceerd") | Bestuur: geslaagd, status `AGENDA_UNPUBLISHED`, geen AI-job. Ruimte onveranderd. |
| `bestuur-gepubliceerd` | A-punten `bestuur-item-a1` (met stuk) en `bestuur-item-a2` (zonder stuk), B-punt `bestuur-item-b1` (met stuk), C-punt `bestuur-report-c1` (rapportpagina met stuk) | Bestuur: geslaagd, `IMPORTED`; drie AI-jobs (A- en B-analyse, C-beoordeling), geen job voor het punt zonder stuk. Ruimte onveranderd. |
| `bestuur-gewijzigd-stuk` | Zelfde agenda als `bestuur-gepubliceerd`; de inhoud van `bestuur-doc-a1` verandert op verzoek | Eerste controle analyseert (3 jobs). Na `PUT /api/scenarios/documents/bestuur-doc-a1` (optioneel body `{"version":n}`; zelfde sessie/CSRF) veroorzaakt de tweede controle precies één heranalyse (alleen `bestuur-item-a1`); een derde controle zonder wijziging geeft `UNCHANGED` en nul extra jobs. |
| `bestuur-bronfout` | Het Bestuur-type geeft HTTP 503 (jaarlijst) / 500 (agenda) | Bestuur: mislukt (`success=false`, `SOURCE_FAILURE`), eerder opgeslagen Bestuur-gegevens blijven. Ruimte: geslaagd en onveranderd. |

`GET /api/scenarios` toont onder `bestuur` het gekozen scenario, het agenda-type, de beschikbare namen, de stukversies en
het aantal agenda-ophalingen.

## AI-mock

In acceptatie kiest de backend een vaste, gevalideerde fixture (`AcceptanceRuntimeFixture`). Voor een Bestuur-vergadering
zijn dat `BESTUUR_ADVICE_AB` (conceptanalyse voor A/B) en `BESTUUR_ADVICE_C` (beoordeling voor C); voor Ruimte blijven
`ADVICE_AB`/`ADVICE_C`. De uitvoer is synthetisch gemarkeerd, bevat geen verwijzing naar de echte bron en komt door de
bestaande `ContentResultValidator`. Er worden geen nieuwe limieten of budgetten afgedwongen.

## Testen en uitvoeren

- Mock (Python, geen credentials): `python3 -m unittest discover -s meeting-source-mock` — vergadering en tijdstip,
  scenario-inhoud, bronfout, gewijzigd stuk, Ruimte byte-voor-byte gelijk aan de baseline, synthetische fixtures,
  configuratie-assertie en productie-overlay zonder mock of Bestuur-type.
- Backend: `BestuurMockContractTest` (echte mock via HTTP, echte discovery en parser), `BestuurAcceptanceFixtureTest`
  (AI-mock-validatie), `AnalysisModelRoutingTest` (fixturekeuze per commissie) en in `DatabaseIntegrationTest` de vier
  `mock scenario ...`-tests (check-now met registrerende AI-fake: jobtelling, isolatie per commissie). Die laatste
  draaien met `PVDD_TEST_DATABASE_URL` (zie `docs/factory/development.md`); zonder `python3` worden ze overgeslagen.
- Acceptatiescenario-script: `python3 tools/acceptance-bestuur-scenarios.py --self-test` start de mock en een stub-backend
  lokaal en doorloopt per scenario "Nu controleren" met vergelijking van beide commissies. Tegen een echte
  acceptatieomgeving (optioneel, nooit een merge-poort):
  `PVDD_SESSION_COOKIE='<naam>=<waarde>' PVDD_CSRF_TOKEN='<waarde>' python3 tools/acceptance-bestuur-scenarios.py --backend-url https://<acceptatie-origin>`.
  Sessie en CSRF komen alleen uit de omgeving en worden nooit geprint.
