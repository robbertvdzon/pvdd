# hkh-320 — worklog

## hkh-324 — documentation (2026-10-12)

### Doel
Documentatie in lijn brengen met het leesmodel `series` en het reeks-overzicht ("Deze vergaderingen
horen bij elkaar") in de agendaweergave.

### Bijgewerkt
- `docs/microservice-specificatie.md`: §5.2 (kader, tegels, selectie, één/nul vergaderingen,
  responsief), §6.3 (veld `series`: venster, maximum, statusafleiding, vaste constanten, geen
  nieuwe route), §13.1 en §13.2 (testdekking).
- `docs/factory/functional-spec.md`: alinea over `series` bij `/api/meetings/next`.
- `docs/functionele-werking-commissie-assistent.md` §11: gebruikersgedrag van het kader.
- `docs/stories/hkh-320-worklog.md`: deze sectie.

### Bewust niet gewijzigd
- `README.md`, `docs/agent-access.md`: geen nieuwe route; `ProductionReadAccessFilter` is
  ongewijzigd, het leestoken gedraagt zich onveranderd.
- `docs/factory/technical-spec.md`, `development.md`, `deployment.md`, `secrets-local.md`,
  `agent-runtime.md`, `docs/operations.md`, `docs/adr/*`: geen migratie, configuratie, uitrol- of
  testopzetwijziging.
- `docs/stappenplannen/*` en de verificatierapporten: geen beschrijving van huidig gedrag.
- Datumregels bovenaan bestaande documenten zijn conform de conventie niet bijgewerkt.

### Verificatie
- `bash tools/verify-documentation.sh`: zie hieronder.
- Alleen bestanden onder `docs/` gewijzigd.
