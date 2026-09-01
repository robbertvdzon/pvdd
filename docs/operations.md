# Operations en herstel

## Read-only productiebronspike

De optionele, standaard uitgeschakelde live inventaris draait zonder database en zonder AI:

```bash
cd backend
PVDD_LIVE_SOURCE_SPIKE=true mvn -q -Dtest=LiveSourceSpikeTest test
```

Leg de uitkomst en het OCR-besluit vast in `docs/production-source-spike.md`.

## Health, metrics en logs

De backend levert Kubernetes-probes via `/actuator/health/liveness` en
`/actuator/health/readiness`, en Prometheusmetrics via `/actuator/prometheus`. Consolelogs gebruiken
ECS JSON. Agent Runtime-tokens, Google ID-tokens en upstream-responsebody's worden niet gelogd.

## Dagelijkse databasebackup

Production draait dagelijks om 02:30 uur Europe/Amsterdam een `pg_dump` in custom format. Elke
dump krijgt een SHA-256-bestand op PVC `pvdd-database-backups`. Een bestand wordt eerst onder een
tijdelijke naam volledig geschreven en pas na succes atomair hernoemd. Er is bewust geen
automatische bewaartermijn of verwijdering; vrije ruimte moet operationeel worden bewaakt.

Controleren:

```bash
oc get cronjob,jobs -n pvdd
oc create job -n pvdd --from=cronjob/pvdd-database-backup pvdd-backup-handmatig-$(date +%s)
oc logs -n pvdd -l app.kubernetes.io/component=database-backup --tail=20
```

## Restoreprocedure

1. Schaal de backend naar nul of zet functionele verwerking stil voordat een productiedatabase
   wordt vervangen.
2. Kies een dump en controleer in de backup-pod: `sha256sum --check pvdd-<tijd>.dump.sha256`.
3. Herstel altijd eerst naar een lege, anders genoemde database met
   `pg_restore --no-owner --no-acl`.
4. Controleer Flyway-history, `application_metadata`, rijtotalen en backendstart.
5. Wijzig pas daarna gecontroleerd de database-URL, of vervang de lege productiedatabase binnen een
   afgesproken onderhoudsvenster.
6. Draai readiness, `/api/version`, login en de technische Runtime-smoketest.

De lokale bewijsproef `tools/test-backup-restore.sh` dumpt de compose-database, valideert de hash,
herstelt naar de lege tijdelijke database `pvdd_restore_test`, controleert de technische tabel en
verwijdert uitsluitend die tijdelijke testdatabase.

## Rollback en credentialrotatie

Applicatierollback en secretrotatie staan in [`deploy/README.md`](../deploy/README.md). Een
imagerollback verandert de database niet. Migreer daarom in fase 2 uitsluitend voorwaarts
compatibel en maak vóór iedere destructieve handmatige datamigratie een bewezen backup.

Het productie-toolingtoken wordt lokaal als `PVDD_PRODUCTION_TOOLING_TOKEN` in het genegeerde
`secrets.env` beheerd. Rotatie: vervang de waarde, draai `./deploy/seal-secrets.sh`, commit het
nieuwe productie-Sealed Secret en wacht tot Argo CD gezond is. Het token opent alleen een normale
sessie via `/api/auth/tooling-session`; bestaande sessies kunnen afzonderlijk worden ingetrokken.
