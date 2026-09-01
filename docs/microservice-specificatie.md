# PvdD Commissie-assistent — functionele en technische specificatie

Status: concept 0.3

Datum: 31 augustus 2026

Repository: `robbertvdzon/pvdd`

## 1. Doel

De PvdD Commissie-assistent ondersteunt de Noord-Hollandse Statenfractie van de Partij voor de
Dieren bij de voorbereiding van de eerstvolgende vergadering van de commissie Ruimte. De
applicatie haalt de actuele agenda en bijbehorende openbare stukken op, laat die via de gedeelde
Agent Runtime analyseren en presenteert per agendapunt een controleerbaar politiek advies vanuit
het standpunt van de Partij voor de Dieren.

De applicatie is een intern hulpmiddel. De gegenereerde tekst is een concept voor menselijke
beoordeling en wordt nooit automatisch gepubliceerd, verstuurd of als formele fractiepositie
vastgelegd.

## 2. Bronnen en afbakening

### 2.1 Primaire bronnen

1. De openbare iBabs-pagina van de commissie Ruimte van Provinciale Staten Noord-Holland:
   <https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/a3de1271-fd63-4e24-8a38-6a6df474ec9d>
2. Het Noord-Hollandse verkiezingsprogramma 2023–2027 van de Partij voor de Dieren:
   <https://noordholland.partijvoordedieren.nl/verkiezingsprogramma-partij-voor-de-dieren-provinciale-staten-2023-2027>
3. De vanuit de agendapagina gelinkte openbare agenda-items, rapportitems en bijlagen.

De opgegeven agenda-URL is een ingang en geen blijvend vergadering-ID. De applicatie ontdekt steeds
de eerstvolgende vergadering van het type **Commissie Ruimte** en slaat het gevonden bron-ID en de
bron-URL op. Op 31 augustus 2026 verwijst de ingang naar de vergadering van 14 september 2026; de
pagina vermeldt dat de agenda op 3 september wordt gepubliceerd. Vóór publicatie toont de app dus
een nette status “agenda nog niet gepubliceerd” en verzint zij geen ontbrekende stukken.

### 2.2 Aanvullende bronnen

Een analyse mag feiten uit de aangeleverde vergaderstukken gebruiken. Het verkiezingsprogramma is
de belangrijkste bron voor het partijstandpunt. Andere bronnen mogen alleen worden gebruikt als
ze:

- expliciet door een vergaderstuk worden genoemd;
- openbaar en herleidbaar zijn;
- in het resultaat met URL, documentnaam en zo mogelijk paginanummer worden vermeld.

In de eerste versie zoekt de AI niet vrij op het web. Zo blijft de analyse reproduceerbaar en is
duidelijk waarop een conclusie is gebaseerd.

### 2.3 Niet in de eerste versie

- automatisch publiceren van bijdragen, vragen, moties of andere politieke uitingen;
- zelfstandig e-mailen of berichten sturen;
- wijzigen van gegevens in iBabs;
- meerdere commissies of andere provincies;
- een mobiele app;
- OCR van slecht gescande documenten, tenzij dit tijdens de technische spike noodzakelijk blijkt.

## 3. Gebruikers en authenticatie

De webapp is alleen beschikbaar voor expliciet toegestane gebruikers.

- De login gebruikt **Google SSO**, gelijk aan de beheerfrontend van `hkh-autopilot`.
- De Flutter-webfrontend verkrijgt een Google ID-token en stuurt dat als bearer-token naar de
  backend.
- De backend verifieert minimaal de RS256-handtekening via Google JWKS, audience, issuer,
  vervaltijd, `email_verified` en een case-insensitive e-mailallowlist.
- De productieallowlist staat voor de eenvoudige MVP bewust hard gecodeerd in de backend en bevat
  uitsluitend `marchanou@gmail.com` en `robbertvdzon@gmail.com`.
- De allowlist wordt genormaliseerd naar lowercase en met tests vastgelegd. Een wijziging vereist
  een codewijziging en nieuwe deployment.
- De Google webclient-ID moet zijn geconfigureerd. Ontbreekt deze, dan faalt de applicatie gesloten
  en zijn beveiligde API-routes niet toegankelijk.
- De Google client-ID is build-/runtimeconfiguratie; er is voor deze browserflow geen Google client
  secret nodig.
- Autorisatie vindt altijd in de backend plaats. Een verborgen frontendknop is geen
  beveiligingsgrens.

Configuratie:

| Variabele | Betekenis |
| --- | --- |
| `PVDD_GOOGLE_CLIENT_ID` | Bestaande Google Web OAuth-client-ID |
| `PVDD_CORS_ALLOWED_ORIGINS` | Alleen nodig als niet alles same-origin draait |

De origin `https://pvdd.vdzonsoftware.nl` moet handmatig worden toegevoegd aan **Authorized
JavaScript origins** van de bestaande Google OAuth-client `Robberts applicaties` in het project
`tuinbewatering`. Voor de bestaande popup/callback-flow is geen redirect-URI nodig.

## 4. Functionele werking

### 4.1 Hoofdflow

1. Een gebruiker logt in.
2. De app toont de eerstvolgende vergadering van commissie Ruimte, inclusief datum, tijd,
   bronlink, laatst gecontroleerd tijdstip en bronstatus.
3. Iedere ochtend om 05:00 uur in `Europe/Amsterdam` controleert de backend of de eerstvolgende
   vergadering een ander bron-ID heeft dan de laatst succesvol verwerkte vergadering.
4. Is er geen eerstvolgende vergadering, of is dit bron-ID al succesvol verwerkt, dan stopt de run
   direct zonder documenten te downloaden of een AI-job te maken.
