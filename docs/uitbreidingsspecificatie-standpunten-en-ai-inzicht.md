# Uitbreidingsspecificatie — actuele standpunten en AI-inzicht

Status: concept 0.2

Datum: 1 september 2026

Relatie:

- bouwt voort op de [normatieve microservicespecificatie](microservice-specificatie.md);
- gebruikt de bronrevisies uit
  [stappenplan 4](stappenplannen/04-bronrevisies-en-heranalyse.md);
- verandert de bestaande read-only aard van de applicatie niet.

Uitvoeringsvolgorde: implementeer **T0 — tooling-sessie** hieronder als eerste, vóór de overige
onderdelen van deze uitbreiding. Dit maakt frontend- en API-verificatie van alle vervolgstappen
mogelijk zonder interactieve Google-login. T0 verfijnt uitsluitend voor deze begrensde
tooling-ingang het eerdere besluit in de microservicespecificatie dat productie geen algemene
API-key-backdoor heeft; Google blijft de normale gebruikersauthenticatie en het tooling-token is
geen rechtstreeks autorisatietoken voor functionele routes.

## T0 — Als eerste: tooling-sessie voor frontend- en REST-verificatie

### Doel en werking

Productie krijgt, naar het patroon van Product Factory, één aparte ingang waarmee een bevoegde
tooling-agent een normale PvdD-gebruikerssessie kan starten zonder een interactieve
Google OAuth-flow:

```text
PVDD_TOOLING_TOKEN
        ↓
POST /api/auth/tooling-session
        ↓
normale HttpOnly-sessiecookie + CSRF-token
        ↓
dezelfde frontend en REST-API als de toegestane tooling-identiteit
```

De tooling-agent kan hierdoor in één browsercontext de productiefrontend openen, gegevens
controleren en dezelfde backend-REST-calls uitvoeren als de normale frontend. Schrijvende calls
vereisen ongewijzigd de geldige sessiecookie én CSRF-token. Functionele API-routes accepteren het
tooling-token zelf nooit als Bearer-token of alternatieve header; alleen het bootstrapendpoint mag
het token verwerken.

### Contract

1. Voeg `POST /api/auth/tooling-session` toe. Het endpoint verwacht het geheim uitsluitend in
   `X-PVDD-Tooling-Token`, nooit in URL, queryparameter, requestbody of cookie.
2. Het endpoint is alleen actief wanneer tooling-auth expliciet is ingeschakeld én een niet-leeg
   token is geconfigureerd. Ontbrekende configuratie, header of een fout token faalt gesloten met
   dezelfde generieke `401`-respons.
3. Vergelijk tokens in constante tijd. Log nooit het token, de header of een afgeleide waarde.
4. De aangevraagde tooling-identiteit moet al in de bestaande productie-allowlist staan. Een
   niet-toegestane identiteit krijgt geen sessie; tooling-auth introduceert geen aparte rol of
   ruimere rechten.
5. Een geslaagde bootstrap maakt via de bestaande sessieservice exact hetzelfde type sessiecookie
   en CSRF-token als Google-login. Sessieduur, intrekking, cookieflags en auditregels blijven gelijk.
6. Voeg een strikte rate limit en een audit-event met alleen resultaat, tijdstip en toegestane
   identiteit toe. Toon of retourneer geen tokenmateriaal.
7. De route moet vanuit browserautomatisering in dezelfde browsercontext aanroepbaar zijn, zodat de
   `Set-Cookie`-respons daarna direct voor frontendnavigatie en API-calls geldt. CORS en
   cookie-instellingen worden hierop getest zonder de origin-allowlist te verbreden.
8. De bestaande Google-login blijft voor menselijke productiegebruikers ongewijzigd. Acceptance
   blijft de reeds gespecificeerde auth-vrije testeromgeving en heeft geen tooling-token nodig.

### Secretbeheer

- De platte productiewaarde staat uitsluitend lokaal in de al door Git genegeerde rootfile
  `secrets.env`, onder `PVDD_PRODUCTION_TOOLING_TOKEN`. De applicatie ontvangt hem in OpenShift als
  `PVDD_TOOLING_TOKEN` via het bestaande sealproces.
