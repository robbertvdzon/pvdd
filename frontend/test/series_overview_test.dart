// De reeks 'Deze vergaderingen horen bij elkaar' op de startweergave: drie, één, nul en een
// ontbrekend `series`-veld, de keuze van een tegel, en de lay-out op desktop- en mobielbreedte.
// Alles draait op de gedeelde `FakeDashboardGateway`, die elke aanroep registreert.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/meeting_overview.dart';

import 'support/fakes.dart';
import 'start_view_past_meeting_test.dart' show pumpStartView;

const heading = 'Deze vergaderingen horen bij elkaar';
const aiLabel = 'AI-concept — controleer bronnen en formulering vóór gebruik';

final firstStart = DateTime(2026, 10, 19, 19, 30);
final secondStart = DateTime(2026, 10, 22, 19, 30);
final thirdStart = DateTime(2026, 10, 27, 19, 30);

AgendaItemSummary itemWithTitle(String id, String title) => AgendaItemSummary(
  id: id,
  sequence: 1,
  displayNumber: '1.a',
  category: 'A',
  title: title,
  substantive: true,
  importStatus: 'COMPLETE',
  analysisStatus: 'SUCCEEDED',
  sourceState: 'CURRENT',
  currentFingerprint: null,
  adviceActuality: 'CURRENT',
  changeTypes: const [],
);

FakeDashboardGateway seriesGateway({int count = 3}) {
  final all = [
    syntheticSeriesMeeting('m-1', firstStart, place: 'Statenzaal'),
    syntheticSeriesMeeting(
      'm-2',
      secondStart,
      place: 'Haarlem',
      status: 'PREVIEW',
    ),
    syntheticSeriesMeeting(
      'm-3',
      thirdStart,
      place: 'Alkmaar',
      status: 'AGENDA_UNPUBLISHED',
    ),
  ];
  return FakeDashboardGateway(
    withMeeting: true,
    startsAt: firstStart,
    series: all.take(count).toList(),
    itemsByMeeting: {
      'm-1': [itemWithTitle('item-a', 'Punt van de eerste')],
      'm-2': [itemWithTitle('item-a', 'Punt van de tweede')],
      'm-3': [itemWithTitle('item-a', 'Punt van de derde')],
    },
  );
}

Finder tile(String id) => find.byKey(ValueKey('series-tile-$id'));

