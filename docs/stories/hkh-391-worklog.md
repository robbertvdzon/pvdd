# hkh-391 worklog: synthetische Bestuur-reeks in de acceptatiemock

- Afhankelijkheden gecontroleerd: `docs/commissie-bestuur-bron.md` legt type `1100617071` vast (bron bereikbaar); opbouw gelijk aan Ruimte.
- Besluit: mock en acceptatieconfiguratie gebruiken `1100617071` (niet `9000000001`). De configmap en de regel in het bron-doc zijn aangepast.
- Scenariobediening ongewijzigd (zelfde route/controller/filters); de mock registreert de vier `bestuur-*`-namen. Een Bestuur-scenario raakt Ruimte niet.
- Stukwijziging loopt via de bestaande `PUT /api/scenarios/documents/<id>`, niet automatisch.
- AI-mock: `BESTUUR_ADVICE_AB`/`BESTUUR_ADVICE_C`; `AnalysisOrchestrator` kiest ze op basis van `Meeting.commissieName`. Aangepaste bestaande verwachting: `AcceptanceRuntimeFixtureTest` telt nu `entries.size` jobs in plaats van 4.
- Zie `docs/acceptatiemock-bestuur-scenarios.md` voor scenario's, aanroep en verwachte resultaten.
