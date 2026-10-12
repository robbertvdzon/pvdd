# hkh-327 — worklog

## hkh-331 — documentation (2026-10-12)

### Doel
Documentatie in lijn brengen met het controleresultaat per vergadering na **Nu controleren**
(alleen frontend; het `meetings`-antwoord van hkh-313 wordt nu getoond).

### Bijgewerkt
- `docs/microservice-specificatie.md`: §4.2 (frontendweergave van `meetings`, terugval, mislukte
  hele controle, 409, verversen) en §13.2 (testdekking `check_result_test.dart`).
- `docs/factory/functional-spec.md`: alinea over het controleresultaat per vergadering.
- `docs/functionele-werking-commissie-assistent.md` §3.3: gebruikersgedrag.
- `docs/stories/hkh-327-worklog.md`: deze sectie.

### Bewust niet gewijzigd
- `README.md`, `docs/agent-access.md`: geen nieuwe route of toegangswijziging.
- `docs/factory/technical-spec.md`, `development.md`, `deployment.md`, `secrets-local.md`,
  `agent-runtime.md`, `docs/operations.md`, `docs/adr/*`: geen backend-, migratie-, configuratie- of
  testopzetwijziging.
- `docs/stappenplannen/*` en de verificatierapporten: geen beschrijving van huidig gedrag.
- Datumregels bovenaan bestaande documenten zijn conform de conventie niet bijgewerkt.

### Verificatie
- `bash tools/verify-documentation.sh`: zie de uitvoer in de samenvatting van de job.
- Alleen bestanden onder `docs/` gewijzigd.
