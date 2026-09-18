import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/main.dart';

import 'support/fakes.dart';

void main() {
  testWidgets('restores session and shows secured technical shell', (
    tester,
  ) async {
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Commissie Ruimte'), findsWidgets);
    expect(
      find.text('Er is nog geen toekomstige vergadering gevonden.'),
      findsOneWidget,
    );
    expect(find.text('robbertvdzon@gmail.com'), findsOneWidget);
    expect(find.textContaining('verzonnen vergaderdata'), findsNothing);

    await tester.tap(find.text('Over deze versie'));
    await tester.pumpAndSettle();
    expect(find.text('Backend'), findsOneWidget);
    expect(find.textContaining('0.1.0+abcdef123456'), findsOneWidget);
  });

  testWidgets('shows Google login state without a stored session', (
    tester,
  ) async {
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(authenticated: false),
        loginBuilder: (onToken) => FilledButton(
          onPressed: () => onToken('new-token'),
          child: const Text('Test Google-login'),
        ),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('Log in met een toegestaan Google-account.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Test Google-login'));
    await tester.pumpAndSettle();
    expect(find.text('Commissie Ruimte'), findsWidgets);
  });

  testWidgets('acceptance bypass opens directly and is permanently labelled', (
    tester,
  ) async {
    await tester.pumpWidget(
      PvddApp(
        acceptanceBypass: true,
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('ACCEPTANCE — gemockte gegevens — geen authenticatie'),
      findsOneWidget,
    );
    expect(find.text('acceptance-tester@pvdd.invalid'), findsOneWidget);
    expect(find.byTooltip('Uitloggen'), findsNothing);
    expect(find.textContaining('Log in met'), findsNothing);
  });

  testWidgets('shell remains usable at 320 pixels', (tester) async {
    tester.view.physicalSize = const Size(320, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byTooltip('Menu openen'), findsOneWidget);
  });

  testWidgets('agenda facts table remains usable at 320 pixels', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(withMeeting: true),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('AI-titel'), findsWidgets);
    expect(find.text('Laatste AI-analyse'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shows A B C progress and one free Markdown analysis', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(withMeeting: true),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('1/3 analyses gereed'), findsOneWidget);
    expect(find.text('A-agenda'), findsOneWidget);
    expect(find.text('B-agenda'), findsOneWidget);
    expect(find.text('C-agenda'), findsOneWidget);
    expect(find.text('Technische sectiekop'), findsNothing);
    expect(find.text('Ingetrokken stuk'), findsNothing);
    expect(find.text('AI-titel'), findsNWidgets(3));
    expect(find.text('Korte conclusie'), findsNWidgets(3));
    expect(find.text('Laatste wijziging'), findsNWidgets(3));
    expect(find.text('Laatste AI-analyse'), findsNWidgets(3));
    expect(find.text('Natuur en wonen combineren'), findsOneWidget);
    expect(find.text('01-09-2026 20:03 · Gereed'), findsOneWidget);

    await tester.ensureVisible(find.text('Natuurinclusief wonen'));
    await tester.tap(find.text('Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    expect(find.text('Vrije Markdown-analyse'), findsOneWidget);
    expect(
      find.text('Een bruikbaar politiek advies zonder vast format.'),
      findsOneWidget,
    );
    expect(
      find.ancestor(
        of: find.text('Een bruikbaar politiek advies zonder vast format.'),
        matching: find.byType(SelectionArea),
      ),
      findsOneWidget,
    );
    expect(find.textContaining('AI-concept'), findsOneWidget);

    String? copiedAdvice;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copiedAdvice =
              (call.arguments as Map<Object?, Object?>)['text'] as String?;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await tester.ensureVisible(find.text('Volledig advies kopiëren'));
    await tester.tap(find.text('Volledig advies kopiëren'));
    await tester.pump();
    expect(
      copiedAdvice,
      '# Vrije Markdown-analyse\n\nEen bruikbaar politiek advies zonder vast format.',
    );
    expect(find.text('Het volledige advies is gekopieerd.'), findsOneWidget);
  });

  testWidgets('shows processed preview advice as provisional and ready', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PvddApp(
        acceptanceBypass: true,
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(
          withMeeting: true,
          preview: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Voorlopige agenda'), findsWidgets);
    expect(find.text('Gereed'), findsWidgets);
    await tester.tap(find.text('Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    expect(
      find.textContaining('dit beschikbare stuk is geanalyseerd'),
      findsOneWidget,
    );
    expect(find.text('Vrije Markdown-analyse'), findsOneWidget);
  });

  testWidgets('refreshes an open detail when its analysis completes', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final gateway = RefreshingDashboardGateway();
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: gateway,
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Lopende analyse'));
    await tester.pumpAndSettle();
    expect(find.text('De analyse is nog niet beschikbaar.'), findsOneWidget);

    gateway.ready = true;
    await tester.pump(const Duration(seconds: 15));
    await tester.pumpAndSettle();
    expect(find.text('Automatisch vernieuwd advies'), findsOneWidget);
    expect(find.text('De analyse is nog niet beschikbaar.'), findsNothing);
  });

  testWidgets('retries only from the failed agenda item', (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final gateway = FakeDashboardGateway(
      withMeeting: true,
      failedAnalysis: true,
    );
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: gateway,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Opnieuw proberen'), findsOneWidget);
    await tester.ensureVisible(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Opnieuw proberen'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));

    expect(gateway.retriedItemId, 'item-a');
    expect(find.text('De AI-analyse is opnieuw gestart.'), findsOneWidget);
  });

  testWidgets(
    'skips items without direct documents and names unreadable files',
    (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        PvddApp(
          authenticationGateway: FakeAuthenticationGateway(),
          versionGateway: FakeVersionGateway(),
          frontendVersionSource: FakeFrontendVersionSource(),
          dashboardGateway: DocumentStatusDashboardGateway(),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Geen stukken — analyse overgeslagen'), findsOneWidget);
      expect(find.text('Stukken niet leesbaar'), findsOneWidget);
      expect(
        find.text('Overgeslagen — geen direct gekoppelde stukken'),
        findsOneWidget,
      );

      await tester.ensureVisible(find.text('Gescand rapport'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Gescand rapport'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('rapport-scan.pdf (Scan — OCR nodig)'),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'geen van de direct gekoppelde stukken is leesbaar',
        ),
        findsOneWidget,
      );
    },
  );
}
