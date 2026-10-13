# Acceptatie van meerdere vergaderingen

Deze controle gebruikt uitsluitend `https://pvdd-acceptance.vdzonsoftware.nl`, de toegewezen
synthetische testidentiteit en de daar gedeployde revision. De scenario's zijn testgegevens;
de parser, opslag, workflow, Runtime-v2-keten en schermen zijn de echte applicatie. De vaste
AI-antwoorden bewijzen verwerking en weergave, niet de kwaliteit of beschikbaarheid van een
live AI-provider. Productie, cluster- en databasecredentials zijn niet nodig voor deze tests.

## Startcontrole en sessie

Controleer `GET /api/version`: `environment=acceptance` en de verwachte `gitRevision`.
Log in via de ingestelde acceptatielogin, met een **JSON-body** (geen formulier) en zonder
redirects. Controleer de effectieve identiteit via `GET /api/auth/me`. Bewaar de sessie alleen
in de gebruikte browsercontext. Deel of fotografeer geen tokens, cookies of headers.

Alle mutaties onder `/api/` vereisen de waarde van cookie `pvdd_csrf` in header `X-CSRF-Token`.
`POST /api/meetings/check-now`, `POST /api/scenarios/reset-data` en
`POST /api/scenarios/scheduled-check` vereisen daarnaast een nieuwe `Idempotency-Key` per bewuste
actie, bijvoorbeeld een UUID. Bij opnieuw versturen na een verloren response blijft die
sleutel gelijk. Voor een tweede functionele controle is juist een nieuwe sleutel nodig;
anders wordt alleen het bewaarde antwoord van de eerste call teruggegeven.

De limiet is zes controles per minuut per identiteit. Houd minstens elf seconden tussen
controles, inclusief klikken op **Nu controleren**, **Opnieuw proberen** en het schedulerpad.
Bij HTTP 429 wacht de test minimaal 61 seconden en herhaalt dezelfde nog niet verwerkte
aanroep. Een verwachte begrenzing mag niet als defecte vergaderverwerking worden beschreven.

## Geïsoleerde scenario's

`POST /api/scenarios/reset` reset alleen de bronmock. Voor een schone applicatietoestand
bestaat `POST /api/scenarios/reset-data`: die verwijdert uitsluitend herkenbare synthetische
vergaderingen en hun afhankelijke testgegevens. Accounts, sessies, instellingen, beleid en
onbekende vergaderingen blijven behouden. Een actieve controle of analyse geeft HTTP 409:
wacht tot die gereed is en probeer dan opnieuw. Omzeil deze bescherming niet.
Het antwoord bevat `mode=synthetic-only`, `deletedMeetings` en `preservedMeetings`. Bij
`preservedMeetings>0` is een lege applicatie niet gegarandeerd; rapporteer die testvoorwaarde
en verwijder geen onbekende records.

Voor elk onafhankelijk scenario:

1. Wacht totdat lopende analyses een eindstatus hebben. Poll de lees-API; start geen nieuwe
   analyses om op voltooiing te wachten.
2. Gebruik `reset-data`, kies daarna `POST /api/scenarios/<naam>` en lees `GET /api/scenarios`.
3. Open of ververs de agenda. Klik **Nu controleren** en leg het resultaat én het werkelijk
   gerenderde scherm vast. Wacht ook op afgeronde adviezen voordat het conceptlabel wordt getest.
4. Controleer hetzelfde scenario op desktop en telefoon (bijvoorbeeld 1280×900 en 390×844).
   Een screenshot van alleen een API-antwoord bewijst geen schermgedrag.
5. Herhaal de controle met een nieuwe idempotentiesleutel. Zonder bronwijziging starten geen
   nieuwe analyses; alleen eerder geregistreerde afgeronde analyses blijven bestaan.

| Scenario | Vereist bewijs |
|---|---|
| `twee-vergaderingen` | Beide datums verwerkt; eigen agenda en stukken; kader met twee tegels; tegelwissel toont de juiste agenda; afgeronde adviezen met AI-conceptlabel. |
| `meer-dan-drie` | Alleen de eerste drie op datum verwerkt; vierde ontbreekt in `agendaFetches`; herhaling start nul analyses. |
| `grens-dag-10-en-11` | Venster begint bij de eerste vergadering: dag 10 hoort erbij, dag 11 niet; scherm toont de twee juiste datums. |
| `extra-agenda-niet-gepubliceerd` | Extra vergadering staat in het controleresultaat als agenda niet gepubliceerd, met nul analyses; ook bij herhaling geen opslag of analyse voor die vergadering. |
| `een-vergadering-mislukt` | Tweede agenda geeft een bronfout; eerste en derde worden wel verwerkt; fout per vergadering zichtbaar, desktop als tabel en mobiel als blokken. |
| `een-vergadering` | Na schone reset één agenda, zonder kader voor een reeks; adviezen blijven AI-concept. |
| `geen-toekomstige-vergadering` | Na schone reset een lege toestand met duidelijke uitleg; **Eerdere vergaderingen** opent het archief, ook wanneer dat nog leeg is. |

