# Worklog hkh-263 — Een voorbije vergadering openen met dezelfde agendaweergave, alleen-lezen

## hkh-264 — development (2026-09-18)

### Doel
Een voorbije vergadering is te openen via `/archief/<vergadering-id>` en toont dezelfde vertrouwde
agendaweergave, duidelijk gemarkeerd als 'al geweest'. Terugkijken is strikt alleen lezen: het scherm
kent geen enkele actie die nieuw werk start.

### Gerealiseerd — backend (module `dashboard`)
- `MeetingDto` heeft het veld `past: Boolean`, in SQL berekend als `m.starts_at < CURRENT_TIMESTAMP`.
  De markering hangt daarmee aan de databasetijd, niet aan de browserklok. Grensgeval: begint de
  vergadering precies nu, dan is `past` nog `false`.
- De SELECT van de vergaderkop staat nu één keer in `DashboardRepository.MEETING_SELECT` en wordt
  gedeeld door `overview()` en de nieuwe `meeting(id)`. Beide leveren dus exact hetzelfde
  antwoordmodel én hetzelfde `past`-veld. De sorteer-/LIMIT-clausule van `overview()` is ongewijzigd.
- `DashboardRepository.meeting(id: UUID): MeetingOverviewDto?` gebruikt dezelfde SELECT met
  `WHERE m.id = ?` en hergebruikt de bestaande private `progress(meetingId)`.
- `DashboardController` heeft `GET /api/meetings/{id}`, met hetzelfde antwoordmodel als
  `/api/meetings/next`. Een geldige maar onbekende UUID levert 404 via de bestaande `notFound()`;
  een id dat geen geldige UUID is valt op het bestaande conversiegedrag van het framework (400).
  De bestaansvraag gaat vóór elke andere controle, dus er lekt geen bestaansinformatie.

### Gerealiseerd — webapp (Flutter)
- `DashboardGateway` heeft `Future<MeetingOverview> meeting(String meetingId)`; `MeetingInfo` heeft
  `past`, met tolerante deserialisatie (`json['past'] as bool? ?? false`), zodat bestaande fixtures
  zonder dat veld niet breken. De bestaande methoden zijn ongewijzigd.
- `MeetingOverviewPage` is hergebruikt in plaats van gekopieerd: de pagina heeft een optionele
  `archivedMeetingId`, een `readOnly`-vlag en een optionele `onBack`. Is `archivedMeetingId` gezet,
  dan laadt de pagina via `gateway.meeting(id)` en `gateway.agendaItems(id)` en start zij géén
  15-secondenpoll. Bij `readOnly` verdwijnen 'Nu controleren' en — via de nieuwe `readOnly`-vlag op
  `_AgendaItemCard` — de herstartactie per agendapunt. Filters, `_meetingCard`, de agendapuntkaarten
  en `_detailView` zijn dezelfde code.
- Boven de vergaderkaart staat, wanneer de server `past` levert, het blok
  "Deze vergadering is al geweest — <vergaderdatum>" met de mededeling dat bekijken geen analyse
  start en niets verandert. Het rode voorbehoud 'AI-concept — controleer bronnen en formulering vóór
  gebruik' staat ongewijzigd bij het advies.
- `_load()` legt de vergaderkop vast zodra die binnen is. Mislukt daarna het laden van de
  agendapunten, dan blijft de kop staan en verschijnt een melding met 'Opnieuw proberen'; al geladen
  informatie blijft staan. Faalt `gateway.meeting(id)` zelf, dan verschijnt dezelfde melding met
  'Opnieuw proberen' (er is dan nog geen kop om te behouden).
- Routering in `main.dart`: `archivedMeetingIdFromPath` herkent `/archief/<uuid>` in `initState`;
  `_selected` blijft 0 (Agenda blijft geselecteerd) en de zijbalk telt onveranderd vijf menu-items.
  Elke menukeuze verlaat het archiefscherm. De terugactie navigeert naar `/agenda` met het neutrale
  label 'Terug naar agenda'; het archiefoverzicht `/archief` bestaat nog niet.

### Testvoorzieningen (voor de tester)
- `frontend/test/support/fakes.dart` — de gedeelde `FakeDashboardGateway` is uit `widget_test.dart`
  gehaald en uitgebreid: `past`, `startsAt`, `title`, `withSources`, instelbare fouten
  (`failMeeting`, `failAgendaItems`) en registratie van elke aanroep in `calls` (plus
  `startingCalls` voor de aanroepen die werk zouden starten).
- `archivedMeetingIdFromPath` (`frontend/lib/archive_route.dart`) is een losse, pure functie, dus
  zonder app-start te testen.
