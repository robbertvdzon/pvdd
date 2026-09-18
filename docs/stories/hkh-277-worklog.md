# Worklog hkh-277 — Eerdere adviesversie met datum en reden terugzien, zonder bronnenlijst

## hkh-278 — development (2026-09-18)

### Doel
Bij een agendapunt waarvan het advies tussentijds is vernieuwd kan de gebruiker naast het laatste
advies ook de eerdere versie teruglezen, met de aanmaakdatum, het moment van vervanging en de reden
van vernieuwing. Bij een eerdere versie hoort bewust geen bronnenlijst; het scherm zegt dat expliciet.
Backend en webapp zitten in één subtaak omdat ze één responsecontract delen.

### Gerealiseerd — backend (module `dashboard`)
- `GET /api/agenda-items/{id}/advice-versions` in `DashboardController.adviceVersions(id)`. Een
  onbekend agendapunt levert HTTP 404 (`DashboardRepository.adviceVersions` geeft `null` na de
  bestaanscontrole op `agenda_item`); een bestaand agendapunt zonder geslaagde adviesrun levert HTTP
  200 met een lege lijst. Een id dat geen geldige UUID is valt op het bestaande conversiegedrag van
  het framework (400).
- `DashboardRepository.adviceVersions(itemId): AdviceVersionsDto?` met de voorgeschreven query —
  `agenda_item_advice aia JOIN analysis_run ar ON ar.id = aia.analysis_run_id WHERE
  aia.agenda_item_id = ? AND ar.status = 'SUCCEEDED'` — en exact de ordening van
  `DashboardRepository.item()`: `CASE aia.actuality WHEN 'CURRENT' THEN 0 WHEN 'STALE' THEN 1 ELSE 2
  END, ar.created_at DESC, ar.id DESC`. Geen paginering, geen limiet.
- Antwoordmodel `AdviceVersionsDto(versions: List<AdviceVersionDto>)` — één object met het veld
  `versions`, conform aanname A2. Per versie: `adviceId`, `analysisRunId`, `createdAt` (`ar.created_at`
  als ISO-8601 instant), `actuality` zoals opgeslagen, `latest`, `advice` (de volledige adviesinhoud
  in dezelfde vorm als `item()` teruggeeft) met daarnaast `displayTitle` en `shortConclusion` uit
  dezelfde JSON-velden als `item()`, `provider`, `model`, `promptVersion`, `analysisGuidance` en
  `refreshReason`.
- `latest` is `true` voor uitsluitend de eerste rij in bovenstaande ordening (de rijindex van de
  `RowMapper`). Een WITHDRAWN-rij die chronologisch de nieuwste is, sorteert dus ná CURRENT/STALE en
  krijgt de vlag niet — bewust, conform aanname A3, en vastgelegd in een eigen test.
- `analysisGuidance` komt uit `analysis_run.analysis_guidance` (kolom is NOT NULL met standaard `''`);
  leeg of alleen witruimte wordt `null`.
- `refreshReason` wordt **gedeeld** afgeleid, niet gekopieerd: de nieuwe enum
  `nl.vdzon.pvdd.dashboard.AdviceRefreshReason` bevat zowel het SQL-fragment
  (`reanalysisPredicate(runAlias)`, kolomnaam `reanalysis`) als de beslissing
  (`retry_of_run_id IS NOT NULL` → `MANUAL_RETRY`; anders oudere FINAL_ADVICE-run aanwezig →
  `CONTEXT_CHANGED`; anders `FIRST_ANALYSIS`). `AiRunQueryRepository.agendaRuns()` gebruikt sindsdien
  dezelfde enum en levert **ongewijzigd** `AGENDA_RETRY` / `AGENDA_REANALYSIS` / `AGENDA_ADVICE` met
  dezelfde titels en uitleg; alleen de tak-keuze is gedeeld.
  - *Waarom eigen codewaarden:* voor deze afleiding bestonden er nog geen. `AGENDA_RETRY` c.s. zijn
    de **soort** logische AI-run (dezelfde reeks bevat ook `POLICY_SYNC`) en niet de reden van
    vernieuwing. De codes uit de opdracht (`MANUAL_RETRY`, `CONTEXT_CHANGED`, `FIRST_ANALYSIS`) zijn
    daarom gebruikt; een integratietest bewijst dat beide weergaven dezelfde run hetzelfde noemen.
