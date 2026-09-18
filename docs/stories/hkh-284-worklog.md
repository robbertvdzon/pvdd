# Worklog hkh-284 — Zonder toekomstige vergadering toont de startweergave de meest recente voorbije vergadering

## hkh-285 — development (2026-09-18)

### Doel
Zolang er een toekomstige vergadering is verandert de startweergave niet. Is die er niet, dan koos
`DashboardRepository.overview()` tot nu toe de **oudste** voorbije vergadering; dat wordt de **meest
recente**. Daarbij zegt de startweergave voortaan expliciet dat er nog geen nieuwe agenda is, dat de
getoonde vergadering al is geweest (met datum), en biedt zij een doorstap naar het overzicht van
eerdere vergaderingen. Acties die voor die voorbije vergadering nieuw werk zouden starten verdwijnen;
'Nu controleren' blijft ongewijzigd werken.

### Stap 0 — aannames geverifieerd in de checkout
1. **`past: Boolean` op `MeetingDto` bestond al** en wordt in `overview()` gevuld: `MEETING_SELECT`
   (gedeeld door `overview()` en `meeting(id)`) bevat `m.starts_at < CURRENT_TIMESTAMP AS past`. Er is
   dus niets aan het antwoordmodel toegevoegd, hernoemd of verwijderd.
2. **De alleen-lezen vlag bestond al**: `MeetingOverviewPage.readOnly` en `_AgendaItemCard.readOnly`
   uit hkh-263. Die is hergebruikt; er is geen kopie van het agendascherm gemaakt.
3. **De navigatie naar het archiefoverzicht bestond al**: `MeetingOverviewPage.onOpenArchive`, dat in
   `TechnicalApplicationShell` naar `/archief` navigeert (hkh-270). Die callback is hergebruikt; er is
   geen route bijgekomen, niet in `DashboardController` en niet in `main.dart`.
4. **Beide testseams bestonden al**: `DatabaseIntegrationTest` draait tegen een echte PostgreSQL en
   heeft `seedMeetingWithAgendaItem(startsAt)` / `seedMeetingWithAdvice(startsAt)` met instelbaar
   `starts_at`; `frontend/test/support/fakes.dart` bevat de injecteerbare `FakeDashboardGateway` met
   het veld `past` en een `calls`-registratie. De fake volstond ongewijzigd; de backendtestopzet is
   uitgebreid met drie helpers (zie Testvoorzieningen). Er is geen seam opnieuw gebouwd.

Er is dus **geen afwijking** van de aannames uit de story.

### Gerealiseerd — backend (module `dashboard`)
- Uitsluitend de sorteerclausule van `DashboardRepository.overview()` is gewijzigd:

  ```sql
  ORDER BY CASE WHEN m.starts_at >= CURRENT_TIMESTAMP THEN 0 ELSE 1 END,
           CASE WHEN m.starts_at >= CURRENT_TIMESTAMP THEN m.starts_at END ASC,
           m.starts_at DESC
  LIMIT 1
  ```

  De eerste sleutel scheidt toekomst van verleden, de tweede kiest binnen de toekomst de vroegste (bij
  voorbije rijen is zij `NULL` en dus zonder betekenis), de derde kiest binnen het verleden de laatste.
  `LIMIT 1` is ongewijzigd.
- `MEETING_SELECT`, `meetingRow()`, `meeting(id)`, `progress()`, `canRetryAnalysis` en het
  antwoordmodel zijn ongewijzigd. Zonder enige vergadering blijft het antwoord
  `MeetingOverviewDto("NO_MEETING", null, null, ProgressDto(0, 0, 0))`.
- Grensgeval: `starts_at == CURRENT_TIMESTAMP` valt in de toekomsttak, gelijk aan de bestaande
  `past`-berekening (`<`), dus `past = false` en er verschijnt geen melding.
- Niet gewijzigd: `DashboardController`, `MeetingDiscoveryService`, `AnalysisFacade`,
  `ProductionReadAccessFilter`. Geen Flyway-migratie, geen index, geen route, geen AI-aanroep.

### Gerealiseerd — webapp (`frontend/lib/meeting_overview.dart`)
- Toont de startweergave een vergadering met `past == true`, dan staat **boven de vergaderkaart** de
  nieuwe melding `_noNewAgendaNotice` met alle vier de gevraagde elementen: de kop *"Er is nog geen
  nieuwe agenda"*, de uitleg *"Je ziet de meest recente vergadering, en die is al geweest:
  &lt;datum&gt;."*, de vergaderdatum in de bestaande lange Nederlandse notatie, en *"Zodra de provincie
  een nieuwe agenda publiceert, staat die hier."*
