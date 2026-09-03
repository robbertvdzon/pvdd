import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/ai_runs_api.dart';
import 'package:pvdd_frontend/ai_runs_page.dart';

void main() {
  testWidgets('shows start and completion timestamps for AI runs', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AiRunsPage(gateway: FakeAiRunsGateway())),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));

    expect(find.textContaining('Gestart: 02-09-2026 08:15'), findsOneWidget);
    expect(find.textContaining('Afgerond: 02-09-2026 08:12'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class FakeAiRunsGateway implements AiRunsGateway {
  @override
  Future<AiRunPage> active() async => AiRunPage([
    _run('active', 'RUNNING', DateTime(2026, 9, 2, 8, 15), null),
  ], null);

  @override
  Future<AiRunPage> finished({String? cursor}) async => AiRunPage([
    _run(
      'finished',
      'SUCCEEDED',
      DateTime(2026, 9, 2, 8),
      DateTime(2026, 9, 2, 8, 12),
    ),
  ], null);

  AiRun _run(
    String id,
    String status,
    DateTime startedAt,
    DateTime? completedAt,
  ) => AiRun(
    id: id,
    type: 'AGENDA_ADVICE',
    title: 'Analyse $id',
    explanation: 'Analyse van een agendapunt',
    status: status,
    createdAt: startedAt.subtract(const Duration(minutes: 1)),
    startedAt: startedAt,
    updatedAt: completedAt ?? startedAt,
    completedAt: completedAt,
    phaseCount: 2,
    completedPhases: completedAt == null ? 1 : 2,
    errorCode: null,
  );
}
