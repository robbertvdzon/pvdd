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
  De klasse kreeg daarom een conditionele start. Zie de tweede ronde hieronder voor de vorm die is
  opgeleverd; de eerste ronde gebruikte `@Testcontainers(disabledWithoutDocker = true)`, waarmee de
  databasetests zonder Docker alleen nog via een tijdelijke, niet-opgeleverde kopie te draaien
  waren.
- `mvn -B --no-transfer-progress clean verify` is groen: 116 tests, 0 fouten, 9 overgeslagen
  (de 8 databasetests zonder Docker en de bestaande `LiveSourceSpikeTest`), inclusief
  `ModulithArchitectureTest`.

## hkh-257 — development, tweede ronde (2026-09-17)

### Aanleiding
De testsubtaak `hkh-258` is afgekeurd op testvoorwaarden, niet op de code: de storyrevisie draait
niet op acceptatie en de acceptatiedataset bevat geen voorbije vergadering. De afkeur vraagt om
drie dingen voor een hertest: (1) uitrol van de storycommit, (2) een dataset met een voorbije
vergadering, en (3) aantoonbaar groene `DatabaseIntegrationTest`-resultaten of gelijkwaardig
rijniveau-bewijs. Punt 3 is in deze ronde opgelost; punt 1 hoort bij de merge- en deploysubtaken.

### Gewijzigd in deze ronde
- `DatabaseIntegrationTest` start niet langer uitsluitend via Docker. De klasse draait nu wanneer
  Docker beschikbaar is (dan start Testcontainers de container zoals voorheen) **of** wanneer de
  al bestaande omgevingsvariabele `PVDD_DATABASE_URL` naar een draaiende PostgreSQL wijst; in dat
  laatste geval wordt geen container gestart en gebruikt Spring de datasource uit
  `application.properties`, die `PVDD_DATABASE_URL`, `PVDD_DATABASE_USER` en
  `PVDD_DATABASE_PASSWORD` al leest. Ontbreken beide, dan wordt de klasse overgeslagen
  (`@EnabledIf` met `disabledReason`) in plaats van het hele vangnet te laten falen — hetzelfde
  netto-effect als in de eerste ronde, maar nu reproduceerbaar zonder de testcode aan te passen.
  Er komt geen nieuwe property, configuratiesleutel of testbean bij; productiecode is niet geraakt.

### Bewijs dat in deze ronde zelf is gedraaid
- Zonder Docker en zonder database, precies zoals het factoryvangnet draait:
  `mvn -B --no-transfer-progress clean verify` in `backend/` → BUILD SUCCESS, 116 tests, 0 fouten,
  9 overgeslagen (de 8 databasetests plus `LiveSourceSpikeTest`), inclusief
  `ModulithArchitectureTest`.
- Mét een lokaal gestarte PostgreSQL 16.15, dezelfde opgeleverde testklasse, geen tijdelijke kopie:
  `PVDD_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/<db> PVDD_DATABASE_USER=<user>
  PVDD_DATABASE_PASSWORD= mvn -B --no-transfer-progress clean verify` → BUILD SUCCESS, 116 tests,
  0 fouten, 1 overgeslagen (alleen `LiveSourceSpikeTest`). `DatabaseIntegrationTest` zelf:
  8 tests, 0 fouten, 0 overgeslagen, waaronder
  `a past meeting refuses analysis while reading it stays free of side effects` (409
  `meeting_in_past`, gelijk aantal `analysis_run`-rijen voor en na, ongewijzigd advies, geen rij in
  `analysis_meeting_queue`) en
  `a future meeting is still queued and an unknown meeting is still not found`.

### Reproductie van het rijniveau-bewijs zonder Docker
> Let op: de sleutelnamen in dit recept zijn in de derde ronde hieronder vervangen door
> `PVDD_TEST_DATABASE_URL`, `PVDD_TEST_DATABASE_USER` en `PVDD_TEST_DATABASE_PASSWORD`.
> Gebruik het recept uit de derde ronde.

