import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/settings_api.dart';
import 'package:pvdd_frontend/settings_page.dart';

void main() {
  testWidgets('shows schedules, policy sources and saves analysis guidance', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 3000);
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

  testWidgets('chooses a model per task and returns to the default', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final gateway = FakeSettingsGateway();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: SettingsPage(gateway: gateway)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('AI-model per taak'), findsOneWidget);
    expect(find.text('Bronnotities'), findsOneWidget);
    expect(find.text('Eindadvies'), findsOneWidget);
    expect(find.text('Standaard uit de configuratie'), findsNWidgets(2));

    final notes = find.byKey(const ValueKey('model-SOURCE_NOTES'));
    await tester.ensureVisible(notes);
    await tester.tap(notes);
    await tester.pumpAndSettle();
    await tester.tap(find.text('claude-haiku-4-5-20251001 · anthropic').last);
    await tester.pumpAndSettle();

    expect(gateway.selected, ('SOURCE_NOTES', 'claude-haiku-4-5-20251001'));
    expect(
      find.textContaining('Gekozen door tester@example.test'),
      findsOneWidget,
    );
    expect(
      find.textContaining('Nieuwe AI-runs gebruiken dit model'),
      findsOneWidget,
    );
    await tester.pump(const Duration(seconds: 5));

    await tester.ensureVisible(find.text('Terug naar standaard'));
    await tester.tap(find.text('Terug naar standaard'));
    await tester.pumpAndSettle();

    expect(gateway.resetTask, 'SOURCE_NOTES');
    expect(find.text('Standaard uit de configuratie'), findsNWidgets(2));
  });
}

class FakeSettingsGateway implements SettingsGateway {
  String? saved;
  bool retryCalled = false;
  (String, String)? selected;
  String? resetTask;
  String? _notesModel;

  static const _opus = ModelExecution(
    vendorId: 'anthropic',
    model: 'claude-opus-5',
    mode: 'SUBSCRIPTION',
  );
  static const _haiku = ModelExecution(
    vendorId: 'anthropic',
    model: 'claude-haiku-4-5-20251001',
    mode: 'SUBSCRIPTION',
  );

  @override
  Future<ApplicationSettings> selectAnalysisModel(
    String task,
    ModelExecution execution,
  ) async {
    selected = (task, execution.model);
    _notesModel = execution.model;
    return _settings('Initiële instructie');
  }

  @override
  Future<ApplicationSettings> resetAnalysisModel(String task) async {
    resetTask = task;
    _notesModel = null;
    return _settings('Initiële instructie');
  }

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
      promptVersion: 'pvdd-advice-v12-direct-documents',
      systemPrompt: 'Vaste veilige prompt',
      additionalInstructions: guidance,
      additionalInstructionsUpdatedAt: DateTime(2026, 9, 2, 7, 30),
      additionalInstructionsUpdatedBy: 'tester@example.test',
      maximumAdditionalInstructionCharacters: 4000,
    ),
    analysisModels: AnalysisModelSettings(
      choices: [
        AnalysisModelChoice(
          task: 'SOURCE_NOTES',
          execution: _notesModel == null ? _opus : _haiku,
          fromSetting: _notesModel != null,
          updatedAt: _notesModel == null ? null : DateTime(2026, 10, 10, 9, 0),
          updatedBy: _notesModel == null ? null : 'tester@example.test',
        ),
        const AnalysisModelChoice(
          task: 'FINAL_ADVICE',
          execution: _opus,
          fromSetting: false,
          updatedAt: null,
          updatedBy: null,
        ),
      ],
      options: const [
        AnalysisModelOption(
          execution: _haiku,
          available: true,
          onlineWorkers: 1,
        ),
        AnalysisModelOption(
          execution: _opus,
          available: true,
          onlineWorkers: 1,
        ),
      ],
      catalogUnavailable: false,
    ),
  );
}
