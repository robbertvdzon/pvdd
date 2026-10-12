// Het resultaat per vergadering na 'Nu controleren': tabel op desktop, blokken op mobiel,
// niet-gepubliceerde agenda, één mislukte vergadering, mislukte hele controle met herhaalknop,
// verversing na succes, 409 en de terugval op het oude enkelvoudige veld.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/meeting_overview.dart';

import 'support/fakes.dart';
import 'start_view_past_meeting_test.dart' show pumpStartView;

const aiLabel = 'AI-concept — controleer bronnen en formulering vóór gebruik';
final dayA = DateTime(2026, 10, 19, 19, 30);
final dayB = DateTime(2026, 10, 22, 19, 30);
final dayC = DateTime(2026, 10, 27, 19, 30);

MeetingCheckOutcome listOutcome(List<MeetingCheckResult> meetings) =>
    MeetingCheckOutcome(
      status: 'IMPORTED',
      revisionNumber: 1,
      differences: const [],
      meetings: meetings,
    );

final threeOutcomes = listOutcome([
  MeetingCheckResult(
    meetingId: 'm-1',
    startsAt: dayA,
    location: 'Statenzaal',
    outcome: 'UNCHANGED',
  ),
  MeetingCheckResult(
    meetingId: 'm-2',
    startsAt: dayB,
    location: 'Haarlem',
    outcome: 'NEW_DOCUMENTS',
    newAnalyses: 7,
  ),
  MeetingCheckResult(
    startsAt: dayC,
    location: 'Alkmaar',
    outcome: 'AGENDA_UNPUBLISHED',
  ),
]);

FakeDashboardGateway gateway() =>
    FakeDashboardGateway(withMeeting: true, startsAt: dayA, itemsByMeeting: {});

