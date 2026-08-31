# Stappenplan 1 — technische fundering

Status: concept 0.2

Bron: [microservice-specificatie](../microservice-specificatie.md)

## Doel en harde grens

Dit plan levert een volledig werkende technische productiestraat op zonder de functionele
vergaderlogica te bouwen. Na afronding bestaat een beveiligde, lege PvdD-app met frontend,
backend, database, Agent Runtime-aansluiting, acceptatiemocks, CI/CD, OpenShift en GitOps.

Het [functionele stappenplan](02-functionele-mvp.md) mag pas starten wanneer de technische
acceptatiepoort in stap T14 volledig groen is. “Bijna klaar”, lokaal werkend of alleen een groene
unit-testset is niet voldoende.

Nog niet bouwen in dit plan:

- vergaderingontdekking of iBabs-parser;
- documentextractie;
- de 05:00-functionele workflow;
- het PvdD-beoordelingskader;
- prompts en analyses voor A/B/C-agendapunten;
- de functionele agenda- en adviesschermen.

## Uitvoeringsregels

- Voer iedere genummerde stap uit als een afzonderlijke, controleerbare wijziging of pull request.
- Gebruik Software Factory nog niet; die aansluiting volgt pas na de functionele MVP in
  [stappenplan 3](03-software-factory-aansluiting.md).
- Meng geen wijzigingen aan verschillende repositories in één wijziging of pull request.
- Rond een stap af met tests, documentatie, review, merge en waar van toepassing deployment.
- Begin niet aan een volgende stap wanneer de acceptatiecriteria van de huidige stap niet groen
  zijn.
- Secrets worden nooit in taakbeschrijvingen, logs, commits of AI-prompts opgenomen.
- Gebruik `hkh-autopilot` als technische referentie en Product Factory voor buildidentiteit en
  frontendthema; kopieer geen productlogica of branding.

## T0 — Baseline en scope vastzetten

Repository: `pvdd`

Uitvoering: rechtstreeks met de gekozen coding agent; nog niet via Software Factory.

Werk:

1. Review `docs/microservice-specificatie.md` op tegenstrijdigheden.
2. Leg ADR’s vast voor Kotlin/Spring, Flutter web, Google SSO, PostgreSQL/Flyway, Agent Runtime en
   OpenShift/GitOps.
3. Maak de factorydocumentatie aan:
   `docs/factory/{README,functional-spec,technical-spec,development,deployment,secrets-local,agent-runtime}.md`.
4. Voeg `.factory/verification.yaml`, `docs/stories/README.md` en een ADR-template toe.
5. Voeg een minimale GitHub-workflow toe met checknaam `Repository verification` die in deze fase
   documentatie en repositoryhygiëne valideert.
6. Leg expliciet vast dat functioneel werk geblokkeerd blijft tot T14.

Acceptatie:

- De factorydocumentatie verwijst naar één normatieve specificatie en bevat geen afwijkende
  requirements.
- De zes technische hoofdkeuzes hebben een ADR.
- Een documentatiecheck kan automatisch draaien.
- De check `Repository verification` bestaat op een pull request en is groen.

## T1 — Backend-, frontend- en lokale runtimeskeleton

Repository: `pvdd`

Werk:

1. Maak `backend/` met Kotlin, JDK 21, Maven, Spring Boot en Spring Modulith.
2. Maak `frontend/` met de in CI en Docker identiek gepinde Flutter stable-versie.
3. Voeg rootconfiguratie, `.gitignore`, veilige voorbeelden voor properties/secrets en een lokaal
   `docker-compose`-bestand toe.
4. Maak alleen technische endpoints: liveness, readiness en een voorlopig `/api/version`.
5. Maak een eenvoudige frontend-shell met “Technische basis gereed”; nog zonder domeinschermen.
6. Laat Nginx `/api/` en `/actuator/` same-origin naar de backend proxyen.

Acceptatie:

- `mvn verify`, `flutter analyze` en `flutter test` zijn lokaal groen.
- Frontend, backend en PostgreSQL starten samen lokaal.
- De frontend leest een technisch backendendpoint via dezelfde origin.
- Er bestaat nog geen vergadering-, document- of analyselogica.

## T2 — Repository verification en architectuurgrenzen

Repository: `pvdd`

Werk:

1. Voeg GitHub Actions-workflow `Repository verification` toe voor pull requests en `main`.
2. Draai backendverify, Flutter analyze/tests/releasebuild, Dockerbuilds en Kustomize-renderchecks.
3. Voeg Spring Modulith-architectuurtests toe.
4. Voeg secret-, formatterings- en documentatiecontroles toe naar het HKH-patroon.
5. Pin JDK-, Maven-, Flutter- en actionversies reproduceerbaar.

Acceptatie:

- De workflow draait op een lege technische wijziging en is groen.
- Een opzettelijke Modulith-overtreding faalt in een regressietest.
- Branch protection kan exact de check `Repository verification` vereisen.

## T3 — PostgreSQL- en Flywayfundering

Repository: `pvdd`

Werk:

1. Configureer JDBC, PostgreSQL 16 en Flyway.
2. Maak één technische migratie voor `application_metadata` met schema-/installatie-informatie.
3. Voeg een repository en interne healthindicator toe die echte databaseconnectiviteit bewijzen.
4. Gebruik Testcontainers voor migratie-, repository- en restarttests.
5. Documenteer lokale databaseconfiguratie zonder echte credentials.

Acceptatie:

- Een lege database migreert automatisch bij start.
- Herhaald starten is idempotent.
- De technische tabel kan worden geschreven en gelezen.
- Readiness faalt veilig wanneer PostgreSQL onbereikbaar is.
- Er zijn nog geen functionele tabellen.

## T4 — Google SSO en backendautorisatie

Repository: `pvdd`

Werk:

1. Implementeer Google ID-tokenvalidatie naar het HKH-patroon.
2. Valideer RS256/JWKS, audience, issuer, expiry, `email_verified` en e-mailadres.
3. Hardcode uitsluitend `marchanou@gmail.com` en `robbertvdzon@gmail.com` als lowercase allowlist.
4. Beveilig alle `/api/**`-routes behalve de expliciet openbare versie-/healthroutes.
5. Voeg `GET /api/auth/me` toe.
6. Voeg frontendlogin, tokenopslag/herstel, uitloggen en verlopen-sessiegedrag toe.
7. Laat onvolledige productieconfiguratie fail-closed starten of de beveiligde API blokkeren.

Acceptatie:

- Zonder token volgt `401`; geldig maar niet toegestaan account geeft `403`.
- Alleen de twee vastgelegde adressen krijgen `200`.
- Verkeerde audience/issuer, verlopen token en ongeverifieerde e-mail worden geweigerd.
- De backend, niet de frontend, is de autorisatiegrens.
- Geen Google client secret wordt gebruikt.

## T5 — Frontendshell en technische UX

Repository: `pvdd`

Werk:

1. Bouw de beveiligde applicatieshell met Product Factory-geïnspireerd thema.
2. Voeg navigatieplaatsen toe voor “Overzicht” en “Over deze versie”, zonder functionele inhoud.
3. Maak login-, loading-, lege-, ongeautoriseerde en technische foutstatussen.
4. Borg toetsenbordbediening, focus, semantiek, contrast en responsive gedrag vanaf 320 px.
5. Toon na login alleen een duidelijke melding dat de functionele module nog niet is geïnstalleerd.

Acceptatie:

- Widgettests dekken alle technische statussen.
- De shell is bruikbaar met toetsenbord en schermlezersemantiek.
- Er staat nergens voorbeeld- of verzonnen vergaderdata in productiecode.

## T6 — Cachecontract en buildidentiteit

Repository: `pvdd`

Werk:

1. Bouw Flutter zonder PWA-strategie en verwijder de gegenereerde service worker.
2. Voeg de service-worker-kill-switch uit de specificatie toe.
3. Hernoem `main.dart.js` content-addressed naar `main.<hash>.js`.
4. Configureer Nginxheaders: immutable alleen voor de gehashte bundle, `no-cache` voor de shell en
   `no-store` voor versie/API.
5. Genereer `/version.json` met versie, volledige/korte SHA, UTC-buildtijd, omgeving en
   buildidentiteit.
6. Maak `/api/version` met dezelfde backendmetadata.
7. Toon beide identiteiten en meld wanneer een nieuwe frontendversie beschikbaar is.

Acceptatie:

- Geautomatiseerde cachecontracttest controleert bestandsnamen en headers.
- Een nieuwe frontendimage is zichtbaar zonder handmatig browsercache wissen.
- Frontend- en backend-SHA/buildtijd zijn afzonderlijk zichtbaar.
- Ontbrekende of ongeldige buildmetadata wordt als `Onbekend` getoond, niet verzonnen.

