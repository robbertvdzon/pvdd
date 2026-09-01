# Stappenplan 4 — bronrevisies en gerichte heranalyse

Status: **BRONACTUALITEIT GEREED**

Bron: [microservice-specificatie](../microservice-specificatie.md)

Afhankelijkheid: de gemockte functionele acceptance F11 en productiebronspike F12 uit
[functionele MVP](02-functionele-mvp.md)

Uitvoeringspositie: voer dit nieuw toegevoegde plan na F11/F12 en vóór de extern op publicatie
wachtende F13-proef uit, en in ieder geval vóór
[stappenplan 3 — Software Factory-aansluiting](03-software-factory-aansluiting.md) uit. De
bestandsnummering weerspiegelt de toevoegingsvolgorde; de uitvoeringsvolgorde is 1 → 2 → 4 → 3.

## Aanleiding

De openbare pagina van de vergadering van commissie Ruimte van 14 september 2026 liet vóór de
aangekondigde publicatiedatum al C-stukken zien, terwijl de pagina expliciet meldde dat de volledige
agenda pas op 3 september zou worden gepubliceerd. Aanwezigheid van één of meer zichtbare punten is
daarom geen betrouwbaar bewijs dat de agenda compleet is.

Daarnaast is een vergadering-ID alleen de identiteit van de vergadering, niet de versie van haar
agenda. Na de eerste publicatie kunnen punten en stukken worden toegevoegd, verwijderd, verplaatst
of vervangen. De functionele MVP stopt bewust bij een reeds succesvol verwerkt vergadering-ID. Dit
vervolgplan vervangt dat eenvoudige MVP-criterium door revisiedetectie zonder de oplevering van de
lopende MVP open te breken.

## Doel en harde productregels

Dit plan maakt bronactualiteit zichtbaar en zorgt dat adviezen aantoonbaar bij de actuele openbare
agenda en stukken horen.

1. Een expliciete melding dat de agenda later wordt gepubliceerd, heeft voorrang op reeds zichtbare
   agendapunten. De vergadering blijft dan `AGENDA_UNPUBLISHED`.
2. Reeds openbare C-stukken mogen vóór volledige publicatie als **voorlopig** worden getoond met
   bronlinks. Zij maken de agenda niet compleet, maar beschikbare documenten en AI-analyse worden
   direct verwerkt en ondubbelzinnig als voorlopig getoond.
3. Na volledige publicatie controleert zowel de 05:00-run als **Nu controleren** ook een vergadering
   die eerder succesvol is verwerkt.
4. Alleen een gelijk vergadering-ID én een gelijke canonieke bronfingerprint betekent
   `UNCHANGED`.
5. Een inhoudelijke wijziging maakt het bestaande advies zichtbaar verouderd en start automatisch
   een gerichte heranalyse. Ongewijzigde punten worden niet opnieuw geanalyseerd.
6. De laatst succesvol geverifieerde versie blijft raadpleegbaar tijdens herverwerking. De app mag
   een oud advies nooit ongemarkeerd als actueel tonen.
7. Bronrevisies, eerdere adviezen en auditmetadata blijven bewaard; een nieuwe import overschrijft
   of verwijdert geen historie.
8. Opmaak-, tracking- en andere niet-inhoudelijke HTML-wijzigingen veroorzaken geen heranalyse.

## Acceptatieomgeving voor testers

De acceptatieomgeving is een echte end-to-endomgeving voor frontend, backend, PostgreSQL,
Flywaymigraties, scheduler, API en orkestratie. Alleen de externe afhankelijkheden zijn
deterministisch afgeschermd:

- de vergadering-, agenda-, document- en beleidsbron komen uitsluitend uit de interne mockserver;
- de echte Agent Runtime-acceptatie-API wordt gebruikt, maar accepteert uitsluitend provider
  `MOCKED` en levert vooraf vastgelegde resultaten;
- acceptance bevat geen productiebrondata, echt modelgebruik, productietokens of koppeling naar de
  productiedatabase.

