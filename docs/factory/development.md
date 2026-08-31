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
