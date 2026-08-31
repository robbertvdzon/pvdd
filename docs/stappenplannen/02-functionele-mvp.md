# Stappenplan 2 — functionele MVP

Status: concept 0.2 — geblokkeerd tot technische acceptatiepoort T14

Bron: [microservice-specificatie](../microservice-specificatie.md)

Afhankelijkheid: [technische fundering](01-technische-fundering.md)

## Doel en startvoorwaarde

Dit plan bouwt de functionele Commissie-assistent boven op de volledig geaccepteerde technische
baseline. Het resultaat vindt iedere ochtend de eerstvolgende nieuwe vergadering van commissie
Ruimte, leest de stukken, laat die via Agent Runtime analyseren en toont controleerbare A/B-adviezen
en C-bespreekbesluiten.

Start dit plan uitsluitend wanneer:

1. `docs/technical-baseline-verification.md` alle T14-controles groen verklaart;
2. de vastgelegde PvdD-commit de tag `technical-baseline-v1` heeft;
3. acceptance en production nog steeds `Synced` en `Healthy` zijn;
4. er geen open technisch baseline-incident bestaat.

## Uitvoeringsregels

- Voer iedere genummerde stap uit als een afzonderlijke, controleerbare wijziging of pull request.
- Gebruik Software Factory nog niet; die aansluiting is bewust het afsluitende
  [stappenplan 3](03-software-factory-aansluiting.md).
- Werk primair in `pvdd`; wijzig andere repositories alleen met een aparte, expliciete wijziging.
- Gebruik opgeslagen fixtures voor gewone tests; CI is niet afhankelijk van live iBabs of echte AI.
- Toon nooit een gedeeltelijke analyse alsof alle documenten zijn gelezen.
- Houd de MVP read-only: geen editor, goedkeuringsworkflow of historiepagina.
- Bewaar alle geïmporteerde gegevens en resultaten; bouw geen cleanupjob.

## F0 — Functionele contracten en fixtures vastzetten

Repository: `pvdd`

Werk:

1. Vertaal de functionele hoofdstukken van de specificatie naar `docs/factory/functional-spec.md`.
2. Leg DTO’s en toestanden vast voor vergadering, agenda-item, document, import en analyse.
3. Maak minstens één volledige synthetische fixture die de waargenomen iBabs-structuur van een
   commissie Ruimte-pagina representeert, inclusief A/B/C-structuur, reportitems en kleine
   documenten; kopieer geen volledige vergaderbundel.
4. Voeg aparte fixtures toe voor geen vergadering, agenda nog niet gepubliceerd, bronstoring,
   onleesbaar document en gewijzigde documenthash.
5. Leg het verwachte vijfdelige A/B-resultaat en C-resultaat als JSON Schema vast.

Acceptatie:

- Fixtures bevatten geen secrets, persoonsgegevens of onnodig overgenomen brontekst.
- Contracten dekken alle expliciete velden uit de specificatie.
- Er is nog geen netwerk- of AI-implementatie; contracttests kunnen wel compileren.

## F1 — Functioneel datamodel en Flywaymigraties

Repository: `pvdd`

Werk:

1. Maak Flywaymigraties voor `meeting`, `agenda_item`, `source_document`, `analysis_run`,
   `agenda_item_advice` en `policy_source`.
2. Modelleer bron-ID’s en SHA-256’s uniek genoeg voor idempotente import.
3. Leg statussen, timestamps, foutcodes, Runtime-job-ID, promptversie en bronfingerprint vast.
4. Maak repositories en pure domeinmodellen per Modulith-module.
5. Sla de laatst succesvol verwerkte vergadering transactioneel op.
6. Voeg geen bewaartermijn of deletepad toe.

Acceptatie:

- Een lege database migreert volledig; herhaald starten blijft groen.
- Repositorytests gebruiken Testcontainers.
- Duplicaatimport maakt geen dubbele vergadering, items of documenten.
- Een mislukte run kan de laatst succesvol verwerkte vergadering niet wijzigen.

## F2 — Vergadering ontdekken en agenda-HTML parseren

Repository: `pvdd`

Werk:

1. Implementeer een begrensde HTTP-client voor de geconfigureerde vergaderbron.
2. Ontdek zonder hardcoded vergadering-UUID de vroegste toekomstige commissie Ruimte-vergadering.
3. Parseer meetingmetadata, hiërarchie, volgorde, A/B/C-secties, toelichting en behandelvoorstel.
4. Parseer agenda-attachments en C-items met hun `/Reports/Item/...`-detailpagina’s.
5. Negeer functioneel lege punten zoals opening en pauze voor AI, maar behoud hun volgorde.
6. Behoud bron-URL en bron-ID voor ieder object.