1. Start een PostgreSQL die vanaf de testrun bereikbaar is (in een Dockerloze runtime kan dat
   rootloos: `apt-get download postgresql-16`, `dpkg-deb -x`, daarna `initdb` en `pg_ctl start` op
   een eigen poort, en `createdb` voor een lege database).
2. Draai `mvn -B --no-transfer-progress clean verify` in `backend/` met `PVDD_DATABASE_URL`,
   `PVDD_DATABASE_USER` en `PVDD_DATABASE_PASSWORD` gezet. Flyway migreert de lege database zelf.
3. Controleer `backend/target/surefire-reports/TEST-nl.vdzon.pvdd.DatabaseIntegrationTest.xml`:
   `tests="8" failures="0" errors="0" skipped="0"`.

### Niet opgelost in deze ronde (hoort bij andere subtaken)
- Uitrol van de storycommit naar acceptatie: dat is de merge- en deploysubtaak; een developer
  deployt niet.
- Een voorbije vergadering in de acceptatiedataset is met de huidige inrichting niet te maken
  zonder de scope te schenden: de vergaderingen komen daar uit de synthetische bron via
  `MeetingDiscoveryService`, die uitsluitend vooruitkijkt, en rechtstreekse databasetoegang is voor
  de agents verboden. De weigering is daarom op rijniveau bewezen met
  `DatabaseIntegrationTest` tegen een echte PostgreSQL, zoals hierboven; een functionele controle op
  acceptatie zou een bron- of datasetuitbreiding vergen die buiten deze story valt.

## hkh-257 — development, derde ronde (2026-09-17)

### Aanleiding
De testsubtaak `hkh-258` is opnieuw afgekeurd op testvoorwaarden, zonder productbug; de
reviewerronde gaf akkoord met drie niet-blokkerende opmerkingen over het in de tweede ronde
opgeleverde databasepad. Die opmerkingen zijn in deze ronde verwerkt. De productiecode van de
story (`AnalysisFacade` en `DashboardController`) is niet aangeraakt en het gedrag van de story is
ongewijzigd.

### Gewijzigd in deze ronde (alleen `DatabaseIntegrationTest`)
- **Testeigen databasesleutel.** De klasse leest niet langer de runtimesleutel `PVDD_DATABASE_URL`,
  maar `PVDD_TEST_DATABASE_URL` (met `PVDD_TEST_DATABASE_USER` en `PVDD_TEST_DATABASE_PASSWORD`),
  en registreert die waarden expliciet via `@DynamicPropertySource`. `PVDD_DATABASE_URL` staat in
  `docker-compose.yml` en in de acceptatie- en productie-overlays en wees dus naar een echte
  database; deze schrijvende en migrerende suite kan daar nu niet meer per ongeluk op landen. Dit
  is een testsleutel: er komt geen property, configuratiesleutel of feature flag in de applicatie
  bij en `application.properties` is ongewijzigd.
- **Fail-fast op een al gevulde database.** Een meegegeven database moet leeg zijn; bij hergebruik
  faalden eerder drie bestaande, ongerelateerde tests met verwarrende assertiefouten. Een
  `@BeforeEach` controleert eenmalig `meeting`, `policy_sync_run` en `policy_web_source` en faalt
  met een expliciete melding die vertelt wat er moet gebeuren.
- **Consistente opruiming.** De test met de voorbije vergadering ruimt zijn advies, `analysis_run`,
  agendapunt en vergadering nu in een `finally` op, net als de test met de toekomstige vergadering.
  De suite laat daarmee geen synthetische vergadering meer achter en beïnvloedt de `overview()`-
  assertions van bestaande tests niet.

### Bewijs dat in deze ronde zelf is gedraaid
- Zonder Docker en zonder database, zoals het factoryvangnet draait:
  `mvn -B --no-transfer-progress clean verify` in `backend/` → BUILD SUCCESS, 116 tests, 0 fouten,
  9 overgeslagen, inclusief `ModulithArchitectureTest`. De skipreden in het surefire-rapport luidt
  nu `Geen Docker en geen PVDD_TEST_DATABASE_URL, dus geen PostgreSQL om tegen te draaien`.
