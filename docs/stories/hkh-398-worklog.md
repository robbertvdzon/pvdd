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