- `secrets.env`, tokenwaarden en tijdelijke plaintext Secret-manifests worden nooit gecommit,
  gelogd, in prompts opgenomen of naar de frontendbuild gekopieerd. Alleen het door het bestaande
  proces versleutelde productie-SealedSecret mag in Git staan.
- `secrets.env.example` mag uitsluitend de lege sleutel
  `PVDD_PRODUCTION_TOOLING_TOKEN=` documenteren.
- Initialisatie- en sealscripts lezen het env-bestand als data, tonen het token niet op stdout en
  gebruiken tijdelijke bestanden met beperkte rechten en gegarandeerde cleanup.
- Het tooling-token is een nieuw willekeurig geheim en wordt niet hergebruikt voor Agent Runtime,
  Google, database of andere applicaties. Rotatie trekt nieuwe bootstrappogingen met het oude token
  direct in; reeds uitgegeven sessies volgen de bestaande sessie-intrekkingsregels.

### Acceptatie T0

- [ ] Dit onderdeel is geïmplementeerd en geverifieerd voordat werk aan een ander onderdeel uit dit
      document begint.
- [ ] Een geautoriseerde tooling-agent kan zonder Google-login een sessie in een browsercontext
      starten, de productiefrontend laden en beschermde GET-routes aanroepen.
- [ ] Een schrijvende REST-call werkt alleen met zowel de sessiecookie als de bijbehorende
      CSRF-token; alleen `PVDD_TOOLING_TOKEN` meesturen werkt niet op functionele routes.
- [ ] Ontbrekend, leeg of fout token, een identiteit buiten de allowlist en uitgeschakelde
      tooling-auth geven geen sessie en lekken geen onderscheidende details.
- [ ] Tests bewijzen dat tokenvergelijking constant-time is, rate limiting werkt en tokenwaarden
      niet in response, logs, metrics, database of frontend terechtkomen.
- [ ] Een repositoryscan bewijst dat de plaintext tokenwaarde en `secrets.env` niet in Git staan.
- [ ] Production gebruikt Google én de begrensde tooling-bootstrap; acceptance blijft zonder
      authenticatie bruikbaar en bevat geen productie-toolingtoken.

## 1. Doel

Deze uitbreiding maakt drie soorten actualiteit zichtbaar:

1. welke actuele PvdD-standpunten uit officiële bronnen beschikbaar zijn en wanneer die voor het
   laatst zijn gecontroleerd;
2. welke AI-runs wachten, lopen of recent zijn afgerond;
3. welke bronwijziging en AI-analyse bij ieder agendapunt horen.

De applicatie haalt maandelijks en op handmatig verzoek officiële PvdD-bronnen op, bewaart iedere
bronversie en bouwt daaruit een gecontroleerd standpuntenoverzicht met klikbare referenties. Een
nieuwe beleidsversie kan bestaande agenda-adviezen verouderd maken en gericht opnieuw laten
analyseren.

## 2. Harde productbesluiten

1. De applicatie gebruikt alleen expliciet toegestane officiële PvdD-bronnen; de AI zoekt niet vrij
   op het web.
2. Het verkiezingsprogramma blijft de politieke basis. Actuelere provinciale standpunten vullen het
   aan, maar overschrijven het programma niet stilzwijgend.
3. Tegenstrijdige bronnen worden zichtbaar als spanning of wijziging, met datum en referenties. De
   applicatie kiest niet zonder uitleg één bron als waarheid.
4. Iedere opgeslagen bron, positie en analyse is gekoppeld aan een inhoudshash en versie.
5. Een maandelijkse controle zonder inhoudelijke wijziging maakt geen nieuwe AI-run.
6. Een gewijzigde beleidsbron veroorzaakt alleen heranalyse van actuele agendapunten waarvan de
   geselecteerde beleidscontext of beleidsfingerprint verandert.
7. Een voorlopige agenda of een nieuw C-stuk mag direct worden geanalyseerd. Wijzigt het punt of een
   bijbehorend stuk later, dan wordt alleen het geraakte advies opnieuw gemaakt.