## T7 — Eigen Agent Runtime-tenant

Repository: `agent-runtime`

Werk:

1. Voeg tenant `pvdd` en configuratie `AR_PVDD_TOKEN` toe.
2. Sta alleen `APPLICATION_WORK` en environmentprefix `PVDD` toe.
3. Geef geen worker-, admin- of repositoryrechten.
4. Sta in acceptatie uitsluitend provider `MOCKED` toe.
5. Sta in productie alleen expliciet geconfigureerde echte providers/modellen toe.
6. Breid secretinitialisatie, sealing, documentatie en veilige kopieerscripts uit.
7. Gebruik afzonderlijke acceptatie- en productietokens.

Acceptatie:

- Tenantisolatietests bewijzen dat PvdD alleen eigen jobs ziet.
- `REPOSITORY_WORK`, verkeerde prefix en verboden provider falen gesloten.
- Acceptatie weigert echte AI en productie weigert `MOCKED`.
- Geen bestaande tenantpolicy verandert onbedoeld.
- Nieuwe Sealed Secrets bevatten alleen versleutelde waarden.

## T8 — Generieke Agent Runtime-client in PvdD

Repository: `pvdd`

Werk:

1. Implementeer een technische client voor create, status, result en cancel.
2. Configureer URL, token, provider, model en time-out buiten de code.
3. Gebruik korte HTTP-time-outs, bearer-auth en veilige foutvertaling.
4. Voeg een interne technische self-testservice toe die alleen in local/acceptance geactiveerd is.
5. Laat de self-test een klein, strikt JSON-schema via `MOCKED` uitvoeren.
6. Expose geen Runtime-token of generieke promptmogelijkheid aan de productiefrontend.

Acceptatie:

- HTTP-contracttests dekken succes, 4xx, 5xx, time-out en verloren submitresponse.
- Dezelfde idempotentiesleutel veroorzaakt geen dubbele Runtime-job.
- Acceptatie rondt een gemockte self-test end-to-end af.
- Productie bevat geen publiek self-test- of vrij promptendpoint.

## T9 — Gemockte vergaderbron als technische acceptatiedienst

Repository: `pvdd`

Werk:

1. Maak een kleine, deterministische HTTP-mockserver als apart component.
2. Lever in deze technische stap alleen health en statische fixturebestanden; de functionele
   parser komt later.
3. Configureer `PVDD_MEETING_SOURCE_BASE_URL` per omgeving.
4. Laat acceptatie uitsluitend naar de interne mockservice wijzen.
5. Voeg een environment guard toe die in acceptatie de echte Noord-Holland-host weigert.
6. Laat productie uitsluitend de expliciet toegestane echte HTTPS-host accepteren.

Acceptatie:

- Mockserver heeft liveness/readiness en draait non-root.
- Acceptatie kan een fixture via de interne Service downloaden.
- Acceptatiestart faalt met de productiebron-URL.
- Productiestart faalt met een interne/mock-URL.

## T10 — Container- en OpenShiftmanifests

Repository: `pvdd`

Werk:

1. Maak production-grade Dockerfiles voor backend, frontend en mockserver.
2. Maak Kustomize base en overlays voor acceptance en production.
3. Definieer Deployments/StatefulSet, Services, PVC, serviceaccounts, probes en resources.
4. Draai containers non-root, zonder privilege escalation en zonder Linux-capabilities.
5. Maak namespacegebonden Sealed Secrets voor database, Google en Agent Runtime.
6. Voeg de publieke frontendroute `pvdd.vdzonsoftware.nl` toe; backend blijft same-origin intern.
7. Acceptance bevat de mockserver; production niet.

Acceptatie:

- `kustomize build` is groen voor beide overlays.
- Alle containers werken met een willekeurige OpenShift-UID.
- Productionrender bevat geen mockserver of acceptatiecredential.
- Acceptatierender bevat geen echte bron- of AI-configuratie.
- Routes, probes en limieten zijn automatisch getest.

## T11 — GitHub imagebuild en GitOps-promotie

Repository: `pvdd`

Werk:

1. Bouw backend- en frontendimages pas na een groene verificatie op exact dezelfde SHA.
2. Bouw de mockserverimage alleen voor acceptance.
3. Publiceer immutable tags/digests naar GHCR.
4. Geef frontend en backend dezelfde UTC-buildtijd en bron-SHA mee.
5. Pin eerst acceptance, wacht op rooktest en pin daarna production.
6. Commit imagepins via de bot als `[skip ci]` zonder buildlus.
7. Stop promotie wanneer `main` tijdens de release is gewijzigd.