Acceptatie:

- Alle opgeslagen HTML-fixtures worden deterministisch geparsed.
- De categorie komt uit de sectiehiërarchie, niet alleen uit het agendanummer.
- Geen toekomstige vergadering en ongepubliceerde agenda zijn normale, onderscheiden uitkomsten.
- Time-outs, onbekende HTML en bronfouten leveren veilige foutcodes.
- Parsertests doen geen live netwerkverkeer.

## F3 — Veilige documentdownload en tekstextractie

Repository: `pvdd`

Werk:

1. Download alleen vanaf expliciet toegestane HTTPS-hosts.
2. Begrens aantallen, bestandsgrootte, totale download, redirects, time-outs en retries.
3. Controleer MIME-type en magic bytes.
4. Ondersteun PDF, tekst, HTML en DOCX.
5. Extraheer tekst per pagina/sectie en bewaar bron-ID, URL, naam, SHA-256 en extractiestatus.
6. Markeer scans zonder tekst als `OCR_REQUIRED`; verzin of analyseer geen ontbrekende inhoud.
7. Sanitiseer HTML en voer nooit macro’s, scripts of documentinstructies uit.

Acceptatie:

- Fixtures dekken ieder ondersteund en geweigerd bestandstype.
- Hashes zijn reproduceerbaar en gewijzigde bytes geven een andere hash.
- Pagina-informatie blijft beschikbaar voor citaties.
- Een gedeeltelijk/onleesbaar dossier kan niet de status “volledig ingelezen” krijgen.

## F4 — Nieuwe-vergaderingworkflow om 05:00 en “Nu controleren”

Repository: `pvdd`

Werk:

1. Implementeer cron `0 0 5 * * *` in `Europe/Amsterdam` met injecteerbare klok.
2. Vergelijk het gevonden bron-ID met de laatst succesvol verwerkte vergadering.
3. Stop direct zonder document- of AI-call bij geen vergadering of hetzelfde bron-ID.
4. Importeer een nieuw bron-ID; markeer een ongepubliceerde agenda nog niet als verwerkt.
5. Maak `POST /api/meetings/check-now` met exact dezelfde orkestratie en concurrencygrens.
6. Voorkom dubbele gelijktijdige runs met een duurzame lock/status.
7. Laat mislukte imports bij de volgende controle hervatbaar zijn.

Acceptatie:

- Tests gebruiken vaste klokken voor wintertijd, zomertijd en DST-overgangen.
- Geen/same meeting veroorzaakt exact nul documentdownloads en nul Runtime-jobs.
- Een ongepubliceerde agenda wordt de volgende ochtend opnieuw geprobeerd.
- Twee gelijktijdige “Nu controleren”-requests creëren hoogstens één actieve run.
- Alleen volledig succes schuift het laatste bron-ID op.

## F5 — Verkiezingsprogramma als versieerbare beleidsbron

Repository: `pvdd`

Werk:

1. Importeer het Noord-Hollandse PvdD-verkiezingsprogramma 2023–2027 via de vastgelegde bron-URL.
2. Bewaar URL, SHA-256, ophaaldatum, paginanummer en tekstchunks.
3. Leg de kernassen uit de specificatie als gecontroleerde selectie-/retrievalregels vast.
4. Selecteer per agendapunt relevante passages zonder vrije websearch.
5. Voeg altijd de noodzakelijke kernprincipes en bronmetadata aan de AI-context toe.
6. Maak een expliciete fout wanneer de beleidsbron ontbreekt of niet verifieerbaar is.

Acceptatie:

- Dezelfde programmapdf levert dezelfde hash en chunks.
- Voor representatieve wonen-, natuur-, vervoer- en luchtvaartfixtures worden relevante passages
  met paginanummer geselecteerd.
- Een analyse kan niet starten zonder geldige primaire beleidsbron.

## F6 — Prompt, JSON-schema en resultaatvalidatie

Repository: `pvdd`

Werk:

1. Maak een versieerbare, prompt-injectionbestendige systeemprompt.
2. Scheid brondata duidelijk van instructies.
3. Definieer A/B-output met exact de vijf gevraagde onderdelen.
4. Definieer C-output met ja/nee, motivering, urgentie, doel en kernvraag.
5. Vereis citaties naar uitsluitend meegegeven document-/beleidspassages.
6. Valideer JSON Schema én domeinregels in de backend.
7. Ontwerp een gefaseerde documentnotitie- en synthesejob wanneer de promptlimiet wordt overschreden.
8. Versieer prompt, schema en selectiealgoritme.

