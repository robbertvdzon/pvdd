// De webapp met data in de vorm van de acceptatiescenario's (meeting-source-mock, /api/scenarios):
// het kader 'Deze vergaderingen horen bij elkaar' met een tegel per vergadering, het wisselen van
// tegel, het resultaatoverzicht na 'Nu controleren' en geen kader bij 'een-vergadering'. Elke test
// draait op een desktop- en een mobielbreedte en mag geen overflow geven. De fake gateway registreert
// elke aanroep.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/dashboard_api.dart';

import 'support/fakes.dart';
import 'start_view_past_meeting_test.dart' show pumpStartView;

const heading = 'Deze vergaderingen horen bij elkaar';
const sizes = {'desktop': Size(1400, 2400), 'mobiel': Size(390, 2400)};

/// D = eerstvolgende vergadering; de rest is een aantal dagen later, zoals in de scenario's.
final base = DateTime(2026, 10, 21, 18, 30);

/// Scenario -> dagen na D van de vergaderingen die de controle verwerkt (de rest wordt niet getoond).
const scenarios = <String, List<int>>{
  'twee-vergaderingen': [0, 3],
  'meer-dan-drie': [0, 2, 5],
  'grens-dag-10-en-11': [0, 10],
  'extra-agenda-niet-gepubliceerd': [0, 3],
  'een-vergadering-mislukt': [0, 4],
};

AgendaItemSummary item(String id, String title) => AgendaItemSummary(
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

FakeDashboardGateway gatewayFor(List<int> offsets) {
  final meetings = [
    for (var i = 0; i < offsets.length; i++)
      syntheticSeriesMeeting(
        'm-${i + 1}',
        base.add(Duration(days: offsets[i])),
        status: i == 1 && offsets.length == 2 ? 'PREVIEW' : 'DISCOVERED',
      ),
  ];
  return FakeDashboardGateway(
    withMeeting: true,
    startsAt: base,
    series: meetings,
    itemsByMeeting: {
      for (var i = 0; i < offsets.length; i++)
        'm-${i + 1}': [item('item-a', 'Agendapunt van vergadering ${i + 1}')],
    },
  );
}

Finder tile(String id) => find.byKey(ValueKey('series-tile-$id'));

void main() {
  for (final entry in sizes.entries) {
    final size = entry.value;
    group('${entry.key} (${size.width.toInt()} px)', () {
      for (final scenario in scenarios.entries) {
        testWidgets('${scenario.key}: kader met een tegel per vergadering', (
          tester,
        ) async {
          final gateway = gatewayFor(scenario.value);
          await pumpStartView(tester, gateway, navigated: [], size: size);

          expect(find.text(heading), findsOneWidget);
          for (var i = 1; i <= scenario.value.length; i++) {
            expect(tile('m-$i'), findsOneWidget);
          }
          expect(tile('m-${scenario.value.length + 1}'), findsNothing);
          expect(find.text('Agendapunt van vergadering 1'), findsOneWidget);
          expect(tester.takeException(), isNull);
        });
      }

      testWidgets(
        'een tegelklik toont de agenda van die vergadering met een tweede aanroep',
        (tester) async {
          final gateway = gatewayFor(scenarios['meer-dan-drie']!);
          await pumpStartView(tester, gateway, navigated: [], size: size);
          final before = gateway.calls
              .where((c) => c.startsWith('agendaItems:'))
              .length;
          expect(before, 1);

          await tester.ensureVisible(tile('m-3'));
          await tester.pumpAndSettle();
          await tester.tap(tile('m-3'));
          await tester.pumpAndSettle();

          expect(
            gateway.calls.where((c) => c.startsWith('agendaItems:')).length,
            2,
          );
          expect(
            gateway.calls,
            containsAllInOrder(['meeting:m-3', 'agendaItems:m-3']),
          );
          expect(gateway.startingCalls, isEmpty);
          expect(find.text('Agendapunt van vergadering 3'), findsOneWidget);
          expect(find.text('Agendapunt van vergadering 1'), findsNothing);

          await tester.ensureVisible(tile('m-1'));
          await tester.pumpAndSettle();
          await tester.tap(tile('m-1'));
          await tester.pumpAndSettle();
          expect(find.text('Agendapunt van vergadering 1'), findsOneWidget);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('na Nu controleren staat per vergadering een resultaat', (
        tester,
      ) async {
        final gateway = gatewayFor(scenarios['extra-agenda-niet-gepubliceerd']!)
          ..checkNowOutcome = MeetingCheckOutcome(
            status: 'IMPORTED',
            revisionNumber: 1,
            differences: const [],
            meetings: [
              MeetingCheckResult(
                meetingId: 'm-1',
                startsAt: base,
                location: 'Statenzaal',
                outcome: 'NEW_DOCUMENTS',
                newAnalyses: 3,
              ),
              MeetingCheckResult(
                startsAt: base.add(const Duration(days: 3)),
                outcome: 'AGENDA_UNPUBLISHED',
              ),
            ],
          );
        await pumpStartView(tester, gateway, navigated: [], size: size);

        await tester.ensureVisible(find.text('Nu controleren'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Nu controleren'));
        await tester.pumpAndSettle();

        expect(gateway.calls.where((c) => c == 'checkNow').length, 1);
        expect(find.byKey(const ValueKey('check-result')), findsOneWidget);
        expect(
          find.descendant(
            of: find.byKey(const ValueKey('check-result')),
            matching: find.text('Agenda nog niet gepubliceerd'),
          ),
          findsOneWidget,
        );
        expect(tester.takeException(), isNull);
      });

      testWidgets('een-vergadering: geen kader en geen tegels', (tester) async {
        final gateway = FakeDashboardGateway(
          withMeeting: true,
          startsAt: base,
          series: [syntheticSeriesMeeting('m-1', base)],
          itemsByMeeting: {
            'm-1': [item('item-a', 'Agendapunt van vergadering 1')],
          },
        );
        await pumpStartView(tester, gateway, navigated: [], size: size);

        expect(find.text(heading), findsNothing);
        expect(tile('m-1'), findsNothing);
        expect(gateway.calls, ['overview', 'agendaItems:meeting-id']);
        expect(tester.takeException(), isNull);
      });
    });
  }
}