Voor eenvoudig handmatig testen gebruikt `https://pvdd-acceptance.vdzonsoftware.nl` geen
Google-authenticatie. Dit is een expliciete acceptance-only modus met de volgende harde grenzen:

1. Frontend en backend gebruiken `PVDD_AUTH_MODE=acceptance-bypass` uitsluitend in combinatie met
   `PVDD_ENVIRONMENT=acceptance`.
2. De backend behandelt iedere request als één vaste synthetische identiteit, bijvoorbeeld
   `acceptance-tester@pvdd.invalid`; er bestaat geen bypassheader, queryparameter of speciaal token.
3. Production en iedere onbekende omgeving weigeren bij startup elke authmodus anders dan
   `google`. Het ontbreken van Google-configuratie blijft daar fail-closed.
4. De acceptance-frontend slaat het loginscherm over en toont permanent een opvallende banner
   **ACCEPTANCE — gemockte gegevens — geen authenticatie**.
5. De acceptance-route krijgt `noindex`, een begrensde requestfrequentie en geen links vanuit de
   productieapp. De omgeving is niet vertrouwelijk: iedereen die de URL kent kan de synthetische
   testdata gebruiken.
6. De productiebuild bevat geen uitvoerbaar pad waarmee de acceptance-bypass kan worden
   geactiveerd. CI test deze scheiding op gerenderde manifests én bij applicatiestart.
7. De Google-client-ID is niet nodig in de acceptancefrontend of -backend en wordt daar na invoering
   van deze modus niet meer als secret/configuratie geïnjecteerd.

Niet in scope:

- handmatig wijzigen of goedkeuren van adviezen;
- terugschrijven naar iBabs;
- meerdere commissies;
- vrije websearch door de AI;
- periodieke verwerking nadat de vergadering is begonnen, behalve een expliciete handmatige
  controle voor herstel of bewijsvoering.

## R0 — Revisiecontract en fixtures vastzetten

Repository: `pvdd`

Werk:

1. Breid de normatieve en compacte functionele specificatie uit met `PREVIEW`, `CURRENT`,
   `CHANGED`, `REPROCESSING`, `SUPERSEDED` en `WITHDRAWN` waar die toestanden nodig zijn.
2. Leg afzonderlijke DTO's vast voor publicatiestatus, bronrevisie, verschilsoort en actualiteit van
   een advies.
3. Leg vast welke velden de canonieke agenda-, punt- en documentfingerprint vormen.
4. Maak synthetische fixtureparen voor:
   - voorpublicatie met zichtbare C-stukken → volledige publicatie met hetzelfde vergadering-ID;
   - toegevoegd en verwijderd agendapunt;
   - gewijzigde volgorde en A/B/C-categorie;
   - gewijzigd behandelvoorstel of toelichting;
   - toegevoegd, verwijderd en vervangen document;
   - gewijzigde documentbytes achter dezelfde URL;
   - uitsluitend irrelevante HTML-/opmaakwijziging.
5. Leg als invariant vast dat een advies alleen actueel is voor exact zijn itemfingerprint,
   promptversie en beleidsbronversie.

Acceptatie:

- De fixtures bevatten geen persoonsgegevens, secrets of onnodig gekopieerde brontekst.
- Iedere verschilsoort heeft een eenduidige verwachte status en herverwerkingsbeslissing.
- Een vergadering-ID wordt nergens meer als inhoudsversie beschreven.
- Voorlopige C-stukken zijn zichtbaar en veroorzaken direct een AI-job zodra hun inhoud leesbaar is.

## R1 — Duurzame bronrevisies en snapshots

Repository: `pvdd`

Werk:

1. Voeg Flywaymigraties toe voor immutable vergader-/agendasnapshots en revisiemetadata, of breid
   het bestaande model equivalent uit zonder historie te overschrijven.
2. Bewaar per controle minimaal controletijd, publicatiestatus, canonieke fingerprint, vorige
   revisie, verschilsoorten en bron-URL.
