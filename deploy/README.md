# OpenShift en GitOps

De overlays `acceptance` en `production` worden door de Argo CD Applications in
`robberts-infrastructure/manifests/root-app/apps/` gevolgd. `root-apps` maakt de Applications en
namespaces opnieuw aan na clusterherstel. Handmatig `oc apply` op PvdD-resources is niet de normale
deployroute.

## Releasevolgorde

Na een groene `Repository verification` bouwt `.github/workflows/release.yml` backend, frontend en
mockserver voor exact dezelfde bron-SHA en UTC-buildtijd. De workflow pint immutable digests eerst
in acceptance, wacht op `tools/smoke-test.sh acceptance`, en pint daarna de productievarianten.
Beide frontendvarianten hebben dezelfde bron-SHA en buildtijd. De mockdigest komt nooit in
production. Als `main` onderweg wijzigt,
stopt de promotie.

## Controleren

```bash
kustomize build deploy/overlays/acceptance >/dev/null
kustomize build deploy/overlays/production >/dev/null
tools/verify-deployment.sh
oc get applications -n argocd pvdd pvdd-acceptance
oc get pods,pvc,route -n pvdd
oc get pods,pvc,route -n pvdd-acceptance
tools/smoke-test.sh production "$(git rev-parse HEAD)"
```

De bestaande Cloudflare-wildcard voor `*.vdzonsoftware.nl` behoudt de Host-header en selecteert zo
de Route `pvdd.vdzonsoftware.nl`; er is geen afzonderlijke tunnel of Cloudflare-token nodig. Voeg
deze productie-origin wel eenmalig toe aan Authorized JavaScript origins van Google OAuth-client
`Robberts applicaties`.

## Rollback

Zet de twee image-digests in `deploy/overlays/production/kustomization.yaml` terug naar een eerder
bewezen production-commit en push die wijziging naar `main`. Argo CD rolt declaratief terug. Draai
daarna de productierooktest met de bron-SHA die bij die digests hoort. Verander of verwijder PVC’s
en Sealed Secrets niet voor een applicatierollback.

## Secrets roteren

`deploy/initialize-secrets.sh` kopieert zonder weergave de bestaande Google-client-ID en de twee
PvdD Runtime-tokens naar het genegeerde `secrets.env`; databasewachtwoorden blijven bij herhaling
behouden. Na een bewuste bronrotatie voert men `deploy/seal-secrets.sh` uit, commit men uitsluitend
de twee `sealed-secret.yaml`-bestanden en controleert men beide omgevingen. Productie- en
acceptatietokens zijn niet onderling uitwisselbaar.
