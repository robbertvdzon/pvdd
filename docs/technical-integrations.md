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
| `PVDD_AGENT_RUNTIME_VENDOR_ID` | `mock` in acceptatie, `openai` in productie |
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
