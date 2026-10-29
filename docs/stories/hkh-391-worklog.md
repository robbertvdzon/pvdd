# hkh-391 worklog: synthetische Bestuur-reeks in de acceptatiemock

- Afhankelijkheden gecontroleerd: `docs/commissie-bestuur-bron.md` legt type `1100617071` vast (bron bereikbaar); opbouw gelijk aan Ruimte.
- Besluit: mock en acceptatieconfiguratie gebruiken `1100617071` (niet `9000000001`). De configmap en de regel in het bron-doc zijn aangepast.
- Scenariobediening ongewijzigd (zelfde route/controller/filters); de mock registreert de vier `bestuur-*`-namen. Een Bestuur-scenario raakt Ruimte niet.
- Stukwijziging loopt via de bestaande `PUT /api/scenarios/documents/<id>`, niet automatisch.
- AI-mock: `BESTUUR_ADVICE_AB`/`BESTUUR_ADVICE_C`; `AnalysisOrchestrator` kiest ze op basis van `Meeting.commissieName`. Aangepaste bestaande verwachting: `AcceptanceRuntimeFixtureTest` telt nu `entries.size` jobs in plaats van 4.
- Zie `docs/acceptatiemock-bestuur-scenarios.md` voor scenario's, aanroep en verwachte resultaten.

## hkh-394 — documentation (2026-10-29)

### Doel
Documentatie afstemmen op de synthetische Bestuur-reeks in de acceptatiemock.

### Bijgewerkt
- `docs/technical-integrations.md`: verwijzing naar `docs/acceptatiemock-bestuur-scenarios.md` in de sectie over
  acceptatiescenario's en het scenarioscript in de lijst met reproduceerbare tests.

### Bewust niet gewijzigd
- `docs/acceptatiemock-bestuur-scenarios.md`, `docs/commissie-bestuur-bron.md`: klopten al met de diff (type `1100617071`).
- `README.md`, `docs/microservice-specificatie.md`, `docs/factory/*`: geen route-, filter- of productiewijziging.

### Verificatie
- `bash tools/verify-documentation.sh`; alleen `docs/` geraakt.
