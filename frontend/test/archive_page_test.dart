import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/main.dart';
import 'package:pvdd_frontend/meeting_overview.dart';
import 'package:pvdd_frontend/pvdd_theme.dart';

import 'support/fakes.dart';

const archivedMeetingId = '6c9ad377-5837-41b7-9f68-573ccf58c859';
// Lokale tijd, zodat de weergegeven datum niet van de tijdzone van de testmachine afhangt.
final meetingStart = DateTime(2026, 9, 7, 19, 30);

void main() {
  testWidgets('shows the same agenda view read-only for a past meeting', (
    tester,
  ) async {
    final gateway = archiveGateway();
    await pumpArchive(tester, gateway);

    // De markering staat boven de vergaderkaart.
    expect(
      find.textContaining(
        'Deze vergadering is al geweest — maandag 7 september 2026',
      ),
      findsOneWidget,
    );
    expect(
      find.text(
        'Je leest bewaarde adviezen terug. Bekijken start geen analyse en verandert niets.',
      ),
      findsOneWidget,
    );
    expect(
      tester
          .getTopLeft(find.textContaining('Deze vergadering is al geweest'))
          .dy,
      lessThan(
        tester.getTopLeft(find.text('Commissie Ruimte 7 september 2026')).dy,
      ),
    );

    // Dezelfde A/B/C-indeling en dezelfde filters als de huidige agenda.
    for (final label in ['Alles', 'A-agenda', 'B-agenda', 'C-agenda']) {
      expect(find.widgetWithText(FilterChip, label), findsOneWidget);
    }
    expect(find.text('Natuurinclusief wonen'), findsOneWidget);
    expect(find.text('Fietsverbinding'), findsOneWidget);
    expect(find.text('Natuurbrief'), findsOneWidget);
    expect(find.text('Technische sectiekop'), findsNothing);
    expect(find.text('Ingetrokken stuk'), findsNothing);

    await tester.tap(find.widgetWithText(FilterChip, 'B-agenda'));
    await tester.pumpAndSettle();
    expect(find.text('Fietsverbinding'), findsOneWidget);
    expect(find.text('Natuurinclusief wonen'), findsNothing);
    await tester.tap(find.widgetWithText(FilterChip, 'Alles'));
    await tester.pumpAndSettle();

    // Dezelfde informatie per agendapunt als bij de huidige vergadering.
    expect(find.text('AI-titel'), findsNWidgets(3));
    expect(find.text('Korte conclusie'), findsNWidgets(3));
    expect(find.text('Natuur en wonen combineren'), findsOneWidget);

    await tester.ensureVisible(find.text('Natuurinclusief wonen'));
    await tester.tap(find.text('Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    expect(find.text('Vrije Markdown-analyse'), findsOneWidget);
    expect(
      find.text('Een bruikbaar politiek advies zonder vast format.'),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'AI-concept — controleer bronnen en formulering vóór gebruik',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Niet leesbaar en daarom niet gebruikt in de analyse: '
        'bijlage-2-toelichting-woningbouw.pdf (Scan — OCR nodig)',
      ),
      findsOneWidget,
    );
    expect(find.text('Bronnen'), findsOneWidget);
    expect(find.text('voordracht.pdf · Gereed'), findsOneWidget);
    expect(
      find.text('bijlage-2-toelichting-woningbouw.pdf · Scan — OCR nodig'),
      findsOneWidget,
    );
  });

  testWidgets('offers no action that starts new work and only reads', (
    tester,
  ) async {
    final gateway = archiveGateway(failedAnalysis: true);
    await pumpArchive(tester, gateway);

    expect(find.text('Nu controleren'), findsNothing);
    expect(find.text('Opnieuw proberen'), findsNothing);

    await tester.ensureVisible(find.text('Natuurinclusief wonen'));
    await tester.tap(find.text('Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    expect(find.text('Opnieuw proberen'), findsNothing);

    // Ook na dertig seconden komt er geen ververstimer op gang.
    await tester.pump(const Duration(seconds: 30));
    await tester.pumpAndSettle();

    expect(gateway.startingCalls, isEmpty);
    expect(gateway.calls, [
      'meeting:$archivedMeetingId',
      'agendaItems:$archivedMeetingId',
      'agendaItem:item-a',
    ]);
  });

  testWidgets('the current meeting view keeps its actions and refresh', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final gateway = FakeDashboardGateway(
      withMeeting: true,
      failedAnalysis: true,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: MeetingOverviewPage(gateway: gateway)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Nu controleren'), findsOneWidget);
    expect(find.text('Opnieuw proberen'), findsOneWidget);
    expect(find.textContaining('Deze vergadering is al geweest'), findsNothing);
    expect(find.text('Terug naar agenda'), findsNothing);

    await tester.pump(const Duration(seconds: 15));
    await tester.pumpAndSettle();
    expect(
      gateway.calls.where((call) => call == 'overview').length,
      greaterThanOrEqualTo(2),
    );
  });

  testWidgets('keeps the meeting header when the agenda items fail to load', (
    tester,
  ) async {
    final gateway = archiveGateway()..failAgendaItems = true;
    await pumpArchive(tester, gateway);

    expect(find.text('Commissie Ruimte 7 september 2026'), findsOneWidget);
    expect(
      find.textContaining('Deze vergadering is al geweest'),
      findsOneWidget,
    );
    expect(
      find.text(
        'De agendapunten van deze vergadering konden niet worden geladen',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Er is geen advies verdwenen of gewijzigd'),
      findsOneWidget,
    );
    expect(find.text('Opnieuw proberen'), findsOneWidget);
    // Filteren heeft geen betekenis zolang er geen agendapunten geladen zijn.
    expect(find.widgetWithText(FilterChip, 'Alles'), findsNothing);

    gateway.failAgendaItems = false;
    await tester.tap(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();

    expect(find.text('Natuurinclusief wonen'), findsOneWidget);
    expect(find.widgetWithText(FilterChip, 'Alles'), findsOneWidget);
    expect(find.text('Commissie Ruimte 7 september 2026'), findsOneWidget);
    expect(
      find.textContaining('Deze vergadering is al geweest'),
      findsOneWidget,
    );
    expect(
      find.text(
        'De agendapunten van deze vergadering konden niet worden geladen',
      ),
      findsNothing,
    );
    expect(gateway.startingCalls, isEmpty);
  });

  testWidgets('a failing meeting header also offers a retry', (tester) async {
    final gateway = archiveGateway()..failMeeting = true;
    await pumpArchive(tester, gateway);

    expect(
      find.text('Deze vergadering kon niet worden geladen'),
      findsOneWidget,
    );
    expect(find.text('Opnieuw proberen'), findsOneWidget);

    gateway.failMeeting = false;
    await tester.tap(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();
    expect(find.text('Commissie Ruimte 7 september 2026'), findsOneWidget);
  });

  for (final size in const [Size(400, 2400), Size(1200, 2400)]) {
    testWidgets('marker and AI reservation stay visible at ${size.width}', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final gateway = archiveGateway();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: MeetingOverviewPage(
              gateway: gateway,
              archivedMeetingId: archivedMeetingId,
              readOnly: true,
              onBack: () {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Deze vergadering is al geweest'),
        findsOneWidget,
      );
      await tester.ensureVisible(find.text('Natuurinclusief wonen'));
      await tester.tap(find.text('Natuurinclusief wonen'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining(
          'AI-concept — controleer bronnen en formulering vóór gebruik',
        ),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('a direct /archief deep link opens the read-only screen', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final gateway = archiveGateway();
    final navigated = <String>[];
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
          appPath: () => '/archief/$archivedMeetingId',
          navigate: navigated.add,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Deze vergadering is al geweest'),
      findsOneWidget,
    );
    expect(gateway.calls.first, 'meeting:$archivedMeetingId');

    // Agenda blijft geselecteerd en de zijbalk telt onveranderd vijf menu-items.
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is ListTile &&
            widget.selectedTileColor == PvddColors.sidebarSelected,
      ),
      findsNWidgets(5),
    );
    expect(
      tester
          .widget<ListTile>(
            find.ancestor(
              of: find.text('Agenda'),
              matching: find.byType(ListTile),
            ),
          )
          .selected,
      isTrue,
    );

    await tester.tap(find.text('Terug naar agenda'));
    await tester.pumpAndSettle();

    expect(navigated, ['/agenda']);
    expect(find.textContaining('Deze vergadering is al geweest'), findsNothing);
    expect(find.text('Nu controleren'), findsOneWidget);
    expect(gateway.calls, contains('overview'));
  });
}

FakeDashboardGateway archiveGateway({bool failedAnalysis = false}) =>
    FakeDashboardGateway(
      withMeeting: true,
      past: true,
      withSources: true,
      failedAnalysis: failedAnalysis,
      startsAt: meetingStart,
      title: 'Commissie Ruimte 7 september 2026',
    );

Future<void> pumpArchive(
  WidgetTester tester,
  FakeDashboardGateway gateway,
) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: MeetingOverviewPage(
          gateway: gateway,
          archivedMeetingId: archivedMeetingId,
          readOnly: true,
          onBack: () {},
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
