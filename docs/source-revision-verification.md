# Bronrevisies en gerichte heranalyse — verificatiebewijs

Status: **BRONACTUALITEIT GEREED**

Datum: 1 september 2026

Definitieve applicatiecommit: `0cadc2af26a425c1f271f0c09742b8b58f1e02b5`

Release-tag: `source-revision-v2`

De historische tag `source-revision-v1` blijft ongewijzigd bestaan. V2 vervangt het eerdere
poortbesluit omdat voorlopige stukken nu direct worden geanalyseerd en bij nieuwe broninformatie
opnieuw worden verwerkt.

Dit document legt het bewijs vast voor R0 tot en met R7 uit
[stappenplan 4](stappenplannen/04-bronrevisies-en-heranalyse.md). Het bevat geen secrets, tokens of
gekopieerde persoonsgegevens uit bronstukken.

## Bewezen productgedrag

- Zichtbare A-, B- en C-stukken worden ook vóór publicatie direct gedownload, geëxtraheerd en
  geanalyseerd. UI en API markeren deze informatie als **Voorlopige agenda**.
- Een nieuwe of gewijzigde voorlopige versie krijgt een nieuwe canonieke bronrevisie. Alleen
  gewijzigde itemfingerprints worden opnieuw aangeboden aan Agent Runtime.
- Een volledig ongewijzigde controle maakt geen nieuwe revisie of AI-opdracht.
- Publicatie van dezelfde vergadering is zelf een betekenisvolle wijziging en veroorzaakt een
  nieuwe analyse, ook als de stukinhoud gelijk is.
- Een timeout van één rapport of document blokkeert de overige beschikbare stukken niet. Een
  volgende controle probeert ontbrekende informatie opnieuw; nieuw beschikbare inhoud veroorzaakt
  dan automatisch een nieuwe revisie en heranalyse.
- Het laatste geldige advies blijft tijdens herverwerking zichtbaar als oud advies en kan nooit
  ongemarkeerd actueel worden.

## Geautomatiseerde verificatie en release

| Controle | Resultaat |
|---|---|
| Lokale backendtest | 88 tests groen, 0 fouten, 1 opt-in live-spike overgeslagen |
| Lokale frontendtest | 8 tests groen; analyse en cachecontract groen |
| Repository verification | GitHub Actions-run `33506239539`, groen voor `0cadc2a…` |
| Build and promote release | GitHub Actions-run `33506902801`, groen voor dezelfde commit |
| Buildtijd backend en frontend | `2026-09-01T12:17:55Z` |
| Acceptance GitOps-commit | `711c457…` |
| Production GitOps-commit | `a9434a5…` |
| Argo CD na productiepromotie | `pvdd-acceptance` en `pvdd`: `Synced`, `Healthy` |

De publieke `/api/version`-endpoints van acceptance en productie rapporteerden exact commit
`0cadc2a…` en dezelfde buildtijd. De release gebruikte immutable images en de normale
acceptance-naar-productiepromotie; er is geen handmatige wijziging in een deployment uitgevoerd.

## Synthetische acceptanceketen

`tools/run-source-revision-acceptance.sh` is tegen de echte OpenShift-acceptanceomgeving
uitgevoerd. PostgreSQL, Flyway, backend, frontend en de Agent Runtime-acceptatie-API waren onderdeel
van de keten; bron- en AI-resultaten waren deterministisch gemockt.

De keten bewees onder meer:

1. eerste zichtbare preview direct geïmporteerd en geanalyseerd;
2. nieuwe informatie in dezelfde preview als revisie 2 opnieuw verwerkt;
3. identieke hercontrole zonder nieuwe revisie of Runtime-job;
4. publicatie van hetzelfde vergadering-ID opnieuw verwerkt;
5. uitsluitend opmaak gewijzigd zonder AI-job;
6. punt toegevoegd, ingetrokken, verplaatst en van categorie veranderd, steeds inclusief
   terugkeer naar de vorige toestand;
7. metadata, documentset en documentbytes achter dezelfde URL gewijzigd en teruggedraaid.

