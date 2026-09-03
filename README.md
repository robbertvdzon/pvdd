# PvdD Commissie-assistent

Technische en functionele assistent voor de voorbereiding van de commissie Ruimte van de
Provincie Noord-Holland. De applicatie bestaat uit een Kotlin/Spring-backend, Flutter-webfrontend
en PostgreSQL-database en wordt via GitOps op OpenShift uitgerold.

De implementatie bevindt zich in fase 1: de technische fundering. Functionele vergaderlogica is
geblokkeerd totdat de technische acceptatiepoort T14 uit het stappenplan groen is.

## Documentatie

- [Functionele werking voor de Commissie-assistent](docs/functionele-werking-commissie-assistent.md)
- [Normatieve specificatie](docs/microservice-specificatie.md)
- [Technische fundering](docs/stappenplannen/01-technische-fundering.md)
- [Functionele MVP](docs/stappenplannen/02-functionele-mvp.md)
- [Bronrevisies en gerichte heranalyse](docs/stappenplannen/04-bronrevisies-en-heranalyse.md)
- [Actuele standpunten en AI-inzicht](docs/uitbreidingsspecificatie-standpunten-en-ai-inzicht.md)
- [Software Factory-aansluiting](docs/stappenplannen/03-software-factory-aansluiting.md)
- [Factory-overzicht](docs/factory/README.md)
- [Architectuurbesluiten](docs/adr/README.md)
- [Technische integraties](docs/technical-integrations.md)
- [OpenShift en GitOps](deploy/README.md)
- [Operations en herstel](docs/operations.md)

## Lokaal starten

`docker compose up --build` start PostgreSQL, backend, frontend en de uitsluitend lokale statische
vergaderbronmock. De frontend staat dan op `http://localhost:18088`, backendhealth op
`http://localhost:18080/actuator/health/readiness` en de mockfixture op
`http://localhost:18091/fixtures/commissie-ruimte.html`.