- Mét een lokaal gestarte PostgreSQL 16.15 en een lege database:
  `PVDD_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/<db> PVDD_TEST_DATABASE_USER=<user>
  PVDD_TEST_DATABASE_PASSWORD= mvn -B --no-transfer-progress clean verify` → BUILD SUCCESS,
  116 tests, 0 fouten, 1 overgeslagen (alleen `LiveSourceSpikeTest`).
  `TEST-nl.vdzon.pvdd.DatabaseIntegrationTest.xml`: `tests="8" failures="0" errors="0"
  skipped="0"`.
- Controle op de fail-fast: dezelfde suite een tweede keer tegen dezelfde, nu gevulde database →
  alle acht databasetests falen met de expliciete melding over een niet-lege database in plaats van
  met verwarrende assertiefouten elders.
- Controle op de veiligheid van de sleutelscheiding: met uitsluitend `PVDD_DATABASE_URL` gezet
  (naar een lege database) en zonder Docker worden de acht tests overgeslagen en blijft die
  database onaangeroerd — nul tabellen, dus Flyway heeft er niet gedraaid.
- `bash tools/verify-documentation.sh` → groen.

### Reproductie van het rijniveau-bewijs zonder Docker (geldende versie)
1. Start een PostgreSQL die vanaf de testrun bereikbaar is en maak daarin een **lege** database
   (rootloos kan dat met `apt-get download postgresql-16`, `dpkg-deb -x`, daarna `initdb`,
   `pg_ctl start` op een eigen poort en `createdb`).
2. Draai `mvn -B --no-transfer-progress clean verify` in `backend/` met `PVDD_TEST_DATABASE_URL`,
   `PVDD_TEST_DATABASE_USER` en `PVDD_TEST_DATABASE_PASSWORD` gezet. Flyway migreert de lege
   database zelf.
3. Controleer `backend/target/surefire-reports/TEST-nl.vdzon.pvdd.DatabaseIntegrationTest.xml`:
   `tests="8" failures="0" errors="0" skipped="0"`.
4. Gebruik voor een volgende run opnieuw een verse database; de fail-fast meldt het anders.

### Signaal voor de keten (onveranderd en niet op te lossen binnen deze subtaak)
De testsubtaak staat vóór de merge- en deploysubtaak, terwijl er bewust geen
PR-previewomgevingen zijn (`docs/microservice-specificatie.md`). De storyrevisie kan tijdens de
testfase daardoor per definitie nergens draaien. Daarnaast bevat de acceptatiedataset geen voorbije
vergadering en kan die er ook niet komen zonder de scope te schenden: `MeetingDiscoveryService`
slaat vergaderingen met `startsAt <= nu` over, dus een bronfixture met een datum in het verleden
wordt nooit geïmporteerd, en de story verbiedt expliciet een wijziging aan discovery. Een
functionele controle op acceptatie vergt dus een procesbesluit plus een dataset- of bronuitbreiding
in een eigen story; het gedrag zelf is in deze ronde op rijniveau tegen een echte PostgreSQL
bewezen.

## hkh-257 — development, vierde ronde (2026-09-17)

### Aanleiding
De testsubtaak `hkh-258` is voor de derde keer afgekeurd op **testvoorwaarden**, opnieuw zonder
aangetoonde productbug: de storyrevisie draait nergens en de acceptatiedataset bevat geen voorbije
vergadering. De reviewerronde gaf akkoord zonder blockers, met één niet-blokkerende suggestie over
de opruiming in de databasetest. Die suggestie is in deze ronde verwerkt. De productiecode van de
story (`AnalysisFacade` en `DashboardController`) is opnieuw niet aangeraakt; het gedrag van de
story is ongewijzigd ten opzichte van de goedgekeurde versie.