- Bewust niet gedaan: geen Flyway-migratie, geen index, geen schrijfroute, geen AI-aanroep, geen
  lezen van `agenda_item_advice.citations`, geen afleiding uit `meeting_revision` /
  `agenda_item_revision` / `document_revision`, geen wijziging aan `item()`, `sources()` of een
  bestaande route, en geen wijziging aan `package-info.java`, `ApiAuthenticationFilter`,
  `ProductionReadAccessFilter` of `ProductionReadAccessFilterTest`. De nieuwe route valt automatisch
  onder dezelfde sessiecontrole als de bestaande agenda-leesroutes.

### Gerealiseerd — webapp (Flutter)
- `DashboardGateway` heeft `Future<List<AdviceVersion>> adviceVersions(String itemId)`;
  `HttpDashboardGateway` leest `/api/agenda-items/<id>/advice-versions` en pakt het veld `versions`
  uit. Nieuw model `AdviceVersion` in `dashboard_api.dart`.
- `_AgendaItemCard` in `meeting_overview.dart` haalt de versies **één keer** op bij het uitklappen van
  de detailweergave, en alleen wanneer het agendapunt een advies heeft (`adviceActuality != null`).
  Wisselen tussen versies leest uit die lijst en doet geen enkele nieuwe aanroep. De bestaande
  15-secondenverversing is hier bewust **niet** mee uitgebreid: `didUpdateWidget` ververst alleen het
  itemdetail, zoals voorheen.
- Bij meer dan één bewaarde versie staat boven de analyse een `Wrap` met één `ChoiceChip` per versie,
  nieuwste eerst: `Laatste advies · <datum>` en daarna `Eerdere versie · <datum>`. Een `Wrap` in
  plaats van een segmentknop, zodat er ook bij drie of meer versies en op een smal scherm niets
  overloopt. Geen paginering en geen 'meer tonen'.
- Onder de keuze staat bij het laatste advies één regel: `Dit advies verving de versie van <datum van
  de eerstvolgende oudere versie>. Reden: <reden van het laatste advies>.` Die regel verschijnt
  alleen wanneer de keuze zichtbaar is.
- Bij precies één bewaarde versie verschijnt geen keuze maar op dezelfde plek: `Van dit agendapunt is
  geen eerdere versie bewaard. Je ziet het enige advies, gemaakt op <datum>.` Bij nul bewaarde versies
  verandert er niets aan de weergave; de bestaande toestand 'nog geen advies' blijft leidend.
- Bij het bekijken van een eerdere versie:
  - de statusbadge in de kaartkop wordt vervangen door `EERDERE VERSIE` (via `_statusLabel`, code
    `EARLIER_VERSION`);
  - de metadatatabel (AI-titel / Korte conclusie / Laatste wijziging / Laatste AI-analyse) wordt niet
    getoond, conform aanname A6;
  - bovenaan de melding `Je bekijkt een eerdere versie van <aanmaakdatum>` met daaronder
    `Vervangen op <aanmaakdatum van de eerstvolgende nieuwere versie>. Reden: <reden van díe nieuwere
    versie>.` en `Aanvullende analyse-instructie van toen: <instructie>.`;
  - is `analysisGuidance` `null`, dan staat daar `niet vastgelegd`; de huidige instelling wordt bij
    een eerdere versie nooit getoond;
  - daaronder de adviesinhoud van díe versie in de bekende secties (dezelfde `_abAdvice`/`_cAdvice`/
    Markdown-weergave als het laatste advies), met dezelfde kopieeractie.
- 'Vervangen op' en de redenzin worden client-side afgeleid uit de teruggegeven volgorde: het item
  direct boven de getoonde versie is de vervanger (aanname A4/A5). Er is geen extra backendveld.
  Levert die afleiding `FIRST_ANALYSIS` op, dan blijft de redenzin weg in plaats van dat er een
  placeholder verschijnt.
- Bij een eerdere versie verschijnt geen bronnenlijst. In plaats daarvan staat onder het rode
  AI-voorbehoud: `Bij deze eerdere versie is niet apart bewaard welke stukken toen zijn gebruikt. De
  bronnenlijst hoort daarom alleen bij het laatste advies.`
- Het laatste advies is ongewijzigd: AI-titel, korte conclusie, volledige analyse, de melding over
  niet-leesbare stukken, het rode AI-voorbehoud en de bronnenlijst met leesbaarheid per stuk. Het
  AI-voorbehoud en de melding over niet-leesbare stukken blijven bij **elke** getoonde versie staan
  (aanname A7); de melding beschrijft de stukken van het agendapunt, niet de bronlijst van die versie.