- `TechnicalApplicationShell` heeft optionele `appPath`/`navigate`-parameters (standaard de echte
  browserimplementatie), zodat een deeplink zonder browser te testen is.
- `DatabaseIntegrationTest.seedMeetingWithAdvice(startsAt)` schrijft een vergadering weg met
  agendapunt, leesbaar document en afgerond advies; `countAllRuns()` telt `analysis_run` over de
  hele tabel. Opruimen gebeurt met de bestaande `removeSyntheticMeeting(meetingId)`.

### Tests
- `dashboard/api/DashboardMeetingReadTest` (unittest, geen database): 200 met vergaderkop, progress
  en `past` voor een bekende id; `past = false` voor een toekomstige; 404 bij een onbekende geldige
  UUID; `verifyNoInteractions` op de `AnalysisFacade`; en de sessiecontrole — `/api/meetings/{id}`
  staat niet in de uitzonderingenlijst van `ApiAuthenticationFilter.shouldNotFilter` en levert
  zonder sessie dezelfde 401 als `/api/meetings/next`.
- `DatabaseIntegrationTest` (tegen echte PostgreSQL): `past = true` voor de voorbije en
  `past = false` voor de toekomstige vergadering, zowel op `/api/meetings/{id}` als op
  `/api/meetings/next`; vergaderkop en `progress(1, 1, 0)`; 404 bij een onbekende UUID; en het aantal
  rijen in `analysis_run`, het advies en de wachtrij zijn ongewijzigd vóór en ná
  `meeting(id)`, `agenda-items` en `agenda-items/{id}`.
- `frontend/test/archive_page_test.dart`: alleen-lezen weergave met filters A/B/C, dezelfde
  informatie per agendapunt, uitgeklapte analyse met bronnen, de melding over onleesbare stukken en
  het AI-voorbehoud; de markering 'al geweest' met de vergaderdatum boven de vergaderkaart;
  archiefvariant zonder 'Nu controleren' en zonder herstartactie tegenover de regressievariant van de
  huidige vergadering waarin beide aanwezig zijn en de 15-secondenverversing loopt; uitsluitend
  leesaanroepen in `calls`, ook na uitklappen; de fouttoestand met behoud van de vergaderkop en
  herstel na 'Opnieuw proberen'; twee vensterbreedtes (400 en 1200) zonder overflow.
- `frontend/test/archive_route_test.dart`: de pure padherkenning, inclusief de paden die terugvallen
  op de agendaweergave.
- `frontend/test/spa_fallback_test.dart`: `frontend/nginx.conf` en `frontend/nginx-acceptance.conf`
  bevatten nog steeds `try_files $uri $uri/ /index.html`, dus een rechtstreekse deeplink komt zonder
  serverwijziging bij de app terecht.

### Bewust niet gewijzigd
Geen Flyway-migratie, geen index, geen nieuwe of gewijzigde schrijfroute, geen wijziging aan
discovery, schedulers, AI-provider of `AnalysisFacade`, geen uitbreiding van
`ProductionReadAccessFilter`, geen wijziging aan `package-info.java` of aan de nginx-configuraties.
De nieuwe route is niet toegevoegd aan `ApiAuthenticationFilter.shouldNotFilter`. Buiten scope en
niet gebouwd: het archiefoverzicht `/archief`, de adviesversie-toggle, `GET /api/meetings?state=past`
en de fallbackcorrectie in `overview()`.