5. Is er een nieuwe vergadering, dan haalt de backend de actuele agenda, agendapunten,
   rapportitems en bijlagen op. Is de agenda nog niet gepubliceerd, dan wordt de vergadering niet
   als verwerkt gemarkeerd en probeert de scheduler het de volgende ochtend opnieuw.
6. De frontend biedt **Nu controleren**. Deze actie voert exact dezelfde controle onmiddellijk uit
   en forceert geen dubbele verwerking van een reeds succesvol verwerkte vergadering.
7. De backend maakt duurzame lokale analysetaken aan en dient daarvoor idempotente
   `APPLICATION_WORK`-jobs in bij Agent Runtime.
8. De frontend toont per taak `wachtend`, `bezig`, `geslaagd`, `mislukt` of `geannuleerd` en blijft
   bruikbaar terwijl de analyse loopt.
9. Pas nadat de import en alle vereiste analyses succesvol zijn afgerond, wordt het bron-ID als de
   laatst verwerkte vergadering vastgelegd. Na afronding toont de app de adviezen met
   bronverwijzingen.

De planning is een vaste MVP-keuze: cron `0 0 5 * * *` met tijdzone `Europe/Amsterdam`. De klok is
injecteerbaar voor tests. Een mislukte import of analyse schuift de laatst succesvol verwerkte
vergadering niet op, zodat de volgende geplande of handmatige controle veilig opnieuw probeert.

### 4.2 Vergadering ontdekken

De scraper hardcodet niet alleen de UUID van één vergadering. Hij gebruikt de openbare
commissiepagina en/of het door iBabs aangeboden jaarendpoint voor agendatype `1100617069`, parseert
de vergaderdatums en kiest de vroegste vergadering waarvan het begintijdstip in de toekomst ligt.
De klok en tijdzone zijn injecteerbaar voor tests.

Wanneer er geen toekomstige vergadering is, toont de app dat expliciet. Wanneer iBabs tijdelijk
niet beschikbaar is, blijft de laatst succesvol geïmporteerde analyse zichtbaar met een duidelijke
waarschuwing dat actualiteit niet kon worden gecontroleerd.

### 4.3 Agenda en stukken inlezen

De bronpagina is server-side als HTML beschikbaar. De eerste implementatie gebruikt daarom een
begrensde HTTP-client en HTML-parser, niet een browserrobot. De importer verwerkt:

- vergaderingmetadata;
- de hiërarchie en volgorde van agendapunten;
- sectiekoppen die A-, B- of C-agenda aangeven;
- titel, toelichting en behandelvoorstel van ieder inhoudelijk punt;
- agenda- en puntbijlagen via `/Agenda/Document/...`;
- C-agenda-items via `/Reports/Item/...` en hun `/Reports/Document/...`-bijlagen.

De categorie wordt uit de semantische sectiekop overgenomen en niet afgeleid uit alleen het
agendanummer. Niet-inhoudelijke punten zoals opening, pauze en sluiting worden bewaard maar niet
naar AI gestuurd.

Veiligheids- en betrouwbaarheidsgrenzen:

- alleen HTTPS en een expliciete allowlist van bronhosts;
- connect-, read- en totale time-outs;
- begrensde downloads en maximaal documentformaat;
- MIME-type én magic-bytecontrole;
- geen uitvoering van scripts, macro’s of inhoud uit documenten;
- PDF, tekst, HTML en DOCX ondersteunen; onbekende typen zichtbaar overslaan;
- tekst per pagina/sectie extraheren en bron-ID, URL, hash en paginanummer behouden;
- gewijzigde documenten herkennen met SHA-256;
- respectvolle requestfrequentie, herkenbare `User-Agent` en begrensde retries.

Een technisch onderzoek moet vóór implementatie bevestigen of alle actuele documenten tekst
bevatten. Bij essentiële scans zonder tekst wordt OCR een expliciete vervolgbeslissing; de app mag
zo’n document niet stilzwijgend als volledig gelezen markeren.

### 4.4 Analyse van A- en B-agendapunten

Voor ieder inhoudelijk punt op de A- of B-agenda geeft het resultaat exact deze vijf onderdelen:

1. **Waar gaat het over?** — feitelijke, neutrale samenvatting van voorstel, besluit, geld,
   planning, betrokken gebieden en gevolgen.
2. **Wat vinden we ervan?** — beoordeling vanuit het PvdD-verkiezingsprogramma, met relevante
   programmapassages en eventuele spanning of onzekerheid.
3. **Wat kunnen/willen we ermee in de commissie?** — handelingsopties, prioriteit en gewenst
   commissieresultaat.
4. **Welke punten willen we maken en wat willen we van de gedeputeerde?** — concrete politieke
   punten, verzoeken, toezeggingen of vervolgacties.
5. **Welke technische vragen gaan we stellen?** — feitelijke, niet-retorische vragen die nodig
   zijn om ontbrekende informatie, aannames, effecten, financiën, juridische ruimte, monitoring en
   alternatieven helder te krijgen.

Elk onderdeel bevat bronverwijzingen naar de gebruikte vergaderstukken en relevante pagina’s van
het verkiezingsprogramma. Feiten, politieke waardering en voorgestelde actie moeten zichtbaar van
elkaar te onderscheiden zijn.

### 4.5 Beoordeling van C-agendapunten

Voor ieder C-agendapunt geeft de app:

- **bespreken en verplaatsen naar B: ja/nee**;
- een korte, concrete motivering vanuit het PvdD-standpunt;
- urgentie: laag, middel of hoog;
- wat bespreking in de commissie moet opleveren;
- de belangrijkste politieke en/of technische vraag wanneer bespreking wordt geadviseerd;
- bronverwijzingen.