De scenario's gebruiken datums relatief aan de eerstvolgende kalenderdag in
`Europe/Amsterdam`; vergelijk dus met de datums uit het scenario-overzicht, niet met vaste
datums uit een oude testrun.

## Gehele controle mislukt, gegevens behouden

Dit is bewust een combinatie van twee scenario's **zonder reset ertussen**:

1. Start schoon met `twee-vergaderingen`, controleer en wacht op afgeronde adviezen. Bewaar
   de vergaderings- en advies-ID's als vergelijkingsbewijs en open de agenda in de browser.
2. Kies `gehele-broncontrole-mislukt`. Alleen de bronlijst wordt onbereikbaar; opgeslagen
   agenda's en adviezen blijven staan.
3. Klik **Nu controleren**. Controleer de duidelijke foutmelding en **Opnieuw proberen**,
   en verifieer dat dezelfde eerdere agenda en adviezen zichtbaar en leesbaar blijven.
4. Kies opnieuw `twee-vergaderingen` en klik **Opnieuw proberen** na de vereiste pauze.
   Controleer dat de fout verdwijnt en een nieuwe echte controle wordt uitgevoerd.
5. Leg fout en herstel op beide schermformaten vast. Begin het tweede schermformaat met
   een nieuwe schone uitvoering van deze combinatie als de eerste run de toestand al heeft gewijzigd.

## Dagelijkse controle zonder wachten tot morgen

`MeetingCheckSchedulerTest` controleert cron `0 0 5 * * *` met zone `Europe/Amsterdam`,
in winter, zomer en rond beide zomertijdwissels. Dit is geautomatiseerd bewijs voor het tijdstip,
geen claim dat tijdens de browsersessie de klok 05:00 bereikte.

De acceptatieroute `POST /api/scenarios/scheduled-check` roept de echte
`MeetingCheckScheduler.scheduledCheck()` aan. Gebruik dezelfde sessie, CSRF-header en
idempotentieregels als bij **Nu controleren**. Test dit op een schoon scenario met meerdere
vergaderingen en vergelijk de selectie en analyses met de handmatige knop. Het endpoint
geeft dezelfde resultaatbody als `check-now`, met headers `X-Meeting-Check-Cron` en
`X-Meeting-Check-Zone` voor de werkelijk gebruikte planning. Het
bestaat niet in productie; de productieklok en productiegegevens worden niet aangepast.

## Afgeronde AI-analyses

In acceptatie registreert de backend vóór elke nieuwe synthetische agendapuntanalyse een exact
passende Runtime-v2-fixture voor tenant `pvdd` en de jobsleutel. Promptupload, jobuitvoering,
schema-/inhoudsvalidatie en resultaatopslag lopen via de gewone keten. Een aparte backendcredential
voor testcontrol blijft op de server; de tester hoeft geen mocks te registreren of secrets te beheren.
De profielen dekken bronnotities, A/B- en C-adviezen en de technische selftest. Een afzonderlijke
beleidssynchronisatie maakt geen deel uit van deze scenario's.

Wacht op de eindstatus en controleer vervolgens het advies in de browser, inclusief het
AI-conceptlabel. `NO_MOCK_RESPONSE_CONFIGURED` is een fout in deze testvoorziening en geen
bewijs dat de functionele criteria gehaald zijn. Een eventuele nieuwe blokkade vermeldt de
concrete response, scenario en revision; ontbrekend bewijs wordt niet als geslaagd ingevuld.

## Reproduceerbare repositorytests

```sh
python3 -m unittest discover -s meeting-source-mock
mvn -B --no-transfer-progress -f backend/pom.xml clean verify
cd frontend && flutter analyze && flutter test
```

De backendintegratietests moeten echt op een tijdelijke PostgreSQL draaien; overgeslagen tests
gelden niet als databasebewijs. De browsercontrole gebeurt daarna op de gedeployde acceptatieversie.