Future<void> expectAiLabel(WidgetTester tester) async {
  await tester.ensureVisible(find.text('Natuur en wonen combineren'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Natuur en wonen combineren'));
  await tester.pumpAndSettle();
  expect(find.textContaining(aiLabel), findsOneWidget);
}

Future<void> check(WidgetTester tester) async {
  final button = find.text('Nu controleren');
  await tester.ensureVisible(button);
  await tester.pumpAndSettle();
  await tester.tap(button);
  await tester.pumpAndSettle();
}

const sizes = [Size(1200, 2400), Size(390, 2400)];

void main() {
  for (final size in sizes) {
    final desktop = size.width > 700;
    group('width ${size.width.toInt()}', () {
      testWidgets('shows three outcomes per meeting', (tester) async {
        final g = gateway()..checkNowOutcome = threeOutcomes;
        await pumpStartView(tester, g, navigated: [], size: size);
        await check(tester);
        expect(
          find.byKey(const ValueKey('check-table')),
          desktop ? findsOneWidget : findsNothing,
        );
        expect(
          find.byKey(const ValueKey('check-block-m-1')),
          desktop ? findsNothing : findsOneWidget,
        );
        for (final d in [dayA, dayB, dayC]) {
          expect(find.text(meetingLongDate(d)), findsWidgets);
        }
        expect(find.text('Statenzaal'), findsWidgets);
        expect(find.text('Haarlem'), findsOneWidget);
        expect(find.text('Alkmaar'), findsOneWidget);
        expect(find.text('Niets gewijzigd'), findsOneWidget);
        expect(find.text('Nieuwe stukken ingelezen'), findsOneWidget);
        expect(find.text('Agenda nog niet gepubliceerd'), findsOneWidget);
        expect(find.text(desktop ? '7' : '7 nieuwe analyses'), findsOneWidget);
        expect(
          find.text(
            'Er is niets opgeslagen. De app kijkt bij de volgende controle opnieuw.',
          ),
          findsOneWidget,
        );
        await expectAiLabel(tester);
        expect(tester.takeException(), isNull);
      });

      testWidgets('one failed meeting is red and others stay clickable', (
        tester,
      ) async {
        final g = gateway()
          ..checkNowOutcome = listOutcome([
            MeetingCheckResult(
              meetingId: 'm-1',
              startsAt: dayA,
              location: 'Statenzaal',
              outcome: 'UNCHANGED',
            ),
            MeetingCheckResult(
              meetingId: 'm-2',
              startsAt: dayB,
              location: 'Haarlem',
              outcome: 'FAILED',
              errorDescription: 'Agenda kon niet worden verwerkt',
            ),
          ]);
        await pumpStartView(tester, g, navigated: [], size: size);
        await check(tester);
        final error = find.text('Agenda kon niet worden verwerkt');
        expect(error, findsOneWidget);
        final context = tester.element(error);
        expect(
          tester.widget<Text>(error).style?.color,
          Theme.of(context).colorScheme.error,
        );
        expect(find.text('Niets gewijzigd'), findsOneWidget);
        g.calls.clear();
        await tester.tap(find.text(meetingLongDate(dayA)).last);
        await tester.pumpAndSettle();
        expect(g.calls, contains('meeting:m-1'));
        expect(tester.takeException(), isNull);
      });

      testWidgets('whole check failure shows retry and keeps data', (
        tester,
      ) async {
        final g = gateway()..checkNowFailures.add(const DashboardUnavailable());
        await pumpStartView(tester, g, navigated: [], size: size);
        final cards = find.text('Commissie Ruimte 14 september 2026');
        final before = cards.evaluate().length;
        await check(tester);
        expect(find.byKey(const ValueKey('check-failed')), findsOneWidget);
        await tester.ensureVisible(find.text('Opnieuw proberen').first);
        await tester.pumpAndSettle();
        expect(cards.evaluate().length, before);
        expect(g.calls.where((c) => c == 'checkNow'), hasLength(1));
        await tester.tap(
          find.widgetWithText(OutlinedButton, 'Opnieuw proberen'),
        );
        await tester.pumpAndSettle();
        expect(g.calls.where((c) => c == 'checkNow'), hasLength(2));
        expect(find.byKey(const ValueKey('check-failed')), findsNothing);
        await expectAiLabel(tester);
      });

      testWidgets('single unidentified failure counts as whole failure', (
        tester,
      ) async {
        final g = gateway()
          ..checkNowOutcome = listOutcome([
            const MeetingCheckResult(
              outcome: 'FAILED',
              errorDescription: 'bron weg',
            ),
          ]);
        await pumpStartView(tester, g, navigated: [], size: size);
        await check(tester);
        expect(find.byKey(const ValueKey('check-failed')), findsOneWidget);
        expect(find.byKey(const ValueKey('check-result')), findsNothing);
      });

      testWidgets('refreshes after success only, 409 keeps existing message', (
        tester,
      ) async {
        final g = gateway()..checkNowOutcome = threeOutcomes;
        await pumpStartView(tester, g, navigated: [], size: size);
        g.calls.clear();
        await check(tester);
        expect(g.calls, containsAll(['checkNow', 'overview']));
        expect(g.calls.any((c) => c.startsWith('agendaItems')), isTrue);

        final failing = gateway()..checkNowFailures.add(Exception('net'));
        await pumpStartView(tester, failing, navigated: [], size: size);
        failing.calls.clear();
        await check(tester);
        expect(failing.calls, ['checkNow']);
      });

      testWidgets('409 keeps the existing message', (tester) async {
        final busy = gateway()
          ..checkNowOutcome = const MeetingCheckOutcome(
            status: 'ALREADY_RUNNING',
            revisionNumber: null,
            differences: [],
          );
        await pumpStartView(tester, busy, navigated: [], size: size);
        final button = find.text('Nu controleren');
        await tester.ensureVisible(button);
        await tester.pumpAndSettle();
        await tester.tap(button);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 500));
        expect(find.text('Er loopt al een broncontrole.'), findsOneWidget);
        expect(find.byKey(const ValueKey('check-failed')), findsNothing);
        expect(find.textContaining('Controleren is niet gelukt'), findsNothing);
        await tester.pumpAndSettle();
      });

      testWidgets('falls back to the single old field', (tester) async {
        final g = gateway();
        await pumpStartView(tester, g, navigated: [], size: size);
        await check(tester);
        expect(find.byKey(const ValueKey('check-result')), findsOneWidget);
        expect(find.text('Niets gewijzigd'), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    });
  }
}