- In die melding staat de doorstap **Alle eerdere vergaderingen**, die `widget.onOpenArchive`
  aanroept — dezelfde callback als de bestaande knop 'Eerdere vergaderingen' in de kopregel, dus in
  één handeling naar `/archief`. De bestaande kopregelknop is ongewijzigd gebleven en blijft
  zichtbaar (conform aanname 5 van de story).
- Op de vergaderkaart staat naast de bestaande statusbadges de markering **AL GEWEEST**, via de
  bestaande `_statusChip`/`_statusLabel` met de nieuwe code `MEETING_PAST`.
- Melding en markering hangen uitsluitend aan het antwoordveld `past`, nooit aan de browserklok.
- De agendapuntkaarten krijgen bij een voorbije vergadering `readOnly: widget.readOnly ||
  meeting.past`. Daarmee vervalt de herstartactie ('Opnieuw proberen') via precies dezelfde vlag als
  het archiefscherm. Op vergaderniveau bood de webapp al geen analyseaanvraag aan, dus daar hoefde
  niets te vervallen.
- 'Nu controleren' blijft zichtbaar en ingeschakeld, en de ververstimer van de startweergave blijft
  lopen (de timer staat alleen uit op het archiefdetail, `archivedMeetingId != null`).
- Bij `past == false` verandert er niets: geen melding, geen markering, alle bestaande acties
  ongewijzigd. Bij `NO_MEETING` blijft de bestaande lege melding *"Er is nog geen toekomstige
  vergadering gevonden."* alleen staan.
- Desktop en mobiel volgen de bestaande shell. Binnen de melding loopt de doorstap onder 600 logische
  pixels over de volle breedte mee met de knoppen erboven; daarboven houdt hij zijn eigen breedte.
  Geen inklapper, geen tooltip, geen afgekorte tekst.
- Het archiefdetail (`readOnly`) krijgt de nieuwe melding bewust **niet**: daar staat al de bestaande
  markering *"Deze vergadering is al geweest — &lt;datum&gt;"* in `_archiveHeader`.
- De private `_longDate` is publiek gemaakt als `meetingLongDate`, in dezelfde lijn als de bestaande
  `adviceVersionDate`/`adviceVersionShortDate`, zodat de widgettests de datum via dezelfde helper
  asserteren als de weergave gebruikt. De opmaak zelf is ongewijzigd; er is geen nieuwe opmaakhulp
  bijgekomen.

### Testvoorzieningen (voor de tester)
- `frontend/test/support/fakes.dart` — de bestaande `FakeDashboardGateway` volstond: `past`,
  `startsAt`, `title`, `failedAnalysis`, `calls` en `startingCalls` bestonden al en `overview()` geeft
  `past` al mee. **Niet gewijzigd**, zodat geen enkele bestaande widgettest van gedrag verandert.
- `backend/src/test/kotlin/nl/vdzon/pvdd/DatabaseIntegrationTest.kt` — drie nieuwe gedeelde helpers,
  naast de bestaande zaaihelpers:
  - `inRolledBackTransaction { ... }` — voert een test uit in één transactie die daarna altijd wordt
    teruggedraaid. Zo kan een test de volledige `meeting`-tabel naar zijn hand zetten zonder de
    gegevens van andere tests aan te tasten, en ziet de reconcile-scheduler op zijn eigen verbinding
    niets van de gezaaide rijen. Extra winst: `CURRENT_TIMESTAMP` ligt binnen één transactie vast,
    waardoor het grensgeval `starts_at == nu` überhaupt aantoonbaar is.
  - `parkExistingMeetings(at)` — schuift álle bewaarde vergaderingen naar één tijdstip, zodat alleen
    de zelf gezaaide vergaderingen de keuze van `overview()` kunnen bepalen.
  - `removeAllMeetings()` — leegt de `meeting`-tabel in foreign-keyvolgorde via het bestaande
    `removeSyntheticMeeting`; alleen zinvol binnen `inRolledBackTransaction`.
- Geen AI-mock en geen externe koppeling nodig; `PVDD__ACCEPTANCE_AGENT_TOKEN` is voor deze story niet
  gebruikt.