8. Een AI-titel en korte conclusie zijn hulpmiddelen. De officiële brontitel blijft altijd zichtbaar
   en herleidbaar.
9. De interface toont nooit tokens, volledige prompts, interne foutdetails of onbegrensde
   broninhoud.

## 3. Navigatie en pagina-indeling

Na login bevat de applicatieshell drie hoofdsecties:

- **Agenda** — huidige vergadering en agendapunten;
- **Standpunten** — actuele, versieerbare PvdD-standpunten;
- **AI-runs** — actieve en recent afgeronde AI-verwerking.

Op brede schermen mogen dit navigatie-items bovenin of in een zijbalk zijn. Op kleine schermen
wordt dezelfde informatiewaarde via een compact menu aangeboden. De actuele sectie is in de URL
herkenbaar en blijft na herladen geselecteerd.

Voorgestelde routes:

- `/agenda`;
- `/standpunten`;
- `/ai-runs`.

## 4. Module actuele PvdD-standpunten

### 4.1 Bronnen

Productie leest uitsluitend HTTPS onder `noordholland.partijvoordedieren.nl` en het bestaande
officiële programma-PDF onder `assets.partijvoordedieren.nl`.

Bronsoorten, in aflopende institutionele betekenis maar zonder stilzwijgende overschrijving:

1. **Verkiezingsprogramma** — het geldende provinciale programma, momenteel 2023–2027;
2. **Idealen en standpunten** — officiële pagina's onder `/onze-idealen/` en `/standpunten/`;
3. **Provinciaal politiek werk** — moties, amendementen, schriftelijke vragen en expliciete
   provinciale position papers;
4. **Nieuws met een expliciet standpunt** — alleen artikelen waarin de Noord-Hollandse fractie een
   concrete beleidspositie inneemt; organisatorisch nieuws en campagneoproepen gelden niet als
   zelfstandig standpunt.

De crawler gebruikt geconfigureerde startpagina's en, indien bruikbaar, de officiële sitemap. Hij
volgt alleen canonieke URL's op de allowlist en nooit willekeurige externe links uit een pagina.

### 4.2 Planning en handmatige controle

De automatische controle draait op de eerste dag van iedere maand om 03:30 uur in
`Europe/Amsterdam`. De klok is injecteerbaar voor tests.

De pagina bevat daarnaast **Standpunten nu actualiseren**. Automatisch en handmatig gebruiken exact
dezelfde duurzame workflow, lock, grenzen en idempotentie. Een tweede gelijktijdig verzoek meldt dat
al een controle bezig is en start geen tweede run.

De pagina toont:

- laatste succesvolle controle;
- laatste poging en eventuele veilige foutmelding;
- eerstvolgende geplande controle;
- actieve bron-/snapshotversie;
- aantallen nieuwe, gewijzigde, verdwenen en ongewijzigde bronnen;
- status en looptijd wanneer een controle bezig is.

### 4.3 Ophalen en normaliseren

Per bronpagina of PDF bewaart de module minimaal:

- canonieke URL;
- bronsoort;
- titel;
- publicatie- en wijzigingsdatum indien betrouwbaar aanwezig;
- ophaaltijd;
- HTTP `ETag` en `Last-Modified` indien aanwezig;
- inhoudstype, grootte en SHA-256;
- geëxtraheerde tekst per sectie of pagina;
- bronstatus actueel, vervangen, verdwenen, mislukt of afgewezen.

HTML-extractie gebruikt alleen de hoofdinhoud en verwijdert navigatie, formulieren, scripts,
tracking en herhaalde sitechrome. PDF-extractie behoudt paginanummers. Alle broninhoud is
onbetrouwbare data en kan geen instructies aan de applicatie of AI geven.

Grenzen:

- herkenbare `User-Agent` en respectvolle requestfrequentie;
- maximaal 250 pagina's per synchronisatie;
- maximaal 5 MiB per HTML-pagina, 25 MiB per PDF en 150 MiB totaal;
- begrensde redirects, connect-/read-time-outs en retries;
- geen login, cookies, formulieren of browserautomatisering;
- respecteer `robots.txt` voor crawlpaden die niet expliciet als primaire bron zijn geconfigureerd.

### 4.4 Standpunten afleiden

Alleen nieuwe of inhoudelijk gewijzigde bronnen gaan naar Agent Runtime. De AI levert strikt
gevalideerde JSON met per positie:

- korte titel;
- samenvatting van maximaal 400 tekens;
- één of meer thema's;
- concrete beleidsrichting;
- bron-ID's en pagina/sectie;
- brondatum;
- indicatie `BASELINE`, `CURRENT_POSITION` of `POLITICAL_WORK`;
- eventuele spanning met een eerder of fundamenteler standpunt.

Een positie zonder geldige lokale bronreferentie wordt geweigerd. De applicatie combineert alleen
posities die semantisch en op bronbasis aantoonbaar bij elkaar horen. Bronversies blijven
afzonderlijk raadpleegbaar.

### 4.5 Atomair publiceren

Een synchronisatie bouwt eerst een kandidaatsnapshot. Pas wanneer alle vereiste bronnen veilig zijn
opgehaald, geëxtraheerd en gevalideerd, wordt die snapshot in één transactie actief.

Bij gedeeltelijke of volledige mislukking:

- blijft de vorige succesvolle snapshot actief;
- worden bestaande agenda-adviezen niet ongeldig gemaakt;
- toont de standpuntenpagina dat actualiteit niet kon worden bevestigd;
- blijft de run veilig hervatbaar.

Wanneer een nieuwe snapshot actief wordt, berekent de applicatie voor ieder toekomstig, niet
ingetrokken agendapunt opnieuw de geselecteerde beleidscontext. Alleen wanneer die selectie of haar
inhoudshash verandert, wordt het bestaande advies `STALE` en ontstaat een nieuwe idempotente
analyse-run.

### 4.6 Standpuntenpagina

De sectie **Standpunten** toont bovenaan synchronisatiestatus en bronversie. Daaronder staan kaarten
met:

- titel;
- korte samenvatting;
- thema's;
- status actueel, gewijzigd, mogelijk tegenstrijdig of vervallen;
- datum van de nieuwste onderliggende bron;
- laatste gedetecteerde inhoudelijke wijziging;
- badges voor programma, standpuntpagina, politiek werk en nieuws;
- één of meer klikbare referenties naar de officiële webpagina of programmapagina;
- bij programma-PDF's het paginanummer;
- een uitklapbare lijst van eerdere bronversies.

De gebruiker kan zoeken en filteren op thema en bronsoort. Sortering is standaard op thema en
daarbinnen op titel; **Recent gewijzigd** is een alternatieve sortering.

## 5. AI-runs-pagina

### 5.1 Logische run in plaats van technische subjobs

De pagina toont logische gebruikersruns. Een grote analyse met meerdere bronnotitiejobs en een
synthese verschijnt als één hoofdregel met optioneel uitklapbare fasen. Hierdoor ziet de gebruiker
niet onnodig tientallen interne jobs.

Runsoorten:

- agenda-advies;
- heranalyse na agenda- of documentwijziging;
- heranalyse na beleidswijziging;
- standpuntenextractie;
- standpuntensynthese;
- technische acceptance-run, uitsluitend herkenbaar in acceptance.

### 5.2 Actieve runs

Bovenaan staat **Nu bezig** met alle logische runs in `PENDING`, `QUEUED`, `WAITING_FOR_WORKER` of
`RUNNING`.

Per run:

- begrijpelijke titel, bijvoorbeeld “C-stuk Fietsersbond opnieuw analyseren”;
- korte, deterministisch opgebouwde uitleg waarom de run bestaat;
- runsoort;
- gekoppeld agendapunt of standpunt;
- status in gewone taal;
- gestart/aangemaakt op;
- live oplopende wachttijd of looptijd;
- aantal fasen en voortgang indien van toepassing;
- veilige fout-/waarschuwingsstatus wanneer de Runtime tijdelijk niet bereikbaar is.