void main() {
  testWidgets('three meetings show the box, three tiles and open the first', (
    tester,
  ) async {
    final gateway = seriesGateway();
    await pumpStartView(tester, gateway, navigated: []);

    expect(find.text(heading), findsOneWidget);
    for (final id in ['m-1', 'm-2', 'm-3']) {
      expect(tile(id), findsOneWidget);
    }
    expect(
      find.descendant(
        of: tile('m-1'),
        matching: find.text(meetingLongDate(firstStart)),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(of: tile('m-2'), matching: find.text('Haarlem')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: tile('m-2'),
        matching: find.text('Voorlopige agenda'),
      ),
      findsOneWidget,
    );
    expect(find.text('Punt van de eerste'), findsOneWidget);
    expect(find.text('Punt van de tweede'), findsNothing);
    expect(find.byIcon(Icons.check_circle), findsOneWidget);
    expect(
      find.descendant(
        of: tile('m-1'),
        matching: find.byIcon(Icons.check_circle),
      ),
      findsOneWidget,
    );
  });

  testWidgets('a tap loads that meeting, marks the tile and only reads', (
    tester,
  ) async {
    final gateway = seriesGateway();
    await pumpStartView(tester, gateway, navigated: []);
    gateway.calls.clear();

    await tester.ensureVisible(tile('m-2'));
    await tester.pumpAndSettle();
    await tester.tap(tile('m-2'));
    await tester.pumpAndSettle();

    expect(gateway.calls, ['meeting:m-2', 'agendaItems:m-2']);
    expect(gateway.startingCalls, isEmpty);
    expect(find.text('Punt van de tweede'), findsOneWidget);
    expect(find.text('Punt van de eerste'), findsNothing);
    expect(
      find.descendant(
        of: tile('m-2'),
        matching: find.byIcon(Icons.check_circle),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: tile('m-1'),
        matching: find.byIcon(Icons.check_circle),
      ),
      findsNothing,
    );
    // De gekozen vergadering heeft haar eigen datum op de vergaderkaart.
    expect(find.text('Nu controleren'), findsOneWidget);
  });

  testWidgets('the chosen meeting stays chosen after a refresh', (
    tester,
  ) async {
    final gateway = seriesGateway();
    await pumpStartView(tester, gateway, navigated: []);
    await tester.ensureVisible(tile('m-3'));
    await tester.pumpAndSettle();
    await tester.tap(tile('m-3'));
    await tester.pumpAndSettle();
    gateway.calls.clear();

    await tester.pump(const Duration(seconds: 15));
    await tester.pumpAndSettle();

    expect(
      gateway.calls,
      containsAllInOrder(['overview', 'meeting:m-3', 'agendaItems:m-3']),
    );
    expect(gateway.startingCalls, isEmpty);
    expect(find.text('Punt van de derde'), findsOneWidget);
    expect(
      find.descendant(
        of: tile('m-3'),
        matching: find.byIcon(Icons.check_circle),
      ),
      findsOneWidget,
    );
  });

  testWidgets('advice keeps the AI label for every chosen meeting', (
    tester,
  ) async {
    final gateway = seriesGateway();
    await pumpStartView(tester, gateway, navigated: []);
    await tester.ensureVisible(tile('m-2'));
    await tester.pumpAndSettle();
    await tester.tap(tile('m-2'));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Punt van de tweede'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Punt van de tweede'));
    await tester.pumpAndSettle();

    expect(find.textContaining(aiLabel), findsOneWidget);
  });

  testWidgets('one meeting leaves the screen as it is, without a box', (
    tester,
  ) async {
    final gateway = seriesGateway(count: 1);
    await pumpStartView(tester, gateway, navigated: []);

    expect(find.text(heading), findsNothing);
    expect(
      find
          .byType(InkWell)
          .evaluate()
          .where((e) => e.widget.key is ValueKey<String>),
      isEmpty,
    );
    expect(gateway.calls, ['overview', 'agendaItems:meeting-id']);
  });

  testWidgets('a missing series field behaves as before', (tester) async {
    final overview = MeetingOverview.fromJson({
      'status': 'NO_MEETING',
      'meeting': null,
      'lastCheckedAt': null,
      'progress': {'total': 0, 'complete': 0, 'failed': 0},
    });
    expect(overview.series, isEmpty);

    final gateway = FakeDashboardGateway(withMeeting: true);
    await pumpStartView(tester, gateway, navigated: []);
    expect(find.text(heading), findsNothing);
    expect(gateway.calls, ['overview', 'agendaItems:meeting-id']);
  });

  testWidgets('series items are parsed from the server answer', (tester) async {
    final overview = MeetingOverview.fromJson({
      'status': 'NO_MEETING',
      'meeting': null,
      'lastCheckedAt': null,
      'progress': {'total': 0, 'complete': 0, 'failed': 0},
      'series': [
        {
          'id': 'x',
          'startsAt': '2026-10-19T17:30:00Z',
          'place': null,
          'status': 'DISCOVERED',
        },
      ],
    });
    expect(overview.series.single.id, 'x');
    expect(overview.series.single.place, isNull);
    expect(overview.series.single.startsAt, DateTime.utc(2026, 10, 19, 17, 30));
  });

  testWidgets('no upcoming meeting explains and steps to the archive', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway();
    final navigated = <String>[];
    await pumpStartView(tester, gateway, navigated: navigated);

    expect(
      find.text('Er is nog geen toekomstige vergadering gevonden.'),
      findsOneWidget,
    );
    expect(find.text(heading), findsNothing);
    await tester.tap(find.text('Alle eerdere vergaderingen'));
    await tester.pumpAndSettle();
    expect(navigated, ['/archief']);
  });

  testWidgets('tiles sit side by side on a desktop width', (tester) async {
    await pumpStartView(
      tester,
      seriesGateway(),
      navigated: [],
      size: const Size(1400, 2400),
    );
    final tops = ['m-1', 'm-2', 'm-3'].map((id) => tester.getTopLeft(tile(id)));
    expect(tops.map((o) => o.dy).toSet().length, 1);
    expect(tops.map((o) => o.dx).toSet().length, 3);
    expect(tester.takeException(), isNull);
  });

  testWidgets('tiles stack on a phone width without overflow', (tester) async {
    await pumpStartView(
      tester,
      seriesGateway(),
      navigated: [],
      size: const Size(360, 2400),
    );
    final tops = [
      'm-1',
      'm-2',
      'm-3',
    ].map((id) => tester.getTopLeft(tile(id))).toList();
    expect(tops.map((o) => o.dx).toSet().length, 1);
    expect(tops[0].dy, lessThan(tops[1].dy));
    expect(tops[1].dy, lessThan(tops[2].dy));
    expect(tester.takeException(), isNull);
  });
}