### Reproduceerbare commando's voor de tester
- Backend: `cd backend && mvn -B --no-transfer-progress clean verify`.
  Zonder Docker en zonder `PVDD_TEST_DATABASE_URL` wordt `DatabaseIntegrationTest` (inclusief de vijf
  nieuwe tests) overgeslagen; de overige tests draaien volledig. Voor het rijniveaubewijs hoort een
  **verse, lege** PostgreSQL meegegeven te worden via `PVDD_TEST_DATABASE_URL`,
  `PVDD_TEST_DATABASE_USER` en `PVDD_TEST_DATABASE_PASSWORD` (zie `docs/factory/development.md`).
- Alleen de databasetests: `cd backend && mvn -B -Dtest=DatabaseIntegrationTest
  -DfailIfNoSpecifiedTests=false test` (met dezelfde omgevingsvariabelen).
- Frontend: `cd frontend && flutter analyze && flutter test`.
- Alleen de nieuwe widgettests: `cd frontend && flutter test test/start_view_past_meeting_test.dart`.

### Tests per acceptatiecriterium
Backend — `DatabaseIntegrationTest`, vijf nieuwe tests (alle binnen `inRolledBackTransaction`):

| AC | Test |
| --- | --- |
| 1 (mix) + 8 | `the overview keeps choosing the nearest upcoming meeting` — nu +1 dag, nu +10 dagen en één voorbije; bewijs: de vergadering van nu +1 dag, `past == false`, `progress == ProgressDto(1, 1, 0)` |
| 1 (alleen toekomst) | `with only upcoming meetings the overview picks the earliest` — nu +2, +5 en +20 dagen; bewijs: de vroegste |
| 2 + 8 | `without an upcoming meeting the overview falls back to the most recent past one` — nu −1 dag, −30 dagen, −90 dagen; bewijs: die van nu −1 dag (niet de oudste), `past == true`, `progress == ProgressDto(1, 1, 0)` |
| Grensgeval | `a meeting that starts exactly now still counts as upcoming` — `starts_at = CURRENT_TIMESTAMP` binnen dezelfde transactie; bewijs: die vergadering wint van een voorbije en heeft `past == false` |
| 3 | `without any stored meeting the overview keeps its empty answer` — lege `meeting`-tabel; bewijs: `status == "NO_MEETING"`, `meeting == null`, `lastCheckedAt == null`, `progress == ProgressDto(0, 0, 0)` |

Webapp — `frontend/test/start_view_past_meeting_test.dart`, acht nieuwe widgettests op de fake
gateway (geen server, geen netwerk, geen klok-afhankelijkheid):

| AC | Test |
| --- | --- |
| 4 | `a past meeting is announced with its date above the meeting card` — kop, uitlegtekst, vergaderdatum (via `meetingLongDate`, geen hardgecodeerde string), de mededeling over een nieuwe agenda, de badge `AL GEWEEST`, en de positiecontrole dat de melding bóven de vergaderkaart staat; plus de onveranderde voortgangstelling `1/3 analyses gereed` |
| 5 | `check now stays available and performs exactly one check` — 'Nu controleren' aanwezig en ingeschakeld, één tik levert precies één `checkNow` op de fake gateway plus de gebruikelijke bevestiging |
| 6 | `offers no action that starts new work and only reads` — met `failedAnalysis` (dus `canRetryAnalysis`) ontbreekt 'Opnieuw proberen' zowel ingeklapt als uitgeklapt, en de volledige aanroeplijst is exact `['overview', 'agendaItems:meeting-id', 'agendaItem:item-a', 'adviceVersions:item-a']` met `startingCalls` leeg |
| 7 | `the notice steps through to the earlier meetings in one action` — één tik op 'Alle eerdere vergaderingen' levert `navigated == ['/archief']`, het archiefoverzicht verschijnt en de gateway leest `pastMeetings:first` |
| 4/8 negatief | `an upcoming meeting leaves the start view unchanged` — zelfde vergaderdatum, alleen `past == false`: geen melding, geen doorstap, geen markering, en 'Nu controleren', 'Eerdere vergaderingen' en 'Opnieuw proberen' staan er onveranderd |
| 3 in de UI | `without any meeting the existing empty notice stays alone` — `NO_MEETING` toont de bestaande lege melding en niets van de nieuwe |
| 9 | `stays usable and complete at 360 pixels` en `stays usable and complete at 1400 pixels` — melding, volledige uitleg, markering, 'Nu controleren' en de doorstap in beide gevallen gevonden, geen overflow-exceptie, en de doorstap loopt op 360 px over de volle breedte terwijl hij op 1400 px zijn eigen breedte houdt |

