## hkh-317 — documentation (2026-10-12)

### Doel
Documentatie in lijn brengen met de per-vergadering verwerking in `MeetingCheckWorkflow` en het
uitgebreide antwoord van `POST /api/meetings/check-now`.

### Bijgewerkt
- `docs/microservice-specificatie.md` §4.2: zin over "alleen het eerste element" vervangen door
  beschrijving van één lock, eigen transactie per vergadering, foutisolatie, gedrag bij
  `AgendaUnpublished`, `SourceFailure` en de nieuwe lijst `meetings` met uitkomsten.

### Bewust niet gewijzigd
- `docs/functionele-werking-commissie-assistent.md`, `docs/factory/*`, README, `docs/agent-access.md`:
  geen gebruikersgedrag, route, configuratie of toegangsregel gewijzigd (frontend valt buiten scope).
- `docs/stappenplannen/*`: uitvoeringsplannen, geen beschrijving van huidig gedrag.

### Verificatie
- `bash tools/verify-documentation.sh` uitgevoerd, zie samenvatting.
- Alleen bestanden onder `docs/` gewijzigd.