### Gewijzigd in deze ronde (alleen `DatabaseIntegrationTest`)
- **Opruiming van `analysis_meeting_queue` in de voorbije-vergaderingtest.** Het `finally`-blok
  ruimde advies, `analysis_run`, agendapunt en vergadering op, maar geen wachtrijrij. In het groene
  pad bestaat die rij niet, dus dit was onschadelijk. Zou de weigering ooit regresseren en
  `queueMeeting` tóch worden aangeroepen, dan faalt eerst `assertEquals(0, countQueued(meetingId))`,
  waarna het `finally` op `DELETE FROM meeting` zou struikelen over de foreign key
  `analysis_meeting_queue.meeting_id REFERENCES meeting(id)` (bevestigd in
  `V5__durable_analysis_orchestration.sql`; nergens `ON DELETE CASCADE`). De daaruit gegooide
  `DataIntegrityViolationException` verving dan de AssertionError, zodat de testuitslag een
  FK-fout toonde in plaats van "expected 0 but was 1". Met de extra `DELETE` blijft de diagnose bij
  een echte regressie direct leesbaar en zijn de twee `finally`-blokken symmetrisch met de
  toekomstige-vergaderingtest.

### Bewijs dat in deze ronde zelf is gedraaid
- Mét een rootloos gestarte PostgreSQL 16 en een verse, lege database:
  `PVDD_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/<db> PVDD_TEST_DATABASE_USER=<user>
  PVDD_TEST_DATABASE_PASSWORD= mvn -B --no-transfer-progress clean verify` in `backend/` →
  **BUILD SUCCESS, 116 tests, 0 failures, 0 errors, 1 skipped** (alleen `LiveSourceSpikeTest`).
  Uit de surefire-rapporten: `DatabaseIntegrationTest` `tests="8" failures="0" errors="0"
  skipped="0"`, `ModulithArchitectureTest` 2/2, `AnalysisRequestTest` 4/4,
  `DashboardAnalysisRequestTest` 4/4.
- Controle op residu na afloop: in `meeting` stonden alleen nog de bestaande
  `meeting-failed`, `meeting-functional-test` en `meeting-source-revision-test`; geen
  `meeting-past-*` of `meeting-future-*`. De enige rij in `analysis_meeting_queue` hoort bij het
  al bestaande `meeting-functional-test`, niet bij de storytests.

### Onveranderd
Geen wijziging aan productiecode, schema, migraties, frontend, `package-info.java`,
`MutationGuard`, de `Idempotency-Key`-afhandeling, schedulers, prompts, provider of model. De
scope blijft binnen de modules `analysis` en `dashboard` plus de testklasse.

### Niet opgelost in deze ronde (hoort bij andere subtaken)
De twee blokkades uit de testafkeur staan onveranderd en zijn binnen deze subtaak niet op te
lossen: de storyrevisie uitrollen hoort bij `hkh-261`/`hkh-262`, en een voorbije vergadering in de
acceptatiedataset kan er niet komen zonder de scope te schenden, omdat `MeetingDiscoveryService`
kandidaten met `startsAt <= nu` overslaat en die klasse expliciet buiten scope valt. Het
storygedrag is daarom op rijniveau tegen een echte PostgreSQL bewezen. De reviewer merkte verder op
dat de testharnasvariabele `PVDD_TEST_DATABASE_URL` nog nergens buiten deze testklasse en dit
worklog is beschreven; dat vastleggen hoort bij `hkh-260` (documentatie) en het melden ervan bij
`hkh-259` (oplevering).

## hkh-257 — development, vijfde ronde (2026-09-17)