3. Bewaar per agendapunt de geldende revisie en status actueel, voorlopig, vervangen of ingetrokken.
4. Bewaar voor documenten bron-ID, URL, `ETag`, `Last-Modified`, grootte en SHA-256 voor zover de
   bron deze metadata betrouwbaar levert.
5. Wissel de actuele snapshot en adviesactualiteit transactioneel; een half geïmporteerde revisie
   mag nooit als actueel verschijnen.

Acceptatie:

- Meerdere revisies van hetzelfde vergadering-ID bestaan naast elkaar en zijn herleidbaar.
- Een mislukte revisie-import laat de vorige succesvolle snapshot intact.
- Verwijderde punten en documenten blijven historisch aantoonbaar, maar staan niet als actueel.
- Er bestaat geen cleanup- of deletepad voor revisiehistorie.

## R2 — Canonieke vergelijking en begrensde broncontrole

Repository: `pvdd`

Werk:

1. Haal bij iedere geplande of handmatige controle minimaal de actuele vergadering- en agendapagina
   op, ook als het vergadering-ID al succesvol is verwerkt.
2. Bouw een canonieke agendafingerprint uit betekenisvolle metadata, geordende hiërarchie,
   categorieën, punt-ID's, titels, toelichtingen, behandelvoorstellen en documentreferenties.
3. Sluit vluchtige markup, whitespace, trackingparameters en presentatiedetails uit.
4. Gebruik conditionele requests met `ETag` en `Last-Modified` waar mogelijk, maar vertrouw voor
   inhoudelijke gelijkheid uiteindelijk op SHA-256.
5. Controleer vóór aanvang van de vergadering ook documentbytes opnieuw wanneer de bron geen
   betrouwbare wijzigingsvalidator biedt. Respecteer bestaande hostallowlist, grootte-, tempo- en
   time-outgrenzen.
6. Classificeer verschillen minimaal als punt toegevoegd, ingetrokken, verplaatst, van categorie
   veranderd, metadata gewijzigd, document toegevoegd/verwijderd of documentinhoud gewijzigd.

Acceptatie:

- Gelijke canonieke inhoud levert deterministisch dezelfde fingerprint.
- Niet-inhoudelijke HTML-wijzigingen leveren nul documentdownloads indien bronvalidators dat veilig
  toestaan en altijd nul AI-jobs.
- Gewijzigde bytes achter dezelfde document-URL worden gedetecteerd.
- Een bronfout maakt bestaande resultaten niet actueel en verwijdert ze ook niet.

## R3 — Voorpublicatie en voorlopige C-stukken

Repository: `pvdd`

Werk:

1. Laat een expliciete toekomstige publicatiemelding prevaleren boven de aanwezigheid van C-items.
2. Importeer van reeds zichtbare C-items metadata, bronlinks en beschikbare documenten.
3. Markeer de vergadering en ieder previewitem ondubbelzinnig als voorlopig en mogelijk onvolledig.
4. Start direct documentdownload, tekstextractie en AI-analyse voor ieder zichtbaar inhoudelijk
   previewitem; markeer het advies als voorlopig.
5. Laat een tijdelijk onbereikbare detailpagina of bijlage de overige voorlopige stukken niet
   blokkeren; verwerk beschikbare inhoud en probeer het ontbrekende deel bij de volgende controle
   opnieuw.
6. Controleer de bron de volgende ochtend en via **Nu controleren** opnieuw.
7. Koppel previewitems bij publicatie op stabiele bron-ID aan de definitieve agenda; behandel
   verdwenen previewitems als ingetrokken zonder ze historisch te wissen en analyseer de nieuwe
   publicatieversie opnieuw.

Acceptatie:

- De pagina kan niet door alleen C-items ten onrechte als volledig gepubliceerd gelden.
- De frontend toont welke informatie voorlopig is en wanneer opnieuw is gecontroleerd.
- Previewdata verschijnt in een voorlopig gemarkeerde AI-analyse en nooit ongemarkeerd als
  definitief advies.