AC 10 is op de branchdiff te controleren: geen bestand onder `backend/src/main/resources/db/migration`,
geen wijziging in `DashboardController` of `main.dart`, geen nieuwe AI-aanroep. De gewijzigde bestanden
zijn precies vier: `DashboardRepository.kt`, `DatabaseIntegrationTest.kt`, `meeting_overview.dart` en
het nieuwe `start_view_past_meeting_test.dart` (plus dit worklog).
AC 11 wordt gedekt door de drie keuzescenario's hierboven.

### Aangepaste bestaande verwachtingen
Geen. Alle bestaande backend- en widgettests — inclusief `archive_page_test.dart`,
`archive_overview_test.dart`, `advice_versions_test.dart`, `ModulithArchitectureTest` en
`ProductionReadAccessFilterTest` — zijn ongewijzigd groen gebleven.

### Bewijs en beperking
Er is geen previewtemplate en geen preview-URL; al het bewijs vóór merge komt uit geautomatiseerde
tests op de storybranch. Zelf gedraaid in deze ronde:
- `mvn -B --no-transfer-progress clean verify` in `backend/` tegen een echte PostgreSQL (rootloos
  gestart, meegegeven via `PVDD_TEST_DATABASE_URL`), zodat `DatabaseIntegrationTest` volledig
  meedraaide inclusief de vijf nieuwe tests;
- `flutter analyze` en `flutter test` in `frontend/`;
- `bash frontend/build-web.sh`, `bash tools/test-frontend-cache.sh` en
  `bash tools/verify-documentation.sh`.

Uitkomsten: backend `BUILD SUCCESS` met 145 tests, 0 failures, 0 errors en 1 skipped (alleen
`LiveSourceSpikeTest`); `DatabaseIntegrationTest` draaide met 24 tests (was 19), 0 overgeslagen.
Frontend `No issues found!` en 68 tests groen (was 60), waarvan 8 nieuw. De cachecontract-controle
liep in de statische tak, omdat er in deze runtime geen Docker is.

**Mutatiecontrole op de sorteerwijziging.** Met de oude clausule
(`ORDER BY CASE WHEN starts_at >= CURRENT_TIMESTAMP THEN 0 ELSE 1 END, starts_at ASC`) teruggezet en
de rest van de branch ongewijzigd, faalt precies één test — *without an upcoming meeting the overview
falls back to the most recent past one* — en geen andere. De nieuwe test bewijst dus echt de
gecorrigeerde fallback; de andere vier keuzetests zijn regressiebewaking op ongewijzigd gedrag.
(Let op bij het nadraaien: de suite eist een **verse, lege** database per run, dus geef voor elke run
een nieuwe lege database mee.)

Beperking, en dit is geen bevinding: de acceptatieomgeving volgt `main` en bevat deze wijziging pas ná
merge. Bovendien bevat de acceptatiedataset per definitie geen voorbije vergadering — de
meeting-source-mock levert uitsluitend 31 december 2035 en `MeetingDiscoveryService.discover()`
filtert kandidaten met `!it.date.isBefore(today)` en slaat agenda's met `startsAt <= now` over. Een
visuele controle op acceptatie is dus alleen mogelijk op een moment dat er feitelijk geen toekomstige
vergadering bewaard is; is die er wel, dan is het verwachte resultaat de ongewijzigde startweergave en
is het scenario niet reproduceerbaar. Zo'n controle is nooit een merge-poort.

## hkh-288 — documentation (2026-09-18)

### Doel
De documentatie in lijn brengen met de storydiff van `ai/hkh-284`: `DashboardRepository.overview()`
kiest zonder toekomstige vergadering voortaan de **meest recente** voorbije vergadering in plaats
van de oudste, en de startweergave meldt dat expliciet, markeert de vergadering als 'al geweest',
biedt een doorstap naar het overzicht van eerdere vergaderingen en biedt de startacties voor die
vergadering niet aan, terwijl 'Nu controleren' blijft werken.

