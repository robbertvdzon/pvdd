# Technische integraties

Dit document beschrijft de Runtime-koppeling en de synthetische acceptatievoorzieningen van
de Commissie-assistent. De parser, vergaderingselectie en analyseworkflow zijn geïmplementeerd.

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
| `PVDD_AGENT_RUNTIME_ACCEPTANCE_FIXTURES_ENABLED` | Registreert exacte Runtime-v2-testantwoorden vóór een job; uitsluitend acceptatie |
| `PVDD_AGENT_RUNTIME_MOCK_CONTROL_TOKEN` | Aparte testcontrolcredential op de backend; nooit beschikbaar voor browsers of testerjobs |

Met acceptatiefixtures ingeschakeld registreert de backend een deterministisch antwoord voor
tenant `pvdd` en de exacte idempotentiesleutel op `/v2/test-control/mocks`. De normale
consumertoken wordt voor uploads en jobs gebruikt; alleen de registratie gebruikt de aparte
testcontrolcredential. De profielen komen uit de analysefase en categorie, niet uit instructies
in brontekst. Productie weigert deze flag en credential bij het starten. Mocks worden niet globaal
gewist. De handmatige helper `tools/prepare-acceptance-runtime-mocks.sh` gebruikt eveneens v2
en vereist een specifieke jobsleutel; de oude v1-wachtrij van generieke antwoorden is vervallen.

De prompt wordt altijd als `text/markdown` in door de Runtime bepaalde chunks geüpload. De jobbody
bevat alleen de korte instructie, objectreferentie en het JSON-schema. Voor submit zoekt de client
naar een bestaande idempotentiesleutel. Bij een verloren submitresponse wordt opnieuw gezocht en
zo nodig exact dezelfde jobbody met hetzelfde object verstuurd. HTTP-fouten worden zonder
responsebody of credential naar een veilige interne fout vertaald.

`RuntimeSelfTestService` bestaat alleen in local en acceptance. Deze service heeft geen controller
en accepteert geen prompt van de frontend. De test vraagt uitsluitend een object met een
stringveld `message` op bij de centrale Runtime-mock.

## Vergaderbronmock

`meeting-source-mock` is een afzonderlijk non-root Python-image. Het levert:

- `/health/live` en `/health/ready`;
- statische bestanden onder `/fixtures/`.
- synthetische vergaderlijsten, agenda's en documenten;
- de hieronder begrensde acceptatiescenario's.

De backendguard koppelt de omgeving gesloten aan de bron-URL. Acceptance accepteert alleen de
interne mockservice. Production accepteert uitsluitend
`https://noordholland.bestuurlijkeinformatie.nl` en weigert HTTP, subdomeintrucs, paden en interne
servicenamen. De gewone downloader en parser verwerken de synthetische bron via dezelfde workflow.

### Meerdere-vergaderingenscenario's (alleen acceptatie)

De acceptatiemock (`meeting-source-mock/server.py`, Python, alleen in `deploy/overlays/acceptance`)
kent een scenarioroute onder `/api/scenarios`. Die route bestaat niet in productie: de productie-overlay
bevat geen mock en de backend registreert daar geen scenarioroute (`MeetingSourcePropertiesTest`,
`ProductionConfigurationTest`). `MOCK_SCENARIO_ROUTES=disabled` zet de route ook in de mock zelf uit (404).
De mock blijft alleen binnen het cluster bereikbaar, via `http://pvdd-meeting-source-mock:8080`, net als
de bestaande bronrevisieroute `POST /fixtures/control/<scenario>`, die ongewijzigd blijft.

#### Bediening via de acceptatie-origin

Op `https://pvdd-acceptance.vdzonsoftware.nl` stuurt de backend (`AcceptanceScenarioController`, alleen
geregistreerd bij `pvdd.environment=acceptance`) uitsluitend de onderstaande aanroepen door naar
`PVDD_MEETING_SOURCE_BASE_URL` + hetzelfde pad. Status en JSON-body komen ongewijzigd terug, met
`Content-Type: application/json` en `Cache-Control: no-store`. Alles buiten de lijst wordt niet doorgestuurd
(404/405). Is de mock onbereikbaar, dan volgt 502 `{"error":"scenario_mock_unreachable"}`.
In productie en lokaal bestaat de route niet (404, ook met sessie of leestoken); de eerdere uitspraak dat
de backend geen scenarioroute heeft geldt dus uitsluitend voor productie.