### Aanleiding
De testsubtaak `hkh-258` is voor de vierde keer afgekeurd op **testvoorwaarden**, opnieuw zonder
aangetoonde productbug: de storyrevisie draait nergens (geen PR-previewomgeving) en de
acceptatiedataset bevat geen voorbije vergadering. Het geautomatiseerde bewijs was volledig groen.
De enige inhoudelijke opening die de tester noemt en die binnen deze subtaak te dichten is, is
acceptatiecriterium 6: `POST /api/meetings/check-now` was tot nu toe alleen statisch beoordeeld
("de route wordt niet geraakt door de diff") en de deelconditie *"ook wanneer de laatst bekende
vergadering al is geweest"* was nergens vastgelegd. Die is in deze ronde met een echte
databasetest afgedekt. De productiecode van de story is opnieuw niet aangeraakt; het gedrag van de
story is ongewijzigd ten opzichte van de eerder goedgekeurde versie.

### Gewijzigd in deze ronde (alleen `DatabaseIntegrationTest`)
- **Nieuwe test `check now keeps importing while the last known meeting already took place`.**
  De test zet een synthetische voorbije vergadering neer, maakt die met `markSuccessful` de
  *laatst bekende* vergadering (`application_metadata.last-successful-meeting-source-id`) en roept
  daarna `MeetingCheckController.checkNow` aan tegen de echte database. De controleroute draait met
  de echte `MeetingCheckWorkflow`, de echte `MeetingRepository`, `WorkflowLockRepository`,
  `AgendaRevisionComparator`, `SourceRevisionRepository` en `MutationGuard`; alleen de
  bronkoppeling (`MeetingDiscoveryGateway`) en de documentingestie zijn vervangen door synthetische
  stubs, zodat er geen externe bron en geen download nodig is. `MeetingDiscoveryService` zelf is
  niet aangeraakt — die valt buiten scope en wordt hier alleen omzeild, niet gewijzigd.
  Vastgelegd wordt: HTTP 200, status `IMPORTED`, de nieuwe vergadering met agendapunten staat in de
  database, en de voorbije vergadering blijft ongemoeid (`analysis_meeting_queue` leeg,
  `analysis_run` leeg, `starts_at` onveranderd). Daarmee is AC6 niet langer alleen statisch, maar
  op rijniveau aangetoond.
  **Correctie uit de zesde ronde:** de oorspronkelijke tekst claimde hier ook de deelconditie
  *"ook wanneer de laatst bekende vergadering al is geweest"*. Die claim was te sterk. De
  `markSuccessful`-stap schrijft `application_metadata.last-successful-meeting-source-id`, maar geen
  enkele productiecode leest die sleutel, en de stub vervangt juist `MeetingDiscoveryService` — de
  klasse die met `startsAt <= nu` bepaalt wat er gebeurt als de laatst bekende vergadering voorbij
  is. Wat deze test aantoont is smaller en staat hierboven; de deelconditie zelf ligt sinds de zesde
  ronde vast in `MeetingDiscoveryServiceTest`.
- **Gedeelde opruiming `removeSyntheticMeeting`.** De twee bestaande `finally`-blokken en het nieuwe
  gebruiken nu één helper die in foreign-keyvolgorde opruimt (documentrevisies → agendapuntrevisies
  → vergaderingrevisies → broncontroles → wachtrij → advies → runs → brondocumenten → agendapunten →
  vergadering). Nergens in het schema staat `ON DELETE CASCADE`, dus die volgorde is nodig; tabellen
  die in het groene pad leeg horen te zijn worden toch geruimd, zodat bij een regressie de assertie
  faalt en niet de opruiming. Dit lost en passant de niet-blokkerende reviewopmerking op dat het
  `finally` van de toekomstige-vergaderingtest korter was dan dat van de voorbije-vergaderingtest.
- **Herstel van gedeelde toestand.** De nieuwe test schrijft `last-successful-meeting-source-id` en
  zet die in het `finally` terug op de waarde die hij aantrof, zodat de metadata niet naar een
  verwijderde synthetische vergadering blijft wijzen.

### Bewijs dat in deze ronde zelf is gedraaid
- Mét een rootloos gestarte PostgreSQL 16 en een verse, lege database:
  `PVDD_TEST_DATABASE_URL=… PVDD_TEST_DATABASE_USER=… PVDD_TEST_DATABASE_PASSWORD= mvn -B
  --no-transfer-progress clean verify` in `backend/` → **BUILD SUCCESS, 117 tests, 0 failures,
  0 errors, 1 skipped** (alleen `LiveSourceSpikeTest`). `DatabaseIntegrationTest`
  `tests="9" failures="0" errors="0" skipped="0"`, inclusief de nieuwe check-now-test.
