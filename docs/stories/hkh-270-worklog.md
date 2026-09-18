# Worklog hkh-270 — Overzicht van eerdere vergaderingen met bijladen, lege toestand en foutmelding

## hkh-271 — development (2026-09-18)

### Doel
Vanuit de agendaweergave opent de gebruiker met één handeling het alleen-lezen overzicht van alle
bewaarde vergaderingen die al zijn geweest (`/archief`), met de meest recente bovenaan, en opent
daarvandaan een individuele vergadering. Backend en webapp zitten in één subtaak omdat ze één
responsecontract delen.

### Gerealiseerd — backend (module `dashboard`)
- `GET /api/meetings` in `DashboardController.meetings(state, limit, cursor)`. De drie
  queryparameters komen **als tekst** binnen (`String?`) en gaan ongewijzigd naar de querylaag. Dat
  `limit` geen `Int` is, is bewust: zo levert ook `limit=abc` de foutcode van deze route in plaats
  van de standaardconversiefout van het framework.
- `DashboardRepository.pastMeetings(state, limit, cursor)` valideert vóór elke databasetoegang
  (`require(state == "past")`, `limit` standaard 20 en geldig in 1..50, cursor decodeerbaar) en gooit
  anders `IllegalArgumentException`. `DashboardController` vertaalt die naar
  `ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_meeting_query")` — exact hetzelfde
  mechanisme als `invalid_ai_run_query`; er is geen nieuw foutbodyformaat.
- Query: `WHERE m.starts_at < CURRENT_TIMESTAMP ORDER BY m.starts_at DESC, m.id DESC LIMIT ?` in een
  CTE `page`, met cursor aanvullend `(m.starts_at, m.id) < (?::timestamptz, ?::uuid)`. De casts en
  het meegeven van een `OffsetDateTime` op UTC houden de keysetvergelijking los van de sessietijdzone.
  Grensgeval: `starts_at == nu` telt niet als voorbij (strikt `<`, op databasetijd).
- `nextCursor` wordt deterministisch bepaald door `limit + 1` rijen op te halen; de extra rij valt
  buiten `items` en dient alleen als cursorsignaal. De laatste pagina levert daardoor nooit een
  cursor, ook niet wanneer die precies `limit` items bevat.
- Cursorformaat: het codeer-/decodeerpatroon van `AiRunQueryRepository` is naar de gedeelde
  `KeysetCursor` (base64url zonder padding van `<instant>|<uuid>`) verhuisd en wordt nu door beide
  lijsten gebruikt. `AiRunQueryRepository` is functioneel ongewijzigd; alleen de twee private
  helpers verwijzen naar `KeysetCursor`.
- Antwoordmodel `PastMeetingPageDto(items, nextCursor, total)` met per item
  `id`, `title`, `startsAt`, `location`, `substantiveItemCount` en `completedAdviceCount`. `total` is
  het aantal bewaarde voorbije vergaderingen ongeacht cursor/limit en gaat met elke pagina mee.
- De tellingen komen uit een `JOIN LATERAL` op de gepagineerde set met **exact** het filter van
  `DashboardRepository.progress()` (`ai.source_state <> 'WITHDRAWN' AND ai.substantive AND
  ai.category IN ('A','B','C') AND documents.readable_document_count > 0`; afgerond = de laatste
  `FINAL_ADVICE`-run is `SUCCEEDED`). Ze worden dus alleen berekend voor de vergaderingen op de
  opgehaalde pagina.
- Geen Flyway-migratie, geen index, geen wijziging aan `package-info.java`, `ApiAuthenticationFilter`
  of `ProductionReadAccessFilter`. De nieuwe route valt automatisch onder dezelfde sessiecontrole als
  de bestaande agenda-leesroute.

### Gerealiseerd — webapp (Flutter)
- `DashboardGateway` heeft `Future<PastMeetingPage> pastMeetings({String? cursor})` met de vaste
  paginagrootte `pastMeetingPageSize = 20`; de limiet is niet instelbaar in de UI. Nieuwe modellen
  `PastMeetingPage` en `PastMeeting` in `dashboard_api.dart`.
- Agendaweergave (`meeting_overview.dart`): naast 'Nu controleren' staat de knop
  **Eerdere vergaderingen** met daaronder de regel dat je daar de bewaarde adviezen terugleest en dat
  terugkijken alleen lezen is. Onder 720 px staan de knoppen onder elkaar over de volle breedte.
  Beide verschijnen alleen wanneer de shell een `onOpenArchive` meegeeft, dus niet op het
  alleen-lezen archiefscherm.