“Ja” wordt alleen geadviseerd wanneer bespreking politieke meerwaarde heeft, bijvoorbeeld bij
mogelijke schade aan natuur, dieren, klimaat, gezondheid of kwetsbare inwoners, strijd met het
verkiezingsprogramma, grote financiële of ruimtelijke gevolgen, onvoldoende informatie of een
reële mogelijkheid om provinciaal beleid te beïnvloeden. De AI mag niet automatisch ieder stuk
naar B willen verplaatsen.

### 4.6 Normatief PvdD-kader

De analyse gebruikt het verkiezingsprogramma als primaire politieke bron. De programmatekst wordt
als versieerbare bron met URL, SHA-256, ophaaldatum en paginachunks opgeslagen. Relevante passages
worden deterministisch aan de AI-context toegevoegd; het model mag geen standpunt aan de partij
toeschrijven zonder bron of expliciet als afleiding aangeduide redenering.

Minimaal terugkerende toetsingsassen zijn:

- intrinsieke waarde en bescherming van dieren en natuur;
- herstel van biodiversiteit en het verbinden van leefgebieden;
- klimaat, energie- en grondstoffengebruik binnen planetaire grenzen;
- gezonde leefomgeving, schone lucht, bodem en water;
- ecologie boven kortetermijneconomie waar die botsen;
- natuurinclusief, klimaatneutraal, energiepositief en circulair bouwen;
- eerst bestaande bebouwing benutten, daarna binnenstedelijk bouwen, met betaalbaarheid en groen;
- voetganger, fietser en openbaar vervoer vóór gemotoriseerd verkeer;
- geen nieuwe of verbrede wegen en inzet op minder luchtvaart;
- transparantie, bescherming van privacy en betrokkenheid van inwoners;
- verdelingseffecten en bescherming van kwetsbare mensen en toekomstige generaties.

### 4.7 Kwaliteit van AI-uitvoer

De Agent Runtime-job gebruikt een streng JSON-responseschema. De backend accepteert geen vrije tekst
als eindresultaat en valideert aanvullend:

- alle vereiste onderdelen aanwezig en niet leeg;
- de agenda-ID’s bestaan in de lokale import;
- elke bronverwijzing verwijst naar een meegegeven bron;
- A/B-uitvoer bevat vijf onderdelen;
- C-uitvoer bevat een binaire bespreekbeslissing en motivering;
- tekst- en lijstlengtes zijn begrensd;
- onzekerheden en ontbrekende informatie zijn expliciet;
- er staan geen verzonnen citaten, paginanummers of URL’s in.

Een ongeldige uitkomst wordt niet als advies getoond. De app toont een veilige foutstatus en laat een
nieuwe technische poging over aan Agent Runtime; de gebruiker kan dezelfde veilige controle via
“Nu controleren” opnieuw starten.

Grote dossiers mogen nooit stilzwijgend worden afgekapt. Wanneer de volledige tekst niet binnen de
Runtime-promptlimiet past, maakt de backend eerst gevalideerde bronnotities per document/chunk en
daarna een synthesejob per agendapunt. Ook tussenresultaten zijn gekoppeld aan bronhash en
promptversie.

## 5. Frontend

### 5.1 Technologie en vormgeving

- Flutter web, Material 3 en dezelfde projectconventies als `hkh-autopilot`.
- Eén beveiligde webfrontend; voor de MVP is geen aparte publieke en adminfrontend nodig.
- Same-origin communicatie via Nginx: de browser gebruikt `/api/...`, Nginx proxyt naar de
  backendservice.
- Look-and-feel gebaseerd op Product Factory: rustige lichtgroene achtergrond, donkergroene
  navigatie, mintgroene accenten, kaarten met subtiele rand en afgeronde hoeken, duidelijke
  typografische hiërarchie en ruime witruimte.
- De huisstijl is geïnspireerd op Product Factory maar krijgt een eigen PvdD-identiteit. Assets
  worden niet klakkeloos gekopieerd.
- Responsive vanaf 320 px, maar primair ontworpen voor laptop/desktop.
- Toetsenbordbediening, zichtbare focus, semantische labels, voldoende contrast en bruikbare
  schermlezerstructuur zijn acceptatiecriteria.

### 5.2 Schermen

1. **Login** — Google-login en veilige configuratiefout.
2. **Overzicht** — eerstvolgende vergadering, bronstatus, laatst gecontroleerd, laatst geanalyseerd,
   buildinformatie en knop “Nu controleren”.
3. **Agenda** — tabs of filters A, B en C; status per punt; zoeken op titel.
4. **A/B-detail** — bronstukken en de vijf analyseonderdelen.
5. **C-detail** — ja/nee-advies, urgentie, motivering en vraag.
6. **Bronnen** — klikbare bronlinks, documenthash/ophaaldatum en programmapassages.
7. **Runstatus** — voortgang en een veilige foutmelding; opnieuw proberen gebeurt via “Nu
   controleren” of de volgende 05:00-run.

De MVP heeft geen editor, goedkeuringsworkflow of historie-scherm. Resultaten zijn read-only en
kunnen worden geselecteerd/gekopieerd. Alle geïmporteerde en gegenereerde gegevens blijven wel in
de database staan.

De UI toont conceptstatus prominent: “AI-concept — controleer bronnen en formulering vóór gebruik”.

### 5.3 Logo en favicon

De frontend krijgt een nieuw, eigen, vriendelijk icoon en logo dat commissievoorbereiding, Noord-Holland
en dieren/natuur associeert zonder het officiële PvdD-beeldmerk ongeautoriseerd te kopiëren. Het
beeldmerk moet ook op 16×16 px herkenbaar zijn en wordt opgeleverd als minimaal SVG plus PNG’s voor
favicon/manifest. Dit eigen ontwerp is onderdeel van de MVP; definitief beeld en kleurgebruik
vereisen menselijke goedkeuring vóór productie.