`PENDING` en `QUEUED` tonen “wacht sinds”; `RUNNING` toont “loopt sinds”. De duur wordt in de
frontend afgeleid uit servertimestamps en de huidige tijd en vereist geen schrijvende heartbeat.

### 5.3 Afgeronde runs

Onder **Afgerond** verschijnen aanvankelijk de tien nieuwste logische runs met status `SUCCEEDED`,
`FAILED` of `CANCELLED`.

Per run:

- titel en doel;
- eindstatus;
- start- en eindtijd;
- totale duur;
- gekoppeld agendapunt of standpunt;
- model/provider en prompt-/selectieversie in een uitklapbaar technisch detail;
- bij fouten alleen een veilige foutcode en gebruikersuitleg.

De knop **Meer laden** vraagt de volgende tien resultaten op via cursorpaginering. Nieuwe actieve of
afgeronde runs mogen de cursor niet laten verspringen of duplicaten veroorzaken.

### 5.4 Run-detail

Een run-detail toont statusovergangen, fasen, bronversies, gerelateerde entiteit en veilige
build-/modelmetadata. Niet tonen:

- bearer-tokens;
- volledige prompts;
- volledige broninhoud;
- interne stacktraces;
- providerpayloads die persoonsgegevens of geheime configuratie kunnen bevatten.

## 6. Uitbreiding van ieder agendapunt

Iedere agenda-itemkaart toont compact:

1. **Titel** — een korte AI-titel als die geldig beschikbaar is; daaronder blijft de officiële
   brontitel zichtbaar met label **Officiële titel**.
2. **Korte conclusie** — maximaal 280 tekens uit een afzonderlijk gevalideerd AI-veld, niet door de
   frontend uit lange Markdown afgekapt.
3. **Laatste gedetecteerde wijziging** — datum/tijd en menselijk leesbare typen, bijvoorbeeld
   “document toegevoegd” of “toelichting gewijzigd”. Bij nooit gewijzigd: “Geen wijziging sinds
   eerste import”.
4. **Laatste AI-analyse** — status, start/eindtijd en relatieve duur. Een actieve run toont live hoe
   lang hij wacht of loopt.
5. **Actualiteit** — actueel, wordt vernieuwd, verouderd, voorlopig of ingetrokken.

De kaart linkt naar het bestaande agendadetail en naar de betreffende AI-run. Een ingetrokken punt
verdwijnt uit de standaardlijst, maar blijft via revisiehistorie raadpleegbaar.

Het AI-resultaatcontract krijgt twee nieuwe verplichte velden:

```json
{
  "displayTitle": "Korte inhoudelijke titel",
  "shortConclusion": "Kernconclusie van maximaal 280 tekens"
}
```

Voor C bevat `shortConclusion` minimaal het advies wel/niet bespreken en waarom. Voor A/B bevat het
de politieke hoofdbeoordeling en belangrijkste aanbevolen actie. Een nieuwe prompt-/schemaversie
zorgt voor gecontroleerde heranalyse van actuele punten; oude resultaten blijven als historie
bewaard.

## 7. Datamodel

Indicatieve tabellen of equivalente Modulith-repositories:

| Entiteit | Belangrijkste gegevens |
| --- | --- |
| `policy_sync_run` | aanleiding maandelijks/handmatig, status, tijden, foutcode, aantallen |
| `policy_source` | stabiele bronidentiteit, canonieke URL, bronsoort, huidige status |
| `policy_source_revision` | bron, SHA-256, HTTP-metadata, titel, datum, tekst, ophaaltijd |
| `policy_snapshot` | versienummer, fingerprint, status kandidaat/actief/vervangen, tijden |
| `policy_snapshot_source` | snapshot en exacte bronrevisies |
| `policy_position` | snapshot, titel, samenvatting, beleidsrichting, thema's, status |
| `policy_position_reference` | positie, bronrevisie, pagina/sectie en lokale passage-ID |
| `logical_ai_run` of readmodel | doel, type, entiteit, status, tijden, parent/fasen, versies |