- Nieuwe pagina `frontend/lib/archive_page.dart` (`ArchivePage`): terugactie 'Terug naar de agenda',
  titel 'Eerdere vergaderingen' met ondertitel, het alleen-lezen-blok bovenaan, per rij een datumblok
  (dag/maand/jaar), titel, locatie, de telling "x inhoudelijke agendapunten · y met afgerond advies"
  en de knop **Openen**. Onderaan 'Meer vergaderingen laden' met de stand "x van y vergaderingen
  getoond"; de knop verdwijnt zodra `nextCursor` null is. Het scherm kent geen enkele actie die werk
  start en geen ververstimer — uitsluitend GET-verkeer naar de lijstroute.
- Bijladen voegt pagina's samen met ontdubbeling op vergadering-id, volgens het patroon uit
  `ai_runs_page.dart`. Al geladen rijen worden nooit weggegooid.
- Lege toestand: 'Er is nog geen vergadering voorbij' met uitleg en de knop
  'Naar de huidige vergadering'.
- Fouttoestand (eerste laadpoging én bijladen): de melding 'Niet alle eerdere vergaderingen konden
  worden geladen' bovenaan met 'Opnieuw proberen', die exact dezelfde aanvraag herhaalt (de cursor van
  de mislukte aanvraag wordt vastgehouden in `_failedCursor`). De stand leest in die toestand
  'x van y vergaderingen geladen'.
- Routering in `main.dart`: `isArchiveOverviewPath` (nieuw in `archive_route.dart`) herkent
  `/archief`; `_selected` blijft 0 en de zijbalk telt onveranderd vijf menu-items. Elke menukeuze
  verlaat het archief.
- Terug vanuit het detailscherm: `_cameFromArchive` is een expliciete navigatievlag (geen
  browserhistorie). Kwam de gebruiker van het overzicht, dan keert 'Terug naar eerdere vergaderingen'
  naar `/archief`; bij een directe deeplink naar `/archief/<id>` blijft de bestaande terugactie
  'Terug naar agenda' → `/agenda` ongewijzigd. `MeetingOverviewPage` heeft daarvoor een
  `backLabel`-parameter met de bestaande tekst als standaard.
- Op een rijbreedte onder 620 px stapelen datumblok, titel, locatie en telling en loopt 'Openen' over
  de volle breedte.

### Testvoorzieningen (voor de tester)
- `frontend/test/support/fakes.dart` — de bestaande gedeelde `FakeDashboardGateway` is hergebruikt en
  uitgebreid met `pastMeetings`: `pastMeetingPages` (pagina per cursor, `null` = eerste pagina) en
  `failingPastMeetingCursors` (fout per cursor, weghalen laat dezelfde aanvraag daarna slagen). Elke
  aanroep komt in `calls` als `pastMeetings:first` of `pastMeetings:<cursor>`; `startingCalls` blijft
  de aanroepen tonen die werk zouden starten. Nieuwe zaaihelpers `syntheticMeetingId`,
  `syntheticPastMeeting` en `syntheticPastMeetingPage`. `UnusedAiRunsGateway`/`UnusedSettingsGateway`
  zijn uit `archive_page_test.dart` hierheen verplaatst zodat beide testbestanden ze delen.
- `DatabaseIntegrationTest.seedArchiveMeeting(startsAt, title, location, succeeded, failed,
  withoutAdvice, withNoise)` — zaait één bewaarde vergadering met inhoudelijke agendapunten, leesbare
  documenten en `FINAL_ADVICE`-runs (`SUCCEEDED`/`FAILED`). Met `withNoise` komen de randgevallen
  erbij die nergens mogen meetellen: een ingetrokken punt, een niet-inhoudelijk punt, een punt buiten
  A/B/C en een punt zonder leesbaar document — elk mét afgerond advies, zodat een te ruime telling
  meteen opvalt. Opruimen met de bestaande `removeSyntheticMeeting(meetingId)`.
- `DatabaseIntegrationTest.hideExistingPastMeetings()` / `restoreStartTimes(rows)` — schuift
  vergaderingen die andere tests hebben achtergelaten tijdelijk naar de toekomst en zet ze in het
  `finally` exact terug, zodat `total` en de volgorde deterministisch te toetsen zijn zonder gegevens
  te verwijderen.
- `failedAdvice(meetingId, itemId, createdAt)` naast het bestaande `succeededAdvice`; beide delen nu
  `preparedAdviceRun(...)`.