### 5.4 Cachebeleid en zichtbare build

De cacheaanpak combineert de bewezen patronen uit HKH Autopilot en Product Factory:

- Flutter bouwen met `--pwa-strategy=none`; geen actieve PWA-cache;
- een kill-switch op `/flutter_service_worker.js` met `Cache-Control: no-store` die oude caches
  wist, de service worker afmeldt en open tabs eenmalig herlaadt;
- `main.dart.js` na de build hernoemen naar `main.<content-hash>.js` en alle verwijzingen aanpassen;
- alleen de gehashte bundle krijgt `public, max-age=31536000, immutable`;
- `index.html`, `flutter_bootstrap.js`, vaste assets en SPA-routes krijgen `Cache-Control: no-cache`;
- `/version.json` en API-antwoorden krijgen `Cache-Control: no-store`;
- de repository bevat een regressietest die de headers, gehashte bundle en afwezigheid van de oude
  vaste bundlenaam controleert.

Iedere frontendbuild bevat en toont:

- applicatieversie;
- volledige Git commit-SHA en korte SHA;
- UTC-buildtijd;
- omgeving (`local`, `acceptance`, `production`);
- build-identiteit, bijvoorbeeld `0.1.0+1a2b3c4d5e6f`.

De gegevens staan in een niet-gecachete `/version.json` en zijn zichtbaar in de footer of een
“Over deze versie”-dialoog. De frontend vergelijkt periodiek haar ingebakken build-identiteit met
`/version.json`; bij verschil toont zij een melding “Nieuwe versie beschikbaar” met een bewuste
herlaadactie. De backend biedt eveneens `/api/version`, zodat frontend- en backendrevision
afzonderlijk controleerbaar zijn.

## 6. Backend en data

### 6.1 Technische basis

De basis volgt `hkh-autopilot`:

- Kotlin op JDK 21;
- Spring Boot en Spring Modulith;
- Maven;
- Spring Web, Validation, Actuator en JDBC;
- PostgreSQL 16;
- Flyway voor uitsluitend voorwaartse schemawijzigingen;
- featuregerichte modules met expliciete Modulith-grenzen;
- echte applicatietests met gefakete externe grenzen en Testcontainers voor PostgreSQL.

Bij bootstrap worden versies uit de dan actuele, groene HKH-build overgenomen en in CI en
Dockerfiles identiek gepind. De huidige HKH-repo bevat namelijk een versieverschil tussen de
Flutter-versie in CI en de oudere builderimage; PvdD neemt die inconsistentie niet over.

Voorgestelde modules:

- `auth` — Google-tokenvalidatie en allowlist;
- `meetings` — vergadering- en agenda-import;
- `documents` — veilige download en tekstextractie;
- `analysis` — lokale runs, promptopbouw, resultaatvalidatie en projectie;
- `agentruntime` — HTTP-adapter en statusreconciliatie;
- `system` — health en buildidentiteit.

### 6.2 Database

Productie krijgt, conform HKH, een eigen PostgreSQL-database in namespace `pvdd`, een eigen PVC en
eigen credentials. PvdD schrijft niet in tabellen van Agent Runtime, Software Factory of een andere
applicatie. Alle tabellen zijn eigendom van deze backend en worden met Flyway beheerd.

Minimaal datamodel:

| Tabel | Belangrijkste gegevens |
| --- | --- |
| `meeting` | bron-ID, commissie, datum/tijd, URL, status, bronhash, gecontroleerd op |
| `agenda_item` | bron-ID, meeting-ID, volgorde, categorie A/B/C, titel, toelichting, URL, hash |
| `source_document` | bron-ID, agenda-item, naam, URL, MIME-type, SHA-256, tekststatus, opgehaald op |
| `analysis_run` | bronfingerprint, promptversie, status, Runtime-job-ID, idempotentiesleutel, fout, tijden |
| `agenda_item_advice` | run, item, vijf A/B-onderdelen of C-besluit, urgentie, citaties, modelmetadata |
| `policy_source` | programma-URL, SHA-256, ophaaldatum, pagina-/sectiechunks |

Voor de eenvoudige MVP worden vergaderingen, documenten, geëxtraheerde tekst, runs en resultaten
voor onbepaalde tijd bewaard. Er is geen bewaartermijn, cleanupjob of automatische verwijdering.
Bron-URL, hash, extractiestatus en de tekst die daadwerkelijk in een prompt is gebruikt blijven
auditbaar. Technische download- en requestgroottelimieten blijven wel gelden.

### 6.3 Voorgestelde backend-API

Alle routes behalve probes en versiemetadata vereisen een geldig Google bearer-token.

| Methode en route | Doel |
| --- | --- |
| `GET /api/auth/me` | token valideren en ingelogde gebruiker retourneren |
| `GET /api/meetings/next` | eerstvolgende vergadering en import-/analysestatus |
| `POST /api/meetings/check-now` | dezelfde nieuwe-vergaderingcontrole als de 05:00-scheduler uitvoeren |
| `POST /api/meetings/{id}/analyses` | idempotente analyse starten |
| `GET /api/meetings/{id}/agenda-items` | agenda en actuele adviesstatus ophalen |
| `GET /api/agenda-items/{id}` | detail, bronnen en advies ophalen |
| `GET /api/analysis-runs/{id}` | lokale plus Runtime-status ophalen |
| `POST /api/analysis-runs/{id}/cancel` | annuleringsverzoek doorgeven |
| `GET /api/version` | backend-buildidentiteit |
| `GET /actuator/health/liveness` | OpenShift liveness |
| `GET /actuator/health/readiness` | OpenShift readiness |

