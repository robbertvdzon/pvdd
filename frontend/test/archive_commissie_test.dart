// Archief per commissie: gedeelde keuze, lege Bestuur-toestand, alleen leesroutes. Draait op
// desktop (1280) en mobiel (390) met de registrerende `CommissieDashboardGateway`.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/main.dart';

import 'support/commissie_fakes.dart';
import 'support/fakes.dart';

const widths = [1280.0, 390.0];

Future<void> pump(
  WidgetTester tester,
  CommissieDashboardGateway gateway,
  double width, {
  String path = '/agenda',
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
        appPath: () => path,
        navigate: (_) {},
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> choose(WidgetTester tester, String commissie) async {
  await tester.tap(find.byKey(ValueKey('commissie-option-$commissie')));
  await tester.pumpAndSettle();
}

String selected(WidgetTester tester) => tester
    .widget<SegmentedButton<String>>(
      find.byKey(const ValueKey('commissie-switch')),
    )
    .selected
    .single;

CommissieDashboardGateway seeded() {
  final gateway = CommissieDashboardGateway();
  gateway.pastByCommissie['RUIMTE'] = [
    CommissieDashboardGateway.past('RUIMTE', 'Ruimte vergadering september'),
  ];
  gateway.pastByCommissie['BESTUUR'] = [
    CommissieDashboardGateway.past('BESTUUR', 'Bestuur vergadering september'),
  ];
  return gateway;
}

Future<void> openArchiveFromAgenda(WidgetTester tester) async {
  await tester.ensureVisible(find.text('Eerdere vergaderingen'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Eerdere vergaderingen'));
  await tester.pumpAndSettle();
}

void expectReadOnly(CommissieDashboardGateway gateway) {
  expect(gateway.writes, isEmpty);
  expect(
    gateway.calls.every(
      (c) => RegExp(
        r'^(overview|meeting|pastMeetings|agendaItems|agendaItem|adviceVersions):',
      ).hasMatch(c),
    ),
    isTrue,
    reason: '${gateway.calls}',
  );
}

void main() {
  for (final width in widths) {
    group('at $width px', () {
      testWidgets('AC1: archive shows only the chosen commissie', (
        tester,
      ) async {
        final gateway = seeded();
        await pump(tester, gateway, width, path: '/archief');
        expect(find.byKey(const ValueKey('commissie-switch')), findsOneWidget);
        expect(
          tester
              .widget<Text>(find.byKey(const ValueKey('archive-commissie')))
              .data,
          'Commissie Ruimte',
        );
        expect(find.text('Ruimte vergadering september'), findsOneWidget);
        expect(find.text('Bestuur vergadering september'), findsNothing);
        expect(gateway.calls, contains('pastMeetings:RUIMTE'));

        await choose(tester, 'BESTUUR');
        expect(find.text('Bestuur vergadering september'), findsOneWidget);
        expect(find.text('Ruimte vergadering september'), findsNothing);
        expect(gateway.calls.last, 'pastMeetings:BESTUUR');
        expect(
          tester
              .widget<Text>(find.byKey(const ValueKey('archive-commissie')))
              .data,
          'Commissie Bestuur',
        );

        await choose(tester, 'RUIMTE');
        expect(find.text('Ruimte vergadering september'), findsOneWidget);
        expect(find.text('Bestuur vergadering september'), findsNothing);
        expect(tester.takeException(), isNull);
      });

      testWidgets('AC2: empty Bestuur explains and refers to Ruimte', (
        tester,
      ) async {
        final gateway = seeded()..pastByCommissie['BESTUUR'] = [];
        await pump(tester, gateway, width, path: '/archief');
        await choose(tester, 'BESTUUR');
        expect(
          find.textContaining('nog geen eerdere vergaderingen'),
          findsOneWidget,
        );
        expect(find.byKey(const ValueKey('archive-empty-bestuur')), findsOne);
        gateway.calls.clear();

        await tester.ensureVisible(
          find.byKey(const ValueKey('archive-to-ruimte')),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('archive-to-ruimte')));
        await tester.pumpAndSettle();
        expect(selected(tester), 'RUIMTE');
        expect(gateway.calls, ['pastMeetings:RUIMTE']);
        expect(find.text('Ruimte vergadering september'), findsOneWidget);
        expect(tester.takeException(), isNull);
      });

      testWidgets(
        'AC3: Bestuur meeting detail with commissie name and advice',
        (tester) async {
          final gateway = seeded();
          await pump(tester, gateway, width, path: '/archief');
          await choose(tester, 'BESTUUR');
          await tester.tap(find.text('Openen'));
          await tester.pumpAndSettle();
          expect(find.byKey(const ValueKey('meeting-commissie-tag')), findsOne);
          expect(
            find.descendant(
              of: find.byKey(const ValueKey('meeting-commissie-tag')),
              matching: find.textContaining('Bestuur'),
            ),
            findsOneWidget,
          );
          expect(find.text('Jaarrekening 2025 provincie'), findsOneWidget);
          expect(
            gateway.calls.where((c) => c.startsWith('meeting:')).single,
            'meeting:bestuur-past-5:BESTUUR',
          );
          await tester.ensureVisible(find.text('Jaarrekening 2025 provincie'));
          await tester.pumpAndSettle();
          await tester.tap(find.text('Jaarrekening 2025 provincie'));
          await tester.pumpAndSettle();
          expect(
            find.textContaining(
              'Bestuursstuk over de jaarrekening',
              findRichText: true,
            ),
            findsWidgets,
          );
          expect(gateway.calls, contains('adviceVersions:b-a-3:BESTUUR'));
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('AC4: choice is shared between agenda and archive', (
        tester,
      ) async {
        final gateway = seeded();
        await pump(tester, gateway, width);
        await choose(tester, 'BESTUUR');
        await openArchiveFromAgenda(tester);
        expect(selected(tester), 'BESTUUR');
        expect(find.text('Bestuur vergadering september'), findsOneWidget);
        expect(gateway.calls.last, 'pastMeetings:BESTUUR');

        // Detail en terug naar het archief.
        await tester.tap(find.text('Openen'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Terug naar eerdere vergaderingen'));
        await tester.pumpAndSettle();
        expect(selected(tester), 'BESTUUR');
        expect(find.text('Bestuur vergadering september'), findsOneWidget);

        // Terug naar de agenda en omgekeerd: kies Ruimte in het archief.
        await tester.tap(find.text('Terug naar de agenda'));
        await tester.pumpAndSettle();
        expect(selected(tester), 'BESTUUR');
        await choose(tester, 'RUIMTE');
        await openArchiveFromAgenda(tester);
        expect(selected(tester), 'RUIMTE');
        expect(gateway.calls.last, 'pastMeetings:RUIMTE');
        expect(tester.takeException(), isNull);
      });

      testWidgets('AC5: archive and detail only read, also after switching', (
        tester,
      ) async {
        final gateway = seeded();
        await pump(tester, gateway, width, path: '/archief');
        await tester.tap(find.text('Openen'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Terug naar eerdere vergaderingen'));
        await tester.pumpAndSettle();
        await choose(tester, 'BESTUUR');
        await tester.tap(find.text('Openen'));
        await tester.pumpAndSettle();
        expectReadOnly(gateway);
        expect(find.text('Nu controleren'), findsNothing);
        expect(tester.takeException(), isNull);
      });

      testWidgets('late answer of the previous commissie is ignored', (
        tester,
      ) async {
        final gateway = seeded();
        await pump(tester, gateway, width, path: '/archief');
        await choose(tester, 'BESTUUR');
        gateway.hold('pastMeetings:RUIMTE');
        await tester.tap(find.byKey(const ValueKey('commissie-option-RUIMTE')));
        await tester.pump();
        await choose(tester, 'BESTUUR');
        gateway.release('pastMeetings:RUIMTE');
        await tester.pumpAndSettle();
        expect(find.text('Bestuur vergadering september'), findsOneWidget);
        expect(find.text('Ruimte vergadering september'), findsNothing);
      });
    });
  }
}
