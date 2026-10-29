// Commissiekeuze, Bestuur-schermen en commissiefout. Alle tests draaien op de commissie-bewuste
// `CommissieDashboardGateway` (geen netwerk) en op twee breedtes: desktop (1280) en mobiel (390).
// Een overflow levert een exception op die elke test aan het eind uitsluit.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/main.dart';
import 'package:pvdd_frontend/meeting_overview.dart';

import 'support/commissie_fakes.dart';
import 'support/fakes.dart';

const widths = [1280.0, 390.0];

Future<void> pump(
  WidgetTester tester,
  CommissieDashboardGateway gateway,
  double width, {
  bool settle = true,
}) async {
  tester.view.physicalSize = Size(width, 3000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      home: TechnicalApplicationShell(
        email: 'robbertvdzon@gmail.com',
        onLogout: () {},
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: gateway,
        aiRunsGateway: UnusedAiRunsGateway(),
        settingsGateway: UnusedSettingsGateway(),
        acceptanceBypass: false,
        appPath: () => '/agenda',
        navigate: (_) {},
      ),
    ),
  );
  if (settle) await tester.pumpAndSettle();
}

Future<void> choose(WidgetTester tester, String commissie) async {
  await tester.tap(find.byKey(ValueKey('commissie-option-$commissie')));
  await tester.pumpAndSettle();
}

Finder tag(String id) => find.byKey(ValueKey('commissie-tag-$id'));

Future<void> expand(WidgetTester tester, String title) async {
  await tester.ensureVisible(find.text(title));
  await tester.pumpAndSettle();
  await tester.tap(find.text(title));
  await tester.pumpAndSettle();
}

Finder rich(String text) => find.textContaining(text, findRichText: true);

