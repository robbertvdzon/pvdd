# Stappenplan 3 — Software Factory-aansluiting

Status: geblokkeerd tot functionele MVP-poort F13

Bron: [microservice-specificatie](../microservice-specificatie.md)

Afhankelijkheden:

- [technische fundering](01-technische-fundering.md);
- [functionele MVP](02-functionele-mvp.md).

## Doel en harde grens

Dit plan sluit het reeds gebouwde en in productie bewezen PvdD-project als laatste aan op Software
Factory. Daarna kunnen nieuwe PvdD-stories via de factory worden ontwikkeld, automatisch worden
gemerged en via de bestaande GitOps-straat worden gevolgd.

Start dit plan uitsluitend wanneer:

1. de technische baseline de tag `technical-baseline-v1` heeft;
2. `docs/functional-mvp-verification.md` alle F13-controles groen verklaart;
3. de PvdD-repository de tag `functional-mvp-v1` heeft;
4. productie in Argo CD `Synced` en `Healthy` is;
5. er geen open productie-incident of mislukte functionele verwerking bestaat.

Dit plan bouwt geen nieuwe vergader-, document-, AI- of frontendfunctionaliteit. Een bevinding die
een productwijziging vereist, wordt eerst als gewone herstelwijziging in de eigenaarrepository
afgerond. Daarna wordt deze aansluiting hervat.

## Uitvoeringsregels

- Houd wijzigingen aan `pvdd` en `softwarefactory` in afzonderlijke pull requests.
- Neem nooit secrets, tokens of de inhoud van de gitignored `projects.yaml` op in storyprompts of
  logs.
- Gebruik de bestaande PvdD-verificatie, GitHub Actions, Argo CD en GitOps-configuratie; maak geen
  tweede deploypad vanuit Software Factory.
- Een factory-story mag uitsluitend in de repository van het geselecteerde project schrijven.
- Activeer automatische merge pas nadat de vereiste checknaam en branchregels feitelijk zijn
  gecontroleerd.
- Pas de werkelijk gebruikte lokale `projects.yaml` bewust toe; de getrackte voorbeeldconfiguratie
  alleen is niet voldoende.

## SF0 — Factory-ready contract controleren

Repository: `pvdd`

Werk:

1. Controleer of `docs/factory/`, `docs/adr/`, `docs/stories/` en
   `.factory/verification.yaml` compleet en actueel zijn.
2. Controleer dat de workflow exact de vereiste checknaam `Repository verification` publiceert op
   pull requests.
3. Leg de toegestane verificatiecommando’s, repositorygrenzen en definitie van DONE vast.
4. Leg vast dat Software Factory alleen Git-wijzigingen initieert en de bestaande GitOps-straat
   volgt; de factory schrijft niet rechtstreeks naar OpenShift.
5. Controleer dat geen lokale paden, credentials of productiegegevens in factorydocumentatie staan.

Acceptatie:

- Een nieuwe coding agent kan vanuit alleen de getrackte factorydocumentatie de repository bouwen
  en verifiëren.
- `Repository verification` is groen op een echte pull request.
- De beschreven verificatiecommando’s komen overeen met de CI-workflow.
- Er is geen applicatiefunctionaliteit gewijzigd.

## SF1 — Software Factory-bootstrap

Repository: `softwarefactory`

Werk:

1. Voeg `pvdd` toe aan de werkelijk gebruikte, gitignored `projects.yaml`.
2. Voeg dezelfde reproduceerbare basisregistratie toe aan `projects.yaml.example` en de relevante
   documentatie.
3. Configureer `git@github.com:robbertvdzon/pvdd.git` en ondersteun de equivalente HTTPS-vorm bij
   repositoryherkenning.
4. Configureer automatische merge met vereiste check `Repository verification`.
5. Gebruik in deze stap tijdelijk `deploy: skip`; activeer deploybewaking pas in SF2.
6. Herlaad of herstart de draaiende factory volgens het bestaande runbook.

Acceptatie:

- `pvdd` is selecteerbaar bij het aanmaken van een story.
- De resolver herkent zowel de SSH- als HTTPS-vorm van de repository.
- De factory start en herlaadt zonder configuratiefout.
- Een read-only intake of dry-run haalt uitsluitend de PvdD-repository op.
- Automatische merge wacht aantoonbaar op `Repository verification`.

## SF2 — GitOps-deploybewaking activeren

Repository: `softwarefactory`

Werk:

1. Vervang `deploy: skip` door afzonderlijke `openshift-watch`-doelen voor backend en frontend in
   namespace `pvdd`.
2. Gebruik Argo CD-app `pvdd` in namespace `argocd` als waarheidsbron.
3. Stel `matchPaths` in op `[backend/, deploy/]` voor backend en `[frontend/, deploy/]` voor
   frontend.
4. Voeg beide deployments toe aan `liveComponents`.
5. Gebruik time-outs die passen bij de gemeten PvdD-build- en GitOps-doorlooptijden.
6. Zorg dat documentatie-only stories niet onnodig op een deployment wachten.

Acceptatie:

- Een backendwijziging wacht na merge op de backenddeployment en Argo CD.
- Een frontendwijziging wacht na merge op de frontenddeployment en Argo CD.
- Een wijziging onder `deploy/` bewaakt beide relevante deployments.
- Een documentatie-only wijziging kan zonder fictieve deploy aflopen.
- Software Factory rapporteert een mislukte of getime-oute uitrol als niet-DONE.

## SF3 — Gecontroleerde proefstories

Repositories: per proefstory uitsluitend de geselecteerde eigenaarrepository

Werk:

1. Laat een kleine documentatie-only PvdD-story volledig via Software Factory lopen.
2. Controleer checkout, story-output, verificatie, pull request en automatische merge.
3. Laat daarna een vooraf beoordeelde, functioneel neutrale PvdD-wijziging onder `frontend/` via de
   factory lopen om imagebuild en deploybewaking te bewijzen.
4. Controleer dat de uitgerolde SHA/buildtijd overeenkomt met de gemergede commit.
5. Test het foutpad door in een gecontroleerde test de vereiste check of deploywatch niet groen te
   laten worden; er mag dan geen DONE-status ontstaan.
6. Herstel de gecontroleerde test volledig en verifieer opnieuw een gezonde productieomgeving.

Acceptatie:

- Beide proefstories wijzigen alleen `pvdd`.
- De documentatiestory veroorzaakt geen onnodige deployment.
- De frontendproef doorloopt verificatie, merge, imagebuild, GitOps en livebewaking.
- De productieapp blijft functioneel gelijk en toont de juiste nieuwe buildidentiteit.
- Het foutpad blokkeert correct en laat geen half-afgeronde DONE-story achter.

## SF4 — Harde Factory-ready-poort

Repositories: `pvdd` en `softwarefactory`, zonder nieuwe productfeatures

Leg de gezamenlijke bewijsronde vast in `docs/software-factory-verification.md` in de PvdD-repo.

Alle onderstaande controles zijn verplicht:

- [ ] De tags `technical-baseline-v1` en `functional-mvp-v1` zijn aanwezig.
- [ ] SF0–SF3 zijn afgerond en de betrokken checks zijn groen.
- [ ] De getrackte voorbeeldconfiguratie en de werkelijk gebruikte lokale configuratie bevatten
      beide het project `pvdd`.
- [ ] `pvdd` is selecteerbaar en de repositoryresolver herkent SSH en HTTPS.
- [ ] Een story kan niet automatisch mergen zonder groene `Repository verification`.
- [ ] Een factory-story kan niet buiten de PvdD-repository schrijven.
- [ ] Documentatie-only werk veroorzaakt geen onnodige deployment.
- [ ] Backend-, frontend- en deploywijzigingen gebruiken de juiste watches.
- [ ] Argo CD blijft de enige declaratieve bron voor de OpenShift-uitrol.
- [ ] Een mislukte check of deployment resulteert niet in DONE.
- [ ] De proefdeployment toont exact de gemergede SHA/buildtijd.
- [ ] De bestaande functionele MVP is na de proefstories nog volledig groen.
- [ ] Logs, story-output en configuratie bevatten geen secrets of tokens.
- [ ] Factoryconfiguratie en beheerinstructies zijn reproduceerbaar gedocumenteerd.

Poortbesluit:

- Alleen bij veertien groene controles krijgt de aansluiting status **SOFTWARE FACTORY GEREED**.
- Tag daarna de PvdD-repository met `software-factory-ready-v1` en leg de definitieve SHA’s van
  `pvdd` en `softwarefactory` vast in het bewijsdocument.
- Bij één rood of onbewezen punt blijft rechtstreeks ontwikkelen mogelijk, maar PvdD-stories via
  Software Factory zijn nog niet vrijgegeven.
