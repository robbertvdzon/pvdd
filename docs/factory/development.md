# Ontwikkelen en verifiëren

Werk rechtstreeks in de eigenaarrepository en meng geen cross-repositorywijzigingen in één commit.
Gebruik lokaal dezelfde gepinde toolversies als CI en de containerbuilds.

Het volledige vangnet groeit tijdens fase 1 naar:

```text
backend:  mvn -B --no-transfer-progress clean verify
frontend: flutter analyze && flutter test && flutter build web
deploy:   kustomize build deploy/overlays/acceptance
          kustomize build deploy/overlays/production
```

Tests gebruiken mocks en fixtures. CI raadpleegt geen echte vergaderbron en voert geen echte AI uit.
Secrets, gedownloade documenten, databases en buildoutput worden niet gecommit.

## Databasetests

`DatabaseIntegrationTest` draait tegen een echte PostgreSQL. Is Docker beschikbaar, zoals in CI, dan
start Testcontainers die database zelf en is verder niets nodig. Zonder Docker kan een lege
wegwerpdatabase worden meegegeven:

```text
PVDD_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/pvdd_test
PVDD_TEST_DATABASE_USER=pvdd        # optioneel, standaard `pvdd`
PVDD_TEST_DATABASE_PASSWORD=...     # optioneel, standaard leeg
```

Een meegegeven database wint van Docker. De sleutel is bewust testeigen: de runtimesleutel
`PVDD_DATABASE_URL` wijst in acceptatie en productie naar een echte database en mag deze
migrerende en schrijvende suite daar nooit heen sturen. De suite verwacht een lege database en
stopt met een duidelijke melding wanneer er al rijen staan; ruim een tijdelijke database na afloop
op. Ontbreken zowel Docker als `PVDD_TEST_DATABASE_URL`, dan wordt de klasse overgeslagen in plaats
van het hele vangnet te laten falen. Overgeslagen databasetests zijn dus geen bewijs; vermeld ze in
een testrapport.
