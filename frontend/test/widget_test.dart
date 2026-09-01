import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/authentication.dart';
import 'package:pvdd_frontend/build_identity.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/frontend_version_monitor.dart';
import 'package:pvdd_frontend/main.dart';

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

    await tester.ensureVisible(find.text('1.a Natuurinclusief wonen'));
    await tester.tap(find.text('1.a Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    expect(find.text('Vrije Markdown-analyse'), findsOneWidget);
    expect(
      find.text('Een bruikbaar politiek advies zonder vast format.'),
      findsOneWidget,
    );
    expect(find.textContaining('AI-concept'), findsOneWidget);
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
    await tester.tap(find.text('1.a Natuurinclusief wonen'));
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
}

class FakeAuthenticationGateway implements AuthenticationGateway {
  FakeAuthenticationGateway({this.authenticated = true});
  bool authenticated;

  @override
  Future<AuthenticatedUser> restore() async {
    if (!authenticated) throw const AuthenticationRejected();
    return const AuthenticatedUser('robbertvdzon@gmail.com');
  }

  @override
  Future<AuthenticatedUser> signIn(String idToken) async {
    authenticated = true;
    return const AuthenticatedUser('robbertvdzon@gmail.com');
  }

  @override
  Future<void> signOut() async => authenticated = false;
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
  FakeDashboardGateway({this.withMeeting = false, this.preview = false});
  final bool withMeeting;
  final bool preview;

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
            status: preview ? 'COMPLETE' : 'ANALYSING',
            publicationStatus: preview ? 'PREVIEW' : 'CURRENT',
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
    AgendaItemSummary(
      id: 'item-a',
      sequence: 1,
      displayNumber: '1.a',
      category: 'A',
      title: 'Natuurinclusief wonen',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: 'SUCCEEDED',
      sourceState: preview ? 'PREVIEW' : 'CURRENT',
      currentFingerprint: null,
      adviceActuality: 'CURRENT',
      changeTypes: [],
    ),
    AgendaItemSummary(
      id: 'item-b',
      sequence: 2,
      displayNumber: '2.a',
      category: 'B',
      title: 'Fietsverbinding',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: 'RUNNING',
      sourceState: preview ? 'PREVIEW' : 'CURRENT',
      currentFingerprint: null,
      adviceActuality: 'STALE',
      changeTypes: ['DOCUMENT_CONTENT_CHANGED'],
    ),
    AgendaItemSummary(
      id: 'item-c',
      sequence: 3,
      displayNumber: null,
      category: 'C',
      title: 'Natuurbrief',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: 'QUEUED',
      sourceState: preview ? 'PREVIEW' : 'CURRENT',
      currentFingerprint: null,
      adviceActuality: null,
      changeTypes: [],
    ),
    AgendaItemSummary(
      id: 'section-c',
      sequence: 4,
      displayNumber: null,
      category: 'C',
      title: 'Technische sectiekop',
      substantive: false,
      importStatus: 'COMPLETE',
      analysisStatus: null,
      sourceState: 'CURRENT',
      currentFingerprint: null,
      adviceActuality: null,
      changeTypes: [],
    ),
    AgendaItemSummary(
      id: 'withdrawn-c',
      sequence: 5,
      displayNumber: null,
      category: 'C',
      title: 'Ingetrokken stuk',
      substantive: true,
      importStatus: 'PENDING',
      analysisStatus: null,
      sourceState: 'WITHDRAWN',
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
    return AgendaItemDetail(
      item: item,
      explanation: 'Synthetische toelichting',
      treatmentProposal: 'Bespreken',
      sourceUrl: Uri.parse('https://example.test/item'),
      advice: const {
        'content':
            '# Vrije Markdown-analyse\n\nEen bruikbaar politiek advies zonder vast format.',
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

class RefreshingDashboardGateway extends FakeDashboardGateway {
  RefreshingDashboardGateway() : super(withMeeting: true);
  bool ready = false;

  AgendaItemSummary get item => AgendaItemSummary(
    id: 'refreshing-item',
    sequence: 1,
    displayNumber: null,
    category: 'C',
    title: 'Lopende analyse',
    substantive: true,
    importStatus: 'COMPLETE',
    analysisStatus: ready ? 'SUCCEEDED' : 'RUNNING',
    sourceState: 'CURRENT',
    currentFingerprint: 'fingerprint',
    adviceActuality: ready ? 'CURRENT' : null,
    changeTypes: const [],
  );

  @override
  Future<List<AgendaItemSummary>> agendaItems(String meetingId) async => [item];

  @override
  Future<AgendaItemDetail> agendaItem(String itemId) async => AgendaItemDetail(
    item: item,
    explanation: null,
    treatmentProposal: null,
    sourceUrl: Uri.parse('https://example.test/item'),
    advice: ready ? const {'content': '# Automatisch vernieuwd advies'} : null,
    adviceActuality: ready ? 'CURRENT' : null,
    sources: const [],
    warning: 'AI-concept — controleer bronnen en formulering vóór gebruik',
  );
}