- Mislukt het ophalen, dan blijft het reeds getoonde laatste advies volledig staan en verschijnt
  bovenaan de detailweergave de inline melding `De adviesversies konden niet worden geladen. Het
  advies hieronder blijft staan.` met de knop **Versies opnieuw laden**, die exact dezelfde
  leesaanvraag herhaalt. Bewust een eigen label, zodat het niet botst met de bestaande
  'Opnieuw proberen' van de herstartactie in dezelfde kaart.
- Dichtklappen zet de keuze terug op het laatste advies, zodat een dichtgeklapte kaart altijd het
  agendapunt zelf beschrijft (badge en metadatatabel van het laatste advies). De al opgehaalde lijst
  blijft staan, dus opnieuw uitklappen leest niets bij.
- Alles gebeurt in dezelfde gedeelde detailweergave, dus zowel bij een voorbije vergadering
  (`readOnly`/`archivedMeetingId`) als bij de huidige agenda. Geen kopie van het scherm, geen nieuwe
  route.
- Nieuwe publieke formatteerhelpers in `meeting_overview.dart`: `adviceVersionDate` (`3 september
  2026`), `adviceVersionShortDate` (`7 sep 2026`), `adviceVersionLabel` en
  `adviceRefreshReasonSentence`. Publiek zodat de tests de datums via dezelfde helper asserteren als
  de weergave gebruikt; de maandnamen zijn gedeeld met de bestaande `_longDate`.

### Testvoorzieningen (voor de tester)
- `frontend/test/support/fakes.dart` — de gedeelde `FakeDashboardGateway` kreeg `adviceVersions`:
  - `adviceVersionsByItem: Map<String, List<AdviceVersion>>` — de versies per agendapunt-id;
    standaard leeg, zodat bestaande tests geen keuze en geen melding in beeld krijgen;
  - `failingAdviceVersionItems: Set<String>` — simuleert de fout; haal een id eruit om dezelfde
    aanvraag daarna te laten slagen, precies zoals 'Versies opnieuw laden' die herhaalt;
  - elke aanroep komt in `calls` als `adviceVersions:<item-id>`; `startingCalls` blijft ongewijzigd
    de aanroepen tonen die werk zouden starten.
  - Zaaihelpers: `syntheticAdviceVersion(...)`, `syntheticAdviceVersions(...)` (de twee versies uit
    de UX-mockups, in serverordening) en de tijdstippen `latestAdviceAt` / `earlierAdviceAt`. De
    gezaaide adviesinhoud gebruikt de gestructureerde A/B-secties, zodat de tekst van díe versie met
    `find.text` te vinden is.
- `DatabaseIntegrationTest.seedAdviceVersion(meetingId, itemId, createdAt, status, guidance,
  retryOfRunId, advice)` — zaait één `FINAL_ADVICE`-run met adviesrij. Met een andere `status` dan
  `SUCCEEDED` blijft de adviesrij staan terwijl de run mislukt of geannuleerd is; `guidance` vult
  `analysis_run.analysis_guidance` en `retryOfRunId` maakt er een handmatig herstarte run van. Het
  bestaande `succeededAdvice(...)` delegeert hiernaartoe, dus alle bestaande zaaipaden delen dezelfde
  code.
- `DatabaseIntegrationTest.seedMeetingWithAgendaItem(startsAt)` — vergadering met één inhoudelijk
  agendapunt en leesbaar document, nog zónder advies, zodat een test zelf de runs bepaalt. Het
  bestaande `seedMeetingWithAdvice(...)` bouwt daar nu op. `countAllAdvice()` naast het bestaande
  `countAllRuns()`. Opruimen met de bestaande `removeSyntheticMeeting(meetingId)`.
- `preparedAdviceRun(...)` heeft twee optionele parameters gekregen (`guidance`, `retryOfRunId`);
  bestaande aanroepen blijven ongewijzigd.
- Geen AI-mock nodig: er wordt in deze story geen analyse gestart.

### Reproduceerbare commando's voor de tester
- Backend: `cd backend && mvn -B --no-transfer-progress clean verify`.
  Zonder Docker en zonder `PVDD_TEST_DATABASE_URL` worden de negentien tests van
  `DatabaseIntegrationTest` plus `LiveSourceSpikeTest` overgeslagen; de overige tests draaien
  volledig. Wie het rijniveaubewijs wil draaien, geeft een **verse, lege** PostgreSQL mee via
  `PVDD_TEST_DATABASE_URL`, `PVDD_TEST_DATABASE_USER` en `PVDD_TEST_DATABASE_PASSWORD` (zie
  `docs/factory/development.md`).
