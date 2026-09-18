// Het alleen-lezen overzicht van eerdere vergaderingen op `/archief`: de ingang vanaf de agenda,
// bijladen zonder duplicaten, de lege toestand, de fouttoestand, de routering en twee
// vensterbreedtes. Alle tests draaien op de gedeelde `FakeDashboardGateway`, die elke aanroep
// registreert en geen enkel netwerkverkeer doet.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/archive_page.dart';
import 'package:pvdd_frontend/main.dart';
import 'package:pvdd_frontend/pvdd_theme.dart';

import 'support/fakes.dart';

void main() {
  testWidgets('the agenda view opens the archive in one action', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway(withMeeting: true);
    final navigated = <String>[];
    await pumpShell(tester, gateway, path: '/agenda', navigated: navigated);

    expect(find.text('Eerdere vergaderingen'), findsOneWidget);
    expect(
      find.textContaining(
        'lees je de bewaarde adviezen terug van vergaderingen die al zijn geweest',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('Terugkijken is alleen lezen'), findsOneWidget);

    await tester.tap(find.text('Eerdere vergaderingen'));
    await tester.pumpAndSettle();

    expect(navigated, ['/archief']);
    expect(find.text('Vergaderingen die al zijn geweest, de meest recente bovenaan.'), findsOneWidget);
    expect(gateway.calls, contains('pastMeetings:first'));
  });

  testWidgets('loading more merges pages without duplicates or gaps', (
    tester,
  ) async {
    final gateway = pagedGateway();
    await pumpArchiveOverview(tester, gateway);

    expect(find.text('Commissie Ruimte vergadering 0'), findsOneWidget);
    expect(find.text('20 van 25 vergaderingen getoond'), findsOneWidget);
    expect(find.text('Commissie Ruimte vergadering 24'), findsNothing);

    await tester.ensureVisible(find.text('Meer vergaderingen laden'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Meer vergaderingen laden'));
    await tester.pumpAndSettle();

    // Elke gezaaide vergadering staat er precies één keer in, zonder overgeslagen id.
    for (var index = 0; index < 25; index++) {
      expect(
        find.text('Commissie Ruimte vergadering $index', skipOffstage: false),
        findsOneWidget,
        reason: 'vergadering $index',
      );
    }
    expect(
      find.text('25 van 25 vergaderingen getoond', skipOffstage: false),
      findsOneWidget,
    );
    // Zonder volgende cursor verdwijnt de bijlaadknop.
    expect(
      find.text('Meer vergaderingen laden', skipOffstage: false),
      findsNothing,
    );
    expect(gateway.calls, ['pastMeetings:first', 'pastMeetings:cursor-1']);
  });

  testWidgets('opening a row navigates to that meeting in the archive', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway()
      ..pastMeetingPages = {
        null: syntheticPastMeetingPage(first: 0, count: 1, total: 1),
      };
    final navigated = <String>[];
    await pumpShell(tester, gateway, path: '/archief', navigated: navigated);

    await tester.tap(find.text('Openen'));
    await tester.pumpAndSettle();

    expect(navigated, ['/archief/${syntheticMeetingId(0)}']);
    expect(gateway.calls, contains('meeting:${syntheticMeetingId(0)}'));
  });

  testWidgets('the overview reads only and never repeats its request', (
    tester,
  ) async {
    final gateway = pagedGateway();
    await pumpArchiveOverview(tester, gateway);

    // De alleen-lezen mededeling staat boven de eerste rij.
    expect(find.text('Alleen lezen'), findsOneWidget);
    expect(
      find.text(
        'Je leest hier bewaarde adviezen terug. Openen start nooit een nieuwe analyse '
        'en verandert geen bestaand advies.',
      ),
      findsOneWidget,
    );
    expect(
      tester.getTopLeft(find.text('Alleen lezen')).dy,
      lessThan(
        tester.getTopLeft(find.text('Commissie Ruimte vergadering 0')).dy,
      ),
    );

    // Geen enkele actie die werk start, en geen ververstimer binnen de testduur.
    expect(find.text('Nu controleren'), findsNothing);
    expect(find.text('Opnieuw proberen'), findsNothing);
    await tester.pump(const Duration(seconds: 30));
    await tester.pumpAndSettle();

    expect(gateway.startingCalls, isEmpty);
    expect(gateway.calls, ['pastMeetings:first']);
  });

  testWidgets('an empty archive explains why there is nothing to see', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway();
    await pumpArchiveOverview(tester, gateway);

    expect(find.text('Er is nog geen vergadering voorbij'), findsOneWidget);
    expect(
      find.textContaining(
        'De assistent bewaart elke vergadering met haar agendapunten en adviezen',
      ),
      findsOneWidget,
    );
    expect(find.text('Naar de huidige vergadering'), findsOneWidget);
    expect(find.text('Openen'), findsNothing);
    expect(find.text('Meer vergaderingen laden'), findsNothing);
    expect(find.textContaining('vergaderingen getoond'), findsNothing);
  });

  testWidgets('a failing first page offers a retry that repeats the request', (
    tester,
  ) async {
    final gateway = pagedGateway()..failingPastMeetingCursors = {null};
    await pumpArchiveOverview(tester, gateway);

    expect(
      find.text('Niet alle eerdere vergaderingen konden worden geladen'),
      findsOneWidget,
    );
    expect(find.text('Opnieuw proberen'), findsOneWidget);
    expect(find.text('Commissie Ruimte vergadering 0'), findsNothing);

    gateway.failingPastMeetingCursors = {};
    await tester.tap(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();

    expect(
      find.text('Niet alle eerdere vergaderingen konden worden geladen'),
      findsNothing,
    );
    expect(find.text('Commissie Ruimte vergadering 0'), findsOneWidget);
    expect(gateway.calls, ['pastMeetings:first', 'pastMeetings:first']);
  });

  testWidgets('a failing next page keeps the rows that were already loaded', (
    tester,
  ) async {
    final gateway = pagedGateway()..failingPastMeetingCursors = {'cursor-1'};
    await pumpArchiveOverview(tester, gateway);

    await tester.ensureVisible(find.text('Meer vergaderingen laden'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Meer vergaderingen laden'));
    await tester.pumpAndSettle();

    // De melding staat bovenaan, de al geladen rijen blijven staan.
    expect(
      find.text('Niet alle eerdere vergaderingen konden worden geladen'),
      findsOneWidget,
    );
    expect(
      find.textContaining('De vergaderingen hieronder zijn wel geladen'),
      findsOneWidget,
    );
    expect(
      find.text('Commissie Ruimte vergadering 0', skipOffstage: false),
      findsOneWidget,
    );
    expect(
      find.text('20 van 25 vergaderingen geladen', skipOffstage: false),
      findsOneWidget,
    );
    expect(
      tester.getTopLeft(
        find.text('Niet alle eerdere vergaderingen konden worden geladen'),
      ).dy,
      lessThan(
        tester.getTopLeft(find.text('Commissie Ruimte vergadering 0')).dy,
      ),
    );

    gateway.failingPastMeetingCursors = {};
    await tester.ensureVisible(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();

    expect(
      find.text('Niet alle eerdere vergaderingen konden worden geladen'),
      findsNothing,
    );
    for (var index = 0; index < 25; index++) {
      expect(
        find.text('Commissie Ruimte vergadering $index', skipOffstage: false),
        findsOneWidget,
        reason: 'vergadering $index',
      );
    }
    // De mislukte aanvraag is met exact dezelfde cursor herhaald.
    expect(gateway.calls, [
      'pastMeetings:first',
      'pastMeetings:cursor-1',
      'pastMeetings:cursor-1',
    ]);
  });

  testWidgets('/archief keeps Agenda selected and adds no menu item', (
    tester,
  ) async {
    final gateway = pagedGateway();
    final navigated = <String>[];
    await pumpShell(tester, gateway, path: '/archief', navigated: navigated);

    expect(find.text('Eerdere vergaderingen'), findsOneWidget);
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

    await tester.tap(find.text('Terug naar de agenda'));
    await tester.pumpAndSettle();

    expect(navigated, ['/agenda']);
    expect(find.text('Nu controleren'), findsOneWidget);
  });

  testWidgets('from the archive a past meeting returns to the archive', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway(past: true, withMeeting: true)
      ..pastMeetingPages = {
        null: syntheticPastMeetingPage(first: 0, count: 1, total: 1),
      };
    final navigated = <String>[];
    await pumpShell(tester, gateway, path: '/archief', navigated: navigated);

    await tester.tap(find.text('Openen'));
    await tester.pumpAndSettle();
    expect(
      find.textContaining('Deze vergadering is al geweest'),
      findsOneWidget,
    );

    await tester.tap(find.text('Terug naar eerdere vergaderingen'));
    await tester.pumpAndSettle();

    expect(navigated, ['/archief/${syntheticMeetingId(0)}', '/archief']);
    expect(find.text('Openen'), findsOneWidget);
    expect(find.textContaining('Deze vergadering is al geweest'), findsNothing);
  });

  testWidgets('a deep link to a past meeting keeps its existing back action', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway(past: true, withMeeting: true);
    final navigated = <String>[];
    await pumpShell(
      tester,
      gateway,
      path: '/archief/${syntheticMeetingId(3)}',
      navigated: navigated,
    );

    expect(find.text('Terug naar agenda'), findsOneWidget);
    expect(find.text('Terug naar eerdere vergaderingen'), findsNothing);

    await tester.tap(find.text('Terug naar agenda'));
    await tester.pumpAndSettle();

    expect(navigated, ['/agenda']);
  });

  for (final size in const [Size(400, 2400), Size(1200, 2400)]) {
    testWidgets('the overview stays usable at ${size.width} pixels', (
      tester,
    ) async {
      final gateway = pagedGateway();
      await pumpArchiveOverview(tester, gateway, size: size);

      expect(find.text('Commissie Ruimte vergadering 0'), findsOneWidget);
      expect(find.text('Dreef 3, Haarlem'), findsWidgets);
      expect(
        find.text('6 inhoudelijke agendapunten · 5 met afgerond advies'),
        findsWidgets,
      );
      expect(find.text('07'), findsWidgets);
      expect(find.text('sep'), findsWidgets);
      expect(find.text('2026'), findsWidgets);
      expect(find.text('Openen'), findsWidgets);
      expect(tester.takeException(), isNull);
    });

    testWidgets('the empty state stays usable at ${size.width} pixels', (
      tester,
    ) async {
      await pumpArchiveOverview(tester, FakeDashboardGateway(), size: size);

      expect(find.text('Er is nog geen vergadering voorbij'), findsOneWidget);
      expect(find.text('Naar de huidige vergadering'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('the error state stays usable at ${size.width} pixels', (
      tester,
    ) async {
      final gateway = pagedGateway()..failingPastMeetingCursors = {null};
      await pumpArchiveOverview(tester, gateway, size: size);

      expect(
        find.text('Niet alle eerdere vergaderingen konden worden geladen'),
        findsOneWidget,
      );
      expect(find.text('Opnieuw proberen'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}

/// Pagina 1 met twintig vergaderingen en een cursor, pagina 2 met zes waarvan de eerste overlapt
/// met pagina 1 en zonder volgende cursor.
FakeDashboardGateway pagedGateway() => FakeDashboardGateway()
  ..pastMeetingPages = {
    null: syntheticPastMeetingPage(
      first: 0,
      count: 20,
      total: 25,
      nextCursor: 'cursor-1',
    ),
    'cursor-1': syntheticPastMeetingPage(first: 19, count: 6, total: 25),
  };

Future<void> pumpArchiveOverview(
  WidgetTester tester,
  FakeDashboardGateway gateway, {
  Size size = const Size(1200, 2400),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: ArchivePage(
          gateway: gateway,
          onBack: () {},
          onOpen: (_) {},
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> pumpShell(
  WidgetTester tester,
  FakeDashboardGateway gateway, {
  required String path,
  required List<String> navigated,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
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
        navigate: navigated.add,
      ),
    ),
  );
  await tester.pumpAndSettle();
}