Muterende requests krijgen een idempotentiesleutel. API-DTO’s staan op alle publieke grenzen;
database- en Runtime-modellen lekken niet naar de frontend.

## 7. Agent Runtime-integratie

### 7.1 Contract

De backend praat rechtstreeks via HTTPS met `https://agent-runtime.vdzonsoftware.nl`, naar het
patroon van `product-factory/ai-execution-impl/.../AgentRuntimeClient.kt`:

- `POST /v1/jobs` om een `APPLICATION_WORK`-job te maken;
- `GET /v1/jobs/{jobId}` voor status;
- `GET /v1/jobs/{jobId}/result` voor het gevalideerde JSON-resultaat;
- `POST /v1/jobs/{jobId}/cancel` om te annuleren;
- optioneel artifactdownloads wanneer de analyse artifacts oplevert.

Ieder request gebruikt een eigen PvdD-consumentcredential in `Authorization: Bearer ...`, korte
HTTP-time-outs en een harde Runtime-uitvoeringstime-out. Een verloren submitresponse wordt met
dezelfde idempotentiesleutel herhaald; er wordt niet blind een tweede job gemaakt.

Voorgestelde configuratie:

| Variabele | Productiewaarde/betekenis |
| --- | --- |
| `PVDD_AGENT_RUNTIME_URL` | `https://agent-runtime.vdzonsoftware.nl` |
| `PVDD_AGENT_RUNTIME_TOKEN` | eigen geheim consumenttoken |
| `PVDD_AGENT_RUNTIME_PROJECT_PREFIX` | `PVDD` |
| `PVDD_AGENT_RUNTIME_PROVIDER` | `CODEX` |
| `PVDD_AGENT_RUNTIME_MODEL` | configureerbaar toegestaan model |
| `PVDD_AGENT_RUNTIME_TIMEOUT_SECONDS` | standaard 3600 |

De frontend ontvangt het Runtime-token nooit en roept Agent Runtime nooit rechtstreeks aan.

### 7.2 Vereiste wijziging in `agent-runtime`

Agent Runtime kent momenteel aparte consumentidentiteiten voor Product Factory, Software Factory,
HKH Autopilot en HKH. Voor PvdD is dus een kleine, afzonderlijke platformwijziging nodig:

- `AR_PVDD_TOKEN` genereren en als SealedSecret in acceptatie en productie opnemen;
- tenant `pvdd` toevoegen aan authenticatie en policy;
- alleen `APPLICATION_WORK` toestaan;
- alleen project/environmentprefix `PVDD` toestaan;
- in productie alleen de gekozen echte providers/modellen toestaan;
- in acceptatie uitsluitend `MOCKED` toestaan;
- geen worker-, admin- of `REPOSITORY_WORK`-rechten geven;
- een veilig script toevoegen dat de consumentcredential zonder weergave naar de gitignored
  PvdD-secretbron kopieert;
- tenantisolatie en fail-closed configuratie met integratietests bewijzen.

Productie en acceptatie gebruiken verschillende PvdD-tokens. Een bestaand Product Factory- of
HKH-token wordt niet hergebruikt.

### 7.3 Duurzame lokale correlatie

Agent Runtime beheert queue, attempts, leases, retries, deadline, uitvoer en artifacts. PvdD bewaart
alleen de functionele correlatie en een kleine outbox/reconciliatiestatus:

- lokaal run-ID en agenda-item-ID;
- stabiele Runtime-idempotentiesleutel;
- bevroren bronfingerprint en promptversie;
- Runtime-job-ID zodra bekend;
- actuele functionele status;
- gevalideerd eindresultaat of veilige foutcode.

Er blijft nooit een HTTP-request of serverthread open tijdens de AI-uitvoering. Een scheduler
reconcileert actieve jobs; een herstart van de backend verliest geen werk.

## 8. Containers, OpenShift en GitOps

### 8.1 Componenten

De productieomgeving bevat minimaal:

- `frontend` Deployment + Service + publieke Route;
- `backend` Deployment + Service, alleen intern nodig doordat frontend same-origin proxyt;
- `database` StatefulSet + Service + 5 GiB PVC;
- `pvdd-runtime` SealedSecret;
- serviceaccounts, security contexts, probes, resource requests/limits en netwerkconfiguratie;
- dagelijkse PostgreSQL-backup en restore-runbook; de MVP verwijdert backups niet automatisch.

Containers draaien non-root, zonder privilege escalation en met alle Linux-capabilities gedropt.
OpenShift-configuratie staat als Kustomize base plus overlays in deze repository.

### 8.2 Publieke URL

De frontendroute krijgt exact:

```yaml
spec:
  host: pvdd.vdzonsoftware.nl
```

De bestaande Cloudflare Tunnel publiceert normaal `*.vdzonsoftware.nl` naar de interne
OpenShift-ingressrouter en behoudt de Host-header. Daardoor is geen eigen tunnel of tunnel-token
nodig. Het werkt echter niet “zonder configuratie”: de Git-managed OpenShift Route moet bestaan en
na uitrol moeten zowel de bekende host als een onbekende wildcardhost worden getest. Publieke HTTPS
eindigt bij Cloudflare; de route volgt het bestaande patroon met edge TLS en
`insecureEdgeTerminationPolicy: Allow` om proxyredirectlussen te voorkomen.

### 8.3 GitHub Actions en releaseketen

De repository krijgt minimaal:

- `Repository verification` op iedere pull request en push naar `main`;
- backend: `mvn clean verify`, Modulithcontrole, Testcontainers en Dockerbuild;
- frontend: `flutter analyze`, `flutter test`, release-webbuild en cachecontracttest;
- Kustomize-rendercontrole voor iedere overlay;
- software composition/dependency- en secretcontrole waar die in de referentierepo beschikbaar is;
- na een groene `main`: immutable backend- en frontendimages naar GHCR;
- tags/digests gebaseerd op de volledige geverifieerde commit-SHA;
- bron-SHA en één UTC-buildtijd als buildargs voor beide images;
- geautomatiseerde wijziging van de imagepins in de GitOps-overlay en een `[skip ci]`-commit.

Argo CD volgt de gepinde overlay met `automated`, `prune` en `selfHeal`. Een push leidt dus via
verificatie, imagebuild, GitOps-pin en Argo-sync tot een werkende versie. “Direct” betekent na een
groene pipeline en rollout; een falende verificatie wordt nooit uitgerold.

Er komt een vaste acceptatieomgeving vóór productie, naar het nieuwere Product Factory-patroon.
Acceptatie gebruikt uitsluitend de `MOCKED` provider van Agent Runtime én een eigen gemockte
vergaderbron. De acceptatiebackend maakt geen verbinding met de echte iBabs-site. Voor de MVP komen
er geen automatische PR-previewomgevingen.

De gemockte vergaderbron draait als aparte, kleine HTTP-server in de acceptatienamespace en levert
deterministische fixtures voor minimaal: geen nieuwe vergadering, aangekondigd maar nog niet
gepubliceerd, een nieuwe vergadering met A/B/C-stukken, een gewijzigd/onleesbaar document en een
bronstoring. `PVDD_MEETING_SOURCE_BASE_URL` wijst in productie naar
`https://noordholland.bestuurlijkeinformatie.nl` en in acceptatie naar deze interne mockservice. Een
environment guard laat acceptatie niet met de productiebron of een echte AI-provider starten.

### 8.4 `robberts-infrastructure`

`robberts-infrastructure` moet de Argo CD-aanwijzers voor PvdD bevatten zodat clusterherstel en
automatische synchronisatie volledig declaratief zijn:

- productie-Application `pvdd` naar de productie-overlay in deze repository;
- acceptatie-Application `pvdd-acceptance`, inclusief de gemockte vergaderbron;
- opname onder `manifests/root-app/apps/`, zodat `root-apps` de resources beheert;
- geen applicatiesecrets in plaintext; uitsluitend namespacegebonden Sealed Secrets in de
  applicatierepository.

De gewone PvdD-manifests blijven in de `pvdd`-repo; de infrastructuurrepo bevat alleen de
clusterbrede/app-of-apps-koppeling en eventuele gedeelde RBAC.

## 9. Software Factory-aansluiting

Deze aansluiting gebeurt bewust pas nadat de technische fundering, de functionele MVP én het
bronrevisieplan volledig zijn geaccepteerd. De bouw van de eerste MVP en de daaropvolgende
actualiteitshardening zijn dus niet afhankelijk van Software Factory. Het afsluitende
[stappenplan 3](stappenplannen/03-software-factory-aansluiting.md) registreert het dan reeds werkende
project en bewijst de volledige story-, merge- en deploybewaking.

De `pvdd`-repo wordt factory-ready opgezet naar het HKH-patroon:

- `docs/factory/README.md`;
- `docs/factory/functional-spec.md`;
- `docs/factory/technical-spec.md`;
- `docs/factory/development.md`;
- `docs/factory/deployment.md`;
- `docs/factory/secrets-local.md`;
- `docs/factory/agent-runtime.md`;
- `docs/adr/` met template en vastgelegde architectuurkeuzes;
- `docs/stories/` voor story- en worklogoutput;
- `.factory/verification.yaml` met uit te voeren checks.

In de lokale, gitignored `softwarefactory/projects.yaml` komt een project `pvdd`. De getrackte
`projects.yaml.example` en relevante documentatie worden eveneens bijgewerkt, zodat de registratie
reproduceerbaar is. Voorgestelde projectconfiguratie:

- repo `git@github.com:robbertvdzon/pvdd.git` of de equivalente HTTPS-URL;
- automatische mergepolicy met vereiste check `Repository verification`, gelijk aan HKH Autopilot;
- twee `openshift-watch`-deploydoelen: `backend` en `frontend` in namespace `pvdd`;
- Argo CD-app `pvdd` in namespace `argocd` als waarheidsbron;
- `liveComponents` voor backend en frontend;
- `matchPaths: [backend/, deploy/]` voor backend en `[frontend/, deploy/]` voor frontend;
- gelijke, voldoende ruime deploytime-outs;
- geen automatische package- of dataopruiming in de MVP.

Omdat `softwarefactory/projects.yaml` lokale runtimeconfiguratie is, is alleen aanpassen van de
Software Factory-code of voorbeeldconfiguratie niet genoeg: de werkelijk gebruikte lokale file
moet worden aangepast en de draaiende factory moet de wijziging opnieuw inlezen.

## 10. Configuratie en secrets

| Categorie | Voorbeelden | Opslag |
| --- | --- | --- |
| Publieke buildconfig | Google client-ID, applicatieversie | GitHub variables/buildargs |
| Backendsecret | databasewachtwoord en Runtime-token | lokale `secrets.env` → SealedSecret |
| Niet-geheime runtimeconfig | bron-URL, schema, planning, provider/model | ConfigMap of gecontroleerde env |
| CI-credential | GHCR/GitOps-push | zo veel mogelijk ingebouwde `GITHUB_TOKEN` met minimale rechten |

Echte secretwaarden, lokale overrides, databases, gedownloade vergaderstukken en buildoutput worden
niet gecommit. Scripts lezen env-bestanden als data en voeren ze niet uit met `source`.

## 11. Beveiliging, privacy en verantwoord gebruik