Acceptatie:

- Een falende verificatie publiceert of promoot niets.
- Acceptance draait exact de zojuist gebouwde SHA en slaagt voor de technische rooktest.
- Production wordt pas daarna gepind.
- `/version.json` en `/api/version` bewijzen de live SHA.

## T12 — Argo CD, infrastructuur en publieke toegang

Repository: `robberts-infrastructure`

Werk:

1. Voeg `pvdd-acceptance` en `pvdd` Applications toe onder `manifests/root-app/apps/`.
2. Laat beide de juiste overlay en namespace volgen met automated sync, prune en self-heal.
3. Leg eventuele gedeelde RBAC minimaal vast.
4. Controleer dat de bestaande Cloudflare-wildcard de Host-header naar OpenShift behoudt.
5. Registreer `https://pvdd.vdzonsoftware.nl` als Authorized JavaScript origin in de bestaande
   Google OAuth-client; dit is een expliciete menselijke beheeractie.
6. Documenteer bootstrap, rollback en controlecommando’s.

Acceptatie:

- Beide Argo CD Applications zijn `Synced` en `Healthy`.
- De productie-URL toont de login en accepteert alleen de twee toegestane accounts.
- Een onbekende wildcardhost komt niet bij PvdD uit.
- Clusterherstel via `root-apps` maakt de PvdD-Applications opnieuw aan.

## T13 — Operations en herstelbaarheid

Repository: `pvdd`

Werk:

1. Voeg gestructureerde technische logs, metrics en healthchecks toe.
2. Voeg dagelijkse PostgreSQL-backup, hashcontrole en een restore-runbook toe, zonder automatische
   verwijdering.
3. Voeg runbooks toe voor Google-configuratie, tokenrotatie, rollback en databaseherstel.
4. Voer container-, dependency- en secretreview uit.

Acceptatie:

- Backup kan in een lege testdatabase worden hersteld.
- Dashboards/logs bevatten geen secrets of Google ID-tokens.
- De operationele runbooks zijn vanaf een schone checkout uitvoerbaar.

## T14 — Harde technische acceptatiepoort

Repositories: alle betrokken repositories, zonder nieuwe features

Voer één gezamenlijke bewijsronde uit en leg het resultaat vast in
`docs/technical-baseline-verification.md`.

Alle onderstaande controles zijn verplicht:

- [ ] Alle T0–T13-wijzigingen zijn gemerged en hun vereiste checks zijn groen.
- [ ] `pvdd`, `agent-runtime` en `robberts-infrastructure` hebben geen open
      technische wijziging nodig voor de afgesproken MVP-basis.
- [ ] Lokale frontend, backend en PostgreSQL starten vanaf een schone checkout.
- [ ] Flyway migreert een lege PostgreSQL en de technische tabel werkt.
- [ ] Google-login werkt voor beide toegestane accounts en weigert andere accounts.
- [ ] Same-origin frontend-backendverkeer werkt.
- [ ] Cachecontract, updatecontrole, SHA en buildtijd zijn bewezen.
- [ ] PvdD heeft een geïsoleerde Agent Runtime-tenant.
- [ ] Acceptance gebruikt uitsluitend `MOCKED` AI en de interne vergaderbronmock.
- [ ] Production weigert mocks en acceptance weigert echte externe systemen.
- [ ] Acceptance en production zijn in Argo CD `Synced` en `Healthy`.
- [ ] `https://pvdd.vdzonsoftware.nl` toont de beveiligde technische shell.
- [ ] Backend, frontend, database en mockserver draaien non-root met probes en limieten.
- [ ] GitHub release/promotie is end-to-end bewezen op een echte technische wijziging.
- [ ] Backup en restore zijn aantoonbaar uitgevoerd.
- [ ] Alle technische documentatie en runbooks zijn actueel.
- [ ] Er staat nog geen functionele vergadering-, document- of analyse-implementatie in productie.

Poortbesluit:

- Alleen bij zeventien groene controles krijgt de baseline status **TECHNISCH GEREED**.
- Tag daarna de PvdD-repository met `technical-baseline-v1` en leg de drie betrokken commit-SHA’s
  vast in het bewijsdocument.
- Bij één rood of onbewezen punt blijft het functionele stappenplan geblokkeerd.
