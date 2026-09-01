# Functionele acceptance — gezamenlijke bewijsronde

Datum: 1 september 2026  
Poortstatus F11: **GROEN**

De functionele keten is met de synthetische vergadering en server-side Agent Runtime-mocks in de
echte OpenShift-acceptatieomgeving uitgevoerd. Er is geen live iBabs-data en geen echte AI voor
dit bewijs gebruikt.

## Bewezen release

| Onderdeel | Waarde |
|---|---|
| Applicatiebron | `2191d15f3a96880a048641e21c72b1c537cea3b8` |
| GitHub repository verification | [`33473330547`](https://github.com/robbertvdzon/pvdd/actions/runs/33473330547) — geslaagd |
| GitHub build en promotie | [`33473713853`](https://github.com/robbertvdzon/pvdd/actions/runs/33473713853) — geslaagd |
| Acceptance GitOps-pin | `d8d69a653b1f197292a05a9b4230a0d4d802560c` |
| Production GitOps-pin | `fe0827f6a818679bacf572fe72a4f7a4269d55fe` |
| Production-configuratiefix | `32b8a39f12035caeebbc9ac28a82733731b18803` |
| Backend-image | `sha256:386c28f1179fefbd692fd473599929510ba093684f4891ccbfb655d4a7bfbf1c` |
| Frontend-image | `sha256:504b3af08c4ecf515d7741ce931db86eb3f346543ce4946ef8f2882782933d87` |
| Vergaderbronmock-image | `sha256:b3480b4ba34d9c0630f6417432b09650d82a678887977e6ad5f12ca3c251c1ec` |

De lokale backendverificatie op deze bronversie gaf 67 geslaagde tests, nul fouten en één bewust
overgeslagen live-brontest. De GitHub-verificatie bouwde backend, frontend en containers opnieuw.

## End-to-end uitkomst

De acceptance-startuptrigger doorliep dezelfde workflow als de 05:00-trigger en `Nu controleren`:
ontdekking, import, PDF-extractie, beleidsimport, analysevoorbereiding, duurzame Runtime-submit,
resultaatvalidatie en opslag. Het synthetische dossier eindigde als volgt:

- vergadering `acceptance-meeting-v1`: `COMPLETE`;
- analysewachtrij: `COMPLETE`, één poging, geen foutcode;
- drie `FINAL_ADVICE`-runs met contract `pvdd-advice-v2`: alle drie `SUCCEEDED/COMPLETE`;
- drie opgeslagen adviezen: precies één A-, één B- en één C-advies;
- drie geëxtraheerde vergaderdocumenten en een geïmporteerde primaire beleidsbron;
- geen tweede run of tweede Runtime-job voor hetzelfde agendapunt.

De bijbehorende, tenant-geïsoleerde Runtime-jobs waren:

| Agendapunt | Runtime-job | Uitkomst |
|---|---|---|
| A — wonen | `3b9be129-f60d-4ffc-8b80-8c2f0c2e5186` | `SUCCEEDED` |
| B — mobiliteit | `e7da9251-fc0e-4e12-a027-fa453aef921c` | `SUCCEEDED` |
| C — natuurverbinding | `249f6952-0957-4105-842d-882399b05445` | `SUCCEEDED` |

De grote-dossierroute is aanvullend via de echte acceptance-API bewezen: de voorbereide
bronnotitiejob `058a0c53-14d5-4980-8570-6e5d4a3e16a6` eindigde `SUCCEEDED`. De gecontroleerde
foutjob `1990a6a9-4e44-4aa2-b2d8-e09d3ea256fb` eindigde zoals bedoeld `FAILED` met foutcode
`SYNTHETIC_ACCEPTANCE_FAILURE`. Lokale integratietests bewijzen de verdere gefaseerde synthese,
retry en zichtbare gedeeltelijke status.

## Restart- en idempotentiebewijs

Tijdens de actieve v2-AI-uitvoering is pod `backend-6db5ffcf5f-d7rbx` verwijderd. Op dat moment
waren al twee Runtime-job-ID's duurzaam opgeslagen. OpenShift startte een vervangende pod uit
dezelfde immutable backend-image. Die pod hervatte polling en verwerking; uiteindelijk bestonden
nog steeds precies drie analyses, drie verschillende Runtime-job-ID's en drie adviezen. Geen submit
is dubbel uitgevoerd en geen resultaat ging verloren.

Dezelfde herstelroute is eerder tijdens de document-/analysevoorbereiding uitgevoerd. Daarbij werd
een PostgreSQL-driverfout bij een nullable paginanummer gevonden en gerepareerd; de regressietest
leest sindsdien passages inclusief pagina en tekst uit PostgreSQL 16.

## Omgevings- en releaseguards

- Acceptance weigerde een echte `CODEX`-submit met HTTP 400 en
  `PROVIDER_FORBIDDEN_IN_ACCEPTANCE`.
- Production weigerde een `MOCKED`-submit met HTTP 400 en `MOCKED_FORBIDDEN`.
- Het Runtime-token kwam niet in frontend, databasepayload of logs terecht.
- Acceptance rapporteerde voor frontend en backend exact SHA
  `2191d15f3a96880a048641e21c72b1c537cea3b8`; `tools/smoke-test.sh` bevestigde tevens dat login
  is afgeschermd.
- Production rapporteerde voor `/version.json` en `/api/version` dezelfde SHA en buildtijd
  `2026-09-01T05:28:31Z`.
- Argo CD stond na de definitieve configuratie op `Synced` en `Healthy`: acceptance op
  `d8d69a6…`, production op `32b8a39…`. Beide deployments waren beschikbaar.
- Production bevatte nul vergaderingen, nul analyseruns en nul adviezen; de acceptance-startuprun
  is daar dus niet uitgevoerd.

Tijdens de eerste productie-uitrol ontbrak de verplichte officiële beleidsprogramma-URL. De nieuwe
pod faalde daardoor fail-closed, terwijl de oude pod verkeer bleef afhandelen. GitOps-fix
`32b8a39…` voegde exact de door de productieguard toegestane URL toe; daarna werd de nieuwe backend
gezond en slaagde de production-smokecheck.

## Besluit

Alle F11-acceptatiecriteria zijn groen. F12 is afzonderlijk vastgelegd in
[`production-source-spike.md`](production-source-spike.md). Dit bewijs maakt F13 nog niet groen:
op 1 september meldde de officiële bron dat de agenda op 3 september wordt gepubliceerd. Zonder
gepubliceerde echte A/B- en C-stukken kan de vereiste productieanalyse en handmatige inhoudelijke
vergelijking nog niet eerlijk worden uitgevoerd.