- Zonder database en zonder Docker (zoals het factoryvangnet draait):
  `mvn -B --no-transfer-progress clean verify` → **BUILD SUCCESS, 117 tests, 0 failures, 0 errors,
  10 skipped**; de 9 databasetests worden overgeslagen met de bestaande reden.
- Residucontrole ná de databaserun, feitelijk in de database gekeken: in `meeting` stonden alleen de
  al bestaande `meeting-failed`, `meeting-functional-test` en `meeting-source-revision-test`; geen
  `meeting-past-*`, `meeting-future-*` of `meeting-check-now-*`. De enige rij in
  `analysis_meeting_queue` hoort bij `meeting-functional-test` en
  `last-successful-meeting-source-id` stond weer op `meeting-functional-test`.

### Onveranderd
Geen wijziging aan productiecode, schema, migraties, frontend, `package-info.java`,
`MutationGuard`, de `Idempotency-Key`-afhandeling, `MeetingCheckController`,
`MeetingCheckWorkflow`, `MeetingDiscoveryService`, schedulers, prompts, provider of model. De
wijziging van deze ronde zit volledig in één testklasse.

### Niet opgelost in deze ronde (hoort bij andere subtaken)
De twee blokkades uit de testafkeur staan onveranderd en zijn binnen deze subtaak niet op te
lossen: de storyrevisie uitrollen hoort bij `hkh-261`/`hkh-262`, en een voorbije vergadering in de
acceptatiedataset kan er niet komen zonder de scope te schenden (`MeetingDiscoveryService` slaat
kandidaten met `startsAt <= nu` over). Het ketensignaal — testsubtaak vóór merge en deploy terwijl
er bewust geen PR-previewomgevingen zijn, waardoor de storyrevisie tijdens de testfase per definitie
nergens draait — is een procesbesluit dat buiten de developerrol valt en dat hier expliciet wordt
doorgegeven.

## hkh-257 — development, zesde ronde (2026-09-17)

### Aanleiding
De testsubtaak `hkh-258` is voor de vijfde keer afgekeurd op **testvoorwaarden**, opnieuw zonder
aangetoonde productbug; het geautomatiseerde bewijs was volledig groen. De reviewerronde gaf
opnieuw akkoord zonder blockers, met één niet-blokkerende suggestie die wél binnen deze subtaak
valt: de nieuwe AC6-test uit de vijfde ronde legt de deelconditie *"ook wanneer de laatst bekende
vergadering al is geweest"* niet werkelijk vast, en het worklog claimde dat wel. Die opening is in
deze ronde gedicht. De productiecode van de story (`AnalysisFacade`, `DashboardController`) is voor
de vierde ronde op rij niet aangeraakt; het storygedrag is ongewijzigd.

### Gewijzigd in deze ronde (alleen tests, fixture en dit worklog)
- **Nieuwe test `keeps looking ahead when the last known meeting took place earlier today`** in
  `MeetingDiscoveryServiceTest`, met de nieuwe fixture `year-2026-meeting-today.html`. De jaarpagina
  bevat een vergadering van vandaag (maandag 31 augustus 2026, `08:00 - 09:30`, dus begonnen én
  afgelopen vóór de vaste testklok van `2026-08-31T10:00:00Z`) en daarna de bestaande toekomstige
  vergadering. De dagfilter `!it.date.isBefore(today)` laat die van vandaag door, waarna de regel
  `if (agenda.startsAt <= now) continue` haar overslaat en de discovery `meeting-future`
  (`2026-09-14T16:30:00Z`) oplevert. Dit is precies het productiepad dat bepaalt dat de
  broncontrole vooruit blijft kijken als de laatst bekende vergadering al is geweest — het pad dat
  de databasetest uit de vijfde ronde juist wegstubte. `MeetingDiscoveryService` zelf is niet
  gewijzigd; alleen de bronantwoorden in de test zijn synthetisch, zoals in alle bestaande tests
  van die klasse.