### Reproduceerbare commando's voor de tester
- Backend: `cd backend && mvn -B --no-transfer-progress clean verify`.
  Zonder Docker en zonder `PVDD_TEST_DATABASE_URL` worden de tien tests van `DatabaseIntegrationTest`
  plus `LiveSourceSpikeTest` overgeslagen; de overige tests draaien volledig. Wie het rijniveaubewijs
  wil draaien, geeft een **verse, lege** PostgreSQL mee via `PVDD_TEST_DATABASE_URL`,
  `PVDD_TEST_DATABASE_USER` en `PVDD_TEST_DATABASE_PASSWORD` (zie `docs/factory/development.md`).
- Frontend: `cd frontend && flutter analyze && flutter test`.
- Losse testbestanden: `flutter test test/archive_overview_test.dart` en
  `flutter test test/archive_route_test.dart`;
  `mvn -Dtest=DashboardPastMeetingsTest -DfailIfNoSpecifiedTests=false test`.

### Tests
Backend — `dashboard/api/DashboardPastMeetingsTest` (unittest, geen database):
- elke ongeldige query (`state` ontbrekend, `state=future`, lege `state`, `limit=0`, `limit=51`,
  `limit=abc`, `cursor=!!!`) wordt geweigerd **vóór** enige databasetoegang; een meekijkende
  `JdbcTemplate` bewijst dat er geen statement is uitgevoerd;
- dezelfde gevallen leveren via de controller HTTP 400 met reason `invalid_meeting_query`;
- een geldige pagina gaat ongewijzigd terug en raakt de `AnalysisFacade` nooit;
- zonder `limit` haalt de query 21 rijen op (20 + het cursorsignaal) en bevat elk uitgevoerd statement
  een `LIMIT`; `limit=1` en `limit=50` leveren 2 respectievelijk 51;
- zonder sessie geeft `/api/meetings` exact hetzelfde weigerantwoord als `/api/meetings/next`
  (401 met `{"error":"authentication_failed"}`), dus de route staat niet in `shouldNotFilter`.

Backend — `DatabaseIntegrationTest` (tegen een echte PostgreSQL):
- *lege toestand*: zonder voorbije vergaderingen leeg `items`, `total = 0`, `nextCursor` null;
- *sortering*: alleen voorbije vergaderingen, `starts_at DESC, id DESC` (met twee vergaderingen op
  hetzelfde tijdstip), toekomstige vergaderingen ontbreken, en het grensgeval `starts_at == nu` telt
  niet als voorbij — bewezen met één statement waarin `CURRENT_TIMESTAMP` vaststaat;
- *tellingen*: een vergadering met twee afgeronde, één mislukte en één nog niet geanalyseerde punt
  plus de vier ruisgevallen levert `substantiveItemCount = 4` en `completedAdviceCount = 2`, precies
  gelijk aan `progress.total` en `progress.complete` van diezelfde vergadering;
- *paginering*: 25 gezaaide vergaderingen; zonder limiet 20 items met cursor, met `limit=5` precies
  vijf pagina's die samen exact de gezaaide verzameling opleveren zonder duplicaten of gaten, en de
  laatste pagina heeft `nextCursor = null` terwijl zij precies `limit` items bevat. In dezelfde test
  leveren de ongeldige queries ook tegen de echte database HTTP 400 met `invalid_meeting_query`.

Frontend — `frontend/test/archive_overview_test.dart` (17 tests):
- de agendaweergave toont de knop en de uitleggende regel; tikken navigeert naar `/archief` en laadt
  de eerste pagina;
- bijladen voegt pagina 1 (20 items + cursor) en pagina 2 (met een overlappend item, geen cursor)
  samen: elke vergadering staat er precies één keer in, de stand loopt van '20 van 25' naar
  '25 van 25 vergaderingen getoond' en de bijlaadknop verdwijnt;
- 'Openen' navigeert naar `/archief/<vergadering-id>`;
- de alleen-lezen mededeling staat boven de eerste rij, er is geen actie die werk start, en na dertig
  seconden is er nog steeds precies één aanroep gedaan;
- de lege toestand toont de uitleg, geen rij en geen bijlaadknop;
- een fout op de eerste pagina toont melding + 'Opnieuw proberen' en herhaalt daarna dezelfde
  aanvraag; een fout op de tweede pagina laat de twintig geladen rijen staan, toont de melding
  bovenaan en levert na 'Opnieuw proberen' vijfentwintig rijen zonder duplicaten, met exact dezelfde
  cursor als de mislukte aanvraag;