- Alleen openbare bronstukken worden automatisch geïmporteerd.
- De app is toch afgeschermd omdat analyses interne politieke voorbereiding bevatten.
- Tokens en broninhoud komen niet in logs.
- Logregels gebruiken technische ID’s en veilige foutcodes.
- HTML wordt gesanitized; AI-uitvoer wordt als tekst/gestructureerde widgets gerenderd, niet als
  uitvoerbare HTML.
- Inhoud uit bronstukken is onbetrouwbare data en nooit een systeeminstructie. Prompt injection in
  documenten mag bronallowlists, toolgebruik, responseformat of geheimhouding niet wijzigen.
- De Agent Runtime-job krijgt geen environmentkeys tenzij een toekomstige, expliciet beoordeelde
  feature die nodig heeft.
- Download- en promptgroottes zijn begrensd om resource-uitputting te voorkomen.
- Rate limiting en auditlogging gelden voor refresh-, analyse- en cancelroutes.
- Documenten, prompts en analyses worden in de MVP zonder bewaartermijn opgeslagen en niet
  automatisch verwijderd.
- Iedere analyse toont model, promptversie, bronfingerprint en aanmaaktijd voor herleidbaarheid.

## 12. Observability en beheer

- Spring Boot Actuator liveness/readiness en een publieke, minimale routecheck;
- gestructureerde logs met meeting-, item-, run- en Runtime-job-ID;
- metrics voor importduur, documentfouten, actieve/terminale analyses en Runtime-callfouten;
- dashboardstatus voor laatste geslaagde broncontrole en laatste geslaagde analyse;
- waarschuwing wanneer de bron langer dan de ingestelde grens niet kon worden gecontroleerd;
- PostgreSQL-backup, hashcontrole en periodieke restore-oefening, zonder automatische verwijdering;
- runbook voor bronwijziging, Google-configuratie, Runtime-tokenrotatie, mislukte analyse, rollback
  en databaseherstel.

## 13. Teststrategie

### 13.1 Backend

- parserfixtures van minstens één volledige historische vergadering met A/B/C-structuur;
- schedulerdekking voor 05:00 `Europe/Amsterdam`, geen vergadering, dezelfde vergadering,
  ongepubliceerde agenda, mislukte run en pas-na-succes opschuiven van het laatste bron-ID;
- contracttest tegen opgeslagen HTML voor vergaderingontdekking, agenda, reportitems en documenten;
- wijzigingsdetectie en idempotentie;
- documenttype-, grootte-, host- en time-outgrenzen;
- prompt-injectionfixture;
- JSON-schema- en bronverwijzingsvalidatie;
- Agent Runtime HTTP-contracttest en verloren-submitresponse;
- reconciliatie na applicatieherstart;
- Google-tokenvalidatie en allowlist, inclusief fail-closed configuratie;
- repositorytests met echte PostgreSQL via Testcontainers;
- Spring Modulith-architectuurverificatie.

Live iBabs en Google worden niet vereist voor de gewone testsuite. Een afzonderlijke, niet-mutante
smoketest mag gecontroleerd de publieke bronnen lezen.

### 13.2 Frontend

- loginstatussen en verlopen token;
- vergadering zonder gepubliceerde agenda;
- A/B-vijfdelige weergave en C-ja/nee-weergave;
- voortgang, fout en “Nu controleren” zonder dubbele verwerking;
- bronlinks en conceptwaarschuwing;
- builddialoog en updatebeschikbaarheid;
- responsive gedrag, toetsenbordfocus en semantiek;
- Nginx-cachecontract en gehashte bundlenaam.

### 13.3 Deployment

- `kustomize build` van iedere overlay;
- containers starten als willekeurige non-root OpenShift-user;
- probes en resourcegrenzen aanwezig;
- frontend praat same-origin met backend;
- ongeauthenticeerde API geeft `401`, niet-toegestane gebruiker `403`;
- `https://pvdd.vdzonsoftware.nl` levert de bedoelde frontend;
- `/version.json` en `/api/version` tonen de uitgerolde SHA/buildtijd;
- Argo CD is `Synced` en `Healthy` op de verwachte revision;
- een onbekende wildcardhost komt niet bij PvdD uit.

## 14. Acceptatiecriteria volledige oplevering

De functionele MVP wordt in stappenplan 2 zelfstandig geaccepteerd. De volledige oplevering,
inclusief de later uitgevoerde Software Factory-aansluiting, is gereed wanneer:

1. alleen een toegestaan Google-account de app en beveiligde API kan gebruiken;
2. de app zonder hardcoded vergadering-ID de eerstvolgende commissie Ruimte vindt;
3. een nog niet gepubliceerde agenda correct als zodanig wordt getoond;
4. een gepubliceerde agenda inclusief gekoppelde openbare stukken reproduceerbaar wordt geïmporteerd;
5. ieder geïmporteerd bronstuk een opgeslagen SHA-256 en herleidbare bronmetadata heeft;
6. ieder A/B-punt de vijf gevraagde, niet-lege onderdelen met controleerbare bronnen heeft;
7. ieder C-punt een gemotiveerd ja/nee-advies over verplaatsing naar B heeft;
8. het verkiezingsprogramma aantoonbaar de primaire bron voor politieke waardering is;
9. de AI-uitvoering asynchroon en idempotent via een eigen PvdD Agent Runtime-tenant verloopt;
10. herstart van backend of browser geen lopende of afgeronde analyse verliest;
11. een nieuwe frontendbuild zonder handmatig cache wissen zichtbaar wordt;
12. frontend en backend hun commit-SHA en UTC-buildtijd tonen;
13. GitHub Actions alleen een groene, immutable build via GitOps naar OpenShift uitrolt;
14. `pvdd.vdzonsoftware.nl` via de declaratieve Route werkt;
15. het project in de werkelijk gebruikte Software Factory-projectconfiguratie selecteerbaar is;
16. analyses duidelijk als menselijk te controleren concept worden gepresenteerd;
17. de 05:00-run en “Nu controleren” stoppen zonder AI-call wanneer er geen nieuw bron-ID is;
18. acceptatie zowel Agent Runtime als de vergaderbron volledig mockt;
19. opgeslagen gegevens niet automatisch worden verwijderd.