- **Eerlijker commentaar in `DatabaseIntegrationTest`** bij de check-now-test: de `markSuccessful`-
  stap zet de database in de toestand uit het acceptatiecriterium, maar stuurt het geteste pad niet,
  omdat geen enkele productiecode `application_metadata.last-successful-meeting-source-id` leest.
  Het commentaar verwijst nu naar `MeetingDiscoveryServiceTest` voor de deelconditie en benoemt wat
  de databasetest wél aantoont: wat de route tegen een echte database schrijft en met rust laat.
- **Correctie van de AC6-claim in het worklog van de vijfde ronde**, zodat `hkh-258` (test) en
  `hkh-259` (oplevering) geen dekking overnemen die er niet was. De correctie staat als expliciete
  noot bij de oorspronkelijke tekst.

### Dekking van AC6 na deze ronde
- De route `POST /api/meetings/check-now` is in de hele storydiff niet geraakt en loopt niet via
  `requestMeeting` (statisch, ongewijzigd).
- Tegen een echte database: `checkNow` levert HTTP 200 met `IMPORTED`, schrijft de nieuwe
  vergadering met agendapunten weg en laat een naast bestaande voorbije vergadering volledig
  ongemoeid (`DatabaseIntegrationTest`).
- De deelconditie "de laatst bekende vergadering is al geweest" ligt nu vast op het niveau waar
  productiecode de beslissing neemt: `MeetingDiscoveryServiceTest` (nieuw deze ronde), plus de
  ongewijzigd groene `MeetingCheckWorkflowTest`.

### Bewijs dat in deze ronde zelf is gedraaid
- Zonder database en zonder Docker (zoals het factoryvangnet draait):
  `mvn -B --no-transfer-progress clean verify` in `backend/` → **BUILD SUCCESS, 118 tests,
  0 failures, 0 errors, 10 skipped** (de 9 databasetests plus `LiveSourceSpikeTest`).
- Mét een rootloos gestarte PostgreSQL 16 en een verse, lege database:
  `PVDD_TEST_DATABASE_URL=… PVDD_TEST_DATABASE_USER=… PVDD_TEST_DATABASE_PASSWORD= mvn -B
  --no-transfer-progress clean verify` → **BUILD SUCCESS, 118 tests, 0 failures, 0 errors,
  1 skipped** (alleen `LiveSourceSpikeTest`); `DatabaseIntegrationTest` `tests="9" failures="0"
  errors="0" skipped="0"`, `MeetingDiscoveryServiceTest` 7/7, `ModulithArchitectureTest` 2/2.
- `bash tools/verify-documentation.sh` → groen, nodig omdat `docs/` is geraakt.

### Onveranderd
Geen wijziging aan productiecode, schema, migraties, frontend, `package-info.java`,
`MutationGuard`, de `Idempotency-Key`-afhandeling, `MeetingCheckController`, `MeetingCheckWorkflow`,
`MeetingDiscoveryService`, schedulers, prompts, provider of model. De wijziging van deze ronde zit
volledig in twee testklassen, één nieuwe testfixture en dit worklog.

### Niet opgelost in deze ronde (hoort bij andere subtaken)
De blokkades uit de testafkeur staan onveranderd en zijn binnen deze subtaak niet op te lossen: de
storyrevisie uitrollen hoort bij `hkh-261`/`hkh-262`, en een voorbije vergadering in de
acceptatiedataset kan er niet komen zonder de scope te schenden. Het ketensignaal — testsubtaak
vóór merge en deploy terwijl er bewust geen PR-previewomgevingen zijn, waardoor de storyrevisie
tijdens de testfase per definitie nergens draait — wordt hier voor de tweede keer doorgegeven als
procesbesluit buiten de developerrol.
