# hkh-398 — Commissiekeuze, Bestuur-schermen en commissiefout in de Flutter-app

Story hkh-397, branch `ai/hkh-397`. Alleen frontend; geen backend-, route-, migratie- of schedulewijziging.

## Gedaan
- `dashboard_api.dart`: alle leesroutes krijgen `?commissie=RUIMTE|BESTUUR` (parameternaam en waarden
  gecontroleerd in `DashboardController.commissieOf`). Modellen lezen `commissieNaam`, `agendaStatus` en
  de per-commissie controle-uitkomst (`commissies` in het check-now-antwoord).
- `main.dart`: commissie als sessiestate (start Ruimte), schakelaar boven de agenda, commissienaam in de
  zijbalk (desktop) of de kop (mobiel). De pagina krijgt per commissie een eigen key, zodat late antwoorden
  van een eerdere commissie genegeerd worden; de 15-secondenverversing laadt alleen de gekozen commissie.
- `meeting_overview.dart`: kop `Agenda — Commissie X`, commissietag op vergaderkaart en agendapunt,
  Bestuur-agenda niet gepubliceerd (datum, uitleg, geen punten; tijd alleen bij een eindtijd uit de bron),
  AI-concepttekst, melding bij ontbrekend stuk, fouttoestand per commissie met behoud van gegevens.
- De 'controletijd 05:00' komt in de app nergens hardgecodeerd voor; er is niets te wijzigen.

## Testen
`cd frontend && flutter analyze && flutter test` (137 tests). Nieuw: `test/commissie_choice_test.dart`
(op 1280 en 390 px, `CommissieDashboardGateway` in `test/support/commissie_fakes.dart` registreert aanroepen
met commissie, kan antwoorden vertragen en per commissie laten falen) en `test/commissie_api_test.dart`.
`FakeDashboardGateway` kreeg `commissieCalls`; bestaande `calls`-lijsten zijn ongewijzigd.

## Niet gedaan / aanname
- Een preview of acceptatiecontrole is niet uitgevoerd; visuele controle op acceptatie volgt na deployment.
- De Bestuur-notice voor een niet-gepubliceerde agenda geldt bewust alleen voor Bestuur (Ruimte ongewijzigd).

## hkh-401 — documentation (2026-10-29)

### Doel
Documentatie in lijn brengen met de commissiekeuze en Bestuur-schermen in de Flutter-app (story hkh-397).

### Bijgewerkt
- `docs/functionele-werking-commissie-assistent.md` §11: subsectie Commissiekeuze in de webapp.
- `docs/microservice-specificatie.md` §13.2: de nieuwe frontendtests voor de commissiekeuze.

### Bewust niet gewijzigd
- `docs/microservice-specificatie.md` §4.2/§5.2: de leesparameter was al gedocumenteerd (hkh-387).
- `docs/factory/*`, `docs/agent-access.md`: geen route-, filter- of proceswijziging.
- Overige vermeldingen van 'Commissie Ruimte' in docs gaan over de bron, niet over de app.

### Verificatie
- `bash tools/verify-documentation.sh`; alleen `docs/` geraakt.