## 15. Implementatievolgorde

De uitvoering is opgesplitst in vier normatieve stappenplannen, die strikt in deze volgorde worden
uitgevoerd:

1. [Technische fundering](stappenplannen/01-technische-fundering.md) — repositorybasis, auth,
   frontend/backend/database, caching, Agent Runtime-tenant, acceptatiemocks, OpenShift, CI/CD,
   GitOps en infrastructuur.
2. [Functionele MVP](stappenplannen/02-functionele-mvp.md) — vergaderingontdekking, import,
   documentextractie, 05:00-workflow, beleidsbron, AI-analyse, A/B/C-interface en functionele
   acceptatie.
3. [Bronrevisies en gerichte heranalyse](stappenplannen/04-bronrevisies-en-heranalyse.md) —
   voorlopige C-stukken, wijzigingsdetectie, revisiehistorie en selectieve heranalyse.
4. [Software Factory-aansluiting](stappenplannen/03-software-factory-aansluiting.md) —
   projectregistratie, automatische merge, GitOps-deploybewaking en gecontroleerde proefstories.

Het functionele plan blijft geblokkeerd totdat de harde technische acceptatiepoort T14 volledig
groen is en als `technical-baseline-v1` is vastgelegd. Het Software Factory-plan blijft vervolgens
geblokkeerd totdat de functionele poort F13 en bronactualiteitspoort R7 groen zijn en als
`functional-mvp-v1` respectievelijk `source-revision-v1` zijn vastgelegd.

## 16. Resterende besluiten

### 16.1 Bronrevisiecontract

De identiteit van een vergadering is geen inhoudsversie. Iedere controle legt daarom een immutable
bronsnapshot vast met publicatiestatus, canonieke fingerprint, controletijd en verschilsoorten.

- `PREVIEW`: de bron kondigt latere publicatie aan. Alleen reeds zichtbare veilige C-metadata en
  bronlinks zijn voorlopig beschikbaar; documenten en AI blijven geblokkeerd.
- `CURRENT`: de volledig gepubliceerde, laatst succesvol vastgelegde bronversie.
- `CHANGED`: een nieuwe canonieke bronversie verschilt inhoudelijk van `CURRENT`.
- `REPROCESSING`: gewijzigde punten worden opnieuw verwerkt; de vorige succesvolle versie blijft
  raadpleegbaar en ondubbelzinnig als verouderd gemarkeerd.
- `SUPERSEDED`: een oudere immutable snapshot is door een nieuwere succesvolle versie vervangen.
- `WITHDRAWN`: een eerder zichtbaar punt of document ontbreekt in de nieuwere bronversie en blijft
  alleen historisch raadpleegbaar.

De canonieke vergaderfingerprint bevat commissie, tijden, locatie en de geordende hiërarchie van
punten. De puntfingerprint bevat bron-ID, positie, categorie, titel, toelichting,
behandelvoorstel en geordende document-ID's plus document-SHA-256. URL-tracking, whitespace en
presentatie-HTML tellen niet mee. Een advies is uitsluitend actueel bij exact dezelfde
puntfingerprint, promptversie en beleidsbronversie.

Een expliciete publicatiemelding wint altijd van zichtbare punten. Een preview kan nooit een
documentdownload, prompt of Runtime-job veroorzaken. Na publicatie en bij iedere volgende
05:00-/handmatige controle wordt hetzelfde vergadering-ID opnieuw vergeleken. Alleen gewijzigde of
nieuwe punten krijgen een revisiegebonden idempotente analyse; oude resultaten kunnen een nieuwere
revisie nooit actueel maken.

Deze punten blokkeren het eerste specificatiedocument niet, maar moeten vóór de betreffende story
worden besloten:

1. Welk echt Agent Runtime-model wordt de productie-default? Dit kan bij implementatie uit de
   toegestane Runtime-catalogus worden gekozen.
2. Is OCR nodig voor gescande stukken die in de eerstvolgende echte agenda voorkomen? Dit volgt uit
   de bronspike; zonder leesbare tekst toont de MVP een expliciete fout in plaats van een onvolledige
   analyse.

## 17. Onderzochte referenties

Voor dit concept zijn de lokale repositories op 31 augustus 2026 onderzocht:

- `hkh-autopilot`: stack, Google-auth, frontendcache, GitHub Actions, PostgreSQL, OpenShift,
  Kustomize, Argo CD, documentatiestijl en Agent Runtime-aansluiting;
- `product-factory`: frontendthema, buildidentiteit/updatecontrole en actuele Agent Runtime-client;
- `agent-runtime`: v1 jobcontract, tenantpolicy, tokens, queue/resultaat en omgevingsgrenzen;
- `robberts-infrastructure`: app-of-apps, publieke wildcardroute en Google OAuth-onboarding;
- `softwarefactory`: `projects.yaml`, verificatie-, merge-, deploy- en live-componentconfiguratie.

De openbare iBabs-pagina is eveneens gecontroleerd. Historische vergaderpagina’s leveren
server-side HTML met agendahiërarchie, `/Agenda/Document/...`-bijlagen en C-items via
`/Reports/Item/...`; dit onderbouwt de keuze om eerst een gewone HTTP-importer te bouwen.