- Volledige publicatie met hetzelfde vergadering-ID wordt opnieuw geïmporteerd en geanalyseerd.

## R4 — Gerichte heranalyse en concurrency

Repository: `pvdd`

Werk:

1. Bereken per inhoudelijk agendapunt een fingerprint uit puntinhoud, categorie, geordende
   document-ID's en document-SHA's.
2. Behoud actuele adviezen voor punten met een gelijke fingerprint.
3. Markeer adviezen van gewijzigde of ingetrokken punten direct als verouderd voordat nieuwe
   resultaten actueel kunnen worden.
4. Maak alleen voor toegevoegde of inhoudelijk gewijzigde punten nieuwe idempotente Runtime-jobs.
5. Heranalyseer bij een categorieovergang met het juiste A/B- of C-contract.
6. Voorkom dat een laat resultaat van een oudere bronrevisie de nieuwste revisie actueel maakt.
7. Maak een revisie die tijdens verwerking opnieuw verandert veilig hervatbaar en voorkom dubbele
   jobs met revisiegebonden idempotentiesleutels.
8. Rond de nieuwe revisie pas af wanneer alle vereiste gewijzigde punten geldig zijn verwerkt.

Acceptatie:

- Eén gewijzigd document veroorzaakt alleen heranalyse van de afhankelijke punten.
- Een ongewijzigd punt behoudt exact zijn bestaande advies en maakt geen Runtime-job.
- Een ingetrokken punt blijft historisch zichtbaar maar niet in het actuele overzicht.
- Restart, verloren submitresponse en twee gelijktijdige controles maken geen dubbel actueel advies.
- Een oud Runtime-resultaat kan een nieuwere revisie nooit overschrijven.

## R5 — API en frontend voor actualiteit

Repository: `pvdd`

Werk:

1. Voeg aan overzicht en detail publicatiestatus, revisienummer/fingerprint, laatst gecontroleerd en
   adviesactualiteit toe zonder Runtime- of database-internals te lekken.
2. Toon duidelijke labels voor **Voorlopige agenda**, **Bron gewijzigd**, **Analyse wordt vernieuwd**,
   **Actueel** en **Ingetrokken**.
3. Houd het laatste geldige advies tijdens heranalyse beschikbaar met een opvallende waarschuwing
   dat het bij een oudere bronversie hoort.
4. Toon per gewijzigd punt op hoofdlijnen wat veranderde en wanneer dat werd ontdekt; toon geen
   technische HTML-diff.
5. Laat **Nu controleren** onderscheiden melden: ongewijzigd, wijziging gevonden, heranalyse gestart
   of broncontrole mislukt.
6. Behoud read-only gedrag, bronlinks, toegankelijkheid, mobiele layout en de AI-conceptwaarschuwing.
7. Implementeer de acceptance-only authmodus uit het omgevingscontract in frontend en backend;
   houd productie ongewijzigd op Google SSO met backendallowlist.
8. Toon in acceptance op iedere pagina de vaste omgevingsbanner en voorkom indexering door
   zoekmachines via headers en metadata.

Acceptatie:

- Een gebruiker kan nooit een verouderd advies voor actueel aanzien.
- Voorlopige C-stukken zijn bruikbaar zichtbaar zonder de indruk van volledigheid.
- Alle statussen zijn met toetsenbord en screenreader begrijpelijk en niet uitsluitend met kleur
  aangegeven.
- Widget- en API-contracttests dekken alle revisie- en foutstatussen.
- Een tester opent acceptance direct zonder account, loginactie, token of browserconfiguratie.
- Dezelfde configuratie op production of een onbekende omgeving laat de applicatie aantoonbaar
  niet starten.

## R6 — End-to-endacceptatie en productiepromotie

Repositories: `pvdd`; alleen bij een gevonden platformfout een aparte wijziging in de eigenaarrepo

Werk:

1. Doorloop alle R0-fixtureparen in de echte OpenShift-acceptatieomgeving met gemockte bron en AI.
2. Bewijs de keten preview → voorlopige analyse → publicatie → nieuwe analyse → bronwijziging →
   gerichte heranalyse → actueel.
3. Bewijs dat een opmaakwijziging en volledig ongewijzigde controle nul AI-jobs veroorzaken.
4. Bewijs herstel na backendrestart tijdens revisie-import en tijdens heranalyse.
5. Draai vóór productiepromotie een niet-muterende bronvergelijking tegen de actuele openbare bron.
6. Promoveer exact de geaccepteerde images via GitOps en voer één gecontroleerde **Nu controleren**
   uit.
7. Leg bewijs, relevante fingerprints, SHA/buildtijd en Argo CD-status vast in
   `docs/source-revision-verification.md` zonder broninhoud of persoonsgegevens te kopiëren.
8. Test zonder Google-sessie in een schone browser dat acceptance direct bruikbaar is en alle
   functionele routes met de synthetische testeridentiteit werken.
9. Test met gerenderde productionmanifests en een negatieve starttest dat de authbypass daar niet
   kan worden geactiveerd.

Acceptatie:

- Acceptance gebruikt geen live iBabs en geen echte AI.
- Acceptance vereist geen authenticatie en bevat uitsluitend synthetische gegevens.
- Productiecontrole wijzigt niets rechtstreeks in iBabs of clusterresources.
- Productie blijft Google SSO en de tweeadressenallowlist afdwingen.
- Frontend en backend draaien exact de geaccepteerde SHA.
- Argo CD blijft `Synced` en `Healthy`.

## R7 — Harde bronactualiteitspoort

Alle onderstaande controles zijn verplicht:

- [x] Een expliciete toekomstige publicatiemelding wint van reeds zichtbare C-items.
- [x] Voorlopige C-items zijn herkenbaar zichtbaar en starten direct document- en AI-verwerking.
- [x] Publicatie met hetzelfde vergadering-ID wordt gedetecteerd en volledig verwerkt.
- [x] Alleen gelijk vergadering-ID plus gelijke canonieke fingerprint betekent ongewijzigd.
- [x] Toevoegen, intrekken, verplaatsen en categoriewijziging worden correct herkend.
- [x] Toegevoegde, verwijderde en op dezelfde URL vervangen documenten worden correct herkend.
- [x] Niet-inhoudelijke HTML-wijzigingen veroorzaken geen AI-job.
- [x] Alleen gewijzigde of nieuwe punten worden opnieuw geanalyseerd.
- [x] Ongewijzigde adviezen blijven behouden.
- [x] Een verouderd advies wordt nooit ongemarkeerd als actueel getoond.
- [x] Een ouder Runtime-resultaat kan een nieuwere revisie niet overschrijven.
- [x] Revisies en eerdere adviezen blijven auditbaar bewaard.
- [x] Geplande en handmatige controle delen dezelfde veilige orkestratie.
- [x] Bronfouten en herstarts beschadigen de laatst succesvolle snapshot niet.
- [x] De volledige acceptatieketen is groen met gemockte bron en AI.
- [x] Acceptance is in een schone browser zonder account of token direct bruikbaar.
- [x] Acceptance toont permanent dat gegevens gemockt zijn en authenticatie uitstaat.
- [x] De authbypass kan in production en onbekende omgevingen niet worden geactiveerd.
- [x] Acceptance bevat geen Google-configuratie, productiebrondata, echte AI of productietokens.
- [x] De productie-uitrol draait de bewezen SHA en Argo CD is gezond.

Poortbesluit:

- Alleen bij twintig groene controles krijgt deze uitbreiding status **BRONACTUALITEIT GEREED**.
- Tag daarna de repository met `source-revision-v2` en leg de definitieve commit-SHA vast in het
  bewijsdocument.
- Pas daarna mag [stappenplan 3 — Software Factory-aansluiting](03-software-factory-aansluiting.md)
  starten.