Acceptatie:

- Golden tests accepteren geldige output en weigeren ontbrekende secties, onbekende bron-ID’s,
  verzonnen pagina’s, te lange tekst en vrije tekst buiten JSON.
- Prompt-injectionfixtures kunnen de rol, bronallowlist of outputvorm niet wijzigen.
- Grote dossiers worden gefaseerd verwerkt en nooit stilzwijgend afgekapt.

## F7 — Duurzame AI-orkestratie via Agent Runtime

Repository: `pvdd`

Werk:

1. Maak per inhoudelijk agenda-item een lokale `analysis_run` en duurzame outboxstatus.
2. Bouw stabiele idempotentiesleutels uit meeting/item, bronfingerprint en promptversie.
3. Dien uitsluitend `APPLICATION_WORK` in via de bestaande technische Runtime-client.
4. Reconcile `QUEUED`, `WAITING_FOR_WORKER`, `RUNNING`, `SUCCEEDED`, `FAILED` en `CANCELLED`.
5. Haal resultaten op, valideer ze en schrijf advies plus citaties transactioneel weg.
6. Herstel na backendrestart zonder dubbele jobs of verloren resultaten.
7. Markeer de vergadering pas succesvol wanneer alle vereiste items geldig zijn verwerkt.

Acceptatie:

- Integratietests met Fake Runtime dekken verloren submitresponse, restart, retry en ongeldig
  resultaat.
- Acceptance doorloopt de echte Agent Runtime-acceptatie-API met provider `MOCKED`.
- De requestthread wacht nooit op AI-uitvoering.
- Het PvdD-Runtime-token bereikt nooit frontend, databasepayload of logs.

## F8 — Functionele backend-API

Repository: `pvdd`

Werk:

1. Implementeer de beveiligde meeting-, agenda-item- en runroutes uit de specificatie.
2. Maak één overzichtprojectie voor de eerstvolgende/actuele vergadering.
3. Lever agenda-items met categorie, volgorde, importstatus, analysestatus en detail-ID.
4. Lever in detail de vijf A/B-onderdelen of het C-bespreekadvies plus bronnen.
5. Geef duidelijke statussen voor geen vergadering, ongepubliceerd, bezig, gedeeltelijk en mislukt.
6. Pas rate limiting en idempotentie toe op `check-now`.

Acceptatie:

- API-contracttests dekken iedere status en categorie.
- Alle functionele routes vereisen Google-auth.
- DTO’s lekken geen database-, prompt- of Runtime-interne gegevens.
- Onbekende of niet-zichtbare ID’s geven veilige `404`.

## F9 — Functionele frontend-MVP

Repository: `pvdd`

Werk:

1. Vervang de lege technische shell door het vergaderingsoverzicht.
2. Toon datum/tijd, bronlink, laatst gecontroleerd, status en knop “Nu controleren”.
3. Toon A/B/C-filters, geordende agendapunten en voortgang.
4. Toon bij A/B exact de vijf analyseonderdelen.
5. Toon bij C ja/nee, urgentie, motivering, commissiedoel en kernvraag.
6. Toon klikbare bronnen en de waarschuwing “AI-concept — controleer bronnen en formulering”.
7. Houd de MVP read-only, zonder historie-, edit- of goedkeuringsscherm.
8. Behoud technische builddialoog, updatecontrole en toegankelijkheid.

Acceptatie:

- Widgettests dekken geen vergadering, ongepubliceerd, bezig, geslaagd, mislukt en alle categorieën.
- “Nu controleren” voorkomt dubbele clicks en ververst de status.
- Alle bronlinks zijn veilig en openen niet als uitvoerbare HTML in de app.
- De layout werkt vanaf 320 px en volledig met toetsenbord.

## F10 — Eigen logo en favicon

Repository: `pvdd`

Werk:

1. Ontwerp een eigen beeldmerk rond dieren/natuur, commissievoorbereiding en Noord-Holland.
2. Kopieer niet zonder toestemming het officiële PvdD-logo.
3. Lever een schaalbare bron plus SVG en PNG-varianten voor site, favicon en webmanifest.
4. Controleer herkenbaarheid op 16×16, lichte/donkere achtergrond en voldoende contrast.
5. Laat de gebruiker het definitieve ontwerp expliciet goedkeuren.

Acceptatie:

- Goedgekeurde assets staan geoptimaliseerd en zonder overbodige metadata in de repository.
- Browserfavicon, login en applicatieshell gebruiken dezelfde herkenbare identiteit.
- De app blijft zonder layout shift en zonder ontbrekende asset laden.