| Methode en pad | Toegestaan |
|---|---|
| `GET /api/scenarios` | overzicht |
| `POST` of `PUT /api/scenarios/<naam>` | naam uit de scenariotabel hieronder; een onbekende naam geeft 400 `unknown_scenario`; er wordt geen body doorgestuurd |
| `POST /api/scenarios/reset` | terug naar `een-vergadering` |
| `POST /api/scenarios/reset-data` | gecontroleerde reset van uitsluitend synthetische vergadergegevens; actieve verwerking geeft 409 |
| `POST /api/scenarios/scheduled-check` | roept de echte dagelijkse scheduler aan; dezelfde idempotentie- en snelheidsgrens als `check-now` |
| `PUT /api/scenarios/documents/<documentId>` | documentId `[A-Za-z0-9._-]{1,100}`; optionele body `{"version": <geheel getal>}` van maximaal 1 KB, anders 400 |

Authenticatie valt onder het gewone `/api/`-regime: zonder sessie 401. Inloggen en sessiecookie gaan zoals
voor de webapp (`POST /api/auth/session` of de acceptatie-inlogstap); de sessiecookie heet `pvdd_session`.
In modus GOOGLE vereisen POST en PUT de CSRF-dubbele token: de waarde van de cookie `pvdd_csrf` moet ook
in de header `X-CSRF-Token` (`UserSessionService.CSRF_HEADER_NAME`) staan, anders 403 `csrf_failed`. In
`ACCEPTANCE_BYPASS` is dat niet nodig.

```text
B=https://pvdd-acceptance.vdzonsoftware.nl
# na inloggen is de sessie opgeslagen in cookies.txt (cookiejar)
CSRF=$(awk '$6=="pvdd_csrf"{print $7}' cookies.txt)
curl -b cookies.txt $B/api/scenarios
curl -b cookies.txt -H "X-CSRF-Token: $CSRF" -X POST $B/api/scenarios/meer-dan-drie
curl -b cookies.txt -H "X-CSRF-Token: $CSRF" -X POST $B/api/scenarios/reset
```

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
| `geen-toekomstige-vergadering` | lege bronlijst | geen toekomstige vergadering; na schone reset uitleg met doorstap naar eerdere vergaderingen |
| `gehele-broncontrole-mislukt` | HTTP 503 op de bronlijst | hele controle mislukt; bestaande agenda's en adviezen blijven bewaard |

Het scenario is interne toestand van de mock: een herstart geeft weer `een-vergadering`. Elke selectie
maakt nieuwe vergaderings-id's (`scn-<naam>-<volgnummer>-<n>`) en wist documentversies en
ophaaltellingen. Voorbeeld voor een verificatiesessie (rechtstreeks op de mock, vanuit het cluster):

```text
curl -X POST http://pvdd-meeting-source-mock:8080/api/scenarios/meer-dan-drie
# ... Nu controleren in de webapp, controleer het resultaat ...
curl http://pvdd-meeting-source-mock:8080/api/scenarios        # agendaFetches: de vierde ontbreekt
curl -X PUT http://pvdd-meeting-source-mock:8080/api/scenarios/documents/<id>-doc-housing
curl -X POST http://pvdd-meeting-source-mock:8080/api/scenarios/reset
```

Vergaderingen van een eerder scenario blijven staan bij selectie en bij `/reset`. Gebruik
`/reset-data` voor onafhankelijke scenario's, zodra lopende verwerking is afgelopen. Bij het
testen van een gehele bronfout is juist behoud gewenst: importeer eerst een normaal scenario,
kies daarna de bronfout zonder reset. De volledige uitvoering, het schedulerbewijs en de
wachttijden om 429 te voorkomen staan in [het actuele acceptatierunbook](epic9-acceptance.md).

Reproduceerbare tests:

```text
python3 -m unittest discover -s meeting-source-mock          # mock, route, productie-overlay
cd backend && mvn -B --no-transfer-progress test -Dtest=DatabaseIntegrationTest   # scenario's tegen PostgreSQL
cd backend && mvn -B --no-transfer-progress test -Dtest=AcceptanceScenarioControllerTest   # doorstuurroute met stub-HTTP-server
cd frontend && flutter test test/scenario_ui_test.dart       # kader, tegelwissel, resultaat; 1400 en 390 px
```

De backendtests gebruiken `MeetingScenarioSource` (testcode), een in-process spiegel van dezelfde
scenariotabel en fixtures; houd die gelijk aan `MEETING_SCENARIOS` in `server.py`.