void main() {
  for (final width in widths) {
    group('at $width px', () {
      tearDown(() {});

      testWidgets('switch has two options and starts on Ruimte', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        final switcher = tester.widget<SegmentedButton<String>>(
          find.byKey(const ValueKey('commissie-switch')),
        );
        expect(switcher.segments.map((s) => s.value), ['RUIMTE', 'BESTUUR']);
        expect(switcher.selected, {'RUIMTE'});
        expect(find.text('Commissie Ruimte'), findsWidgets);
        expect(find.text('Commissie Bestuur'), findsOneWidget);
        // Alleen Ruimte geladen bij de start.
        expect(gateway.calls.where((c) => c.contains('BESTUUR')), isEmpty);
        expect(tester.takeException(), isNull);
      });

      testWidgets('header and tags follow the chosen commissie', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        expect(
          tester
              .widget<Text>(find.byKey(const ValueKey('agenda-heading')))
              .data,
          'Agenda — Commissie Ruimte',
        );
        expect(tag('r-a-4'), findsOneWidget);
        expect(
          find.descendant(of: tag('r-a-4'), matching: find.text('Ruimte')),
          findsOneWidget,
        );
        if (width > 800) {
          expect(
            tester
                .widget<Text>(find.byKey(const ValueKey('sidebar-commissie')))
                .data,
            'COMMISSIE RUIMTE',
          );
        } else {
          expect(
            tester
                .widget<Text>(find.byKey(const ValueKey('appbar-commissie')))
                .data,
            contains('Ruimte'),
          );
        }
        await choose(tester, 'BESTUUR');
        expect(
          tester
              .widget<Text>(find.byKey(const ValueKey('agenda-heading')))
              .data,
          'Agenda — Commissie Bestuur',
        );
        expect(
          find.descendant(of: tag('b-a-3'), matching: find.text('Bestuur')),
          findsOneWidget,
        );
        expect(
          find.descendant(
            of: find.byKey(const ValueKey('meeting-commissie-tag')),
            matching: find.text('Bestuur'),
          ),
          findsOneWidget,
        );
        expect(find.text('COMMISSIE RUIMTE'), findsNothing);
        expect(tester.takeException(), isNull);
      });

      testWidgets(
        'switching shows only the chosen commissie and reads only it',
        (tester) async {
          final gateway = CommissieDashboardGateway();
          await pump(tester, gateway, width);
          expect(find.text('Programma Natuurnetwerk'), findsOneWidget);
          gateway.calls.clear();

          await choose(tester, 'BESTUUR');
          expect(find.text('Jaarrekening 2025 provincie'), findsOneWidget);
          expect(find.text('Programma Natuurnetwerk'), findsNothing);
          expect(find.text('Vergadering Ruimte'), findsNothing);
          expect(gateway.calls, [
            'overview:BESTUUR',
            'agendaItems:bestuur-m:BESTUUR',
          ]);

          gateway.calls.clear();
          await choose(tester, 'RUIMTE');
          expect(find.text('Programma Natuurnetwerk'), findsOneWidget);
          expect(find.text('Jaarrekening 2025 provincie'), findsNothing);
          expect(gateway.calls, [
            'overview:RUIMTE',
            'agendaItems:ruimte-m:RUIMTE',
          ]);
          expect(gateway.writes, isEmpty);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('a late answer of the previous commissie is ignored', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway()..hold('overview:RUIMTE');
        await pump(tester, gateway, width, settle: false);
        await tester.pump();
        await choose(tester, 'BESTUUR');
        expect(find.text('Jaarrekening 2025 provincie'), findsOneWidget);

        gateway.release('overview:RUIMTE');
        await tester.pumpAndSettle();
        expect(find.text('Jaarrekening 2025 provincie'), findsOneWidget);
        expect(find.text('Programma Natuurnetwerk'), findsNothing);
        // Het late Ruimte-antwoord leidt niet meer tot het laden van Ruimte-agendapunten.
        expect(
          gateway.calls.any((c) => c.startsWith('agendaItems:ruimte')),
          isFalse,
        );
        expect(tester.takeException(), isNull);
      });

      testWidgets('the 15 second refresh loads only the chosen commissie', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        await choose(tester, 'BESTUUR');
        gateway.calls.clear();
        await tester.pump(const Duration(seconds: 15));
        await tester.pumpAndSettle();
        expect(gateway.calls, isNotEmpty);
        expect(
          gateway.calls.every(
            (c) => c.endsWith(':BESTUUR') || c == 'overview:BESTUUR',
          ),
          isTrue,
        );
        expect(gateway.writes, isEmpty);
      });

      testWidgets(
        'unpublished Bestuur agenda shows the date, a notice and no items',
        (tester) async {
          final gateway = CommissieDashboardGateway(
            bestuur: BestuurAgenda.unpublished,
          );
          await pump(tester, gateway, width);
          await choose(tester, 'BESTUUR');
          expect(
            find.textContaining(meetingLongDate(bestuurStart)),
            findsOneWidget,
          );
          expect(
            find.textContaining(
              'De agenda van Commissie Bestuur is er nog niet',
            ),
            findsOneWidget,
          );
          expect(
            find.byKey(const ValueKey('agenda-unpublished-notice')),
            findsOneWidget,
          );
          expect(find.byType(ExpansionTile), findsNothing);
          expect(find.textContaining('A-agenda'), findsNothing);
          // De tijd komt alleen uit de bron; zonder eindtijd staat er geen hardgecodeerde tijd.
          expect(find.textContaining('13:00'), findsNothing);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('published Bestuur agenda shows A, B and C cards with tags', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        await choose(tester, 'BESTUUR');
        expect(find.text('A-stuk'), findsNWidgets(2));
        expect(find.text('B-stuk'), findsOneWidget);
        expect(find.text('C-stuk'), findsOneWidget);
        for (final id in ['b-a-3', 'b-b-5', 'b-c-8', 'b-d-9']) {
          expect(
            find.descendant(of: tag(id), matching: find.text('Bestuur')),
            findsOneWidget,
          );
        }
        expect(find.text('Analyse klaar'), findsNothing);
        expect(find.text('Gereed'), findsNWidgets(3));
        expect(
          find.byKey(const ValueKey('missing-document-b-d-9')),
          findsOneWidget,
        );
        expect(
          find.byKey(const ValueKey('missing-document-b-a-3')),
          findsNothing,
        );
        expect(tester.takeException(), isNull);
      });

      testWidgets(
        'A/B item shows the draft analysis with the AI notice and versions',
        (tester) async {
          final gateway = CommissieDashboardGateway();
          gateway.versions['b-a-3'] = syntheticAdviceVersions();
          await pump(tester, gateway, width);
          await choose(tester, 'BESTUUR');
          await expand(tester, 'Jaarrekening 2025 provincie');
          expect(
            find.byKey(const ValueKey('ai-concept-notice')),
            findsOneWidget,
          );
          expect(
            tester
                .widget<Text>(find.byKey(const ValueKey('ai-concept-notice')))
                .data,
            aiConceptNotice,
          );
          for (final section in [
            'Samenvatting',
            'Aansluiting bij PvdD-bronnen',
            'Aandachtspunten',
            'Mogelijke inzet',
            'Stukken',
            'Bronverwijzingen',
          ]) {
            expect(rich(section), findsWidgets, reason: section);
          }
          expect(find.textContaining('Jaarrekening.pdf'), findsWidgets);
          // De bestaande versiekeuze blijft bereikbaar.
          expect(find.textContaining('Laatste advies'), findsOneWidget);
          expect(find.textContaining('Eerdere versie'), findsOneWidget);
          expect(gateway.calls, contains('adviceVersions:b-a-3:BESTUUR'));
          expect(
            gateway.calls.any(
              (c) => c.contains(':RUIMTE') && c.startsWith('agendaItem:'),
            ),
            isFalse,
          );
          expect(gateway.writes, isEmpty);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('C item shows the assessment', (tester) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        await choose(tester, 'BESTUUR');
        await expand(tester, 'Ingekomen brief gemeente');
        expect(rich('Beoordeling'), findsWidgets);
        expect(rich('Bespreking te overwegen'), findsOneWidget);
        expect(find.byKey(const ValueKey('ai-concept-notice')), findsOneWidget);
        expect(tester.takeException(), isNull);
      });

      testWidgets('item without document says so and has no analysis', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        await choose(tester, 'BESTUUR');
        await expand(tester, 'Statenvoorstel vergaderstructuur');
        expect(find.textContaining('Stuk ontbreekt'), findsOneWidget);
        expect(
          find.textContaining('Het stuk ontbreekt: er komt geen analyse'),
          findsOneWidget,
        );
        expect(find.byKey(const ValueKey('ai-concept-notice')), findsNothing);
        expect(tester.takeException(), isNull);
      });

      testWidgets(
        'a failing commissie is named, the other succeeded, data stays',
        (tester) async {
          final gateway = CommissieDashboardGateway()
            ..checkNowOutcome = const MeetingCheckOutcome(
              status: 'UNCHANGED',
              revisionNumber: 1,
              differences: [],
              commissies: [
                CommissieCheck(
                  commissie: 'RUIMTE',
                  name: 'Ruimte',
                  success: true,
                ),
                CommissieCheck(
                  commissie: 'BESTUUR',
                  name: 'Bestuur',
                  success: false,
                  melding: 'bron onbereikbaar',
                ),
              ],
            );
          await pump(tester, gateway, width);
          await choose(tester, 'BESTUUR');
          await tester.ensureVisible(find.text('Nu controleren'));
          await tester.pumpAndSettle();
          await tester.tap(find.text('Nu controleren'));
          await tester.pumpAndSettle();
          expect(
            find.textContaining('Commissie Bestuur: controle mislukt'),
            findsOneWidget,
          );
          expect(
            find.textContaining('Commissie Ruimte: controle geslaagd'),
            findsOneWidget,
          );
          // Eerder geladen gegevens blijven zichtbaar.
          expect(find.text('Jaarrekening 2025 provincie'), findsOneWidget);
          expect(find.text('Opnieuw proberen'), findsOneWidget);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('all commissies failing is reported per name', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway()
          ..checkNowOutcome = const MeetingCheckOutcome(
            status: 'SOURCE_FAILURE',
            revisionNumber: null,
            differences: [],
            commissies: [
              CommissieCheck(
                commissie: 'RUIMTE',
                name: 'Ruimte',
                success: false,
              ),
              CommissieCheck(
                commissie: 'BESTUUR',
                name: 'Bestuur',
                success: false,
              ),
            ],
          );
        await pump(tester, gateway, width);
        await tester.ensureVisible(find.text('Nu controleren'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Nu controleren'));
        await tester.pumpAndSettle();
        expect(
          find.textContaining('Commissie Ruimte: controle mislukt'),
          findsOneWidget,
        );
        expect(
          find.textContaining('Commissie Bestuur: controle mislukt'),
          findsOneWidget,
        );
        expect(find.text('Programma Natuurnetwerk'), findsOneWidget);
      });

      testWidgets('a failed read keeps the message and the switch', (
        tester,
      ) async {
        final gateway = CommissieDashboardGateway();
        await pump(tester, gateway, width);
        gateway.failing.add('BESTUUR');
        await choose(tester, 'BESTUUR');
        expect(
          find.text('Het vergaderingsoverzicht is tijdelijk niet beschikbaar.'),
          findsOneWidget,
        );
        expect(find.text('Programma Natuurnetwerk'), findsNothing);
        expect(find.byKey(const ValueKey('commissie-switch')), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    });
  }
}