### Bijgewerkt
- `docs/microservice-specificatie.md`
  - §4.1 (hoofdflow, stap 2): toegevoegd dat de app zonder toekomstige vergadering de vergadering
    met het meest recente begintijdstip uit het verleden toont, met de melding dat er nog geen
    nieuwe agenda is.
  - §5.2 (schermlijst, scherm 2 **Overzicht**): dezelfde terugval plus de melding "Er is nog geen
    nieuwe agenda" benoemd.
  - §5.2 (nieuwe alinea direct na de schermlijst): de volledige regel van de terugval —
    sorteerkeuze, de melding met kop, uitleg en vergaderdatum boven de vergaderkaart, de doorstap
    **Alle eerdere vergaderingen** naar `/archief`, de markering **AL GEWEEST**, de herkomst uit
    het antwoordveld `past` (niet de browserklok), het ongewijzigd blijven van 'Nu controleren' en
    de 15-secondenverversing, het niet aanbieden van de startacties via dezelfde alleen-lezen vlag
    als het terugkijkscherm, en het ongewijzigde gedrag bij een toekomstige vergadering en bij
    `NO_MEETING`, inclusief het gestapelde gedrag op een smal scherm.
  - §6.3 (routetabel): de omschrijving van `GET /api/meetings/next` vermeldt nu dat de route de
    vergadering van de startweergave levert — eerstvolgende toekomstige, anders de meest recente
    voorbije.
  - §6.3 (nieuwe alinea vóór de `past`-alinea): de keuzeregel met de drie sorteersleutels, het
    grensgeval `starts_at` gelijk aan nu, het ongewijzigde `NO_MEETING`-antwoord met telling 0/0/0
    en de vaststelling dat er geen route, index of migratie bij komt.
  - §13.1 (backendtests): bullet toegevoegd voor de vijf keuzescenario's van de startroute,
    inclusief de assertie op de voortgangstelling.
  - §13.2 (frontendtests): bullet toegevoegd voor de startweergave zonder toekomstige vergadering
    (melding, markering, 'Nu controleren', ontbrekende startacties, doorstap, twee
    vensterbreedtes) met de regressies op het toekomstgeval en de lege toestand.
- `docs/functionele-werking-commissie-assistent.md`
  - §11 (opsomming webapp, **Agenda**): de bullet noemt nu ook de meest recente voorbije
    vergadering zolang er geen toekomstige is.
  - §11 (nieuwe alinea vóór het blok over **Eerdere vergaderingen**): de gebruikersuitleg van de
    terugval — melding met datum, knop **Alle eerdere vergaderingen**, markering **AL GEWEEST**,
    werkende **Nu controleren**, de niet aangeboden start- en herstartacties, de ongewijzigde lege
    melding en het gedrag op een smal scherm.
- `docs/factory/functional-spec.md`
  - 'Gebruikersflow': terugval op de meest recente voorbije vergadering toegevoegd.
  - 'Frontend en API' (nieuwe alinea vóór de `GET /api/meetings/{id}`-alinea): de sorteerclausule
    van `/api/meetings/next`, het ongewijzigde `NO_MEETING`-antwoord, de melding met doorstap en
    badge bij `past = true`, het ongewijzigde gedrag bij `past = false`, en 'geen migratie, geen
    nieuwe route, geen extra AI-aanroep'.
- `docs/stories/hkh-284-worklog.md`: deze sectie.

### Bewust niet gewijzigd
- `README.md` — beschrijft de repo-indeling en het lokaal starten; niets daarvan is geraakt.
- `docs/agent-access.md` — er komt geen route bij en `ProductionReadAccessFilter` is ongewijzigd,
  dus het leestoken gedraagt zich onveranderd; de bestaande tekst klopt nog.
- `docs/factory/technical-spec.md`, `docs/factory/development.md`, `docs/factory/deployment.md`,
  `docs/factory/secrets-local.md`, `docs/factory/agent-runtime.md` — stack, testopzet en
  uitrolketen zijn niet geraakt; de drie nieuwe testhelpers in `DatabaseIntegrationTest` draaien
  binnen de al gedocumenteerde databasetestopzet en veranderen de aanroepwijze niet.
- `docs/stappenplannen/*` — uitvoeringsplannen per fase; die beschrijven geen huidig gedrag.
- `docs/operations.md`, `docs/adr/*`, `deploy/README.md` — geen migratie, geen index, geen
  configuratie- of uitrolwijziging.
- `docs/functional-acceptance-verification.md`, `docs/source-revision-verification.md`,
  `docs/technical-baseline-verification.md`, `docs/production-source-spike.md` — vastgelegde
  verificatierapporten van een eerder moment; die worden niet met terugwerkende kracht
  herschreven.
- `docs/uitbreidingsspecificatie-standpunten-en-ai-inzicht.md` — gaat over standpunten en
  AI-inzicht; niet geraakt.
- De datumregels bovenaan bestaande documenten ('Laatste actualisatie', 'Datum'/'Status') zijn
  conform de bestaande conventie niet bijgewerkt.

### Verificatie
- `bash tools/verify-documentation.sh` → `Documentatie en repositoryhygiëne zijn in orde.`
  (exitcode 0)
- `git status --short` toont uitsluitend wijzigingen onder `docs/`; geen productiecode, tests of
  infrastructuur geraakt.
