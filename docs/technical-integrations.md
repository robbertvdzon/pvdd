# Technische integraties

Dit document beschrijft alleen de technische fase. Er is nog geen parser, vergaderingselectie of
politieke analyse geïmplementeerd.

## Agent Runtime

De backend bevat een interne client voor het v2-contract van Agent Runtime: uploads hervatten, een
job aanmaken, status opvragen, resultaat ophalen en annuleren. Alle instellingen komen uit de omgeving:

| Variabele | Betekenis |
| --- | --- |
| `PVDD_AGENT_RUNTIME_BASE_URL` | Basis-URL zonder credentials |
| `PVDD_AGENT_RUNTIME_TOKEN` | Eigen PvdD-consumertoken; nooit naar frontend of logs |
| `PVDD_AGENT_RUNTIME_VENDOR_ID` | `mock` in acceptatie, `anthropic` in productie |
| `PVDD_AGENT_RUNTIME_MODEL` | `mock` in acceptatie, expliciet model in productie |
| `PVDD_AGENT_RUNTIME_MODE` | `MOCK`, `API` of `SUBSCRIPTION`; altijd expliciet |
| `PVDD_AGENT_RUNTIME_CONNECT_TIMEOUT` | Korte connect-time-out, standaard één seconde |
| `PVDD_AGENT_RUNTIME_REQUEST_TIMEOUT` | HTTP-time-out, standaard drie seconden |
| `PVDD_AGENT_RUNTIME_UPLOAD_TIMEOUT` | Time-out per uploadrequest, standaard dertig seconden |

De prompt wordt altijd als `text/markdown` in door de Runtime bepaalde chunks geüpload. De jobbody
bevat alleen de korte instructie, objectreferentie en het JSON-schema. Voor submit zoekt de client
naar een bestaande idempotentiesleutel. Bij een verloren submitresponse wordt opnieuw gezocht en
zo nodig exact dezelfde jobbody met hetzelfde object verstuurd. HTTP-fouten worden zonder
responsebody of credential naar een veilige interne fout vertaald.

`RuntimeSelfTestService` bestaat alleen in local en acceptance. Deze service heeft geen controller
en accepteert geen prompt van de frontend. De test vraagt uitsluitend een object met een
stringveld `message` op bij de centrale Runtime-mock.

## Vergaderbronmock

`meeting-source-mock` is een afzonderlijk non-root Nginx-image. Het levert alleen:

- `/health/live` en `/health/ready`;
- statische bestanden onder `/fixtures/`.

De backendguard koppelt de omgeving gesloten aan de bron-URL. Acceptance accepteert alleen de
interne mockservice. Production accepteert uitsluitend
`https://noordholland.bestuurlijkeinformatie.nl` en weigert HTTP, subdomeintrucs, paden en interne
servicenamen. De functionele downloader en parser worden pas in fase 2 toegevoegd.

### Meerdere-vergaderingenscenario's (alleen acceptatie)

De acceptatiemock (`meeting-source-mock/server.py`, Python, alleen in `deploy/overlays/acceptance`)
kent een scenarioroute onder `/api/scenarios`. Die route bestaat niet in productie: de productie-overlay
bevat geen mock en de backend heeft geen scenarioroute (`MeetingSourcePropertiesTest`,
`ProductionConfigurationTest`). `MOCK_SCENARIO_ROUTES=disabled` zet de route ook in de mock zelf uit (404).
De mock is alleen binnen het cluster bereikbaar, via `http://pvdd-meeting-source-mock:8080`, net als
de bestaande bronrevisieroute `POST /fixtures/control/<scenario>`, die ongewijzigd blijft.

| Aanroep | Betekenis |
|---|---|
| `GET /api/scenarios` | Huidig scenario, de vergaderingen (id, datum, gepubliceerd, bronfout), documentversies en het aantal agenda-ophaalacties per vergadering |
| `POST /api/scenarios/<naam>` (of `PUT`) | Kiest een scenario en geeft het overzicht terug. Onbekende naam: 400 met de lijst toegestane namen; het huidige scenario blijft |
| `POST /api/scenarios/reset` | Terug naar het standaardscenario `een-vergadering` |
| `PUT /api/scenarios/documents/<documentId>` | Wijzigt de inhoud van één document. Body `{"version": 3}` zet een versie, zonder body wordt de versie met één verhoogd. Het document-id staat in `GET /api/scenarios` niet apart; het is `<vergadering-id>-doc-housing` of `<vergadering-id>-doc-mobility` |

Scenario's (D = eerstvolgende kalenderdag in Europe/Amsterdam na het selectiemoment, dus altijd in de
toekomst; starttijd 18:30):

| Naam | Vergaderingen | Verwacht bij `Nu controleren` |
|---|---|---|
| `twee-vergaderingen` | D, D+3 | beide verwerkt |
| `meer-dan-drie` | D, D+2, D+5, D+8 | eerste drie; de vierde wordt niet opgehaald |
| `grens-dag-10-en-11` | D, D+10, D+11 | D en D+10 verwerkt, D+11 niet |
| `extra-agenda-niet-gepubliceerd` | D, D+3 (voorlopig) | D+3 'Agenda nog niet gepubliceerd', niets opgeslagen |
| `een-vergadering-mislukt` | D, D+2 (HTTP 500 op de agenda), D+4 | D en D+4 verwerkt, fout bij de tweede, HTTP 200 |
| `een-vergadering` (standaard) | 31 december 2035 | de bestaande situatie, geen kader |

Het scenario is interne toestand van de mock: een herstart geeft weer `een-vergadering`. Elke selectie
maakt nieuwe vergaderings-id's (`scn-<naam>-<volgnummer>-<n>`) en wist documentversies en
ophaaltellingen. Voorbeeld voor een verificatiesessie (vanuit het cluster):

```text
curl -X POST http://pvdd-meeting-source-mock:8080/api/scenarios/meer-dan-drie
# ... Nu controleren in de webapp, controleer het resultaat ...
curl http://pvdd-meeting-source-mock:8080/api/scenarios        # agendaFetches: de vierde ontbreekt
curl -X PUT http://pvdd-meeting-source-mock:8080/api/scenarios/documents/<id>-doc-housing
curl -X POST http://pvdd-meeting-source-mock:8080/api/scenarios/reset
```

Vergaderingen van een eerder scenario blijven in de acceptatiedatabase staan; ze verdwijnen niet door
een reset.

Reproduceerbare tests:

```text
python3 -m unittest discover -s meeting-source-mock          # mock, route, productie-overlay
cd backend && mvn -B --no-transfer-progress test -Dtest=DatabaseIntegrationTest   # scenario's tegen PostgreSQL
cd frontend && flutter test test/scenario_ui_test.dart       # kader, tegelwissel, resultaat; 1400 en 390 px
```

De backendtests gebruiken `MeetingScenarioSource` (testcode), een in-process spiegel van dezelfde
scenariotabel en fixtures; houd die gelijk aan `MEETING_SCENARIOS` in `server.py`.
