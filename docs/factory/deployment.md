# Deployment

PvdD wordt declaratief uitgerold via GitHub Actions, immutable GHCR-images, Kustomize en Argo CD.
Acceptance wordt vóór productie gepromoveerd en gebruikt uitsluitend gemockte AI en een interne
vergaderbronmock. Productie weigert beide mocks.

De publieke frontend gebruikt `https://pvdd.vdzonsoftware.nl`; de backend blijft same-origin achter
de frontendproxy. Clusterwijzigingen worden niet handmatig als blijvende configuratie toegepast:
Git is de bron van waarheid. Concrete bootstrap-, rollback- en herstelcommando’s worden in T10–T13
toegevoegd.
