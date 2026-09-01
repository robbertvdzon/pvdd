# Technische specificatie

## Stack

- Kotlin op JDK 21, Spring Boot, Spring Modulith en Maven;
- PostgreSQL 16 met Flyway;
- Flutter web met Material 3, gepind op revision `ee80f08bbf97172ec030b8751ceab557177a34a6`;
- eenmalige Google ID-tokenauthenticatie met backend-allowlist en duurzame, gehashte
  backend-sessies in een veilige cookie;
- Agent Runtime via het asynchrone v1-jobcontract;
- containers, Kustomize, OpenShift, Argo CD en GitHub Actions.

Er bestaat geen algemene productiebackdoor. Menselijke toegang gebruikt de backendsessie;
servicecredentials zijn uitsluitend toegestaan voor één benoemde integratie en scope.

De normatieve details en acceptatie-eisen staan in
[stappenplan 1](../stappenplannen/01-technische-fundering.md). Er komt vóór de technische
acceptatie geen functioneel datamodel, parser, documentextractie of politieke analyse.
