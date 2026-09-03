import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/settings_api.dart';
import 'package:pvdd_frontend/settings_page.dart';

void main() {
  testWidgets('shows schedules, policy sources and saves analysis guidance', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final gateway = FakeSettingsGateway();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: SettingsPage(gateway: gateway)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Geplande jobs'), findsOneWidget);
    expect(find.text('0 0 5 * * *'), findsOneWidget);
    expect(find.text('Bekeken delen'), findsOneWidget);
    expect(find.textContaining('/moties'), findsOneWidget);
    expect(find.text('Aanvullende analyse-instructie'), findsOneWidget);

    await tester.enterText(
      find.byType(TextField),
      'C blijft C tenzij politieke behandeling nodig is.',
    );
    await tester.ensureVisible(find.text('Opslaan'));
    await tester.tap(find.text('Opslaan'));
    await tester.pumpAndSettle();

    expect(gateway.saved, 'C blijft C tenzij politieke behandeling nodig is.');
    expect(find.textContaining('Toekomstige vergaderingen'), findsOneWidget);
    await tester.pump(const Duration(seconds: 5));

    await tester.ensureVisible(
      find.text('Alle mislukte analyses opnieuw proberen'),
    );
    await tester.tap(find.text('Alle mislukte analyses opnieuw proberen'));
    await tester.pumpAndSettle();
    expect(find.text('Mislukte analyses opnieuw starten?'), findsOneWidget);
    await tester.tap(find.text('Opnieuw starten'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(gateway.retryCalled, isTrue);
    expect(find.textContaining('2 mislukte analyses zijn'), findsOneWidget);
  });
}

class FakeSettingsGateway implements SettingsGateway {
  String? saved;
  bool retryCalled = false;

  @override
  Future<ApplicationSettings> load() async => _settings('Initiële instructie');

  @override
  Future<ApplicationSettings> updateAnalysisInstructions(String value) async {
    saved = value;
    return _settings(value);
  }

  @override
  Future<int> retryAllFailedAnalyses() async {
    retryCalled = true;
    return 2;
  }

  ApplicationSettings _settings(String guidance) => ApplicationSettings(
    scheduledJobs: const [
      ScheduledJobSetting(
        key: 'meeting-check',
        name: 'Vergaderingen en agenda controleren',
        kind: 'CRON',
        schedule: '0 0 5 * * *',
        timeZone: 'Europe/Amsterdam',
        explanation: 'Elke dag om 05:00.',
      ),
    ],
    policySources: const PolicySourceSettings(
      programmeUrl: 'https://assets.partijvoordedieren.nl/programma.pdf',
      startUrls: ['https://noordholland.partijvoordedieren.nl/onze-idealen'],
      websiteHost: 'noordholland.partijvoordedieren.nl',
      discoveryPaths: ['/onze-idealen', '/moties'],
      allowedHosts: ['noordholland.partijvoordedieren.nl'],
      maximumPages: 250,
    ),
    analysisPrompt: AnalysisPromptSettings(
      promptVersion: 'pvdd-advice-v11',
      systemPrompt: 'Vaste veilige prompt',
      additionalInstructions: guidance,
      additionalInstructionsUpdatedAt: DateTime(2026, 9, 2, 7, 30),
      additionalInstructionsUpdatedBy: 'tester@example.test',
      maximumAdditionalInstructionCharacters: 4000,
    ),
  );
}
