# Bronrevisies en gerichte heranalyse — verificatiebewijs

Status: **BRONACTUALITEIT GEREED**

Datum: 1 september 2026

Definitieve applicatiecommit: `5821599af9d8fb70eec9ccd3eb1b790e270c0987`

Release-tag: `source-revision-v1`

Dit document legt het bewijs vast voor R0 tot en met R7 uit
[stappenplan 4](stappenplannen/04-bronrevisies-en-heranalyse.md). Het bevat geen secrets,
tokens, persoonsgegevens uit bronstukken of gekopieerde documentinhoud.

## Geautomatiseerde verificatie en release

| Controle | Resultaat |
|---|---|
| Lokale backendtest | 80 tests groen, 0 fouten, 1 opt-in live-spike overgeslagen |
| Repository verification | GitHub Actions-run `33492460400`, groen voor commit `5821599…` |
| Build and promote release | GitHub Actions-run `33493074046`, groen voor dezelfde commit |
| Buildtijd backend en frontend | `2026-09-01T09:36:12Z` |
| Acceptance GitOps-commit | `e7d64a2…` |
| Production GitOps-commit | `f5b026b…` |
| Argo CD na productiepromotie | `pvdd-acceptance` en `pvdd`: `Synced`, `Healthy`, revision `f5b026b…` |

De workflow bouwde en promoveerde immutable images. De na uitrol waargenomen digests waren:

- backend in acceptance en productie:
  `sha256:a864f487aeb9776e9f8fb166113b0905885123b7f6291cf5244f0ca056f24298`;
- frontend acceptance:
  `sha256:909de976222032f1c9561a1fb7690641e31a74d05147c308392e8e5e570026dd`;
- frontend productie:
  `sha256:ae613fc25c499ad93288395bdd44abdd76458bd4cc374b0209c9b6705c00e695`;
- bronmock acceptance:
  `sha256:16848c3018c5f74a6cfc95716d264a03be608a7268ac46c9eaf64c086b84f1af`.

De frontendimages zijn afzonderlijke omgevingsbuilds. De publieke `/api/version`-endpoints van
beide omgevingen rapporteerden exact applicatiecommit `5821599…` en dezelfde buildtijd.

## Volledige synthetische acceptanceketen

`tools/run-source-revision-acceptance.sh` is tegen de echte OpenShift-acceptanceomgeving
uitgevoerd. PostgreSQL, Flyway, backend, frontend en de echte Agent Runtime-acceptatie-API waren
onderdeel van de keten; alleen bronresultaten en AI-resultaten waren deterministisch gemockt.

De schone matrix doorliep negentien broncontroles en achttien inhoudsrevisies:

1. preview met zichtbare C-stukken;
2. volledige publicatie met hetzelfde vergadering-ID;
3. uitsluitend opmaak gewijzigd;
4. agendapunt toegevoegd en teruggedraaid;
5. agendapunt ingetrokken en teruggedraaid;
6. punt verplaatst en teruggedraaid;
7. categorie gewijzigd en teruggedraaid;
8. metadata gewijzigd en teruggedraaid;
9. document toegevoegd en teruggedraaid;
10. document verwijderd en teruggedraaid;
11. documentbytes achter dezelfde URL gewijzigd en teruggedraaid.

Na de schone matrix waren negen gerichte AI-runs succesvol: drie voor de eerste publicatie en één
voor ieder van de zes inhoudelijke scenario's die heranalyse vereisten. Opmaak, een pure
verplaatsing, terugkeer naar een eerder bewezen fingerprint en volledig ongewijzigde controles
maakten geen nieuwe Runtime-job. De telling per relevante revisie was
`2=3, 3=1, 9=1, 11=1, 13=1, 15=1, 17=1`; alle andere revisies hadden nul jobs.

De uiteindelijke acceptance-database bevat na de aanvullende herstartproeven:

- revisie 24 als enige `CURRENT` revisie en 23 `SUPERSEDED` revisies;
- 31 auditbare broncontroles;
- 9 `SUCCEEDED` analyses en geen mislukte analyse;
- 3 `CURRENT`, 5 `STALE` en 1 `WITHDRAWN` adviezen.