- Frontend: `cd frontend && flutter analyze && flutter test`.
- Alleen de nieuwe tests: `cd frontend && flutter test test/advice_versions_test.dart`, en
  `cd backend && mvn -B -Dtest=DatabaseIntegrationTest -DfailIfNoSpecifiedTests=false test`.

### Tests
Backend — `DatabaseIntegrationTest` (tegen een echte PostgreSQL), vijf nieuwe tests:
- *volgorde, velden en 404*: twee geslaagde runs leveren twee versies, nieuwste eerst, met
  `CURRENT`/`STALE`, `latest` alleen op de eerste, de gezaaide `createdAt`-waarden, `adviceId` en
  `analysisRunId`, de adviesinhoud (`displayTitle`, `shortConclusion`, `advice.content`), `provider`,
  `model` en `promptVersion`; een run met alleen witruimte in `analysis_guidance` levert `null`
  naast de bewaarde tekst van de andere run; een bestaand agendapunt zonder geslaagde run levert een
  lege lijst; een willekeurige onbekende UUID levert HTTP 404.
- *mislukte en geannuleerde runs*: een agendapunt met één `SUCCEEDED`, één `FAILED` en één
  `CANCELLED` run — alle drie mét adviesrij — levert uitsluitend de geslaagde run.
- *reden van vernieuwing*: de drie takken (eerste analyse, heranalyse zonder retry, handmatige
  herstart) leveren `FIRST_ANALYSIS`, `CONTEXT_CHANGED` en `MANUAL_RETRY`, en dezelfde drie runs
  krijgen in de AI-runsweergave (`GET /api/ai-runs/{id}`) respectievelijk `AGENDA_ADVICE`,
  `AGENDA_REANALYSIS` en `AGENDA_RETRY` — één gedeelde afleiding, twee weergaven.
- *uitsluitend lezen, geen bronnen*: geen enkel veld van `AdviceVersionDto`/`AdviceVersionsDto` en
  geen enkel woord in het geserialiseerde antwoord verwijst naar citaten, bronnen of revisies, en de
  rijaantallen in `analysis_run` en `agenda_item_advice` plus de volledige adviesmomentopname zijn
  vóór en na de aanroep gelijk.
- *ordening boven chronologie*: een WITHDRAWN-rij die chronologisch de nieuwste is, sorteert ná
  CURRENT en STALE en krijgt dus niet `latest`.

Frontend — `frontend/test/advice_versions_test.dart` (18 tests):
- twee versies: beide opties zichtbaar met hun datum, de vervangregel onder de keuze, en wisselen
  toont de inhoud van de andere versie en verbergt die van de eerste;
- eerdere versie: aanmaakdatum, vervangdatum (de datum van de eerstvolgende nieuwere versie) en de
  juiste redenzin, in twee varianten (`CONTEXT_CHANGED` en `MANUAL_RETRY`); de badge `EERDERE VERSIE`
  vervangt de statusbadge en de metadatatabel verdwijnt en komt bij terugschakelen weer terug;
- precies één versie: de expliciete melding, geen enkele `ChoiceChip`, en het laatste advies blijft
  volledig staan; nul versies: er verandert niets aan de weergave;
- `niet vastgelegd` bij een eerdere versie zonder guidance, terwijl de wél gevulde instructie van het
  laatste advies nergens in beeld komt; met bewaarde guidance verschijnt juist die tekst;
- eerdere versie zonder bronnenlijst: `Bronnen` en beide brontitels zijn weg en de melding staat er,
  terwijl het laatste advies de bronnenlijst met leesbaarheid per stuk houdt;
- AI-voorbehoud en de melding over niet-leesbare stukken zijn zichtbaar bij het laatste advies én bij
  de eerdere versie;
- dezelfde detailweergave tweemaal opgebouwd, met en zonder de alleen-lezen/archiefvlag, met in beide
  gevallen de keuze, de badge en de melding zonder bronnenlijst;
- drie versies: drie opties, en de middelste versie meldt te zijn vervangen door de eerstvolgende
  nieuwere versie (niet door het laatste advies);