## F11 — Volledige acceptatie met vergaderbronmock en gemockte AI

Repository: `pvdd`

Werk:

1. Breid de technische mockserver uit met de in F0 vastgelegde scenario’s.
2. Registreer deterministische Agent Runtime-mockresultaten voor A/B, C, fout en groot dossier.
3. Test de hele keten: 05:00/check-now → import → extractie → Runtime → opslag → frontend.
4. Test herstart tijdens import en tijdens AI-uitvoering.
5. Test dat dezelfde vergadering geen tweede AI-run veroorzaakt.
6. Test environment guards opnieuw.

Acceptatie:

- Alle scenario’s draaien in de echte OpenShift-acceptatieomgeving zonder live iBabs of echte AI.
- `/version.json` en `/api/version` horen bij dezelfde geteste release.
- Argo CD blijft `Synced` en `Healthy`.
- De bewijsresultaten staan in `docs/functional-acceptance-verification.md`.

## F12 — Productiebronspike en OCR-besluit

Repository: `pvdd`

Werk:

1. Draai een read-only import tegen de actuele openbare commissie Ruimte-bron.
2. Inventariseer documenttypen, groottes, tekstdekking en eventuele scans.
3. Vergelijk de parse-uitkomst handmatig met de bronpagina.
4. Leg vast of OCR werkelijk nodig is.
5. Wanneer OCR nodig is: maak hiervoor een aparte vervolgwijziging en houd productie functioneel
   geblokkeerd totdat gescande essentiële stukken niet meer stilzwijgend ontbreken.
6. Pas geen live brondata handmatig in de database aan om tests groen te maken.

Acceptatie:

- Vergadering, categorieën, items en documentaantallen komen overeen met de openbare bron.
- Ieder onleesbaar document is zichtbaar gemarkeerd.
- Het OCR-besluit en bewijs zijn gedocumenteerd.
- Er is nog geen echte AI-opdracht op productie uitgevoerd.

## F13 — Productiepromotie en functionele MVP-poort

Repositories: `pvdd`; alleen bij een gevonden platformfout een aparte wijziging in de eigenaarrepo

Werk:

1. Promoveer exact de in F11 geaccepteerde images via GitOps naar productie.
2. Voer “Nu controleren” eenmaal gecontroleerd uit met de echte openbare bron.
3. Laat de echte Agent Runtime de nieuwe vergadering verwerken wanneer er een nieuwe,
   gepubliceerde vergadering beschikbaar is.
4. Vergelijk minimaal één A/B-resultaat en één C-resultaat handmatig met alle bronstukken.
5. Controleer Google-toegang, bronlinks, cacheverversing, buildidentiteit, logs en database.
6. Leg afwijkingen vast als nieuwe backlogitems; repareer niets handmatig in de clusterresources.
7. Leg de gezamenlijke bewijsronde vast in `docs/functional-mvp-verification.md`.

Functionele eindpoort:

- [ ] De eerstvolgende vergadering wordt zonder hardcoded UUID gevonden.
- [ ] Geen/same meeting stopt zonder document- of AI-call.
- [ ] Een ongepubliceerde agenda wordt later opnieuw geprobeerd.
- [ ] Alle inhoudelijke A/B-items hebben vijf geldige onderdelen met bronnen.
- [ ] Alle C-items hebben een gemotiveerd ja/nee-advies met bronnen.
- [ ] Het verkiezingsprogramma is aantoonbaar de primaire politieke bron.
- [ ] Geen essentieel document is stilzwijgend overgeslagen of afgekapt.
- [ ] De workflow is restartbestendig en idempotent.
- [ ] De frontend is read-only, toegankelijk en toont de AI-conceptwaarschuwing.
- [ ] Alleen de twee toegestane Google-accounts hebben toegang.
- [ ] Acceptance blijft volledig gemockt; production gebruikt echte bron en echte AI.
- [ ] Alle gegevens blijven bewaard en er draait geen cleanupjob.
- [ ] Frontend en backend tonen de daadwerkelijk uitgerolde SHA/buildtijd.
- [ ] Argo CD bevestigt de productie-uitrol en beide deployments zijn gezond.
- [ ] Functionele en operationele documentatie is actueel.

Alleen bij vijftien groene controles krijgt de release status **FUNCTIONELE MVP GEREED**. Tag de
repository daarna met `functional-mvp-v1` en leg de definitieve commit-SHA’s vast in het
acceptatiebewijs. Pas daarna mag
[stappenplan 3 — Software Factory-aansluiting](03-software-factory-aansluiting.md) starten.
