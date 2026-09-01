import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/authentication.dart';
import 'package:pvdd_frontend/build_identity.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/frontend_version_monitor.dart';
import 'package:pvdd_frontend/main.dart';
import 'package:pvdd_frontend/token_store.dart';

void main() {
  testWidgets('restores session and shows secured technical shell', (
    tester,
  ) async {
    final store = MemoryTokenStore()..write('valid-token');
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        tokenStore: store,
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
        authenticationGateway: FakeAuthenticationGateway(),
        tokenStore: MemoryTokenStore(),
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
        tokenStore: MemoryTokenStore(),
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
        tokenStore: MemoryTokenStore()..write('valid-token'),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: FakeDashboardGateway(),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byTooltip('Menu openen'), findsOneWidget);
  });

  testWidgets('shows A B C progress and exactly five A B advice sections', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        tokenStore: MemoryTokenStore()..write('valid-token'),
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

    await tester.ensureVisible(find.text('1.a Natuurinclusief wonen'));
    await tester.tap(find.text('1.a Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    for (final heading in [
      'Waar gaat het over?',
      'Wat vinden we ervan?',
      'Wat kunnen/willen we ermee in de commissie?',
      'Welke punten willen we maken en wat willen we van de gedeputeerde?',
      'Welke technische vragen gaan we stellen?',
    ]) {
      expect(find.text(heading), findsOneWidget);
    }
    expect(find.textContaining('AI-concept'), findsOneWidget);
  });
}

class MemoryTokenStore implements TokenStore {
  String? value;
  @override
  void clear() => value = null;
  @override
  String? read() => value;
  @override
  void write(String value) => this.value = value;
}

class FakeAuthenticationGateway implements AuthenticationGateway {
  @override
  Future<AuthenticatedUser> me(String idToken) async =>
      const AuthenticatedUser('robbertvdzon@gmail.com');
}

class FakeVersionGateway implements VersionGateway {
  @override
  Future<BuildIdentity> backend() async => const BuildIdentity(
    version: '0.1.0',
    gitRevision: 'abcdef123456abcdef123456abcdef123456abcd',
    buildTime: '2026-08-31T10:00:00Z',
    environment: 'local',
    identity: '0.1.0+abcdef123456',
  );
}

class FakeFrontendVersionSource implements FrontendVersionSource {
  @override
  Future<BuildIdentity> latest() async => BuildIdentity.frontend();
}

class FakeDashboardGateway implements DashboardGateway {
  FakeDashboardGateway({this.withMeeting = false});
  final bool withMeeting;

  @override
  Future<MeetingOverview> overview() async => MeetingOverview(
    status: withMeeting ? 'ANALYSING' : 'NO_MEETING',
    meeting: withMeeting
        ? MeetingInfo(
            id: 'meeting-id',
            title: 'Commissie Ruimte 14 september 2026',
            startsAt: DateTime.utc(2026, 9, 14, 16, 30),
            endsAt: DateTime.utc(2026, 9, 14, 20, 30),
            location: 'Statenzaal',
            sourceUrl: Uri.parse('https://example.test/agenda'),
            status: 'ANALYSING',
            publicationStatus: 'CURRENT',
            revisionNumber: 2,
            canonicalFingerprint: List.filled(64, 'a').join(),
            revisionStatus: 'REPROCESSING',
          )
        : null,
    lastCheckedAt: DateTime.utc(2026, 8, 31, 5),
    progress: Progress(withMeeting ? 3 : 0, withMeeting ? 1 : 0, 0),
  );

  @override
  Future<List<AgendaItemSummary>> agendaItems(String meetingId) async => [
    const AgendaItemSummary(
      id: 'item-a',
      sequence: 1,
      displayNumber: '1.a',
      category: 'A',
      title: 'Natuurinclusief wonen',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: 'SUCCEEDED',
      sourceState: 'CURRENT',
      currentFingerprint: null,
      adviceActuality: 'CURRENT',
      changeTypes: [],
    ),
    const AgendaItemSummary(
      id: 'item-b',
      sequence: 2,
      displayNumber: '2.a',
      category: 'B',
      title: 'Fietsverbinding',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: 'RUNNING',
      sourceState: 'CURRENT',
      currentFingerprint: null,
      adviceActuality: 'STALE',
      changeTypes: ['DOCUMENT_CONTENT_CHANGED'],
    ),
    const AgendaItemSummary(
      id: 'item-c',
      sequence: 3,
      displayNumber: null,
      category: 'C',
      title: 'Natuurbrief',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: 'QUEUED',
      sourceState: 'CURRENT',
      currentFingerprint: null,
      adviceActuality: null,
      changeTypes: [],
    ),
  ];

  @override
  Future<AgendaItemDetail> agendaItem(String itemId) async {
    final item = (await agendaItems(
      'meeting-id',
    )).firstWhere((value) => value.id == itemId);
    Map<String, dynamic> section(String text) => {
      'text': text,
      'citations': <dynamic>[],
    };
    return AgendaItemDetail(
      item: item,
      explanation: 'Synthetische toelichting',
      treatmentProposal: 'Bespreken',
      sourceUrl: Uri.parse('https://example.test/item'),
      advice: item.category == 'C'
          ? {
              'besprekenEnNaarB': true,
              'urgentie': 'HOOG',
              'motivering': section('Politieke meerwaarde'),
              'commissieDoel': section('Natuur beschermen'),
              'kernvraag': section('Wat doet de gedeputeerde?'),
            }
          : {
              'waarGaatHetOver': section('Samenvatting'),
              'watVindenWeErvan': section('Beoordeling'),
              'commissieInzet': section('Inzet'),
              'puntenVoorGedeputeerde': section('Punten'),
              'technischeVragen': section('Vragen'),
            },
      adviceActuality: item.adviceActuality,
      sources: const [],
      warning: 'AI-concept — controleer bronnen en formulering vóór gebruik',
    );
  }

  @override
  Future<MeetingCheckOutcome> checkNow() async => const MeetingCheckOutcome(
    status: 'UNCHANGED',
    revisionNumber: 2,
    differences: [],
  );
}
