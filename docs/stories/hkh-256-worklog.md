# Worklog hkh-256 — Analyseaanvraag voor een voorbije vergadering wordt geweigerd

## hkh-257 — development (2026-09-17)

### Doel
De belofte "terugkijken start nooit een AI-analyse" ook in de backend afdwingen, zodat een
rechtstreekse API-aanroep geen analyse meer kan starten voor een vergadering die al is geweest.

### Gerealiseerd
- `AnalysisCommandStatus` (module `analysis`) heeft de waarde `MEETING_IN_PAST`.
- `AnalysisFacade.requestMeeting` vergelijkt na de bestaanscontrole `meeting.startsAt` met
  `clock.instant()`. Ligt het begintijdstip niet later dan nu (`startsAt <= nu`, gelijkheid wordt
  geweigerd), dan levert de methode `AnalysisCommandResult(MEETING_IN_PAST, meetingId)` en wordt
  `repository.queueMeeting` niet aangeroepen. Er vindt in dat pad geen schrijfactie plaats.
- `DashboardController.requestAnalysis` vertaalt die status naar
  `ResponseStatusException(HttpStatus.CONFLICT, "meeting_in_past")`, in dezelfde stijl als
  `not_cancellable` en `not_retryable`. De vertaling van `NOT_FOUND` naar 404 en het 202-pad zijn
  ongewijzigd; de aanvraag doorloopt onveranderd `guard.execute(email, "analyse-$id", key)`.
- Volgorde van controles: bestaan gaat vóór datum, dus een onbekende id blijft 404.

### Tests
- `analysis/AnalysisRequestTest` — facadegedrag met een gestuurde `Clock.fixed`: verleden,
  grensgeval (`startsAt` exact gelijk aan nu), toekomst en onbekende id, inclusief `verify(never)`
  op `queueMeeting`.
- `dashboard/api/DashboardAnalysisRequestTest` — vertaling naar HTTP: 409 met `meeting_in_past`,
  404 bij `NOT_FOUND` en het ongewijzigde 202-contract van de route.
- `dashboard/api/DashboardAnalysisRequestTest` — lezen start geen werk: `agenda-items` en
  `agenda-items/{id}` bereiken de `AnalysisFacade` niet (`verifyNoInteractions`). Deze controle
  draait ook zonder database.
- `DatabaseIntegrationTest` — tegen PostgreSQL: een voorbije vergadering met bestaand advies levert
  409 `meeting_in_past`, het aantal `analysis_run`-rijen en het advies zijn voor en na de aanvraag
  gelijk, er komt geen rij in `analysis_meeting_queue`, en het lezen van
  `agenda-items` en `agenda-items/{id}` start geen werk. Een toekomstige vergadering wordt
  onveranderd ingepland en een onbekende id blijft 404.

### Bewust niet gewijzigd
Geen Flyway-migratie of schemawijziging, geen frontendwijziging, geen nieuwe header,
queryparameter, configuratiesleutel of feature flag, geen nieuwe metrics of logvelden.
`MutationGuard`, de `Idempotency-Key`-afhandeling, `cancel`, `retryAgendaItem`,
`retryAllFailedAgendaItems`, `DashboardRepository.can_retry_analysis`, `MeetingDiscoveryService`,
`POST /api/meetings/check-now`, schedulers, prompts, provider en model zijn ongewijzigd. De
`package-info.java`-bestanden met `allowedDependencies` zijn niet aangepast.

### Aandachtspunten
- `DashboardController` is inderdaad de enige aanroeper van `requestMeeting`; er is geen interne
  aanroeper (scheduler, discovery of retry-pad) die door de weigering wordt geraakt.
- In de omgeving van deze stap is geen Docker beschikbaar. `DatabaseIntegrationTest` brak daardoor
  het volledige vangnet af met `Could not find a valid Docker environment`, ongeacht deze story.
  De klasse draagt nu `@Testcontainers(disabledWithoutDocker = true)`: zonder Docker wordt zij
  overgeslagen in plaats van te falen, en waar Docker wel is — CI, ontwikkelmachines — draait zij
  onveranderd volledig. Dit is de enige wijziging buiten de storyscope en is bewust conditioneel:
  bij beschikbare Docker verandert er niets.
- Om de databaseassertions toch te bewijzen is in deze stap een losse PostgreSQL 16 gestart en is
  `DatabaseIntegrationTest` daar eenmalig tegenaan gedraaid via een tijdelijke, niet-opgeleverde
  kopie zonder Testcontainers: alle 8 tests groen, inclusief de twee nieuwe. De tijdelijke kopie is
  weer verwijderd.
- `mvn -B --no-transfer-progress clean verify` is groen: 116 tests, 0 fouten, 9 overgeslagen
  (de 8 databasetests zonder Docker en de bestaande `LiveSourceSpikeTest`), inclusief
  `ModulithArchitectureTest`.