- dichtklappen zet de kaart terug op het laatste advies en opnieuw openen leest niets bij;
- `FIRST_ANALYSIS` als vervangreden laat de redenzin weg in plaats van een placeholder te tonen;
- een mislukte versieaanvraag laat het laatste advies volledig staan, toont de melding en de knop
  'Versies opnieuw laden', en die herhaalt exact dezelfde aanvraag (twee registraties in `calls`);
- uitsluitend leesverkeer: na uitklappen precies één versieaanvraag, na driemaal wisselen nog steeds
  één, geen enkele werkstartende aanroep en elke geregistreerde aanroep is een leesaanroep;
- de keuze en alle meldingen op 400 en 1280 logische pixels breed, zonder overflow-exceptie.

### Aangepaste bestaande verwachting
`frontend/test/archive_page_test.dart` — de test *'offers no action that starts new work and only
reads'* asserteert de volledige aanroeplijst. Uitklappen leest sinds deze story naast het itemdetail
ook de adviesversies, dus de lijst bevat één extra regel `adviceVersions:item-a`. De assertie blijft
een exacte lijst en bewijst dus onverminderd dat er alleen leesverkeer ontstaat; er is geen
verwachting verzwakt. Dit is de enige bestaande testverwachting die is aangepast. Alle andere
bestaande tests — de bronnenlijst, het agendascherm, het archief, `ModulithArchitectureTest` en
`ProductionReadAccessFilterTest` — zijn ongewijzigd groen.

### Bewust niet gewijzigd
Geen Flyway-migratie (`backend/src/main/resources/db/migration` bevat geen nieuw bestand), geen index,
geen schrijfroute en geen AI-aanroep. `ProductionReadAccessFilter` is niet uitgebreid met de nieuwe
route: dat staat alleen in de epiccontext en in geen enkel acceptatiecriterium van deze story, dus een
leestoken krijgt op `/api/agenda-items/{id}/advice-versions` hetzelfde antwoord als op elke andere
niet-beoordeelde route. `DashboardRepository.item()` en `sources()` zijn ongewijzigd; de nieuwe query
herhaalt hun ordening letterlijk in plaats van hun SQL te verbouwen.

### Bewijs en beperking
Al het bewijs vóór merge komt uit geautomatiseerde tests op de storybranch; er is geen previewtemplate
en geen preview-URL, en acceptatie volgt `main` en bevat deze wijziging pas ná merge. Zelf gedraaid in
deze ronde:
- `mvn -B --no-transfer-progress clean verify` in `backend/` tegen een echte PostgreSQL (rootloos
  gestart, meegegeven via `PVDD_TEST_DATABASE_URL`), zodat `DatabaseIntegrationTest` volledig
  meedraaide inclusief de vijf nieuwe tests;
- `flutter analyze` en `flutter test` in `frontend/`;
- `bash tools/test-frontend-cache.sh` en `bash tools/verify-documentation.sh`.

Uitkomsten: backend `BUILD SUCCESS` met 140 tests, 0 failures, 0 errors en 1 skipped (alleen
`LiveSourceSpikeTest`); `DatabaseIntegrationTest` draaide met 19 tests (was 14). Frontend
`No issues found!` en 60 tests groen (was 42), waarvan 18 nieuw.

Beperking: de acceptatiedataset bevat geen agendapunt met twee geslaagde adviesruns — de
meeting-source-mock levert één vergadering en de analyse draait daar hooguit één keer per punt. Het
scherm toont daar na deployment dus de melding 'geen eerdere versie bewaard'; dat is geen afwijking
maar het verwachte gedrag bij één bewaarde versie. Een menselijke controle van de versiekeuze zelf op
acceptatie vergt een tweede geslaagde run (bijvoorbeeld via de herstartactie) en is uitdrukkelijk geen
merge-poort.

### Afwijkingen ten opzichte van het plan
- De opdracht sprak over `AiRunQueryRepository.agendaRuns()`; die methode is privé en heet zo, maar de
  klasse staat in `nl.vdzon.pvdd.dashboard` (niet in een eigen module). De afleiding is daarom
  gedeeld via de nieuwe enum `AdviceRefreshReason` in datzelfde pakket.
- `DashboardController` staat in `nl.vdzon.pvdd.dashboard.api`, niet rechtstreeks in `dashboard`; het
  nieuwe eindpunt staat daar naast de bestaande leesroutes.
- Bestaande codewaarden voor de reden van vernieuwing bestonden niet (zie hierboven), dus de codes
  `MANUAL_RETRY`, `CONTEXT_CHANGED` en `FIRST_ANALYSIS` uit de opdracht zijn gebruikt.