De volledige matrix eindigde met negentien revisies en elf succesvolle finale adviezen. De
adviesactualiteit eindigde op drie `CURRENT`, zeven `STALE` en één `WITHDRAWN`. De extra
broncontroles die nodig waren door acceptance-rate-limits bleven auditbaar en maakten geen dubbele
actuele adviezen.

## Live bron en foutisolatie

De opt-in read-only bronspike tegen de openbare Noord-Hollandse bron herkende de vergadering van
14 september 2026 als nog niet volledig gepubliceerd en vond vijf zichtbare C-items. Beschikbare
PDF's werden zonder OCR geëxtraheerd. Een trage rapportpagina en afzonderlijke documenttimeouts
werden geïsoleerd: bruikbare stukken bleven beschikbaar en de spike eindigde zonder database- of
AI-mutatie.

Bij de echte productiebron bleek bovendien dat PDFBox tekst uit twee gedrukte kolommen kan
interleaven en woorden kan splitsen. De citaatcontrole houdt daarom bron-ID, brontype, pagina en
sectie strikt gelijk en vereist vervolgens een volgordevaste overeenkomst van minimaal 80% van de
citaatwoorden. Daarmee worden opmaak- en kleine OCR-artefacten geaccepteerd, maar een verzonnen of
aan een andere bron gekoppeld citaat blijft ongeldig.

De drie aangetroffen productie-integratieproblemen zijn afgedekt met regressietests:

- een langzame detailpagina rolt niet langer de volledige voorlopige import terug;
- alle objecten in de strict Agent Runtime-response-schema's noemen al hun properties in
  `required`;
- lege C-verzamelpunten hoeven geen niet-bestaande beleidsrelatie te verzinnen wanneer het advies
  is om ze niet naar B te verplaatsen.

## Productiebewijs

Op de definitieve build is via de bestaande, toegestane Google-sessie **Nu controleren** gebruikt.
Er is geen Google- of Agent Runtime-token uitgelezen, gekopieerd of vastgelegd.

De openbare bron veranderde tijdens de proeven meerdere keren in bereikbare metadata en
documentinhoud. Iedere betekenisvolle verandering leverde direct een nieuwe voorlopige revisie en
heranalyse. De eindtoestand in productie is:

- vergadering `a3de1271-fd63-4e24-8a38-6a6df474ec9d` op status `COMPLETE`;
- `publication_status=PREVIEW` en bronrevisie 6 als enige `CURRENT` revisie;
- vijf finale runs met `prompt_version=pvdd-advice-v6`, alle vijf `SUCCEEDED`;
- vijf adviezen met actualiteit `CURRENT`;
- zes geëxtraheerde documenten en drie afzonderlijk auditbare `DOWNLOAD_FAILED/TIMEOUT`-pogingen;
- frontend: **5/5 analyses gereed**, **Gereed** en **Voorlopige agenda**;
- ieder actueel C-advies bevat de beslissing over verplaatsen naar B, urgentie, motivering,
  commissiedoel, eventuele kernvraag en broncitaten.

Oudere mislukte, geannuleerde en vervangen runs zijn bewust niet verwijderd. Zij bewijzen de
herstelketen en tellen niet mee als actueel advies. Agent Runtime verwerkte opdrachten serieel; twee
lange v6-opdrachten slaagden via de bestaande automatische retry na een eerste execution-timeout.

## Authenticatie- en omgevingsgrenzen

- Acceptance opent met de synthetische testeridentiteit en de vaste banner
  **ACCEPTANCE — gemockte gegevens — geen authenticatie**.
- Acceptance antwoordt met `X-Robots-Tag: noindex, nofollow`.
- Productie blijft via Google SSO en de backendallowlist voor
  `marchanou@gmail.com` en `robbertvdzon@gmail.com` afgeschermd.
- Manifest- en negatieve applicatiestarttests bewijzen dat `acceptance-bypass` niet in productie of
  een onbekende omgeving kan starten.
- Acceptance bevat geen Google-configuratie, productiebron, echte AI-provider of productietoken.

## Poortbesluit

Alle twintig R7-controles zijn groen. Stappenplan 4 is afgerond en de bewezen applicatiecommit is
vastgezet met tag `source-revision-v2`. De volgende fase blijft
[stappenplan 3 — Software Factory-aansluiting](stappenplannen/03-software-factory-aansluiting.md).