## Herstel, idempotentie en actualiteit

De import-herstartproef hield de revisietransactie aantoonbaar geblokkeerd en beëindigde daarna de
betrokken acceptance-backendpod. De vervangende pod hervatte de workflow en voltooide revisie 21;
een herhaling gaf `UNCHANGED`. De vorige actuele snapshot bleef gedurende de onderbreking intact.

Voor de analyse-herstartproef kreeg één gemockte Runtime-opdracht een vertraging. Nadat de run een
Runtime-job-ID had ontvangen, is de betrokken backendpod beëindigd. De vervangende pod hervatte de
run tot `SUCCEEDED`; er bleef precies één idempotentiesleutel bestaan en de eindtelling keerde terug
naar negen runs en negen adviezen. Daarna is de bron teruggezet naar de gepubliceerde fixture en is
revisie 24 volledig afgerond.

Een aanvullende regressiecontrole bewees dat:

- ingetrokken of historisch verwijderde documenten niet meer als AI-bron worden geselecteerd;
- een eerder succesvol advies weer `CURRENT` kan worden wanneer de bron naar exact de bewezen
  fingerprint terugkeert;
- het dashboard het `CURRENT` advies kiest, ook als een later aangemaakt advies inmiddels `STALE`
  is;
- een laat resultaat van een oudere revisie een nieuwe revisie niet actueel kan maken.

De semantische browsercontrole op de eindbuild toonde drie van drie analyses gereed, revisie 24
actueel, de geldige adviezen als gereed en het ingetrokken punt expliciet als **Ingetrokken**. Er
werd geen verouderd advies meer ongemarkeerd of ten onrechte als nieuwste advies getoond.

## Live bron en productie

De opt-in, read-only bronspike tegen de openbare Noord-Hollandse bron is zonder database- of
AI-mutatie uitgevoerd. De bron meldde voor de vergadering van 14 september 2026 nog
`AgendaUnpublished`; vijf voorlopige C-items en zes documentreferenties werden herkend en er was
geen OCR nodig. Een tijdelijke timeout bij één documentrequest is eenmaal herhaald; de tweede
uitvoering was groen.

Na promotie is precies één gecontroleerde **Nu controleren** uitgevoerd op de definitieve build via
een reeds toegestane, ingelogde browsersessie. Er is daarbij geen Google-token uitgelezen of
gekopieerd. Productie bleef op revisie 1 in `PREVIEW`, met drie auditbare broncontroles en nul
AI-runs. De frontend toonde om 11:46 lokale tijd:

- **Agenda nog niet gepubliceerd** en **Voorlopige agenda**;
- vijf herkenbare voorlopige C-items;
- nul van nul analyses gereed.

De controle schrijft niets terug naar de openbare bron en wijzigde geen clusterresource buiten de
normale GitOps-release.

## Authenticatie- en omgevingsgrenzen

- Een schone browser op acceptance opende direct met de synthetische testeridentiteit en de vaste
  banner **ACCEPTANCE — gemockte gegevens — geen authenticatie**.
- Acceptance antwoordde met `X-Robots-Tag: noindex, nofollow`.
- Een niet-geauthenticeerde productieaanvraag naar `/api/auth/me` antwoordde met HTTP 401.
- Productie bleef via Google SSO en de backendallowlist afgeschermd.
- Manifest- en negatieve applicatiestarttests bewezen dat `acceptance-bypass` niet in productie of
  een onbekende omgeving kan starten.
- Acceptance gebruikte geen Google-configuratie, productiebron, echte AI-provider of
  productietoken.

## Poortbesluit

Alle twintig R7-controles zijn groen. Stappenplan 4 is afgerond en de bewezen applicatiecommit is
vastgezet met tag `source-revision-v1`. De volgende toegestane fase is
[stappenplan 3 — Software Factory-aansluiting](stappenplannen/03-software-factory-aansluiting.md).