Bron- en snapshotrevisies zijn immutable. Correcties maken een nieuwe revisie; er is geen update die
historie overschrijft. Het bestaande `analysis_run` blijft de technische waarheid voor agenda-AI.
Een apart readmodel mag agenda- en standpuntenruns samenvoegen voor de UI.

`agenda_item_advice` bewaart aanvullend `display_title` en `short_conclusion`, of valideert deze
velden aantoonbaar in de bestaande JSONB-output.

## 8. API-contract

Alle functionele routes gebruiken de bestaande productie-authenticatie en acceptance-bypassregels.
De tooling-ingang uit T0 wisselt een apart geheim uitsluitend in voor zo'n normale sessie en geeft
geen rechtstreekse toegang tot de functionele routes.

| Methode en route | Doel |
| --- | --- |
| `POST /api/auth/tooling-session` | T0: normale sessie bootstrappen voor bevoegde tooling |
| `GET /api/policy/overview` | actieve snapshot, synchronisatiestatus en standpunten |
| `GET /api/policy/positions/{id}` | positie met alle referenties en versiehistorie |
| `POST /api/policy/refresh` | idempotente handmatige maandworkflow starten |
| `GET /api/policy/sync-runs/current` | actuele synchronisatierun ophalen |
| `GET /api/ai-runs?state=active` | alle actieve logische runs |
| `GET /api/ai-runs?state=finished&limit=10&cursor=...` | afgeronde runs met stabiele cursor |
| `GET /api/ai-runs/{id}` | veilig run-detail en fasen |
| bestaande agenda-itemroutes | uitbreiden met wijziging, laatste run, AI-titel en conclusie |

`POST /api/policy/refresh` vereist een `Idempotency-Key`, gebruikt een duurzame lock en krijgt een
strikte rate limit. Antwoord `202` betekent gestart of reeds ingepland; `409` betekent dat een
andere synchronisatie actief is.

Alle timestamps zijn ISO-8601 UTC. De frontend presenteert ze in de lokale tijdzone. Looptijden
worden niet als opgeslagen tekst maar uit start-/eindtimestamps berekend.

## 9. Status- en uitlegregels

De korte uitleg van een run komt uit gecontroleerde applicatiemetadata, niet uit vrij AI-proza.
Voorbeelden:

- “Eerste analyse van nieuw C-stuk”;
- “Opnieuw gestart omdat een document veranderde”;
- “Opnieuw gestart omdat geselecteerde PvdD-standpunten veranderden”;
- “Nieuwe officiële standpuntpagina verwerken”;
- “Maandelijkse controle van PvdD-bronnen”.

De API levert machinecodes plus een beperkt aantal parameters. De frontend vertaalt deze naar
Nederlandse tekst. Onbekende codes krijgen een veilige generieke omschrijving.

## 10. Security en omgevingsgrenzen

Productie:

- leest alleen de expliciete officiële PvdD-hosts via HTTPS;
- gebruikt echte Agent Runtime en een eigen productietoken;
- vereist Google SSO en backendallowlist;
- biedt daarnaast uitsluitend de in T0 begrensde tooling-bootstrap; het tooling-token autoriseert
  nooit rechtstreeks een functionele API-route;
- kan niet naar acceptance-mocks worden omgeconfigureerd.

Acceptance:

- gebruikt uitsluitend synthetische website-, programma- en wijzigingsfixtures;
- gebruikt Agent Runtime-provider `MOCKED`;
- doet geen request naar echte PvdD-websites, iBabs of echte AI;
- gebruikt de in stappenplan 4 vastgelegde auth-vrije testeromgeving;
- bevat scenario's voor gewijzigde pagina, gewijzigd programma, verdwenen pagina, conflict,
  time-out, ongeldige AI-output en paginering.

URL's en tekst uit websites blijven onbetrouwbare brondata. SSRF-bescherming valideert scheme,
host, poort, DNS-/redirectdoel en downloadgrenzen bij iedere request.

## 11. Observability en beheer

Metrics bevatten minimaal:

- tijdstip en duur laatste standpuntensynchronisatie;
- aantal opgehaalde, ongewijzigde, gewijzigde en afgewezen bronnen;
- actieve en afgeronde logische AI-runs per type/status;
- wachttijd en looptijdpercentielen;
- aantal door beleidswijziging verouderde en opnieuw geanalyseerde adviezen.

Logs gebruiken technische ID's en veilige codes. Zij bevatten geen volledige passages, prompts,
AI-resultaten of tokens. Een runbook beschrijft bronwijziging, gedeeltelijke synchronisatie,
handmatige refresh, rollback naar vorige actieve snapshot en vastgelopen AI-runs.

## 12. Acceptatiecriteria

- [ ] T0 is als eerste opgeleverd: tooling kan zonder Google een normale sessie bootstrappen en
      daarmee de frontend en beschermde REST-routes verifiëren.
- [ ] Het plaintext tooling-token staat alleen in het gitignored `secrets.env`, bereikt OpenShift
      via het bestaande sealproces en lekt niet naar Git, frontend, logs, metrics of database.
- [ ] De maandworkflow draait eenmaal op de eerste dag van de maand om 03:30
      `Europe/Amsterdam`.
- [ ] Handmatig actualiseren gebruikt dezelfde idempotente workflow en maakt geen dubbele run.
- [ ] Alleen officiële geallowliste PvdD-bronnen worden opgehaald.
- [ ] Ongewijzigde bronnen maken geen nieuwe snapshot of AI-run.
- [ ] Nieuwe en gewijzigde bronnen worden versieerbaar opgeslagen en herleidbaar verwerkt.
- [ ] Een mislukte synchronisatie laat de vorige actieve snapshot intact.
- [ ] De standpuntenpagina toont titel, samenvatting, thema, wijzigingsdatum en klikbare bronnen.
- [ ] Programmareferenties bevatten paginanummers; webreferenties openen de canonieke officiële URL.
- [ ] Tegenstrijdige of gewijzigde standpunten worden niet stilzwijgend samengevoegd.
- [ ] Een beleidswijziging heranalyseert alleen agenda-items met gewijzigde beleidscontext.
- [ ] Een nieuw of gewijzigd voorlopig agendapunt wordt direct geanalyseerd.
- [ ] De AI-runs-pagina toont alle actieve logische runs met doel en live looptijd.
- [ ] Afgerond toont eerst tien runs en **Meer laden** gebruikt stabiele cursorpaginering.
- [ ] Technische subjobs zijn gegroepeerd maar op detailniveau veilig inzichtelijk.
- [ ] Ieder agendapunt toont laatste wijziging, laatste run, AI-titel en korte conclusie.
- [ ] De officiële agendatitel blijft zichtbaar naast de AI-titel.
- [ ] Verouderde, voorlopige en ingetrokken informatie kan niet als ongemarkeerd actueel verschijnen.
- [ ] Prompts, tokens, broninhoud en stacktraces lekken niet via API, UI, metrics of logs.
- [ ] Acceptance bewijst alle flows met mocks en kan niet naar echte externe systemen verbinden.
- [ ] Production en acceptance zijn na uitrol `Synced` en `Healthy` en tonen dezelfde bewezen SHA.

## 13. Niet-blokkerende aannames ter bevestiging

Deze specificatie kan zonder voorafgaande beantwoording worden geïmplementeerd met de volgende
defaults:

1. de maandcontrole draait op de eerste dag om 03:30 uur;
2. officiële idealen/standpunten, provinciaal politiek werk en nieuws met expliciete beleidsinhoud
   tellen mee naast het verkiezingsprogramma;
3. de AI-titel staat prominent, maar de officiële titel blijft er direct onder staan;
4. een bronconflict wordt getoond en niet automatisch opgelost;
5. **Meer laden** voegt steeds tien afgeronde runs toe;
6. alle bron- en runhistorie blijft onbeperkt bewaard, overeenkomstig de bestaande MVP-keuze.

Wanneer één van deze productkeuzes anders moet, wordt dit document vóór implementatie aangepast.