### Bewijs en beperking
Al het bewijs vóór merge komt uit geautomatiseerde tests op de storybranch. Er is geen preview-URL
en geen previewtemplate, dus browserbewijs op een draaiende omgeving is vóór merge niet mogelijk;
AC 12 leunt daarom op de configuratiecontrole. Na merge, zodra acceptatie main heeft gevolgd, is
`/archief/<bestaande-vergadering-id>` met een herlaad (F5) in de browser te controleren. Let op: de
acceptatiedataset bevat geen voorbije vergadering (discovery importeert alleen toekomstige agenda's),
dus een voorbije vergadering is daar alleen met een aparte seeding-voorziening te tonen.

### Herstelronde — verificatie (2026-09-18)

De factoryverificatie viel om op `frontend-cache-contract` (`bash tools/test-frontend-cache.sh`,
exitcode 128). De oorzaak lag niet in de storywijziging: regel 6 las de revisie met
`git -C "$repository_root" rev-parse HEAD`, en in de verificatiecontainer draait dat commando als een
andere gebruiker dan de eigenaar van `/work`. Git weigert dat met `detected dubious ownership` en
stopt het script door `set -euo pipefail` al vóór de eerste build.

De revisie dient in dit script alleen als bouwstempel (`PVDD_GIT_REVISION`); het cachecontract zelf —
bundelnaam per build, `no-cache` op index, `immutable` op de bundel, `no-store` op `version.json` en
de service worker, 404 op de oude bundel — hangt er niet van af. Regel 6 volgt nu daarom exact het
patroon dat `frontend/build-web.sh` al gebruikt: een meegegeven `PVDD_GIT_REVISION` wint, anders
`git rev-parse HEAD` en bij een onleesbare repository veertig nullen. Er is geen Gitconfiguratie
gewijzigd en geen assertie versoepeld.

Zelf gedraaid in deze ronde:
- `mvn -B --no-transfer-progress clean verify` in `backend/` tegen een echte PostgreSQL
  (`PVDD_TEST_DATABASE_URL`, rootloos gestart): 125 tests, 0 failures, 0 errors, 1 skipped
  (alleen `LiveSourceSpikeTest`). `DatabaseIntegrationTest` draaide dus volledig, inclusief de nieuwe
  test op `past`, de vergaderkop, de 404 en het ongewijzigde aantal rijen in `analysis_run`. Let op:
  de factoryverificatie heeft geen Docker en geen `PVDD_TEST_DATABASE_URL`, dus daar worden die tien
  tests overgeslagen; het rijniveaubewijs komt uit deze ronde en uit CI.
- `flutter analyze` in `frontend/`: geen bevindingen.
- `flutter test` in `frontend/`: 25 tests, alle groen.
- `bash tools/verify-documentation.sh`: in orde.
- `tools/test-frontend-cache.sh` is in deze runtime niet volledig te draaien: er is geen Docker voor
  de nginx-container. Het herstelde deel is wel bewezen door de eerste elf regels te draaien met een
  `git` die exact de foutmelding en exitcode 128 van de verificatiecontainer teruggeeft; het script
  komt dan door de revisiebepaling heen en bouwt twee verschillende bundels. De containerassertie
  zelf blijft ongewijzigd en wordt door de factoryverificatie en CI gedekt.

### Tweede herstelronde — verificatie (2026-09-18)

De factoryverificatie viel opnieuw om op `frontend-cache-contract`, nu met exitcode 127:
`tools/test-frontend-cache.sh: line 17: docker: command not found`. De revisiebepaling uit de vorige
ronde werkte dus (beide webbuilds draaiden), maar het script start daarna een nginx-container om de
daadwerkelijk verstuurde `Cache-Control`-headers te controleren, en de verificatieomgeving heeft geen
Docker. Zolang een storywijziging `frontend/` raakt, kan dit commando daar dus per definitie niet
groen worden; de oorzaak ligt buiten de storywijziging.

Het script slaat de containerasserties nu over wanneer `docker info` niet werkt, en controleert in
plaats daarvan dezelfde regels statisch:
- de buildoutput: bundelnaam volgens `main.<16 hex>.js`, aanwezige `index.html` en `version.json`,
  geen achtergebleven `flutter_service_worker.js`, en nog steeds een andere bundelnaam per build;
- `frontend/nginx.conf` én `frontend/nginx-acceptance.conf`: de bundellocatie met
  `public, max-age=31536000, immutable`, `no-cache` op de overige paden, `no-store` op
  `version.json` en de service worker, `try_files $uri =404`, de SPA-fallback
  `try_files $uri $uri/ /index.html` en de service worker die `caches.keys` leegt en zichzelf
  `unregister`t.

Met Docker — CI en lokaal — draait het volledige contract ongewijzigd; de containerasserties zijn
niet aangepast of versoepeld. De statische variant bewijst de verstuurde headers niet, alleen de
configuratie die ze oplevert; dat staat zo in `docs/factory/development.md`, in dezelfde lijn als de
al vastgelegde regel dat de databasetests zonder Docker worden overgeslagen in plaats van het hele
vangnet te laten falen. Als bijvangst dekt de statische variant AC 12 nu ook vanuit het vangnet zelf,
naast `frontend/test/spa_fallback_test.dart`.

Zelf gedraaid in deze ronde (alle vier de geraakte verificatiecommando's):
- `mvn -B --no-transfer-progress clean verify` in `backend/` tegen een echte PostgreSQL
  (rootloos gestart, `PVDD_TEST_DATABASE_URL`): BUILD SUCCESS, 125 tests, 0 failures, 0 errors,
  1 skipped (alleen `LiveSourceSpikeTest`). `DatabaseIntegrationTest` draaide dus volledig, inclusief
  de nieuwe test op de vergaderkop, `past`, de 404 en het ongewijzigde aantal rijen in
  `analysis_run`.
- `flutter analyze` in `frontend/`: `No issues found!`.
- `flutter test` in `frontend/`: 25 tests, alle groen.
- `bash tools/test-frontend-cache.sh`: exitcode 0,
  `cachecontract: main.a5e23e9dff65250d.js -> main.d7d26a2094777f18.js (statisch; geen Docker voor de
  nginx-container)`. Hiermee is ook `bash build-web.sh` (`frontend-flutter-build`) tweemaal groen
  gedraaid.
- `bash tools/verify-documentation.sh`: in orde.

Aan de storyfunctionaliteit is in deze ronde niets gewijzigd.

## hkh-267 — documentation (2026-09-18)

### Doel
De projectdocumentatie laten kloppen met het opgeleverde gedrag van `hkh-263`, op basis van de
volledige storydiff ten opzichte van de base branch.

### Bijgewerkt
- `docs/microservice-specificatie.md` (normatief):
  - §5.2 — nieuw scherm 8 **Voorbije vergadering (alleen-lezen)** op `/archief/<vergadering-id>`,
    met daaronder een alinea over de markering ‘al geweest’ met vergaderdatum, het ontbreken van
    iedere startactie, het uitblijven van de 15-secondenverversing, Agenda dat het geselecteerde
    menu-item blijft, de terugactie naar de agendaweergave, de fouttoestand met behoud van de
    vergaderkop en de bestaande SPA-fallback. De zin over ontbrekende schermen benoemt nu
    nauwkeurig wat er wél is: geen editor, geen goedkeuringsworkflow, geen scherm met eerdere
    adviesversies en geen overzicht van voorbije vergaderingen.
  - §6.3 — tabelregel `GET /api/meetings/{id}`, plus alinea's over de vergaderkop en
    voortgangstelling in het antwoord, `404` bij een geldige maar onbekende UUID en `400` bij een
    niet-UUID, bestaan-vóór-elke-andere-controle, de sessiecontrole die op deze route geldt, en het
    veld `past` dat server-side op databasetijd wordt bepaald (`starts_at < CURRENT_TIMESTAMP`,
    gelijkheid telt als toekomst) en ook op `/api/meetings/next` staat. De opsomming van leesroutes
    die nooit werk starten is uitgebreid met de nieuwe route.
  - §13.1 en §13.2 — testdekking van de leesroute (404, `past`, ongewijzigd `analysis_run`) en van
    het alleen-lezen scherm (markering, geen startacties, alleen leesaanroepen, fouttoestand met
    behoud van de vergaderkop, padherkenning en SPA-fallback).
- `docs/functionele-werking-commissie-assistent.md` — §8 vermeldt nu ook dat het terugkijkscherm
  zelf alleen leest; §11 beschrijft `/archief/<vergadering-id>` functioneel: dezelfde agendaweergave
  en dezelfde informatie per agendapunt, de markering ‘al geweest’ met datum, het onveranderde
  AI-voorbehoud, het ontbreken van startacties en menu-item, de terugactie, de fouttoestand en het
  feit dat een overzicht van voorbije vergaderingen nog niet bestaat.
- `docs/factory/functional-spec.md` (Frontend en API) — compacte ontwikkelvertaling van de nieuwe
  leesroute, het `past`-veld en het hergebruik van `MeetingOverviewPage` met een alleen-lezen vlag.
- `docs/agent-access.md` — de leesroute `GET /api/meetings/{id}` staat bewust niet in
  `ProductionReadAccessFilter` en geeft met `X-AI-Read-Token` dus `403`; voor productieonderzoek
  blijven `/api/meetings/next` en `/api/meetings/{id}/agenda-items` beschikbaar.

### Bewust niet gewijzigd
- `docs/factory/development.md` is in deze story al door de developer bijgewerkt met de sectie
  **Cachecontract van de frontend** (statische variant zonder Docker); die tekst klopt met
  `tools/test-frontend-cache.sh` en is hier niet aangepast.
- `docs/stappenplannen/*` beschrijven de uitvoeringsvolgorde per fase en zijn geen beschrijving van
  huidig gedrag; de bestaande tekst is niet onjuist geworden.
- `docs/functional-acceptance-verification.md`, `docs/technical-baseline-verification.md` en
  `docs/source-revision-verification.md` zijn vastgelegde bewijsrondes met een eigen datum en
  release; die worden niet met terugwerkende kracht herschreven.
- `README.md`, `docs/operations.md`, `deploy/README.md` en de ADR's zijn niet geraakt: geen
  Flyway-migratie, geen schemawijziging, geen schrijfroute, geen nieuwe configuratiesleutel en geen
  wijziging aan nginx, deployment of herstelprocedures.

### Verificatie
- `bash tools/verify-documentation.sh` → `Documentatie en repositoryhygiëne zijn in orde.`
- Alleen bestanden onder `docs/` gewijzigd; geen productiecode, tests of infrastructuur geraakt.
