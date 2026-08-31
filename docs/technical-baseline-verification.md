# Technische baseline — gezamenlijke bewijsronde

Datum: 31 augustus 2026  
Poortstatus: **TECHNISCH GEREED — 17 van 17 controles groen**

De technische fundering T0–T13 is gebouwd en live bewezen. De eigenaar heeft de menselijke
Google-proef met beide toegestane accounts en een niet-toegestaan account uitgevoerd en op
31 augustus 2026 bevestigd. Alle T14-controles zijn daarmee groen en fase 2 mag starten vanaf tag
`technical-baseline-v1`.

## Bewezen revisies

| Onderdeel | Revisie | Betekenis |
|---|---|---|
| PvdD applicatiebron | `a2a61743d8bd1827da4788252761a23b7ebf8701` | live backend en omgevingsspecifieke frontends |
| PvdD acceptance-pin | `8666222bf57fd93034bbda90a9547ceab31931fc` | immutable acceptance-digests |
| PvdD production-pin | `64076cd5b75d971a69fac64d81c508a42d885c38` | immutable production-digests |
| Agent Runtime | `a24811229a41a258c8ce077a23919edb8fe1b192` | geïsoleerde PvdD-tenant en policies |
| Infrastructuur | `592cd7e076b24efa5507fae9ca5f067cfbb4e932` | beide Argo CD Applications |

Repository verification voor de PvdD-bron is geslaagd in GitHub-run
[`33430432006`](https://github.com/robbertvdzon/pvdd/actions/runs/33430432006). De volledige
imagebuild en promotie acceptance → production is geslaagd in run
[`33431010195`](https://github.com/robbertvdzon/pvdd/actions/runs/33431010195). Agent Runtime is
geverifieerd in run
[`33425099893`](https://github.com/robbertvdzon/agent-runtime/actions/runs/33425099893) en gebouwd in
run [`33425473594`](https://github.com/robbertvdzon/agent-runtime/actions/runs/33425473594).

## T14-controles

- [x] **Alle T0–T13-wijzigingen en vereiste checks zijn groen.** Alle wijzigingen staan direct op
      `main`; de bovengenoemde verificatie- en release-runs zijn geslaagd. Infrastructuur heeft
      geen eigen workflow, maar beide declaratieve Applications zijn live door Argo CD verwerkt.
- [x] **Geen open technische wijziging nodig.** `pvdd`, `agent-runtime` en
      `robberts-infrastructure` waren tijdens de bewijsronde schoon en gelijk aan `origin/main`.
- [x] **Lokale stack start vanaf een schone bronstaat.** De GitHub-verificatie bouwde backend,
      frontend en alle containers opnieuw. Lokaal startte `docker compose up -d --wait` frontend,
      backend en PostgreSQL gezamenlijk.
- [x] **Flyway en technische tabel werken.** Backend-Testcontainers-tests bewezen migratie op een
      lege PostgreSQL 16, herstart-idempotentie, schrijven/lezen van `application_metadata` en
      fail-closed readiness.
- [x] **Google-login met de drie echte accounts.** De productiepagina toont de Google-knop,
      Google Identity initialiseert en `/api/auth/me` geeft zonder token `401`. Unit-tests bewijzen
      RS256/JWKS, audience, issuer, expiry, `email_verified` en de exacte allowlist
      `marchanou@gmail.com`/`robbertvdzon@gmail.com`. De eigenaar bevestigde op 31 augustus 2026
      dat de Authorized JavaScript origin actief is, beide toegestane accounts kunnen inloggen en
      een ander account door de backend wordt geweigerd. Loginbewijzen zijn op verzoek niet
      opgeslagen.
- [x] **Same-origin frontend/backend werkt.** De live frontend bedient `/api/version` via dezelfde
      host met `200`; de beschermde route `/api/auth/me` geeft zonder login `401`.
- [x] **Cache en buildidentiteit zijn bewezen.** Live: shell `no-cache`, `/version.json`
      `no-store`, `main.d15d30411eacb583.js` immutable, service-worker-kill-switch `no-store` plus
      cacheverwijdering. Frontend en backend rapporteren SHA
      `a2a61743d8bd1827da4788252761a23b7ebf8701` en buildtijd `2026-08-31T19:31:33Z`.
- [x] **Agent Runtime-tenant is geïsoleerd.** Tenanttests dekken eigenaarschap, jobsoort,
      environmentprefix en provider/modelpolicy. Een live PvdD-acceptatiejob eindigde
      `SUCCEEDED` met het voorbereide resultaat `pvdd-runtime-ok`.
- [x] **Acceptance gebruikt alleen mocks.** Een live `MOCKED`-job slaagde, een live `CODEX`-job
      werd met `400` geweigerd en fixture `pvdd-technical-v1` was via de interne Service
      bereikbaar.
- [x] **Omgevingsgrenzen weigeren verkeerde systemen.** Production weigerde een live
      `MOCKED`-submit met `400 MOCKED_FORBIDDEN`; bronconfiguratietests weigeren productiehosts in
      acceptance en interne/mockhosts in production. Production bevat geen mock-Service.
- [x] **Argo CD is gezond.** `pvdd-acceptance` stond op revision `8666222…` en `pvdd` op
      `64076cd…`; beide waren `Synced` en `Healthy`.
- [x] **Publieke productie-URL toont de beveiligde shell.** De in-browser-proef op
      `https://pvdd.vdzonsoftware.nl` toonde de titel `PvdD Commissie-assistent` en de knop
      `Inloggen met Google`. Een onbekende wildcardhost gaf `503` en kwam niet bij PvdD uit.
- [x] **Containers voldoen aan het OpenShift-contract.** Live backend, frontend, database en
      acceptance-mock draaien met `runAsNonRoot`, `allowPrivilegeEscalation: false`, alle
      capabilities verwijderd, liveness/readiness, requests en limits. Alle pods waren `Running`.
- [x] **Release/promotie is end-to-end bewezen.** GitHub-run `33431010195` bouwde immutable
      digests met dezelfde SHA/buildtijd, smokete acceptance en promootte pas daarna production.
- [x] **Backup en restore zijn uitgevoerd.** `tools/test-backup-restore.sh` valideerde SHA-256 en
      herstelde naar de lege database `pvdd_restore_test`, inclusief `application_metadata`.
      Live productiejob `pvdd-backup-proof-193827` schreef succesvol een dump plus hash naar het
      gebonden 10 GiB-volume `pvdd-database-backups`.
- [x] **Documentatie en runbooks zijn actueel.** ADR's, factorydocumentatie, OpenShift/GitOps,
      Google, rollback, tokenrotatie, backup en restore zijn beschreven. Secret review vond geen
      sleutelpatronen in versiebeheer; lokale secretbron is mode `0600`. Flutter direct
      dependencies waren actueel; container- en dependencybomen zijn opnieuw opgebouwd.
- [x] **Geen functionele implementatie in productie.** De productiecode bevat alleen technische
      shell-, auth-, versie-, database-, Runtime-client- en bronboundarycomponenten. De UI meldt
      expliciet dat vergaderingen en analyses pas in fase 2 worden toegevoegd.

## Poortbesluit

Alle zeventien controles zijn groen. De technische baseline is **TECHNISCH GEREED**. Functionele
ontwikkeling volgens stappenplan 2 mag uitsluitend voortbouwen op tag `technical-baseline-v1` of
een aantoonbare opvolger daarvan.