- op `/archief` blijft Agenda geselecteerd, telt de zijbalk onveranderd vijf menu-items en gaat de
  terugactie naar `/agenda`;
- vanuit het overzicht keert de terugactie van een voorbije vergadering terug naar `/archief`, terwijl
  een directe deeplink naar `/archief/<id>` de bestaande terugactie naar `/agenda` houdt;
- overzicht, lege toestand en fouttoestand op 400 en 1200 px breed, zonder overflow-exceptie.

Frontend — `frontend/test/archive_route_test.dart`: `isArchiveOverviewPath` herkent `/archief` (met en
zonder slash) en niets anders; de bestaande padherkenning is ongewijzigd.

### Bewust niet gewijzigd
Geen Flyway-migratie en geen index (`git status` toont geen bestand onder
`backend/src/main/resources/db/migration`, en de diff bevat geen `CREATE INDEX`). Elke nieuwe query is
begrensd: de paginaquery met `LIMIT ?`, de LATERAL-telling via de gepagineerde set en de totaaltelling
met een expliciete `LIMIT 1` op het aggregaat. `package-info.java` van `dashboard`,
`ApiAuthenticationFilter`, `ProductionReadAccessFilter` en `ProductionReadAccessFilterTest` zijn
ongewijzigd; het archiefdetailscherm en `GET /api/meetings/{id}` zijn alleen geraakt voor de
terugactie (`backLabel`).

### Bewijs en beperking
Al het bewijs vóór merge komt uit geautomatiseerde tests op de storybranch; er is geen preview-URL en
geen previewtemplate, en acceptatie volgt `main` en bevat deze wijziging pas ná merge. Zelf gedraaid
in deze ronde:
- `mvn -B --no-transfer-progress clean verify` in `backend/` tegen een echte PostgreSQL (rootloos
  gestart, `PVDD_TEST_DATABASE_URL`): BUILD SUCCESS, 135 tests, 0 failures, 0 errors, 1 skipped
  (alleen `LiveSourceSpikeTest`). `DatabaseIntegrationTest` draaide dus volledig, inclusief de vier
  nieuwe archieftests.
- `flutter analyze` in `frontend/`: `No issues found!`.
- `flutter test` in `frontend/`: 42 tests, alle groen (was 25).
- `bash tools/test-frontend-cache.sh` (inclusief tweemaal `build-web.sh`) en
  `bash tools/verify-documentation.sh`.

Beperking: de acceptatiedataset bevat geen voorbije vergadering, omdat de discovery alleen
toekomstige agenda's importeert. Het overzicht toont daar na deployment dus een lege toestand zolang
de eerstvolgende vergadering nog niet is geweest; dat is geen afwijking maar het verwachte gedrag van
AC 10. Na deployment is verder te controleren dat de deeplink `/archief` in de browser laadt (de
SPA-fallback `try_files` staat al in beide nginx-configuraties) en dat de tellingen overeenkomen met
wat het agendascherm voor diezelfde vergadering laat zien.

### Afwijkingen
Geen. De route `/archief/<id>` uit de afhankelijke story bestond al, dus 'Openen' navigeert daarheen;
de afwijkingsregel was niet nodig.

## hkh-274 — documentation (2026-09-18)

### Doel
De documentatie laten kloppen met wat in deze story werkelijk is gebouwd: de lijstroute
`GET /api/meetings?state=past` en het alleen-lezen overzicht `/archief`, inclusief de ingang vanaf
de agendaweergave, bijladen, lege toestand, fouttoestand en de gewijzigde terugactie van het
archiefdetailscherm. Basis is de volledige storydiff ten opzichte van de base branch.

