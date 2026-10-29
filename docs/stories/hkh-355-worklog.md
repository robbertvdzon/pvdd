# hkh-355 — worklog

## hkh-359 — documentation (2026-10-29)

### Doel
Documentatie bijwerken voor commissieconfiguratie, bronvaststelling Bestuur en de kolom `commissie`.

### Bijgewerkt
- `docs/microservice-specificatie.md` §6.2: kolom `commissie`, migratie V17, bronconfiguratie en alias.
- `docs/microservice-specificatie.md` §4.2: overslaan van een commissie zonder agenda-type (één WARN).
- `docs/functionele-werking-commissie-assistent.md` §3.1: verwijzing naar Bestuur (niet actief in productie).
- `docs/commissie-bestuur-bron.md` was al aangemaakt in de developmentsubtaak en klopt met de code.

### Bewust niet gewijzigd
- `README.md`, `docs/factory/*`: geen wijziging van gedrag voor gebruikers of API.
- `docs/stappenplannen/*`: uitvoeringsplannen, geen beschrijving van huidig gedrag.

### Verificatie
- `bash tools/verify-documentation.sh` uitgevoerd; alleen `docs/` is geraakt.