### Bijgewerkt
- `docs/microservice-specificatie.md` (normatief)
  - §5.2 Schermen: nieuw scherm 9 **Eerdere vergaderingen** op `/archief`; een nieuwe alinea over
    ingang, rijinhoud, bijladen met de stand, lege toestand, fouttoestand, het ontbreken van elke
    startactie en ververstimer, en het gedrag op een smal scherm. De alinea over het alleen-lezen
    detailscherm meldt nu dat de terugactie naar het overzicht keert wanneer de gebruiker
    daarvandaan kwam en bij een rechtstreekse aanroep onveranderd naar de agendaweergave gaat
    (expliciete navigatievlag). De zin dat er “geen overzicht van voorbije vergaderingen” is, is
    achterhaald en vervangen; de beperking rond eerdere adviesversies blijft staan.
  - §6.3 API: tabelregel voor `GET /api/meetings?state=past&limit=20&cursor=...` plus twee alinea's
    met het contract: verplichte `state`, `limit` 1..50 met standaard 20, ondoorzichtige cursor in
    hetzelfde formaat als `/api/ai-runs`, `400` met `invalid_meeting_query` vóór elke
    databasetoegang, sortering en het grensgeval `starts_at == nu`, `items`/`nextCursor`/`total`,
    de tellingen met exact het filter van de voortgangstelling alleen over de opgehaalde pagina,
    `nextCursor` via `limit + 1`, geen migratie of index, en dezelfde sessieregels.
  - §13.1 en §13.2 Teststrategie: bullets voor de backendlijstroute (volgorde, grensgeval,
    paginering, lege lijst, tellingen, ongeldige parameters) en voor het webappoverzicht (ingang,
    bijladen zonder duplicaten, verdwijnende bijlaadknop, lege en fouttoestand, alleen leesverkeer,
    terugactie); de bullet over padherkenning noemt nu ook `/archief`.
- `docs/functionele-werking-commissie-assistent.md`
  - §9: de belofte dat terugkijken geen AI-werk start geldt expliciet ook voor het overzicht en het
    bijladen daarvan.
  - §11: nieuwe beschrijving in gebruikerstaal van **Eerdere vergaderingen** (ingang naast **Nu
    controleren**, volgorde, rijinhoud, twintig per keer met **Meer vergaderingen laden** en de
    stand, geen duplicaten, lege toestand, fouttoestand met behoud van geladen rijen, geen eigen
    menu-item, terugactie, smal scherm). De passage over het detailscherm zegt nu dat de terugactie
    naar het overzicht keert wanneer de gebruiker daarvandaan kwam; de zin dat een overzicht van
    voorbije vergaderingen nog niet bestaat, is vervangen.
- `docs/factory/functional-spec.md`: twee alinea's met de compacte ontwikkelvertaling van de
  lijstroute en van het `/archief`-scherm; de bestaande zin over de terugactie van
  `/archief/<vergadering-id>` is aangepast omdat die nu van de herkomst afhangt.
- `docs/agent-access.md`: de opsomming van nog niet beoordeelde leesroutes noemt naast
  `GET /api/meetings/{id}` ook `GET /api/meetings?state=past`. `ProductionReadAccessFilter` is in
  deze story bewust ongewijzigd, dus die route geeft met `X-AI-Read-Token` `403`
  (`allowedPath` matcht `/api/meetings` niet); de aanbevolen routes voor productieonderzoek
  blijven ongewijzigd.
- `docs/stories/hkh-270-worklog.md`: deze sectie.

### Bewust niet gewijzigd
- `README.md` — beschrijft stack, documentatie-index en lokaal starten; geen schermen of routes.
- `docs/factory/technical-spec.md` — alleen de stack; deze story voegt geen technologie toe.
- `docs/factory/development.md`, `docs/factory/deployment.md`, `docs/factory/secrets-local.md`,
  `docs/factory/agent-runtime.md`, `docs/operations.md` — geen nieuwe commando's,
  omgevingsvariabelen, migraties, infrastructuur of runbookstappen; de bestaande test- en
  cachecontractbeschrijvingen blijven kloppen.
- `docs/stappenplannen/*` — uitvoeringsplannen per fase, geen beschrijving van huidig gedrag.
- `docs/uitbreidingsspecificatie-standpunten-en-ai-inzicht.md` — beschrijft de AI-runslijst; het
  cursorformaat daarvan is functioneel ongewijzigd (alleen intern gedeeld via `KeysetCursor`).
- `docs/functional-acceptance-verification.md`, `docs/technical-baseline-verification.md`,
  `docs/source-revision-verification.md`, `docs/production-source-spike.md` — gedateerde
  bewijsrondes; die worden niet met terugwerkende kracht herschreven.
- `docs/adr/*` — geen nieuw architectuurbesluit.
- Datumregels boven bestaande documenten (‘Laatste actualisatie’, ‘Datum’/‘Status’) — conform de
  bestaande conventie bij storydocumentatie niet bijgewerkt.

### Verificatie
- `bash tools/verify-documentation.sh` — geslaagd.
- `git status --short` toont uitsluitend bestanden onder `docs/`; geen productiecode, tests of
  infrastructuur geraakt.
