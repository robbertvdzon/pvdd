// Gedeelde testvoorzieningen voor widgettests. Deze fakes bevatten uitsluitend synthetische
// gegevens en doen geen enkel netwerkverkeer.
import 'package:pvdd_frontend/authentication.dart';
import 'package:pvdd_frontend/build_identity.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/frontend_version_monitor.dart';

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

/// Instelbare gateway voor widgettests: geeft antwoorden, kan een fout simuleren op `meeting` en
/// `agendaItems`, en legt elke gedane aanroep vast in [calls].
class FakeDashboardGateway implements DashboardGateway {
  FakeDashboardGateway({
    this.withMeeting = false,
    this.preview = false,
    this.failedAnalysis = false,
    this.past = false,
    this.failMeeting = false,
    this.failAgendaItems = false,
    this.withSources = false,
    this.title = 'Commissie Ruimte 14 september 2026',
    DateTime? startsAt,
  }) : startsAt = startsAt ?? DateTime.utc(2026, 9, 14, 16, 30);
  final bool withMeeting;
  final bool preview;
  final bool failedAnalysis;

  /// Zoals de server het veld levert: de vergadering heeft al plaatsgevonden.
  final bool past;

  /// Laat `meeting` falen zolang dit aanstaat.
  bool failMeeting;

  /// Laat `agendaItems` falen zolang dit aanstaat.
  bool failAgendaItems;

  /// Voegt aan het itemdetail een bronnenlijst toe met één verwerkt en één onleesbaar stuk.
  final bool withSources;

  /// Het begintijdstip van de vergadering.
  final DateTime startsAt;

  /// De titel van de vergadering.
  final String title;

  /// Alle aanroepen in volgorde, bijvoorbeeld `meeting:meeting-id` of `agendaItem:item-a`.
  final List<String> calls = <String>[];

  String? retriedItemId;

  /// De aanroepen die nieuw werk zouden starten.
  Iterable<String> get startingCalls => calls.where(
    (call) => call.startsWith('checkNow') || call.startsWith('retryAnalysis'),
  );

  MeetingInfo meetingInfo(String id) => MeetingInfo(
    id: id,
    title: title,
    startsAt: startsAt,
    endsAt: startsAt.add(const Duration(hours: 4)),
    location: 'Statenzaal',
    sourceUrl: Uri.parse('https://example.test/agenda'),
    status: preview ? 'COMPLETE' : 'ANALYSING',
    publicationStatus: preview ? 'PREVIEW' : 'CURRENT',
    revisionNumber: 2,
    canonicalFingerprint: List.filled(64, 'a').join(),
    revisionStatus: 'REPROCESSING',
    past: past,
  );

  @override
  Future<MeetingOverview> overview() async {
    calls.add('overview');
    return MeetingOverview(
      status: withMeeting ? 'ANALYSING' : 'NO_MEETING',
      meeting: withMeeting ? meetingInfo('meeting-id') : null,
      lastCheckedAt: DateTime.utc(2026, 8, 31, 5),
      progress: Progress(withMeeting ? 3 : 0, withMeeting ? 1 : 0, 0),
    );
  }

  @override
  Future<MeetingOverview> meeting(String meetingId) async {
    calls.add('meeting:$meetingId');
    if (failMeeting) throw const DashboardUnavailable();
    return MeetingOverview(
      status: 'COMPLETE',
      meeting: meetingInfo(meetingId),
      lastCheckedAt: DateTime.utc(2026, 8, 31, 5),
      progress: const Progress(3, 1, 0),
    );
  }

  @override
  Future<List<AgendaItemSummary>> agendaItems(String meetingId) async {
    calls.add('agendaItems:$meetingId');
    if (failAgendaItems) throw const DashboardUnavailable();
    return items;
  }

  List<AgendaItemSummary> get items => [
    AgendaItemSummary(
      id: 'item-a',
      sequence: 1,
      displayNumber: '1.a',
      category: 'A',
      title: 'Natuurinclusief wonen',
      substantive: true,
      importStatus: 'COMPLETE',
      analysisStatus: failedAnalysis
          ? (retriedItemId == null ? 'FAILED' : 'PENDING')
          : 'SUCCEEDED',
      sourceState: preview ? 'PREVIEW' : 'CURRENT',
      currentFingerprint: null,
      adviceActuality: 'CURRENT',
      changeTypes: [],
      lastDetectedChangeAt: DateTime(2026, 9, 1, 19, 48),
      displayTitle: 'Natuur en wonen combineren',
      shortConclusion:
          'Het voorstel is kansrijk als harde natuurnormen worden toegevoegd.',
      lastAnalysisRun: AnalysisRunInfo(
        id: 'analysis-a',
        status: 'SUCCEEDED',
        createdAt: DateTime(2026, 9, 1, 19, 58),
        updatedAt: DateTime(2026, 9, 1, 20, 3),
        completedAt: DateTime(2026, 9, 1, 20, 3),
      ),
      canRetryAnalysis: failedAnalysis && retriedItemId == null,
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
    const AgendaItemSummary(
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
    const AgendaItemSummary(
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
    calls.add('agendaItem:$itemId');
    final item = items.firstWhere((value) => value.id == itemId);
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
      sources: withSources
          ? [
              SourceLink(
                'voordracht.pdf',
                Uri.parse('https://example.test/voordracht.pdf'),
                'EXTRACTED',
              ),
              SourceLink(
                'bijlage-2-toelichting-woningbouw.pdf',
                Uri.parse('https://example.test/bijlage-2.pdf'),
                'OCR_REQUIRED',
              ),
            ]
          : const [],
      warning: 'AI-concept — controleer bronnen en formulering vóór gebruik',
    );
  }

  @override
  Future<MeetingCheckOutcome> checkNow() async {
    calls.add('checkNow');
    return const MeetingCheckOutcome(
      status: 'UNCHANGED',
      revisionNumber: 2,
      differences: [],
    );
  }

  @override
  Future<void> retryAnalysis(String itemId) async {
    calls.add('retryAnalysis:$itemId');
    retriedItemId = itemId;
  }
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
  Future<List<AgendaItemSummary>> agendaItems(String meetingId) async {
    calls.add('agendaItems:$meetingId');
    return [item];
  }

  @override
  Future<AgendaItemDetail> agendaItem(String itemId) async {
    calls.add('agendaItem:$itemId');
    return AgendaItemDetail(
      item: item,
      explanation: null,
      treatmentProposal: null,
      sourceUrl: Uri.parse('https://example.test/item'),
      advice: ready
          ? const {'content': '# Automatisch vernieuwd advies'}
          : null,
      adviceActuality: ready ? 'CURRENT' : null,
      sources: const [],
      warning: 'AI-concept — controleer bronnen en formulering vóór gebruik',
    );
  }
}

class DocumentStatusDashboardGateway extends FakeDashboardGateway {
  DocumentStatusDashboardGateway() : super(withMeeting: true);

  final noDocuments = const AgendaItemSummary(
    id: 'no-documents',
    sequence: 1,
    displayNumber: '1',
    category: 'A',
    title: 'Punt zonder stukken',
    substantive: true,
    importStatus: 'COMPLETE',
    analysisStatus: null,
    documentStatus: 'NO_DOCUMENTS',
    documentCount: 0,
    readableDocumentCount: 0,
    sourceState: 'CURRENT',
    currentFingerprint: null,
    adviceActuality: null,
    changeTypes: [],
  );

  final unreadable = const AgendaItemSummary(
    id: 'unreadable',
    sequence: 2,
    displayNumber: '2',
    category: 'B',
    title: 'Gescand rapport',
    substantive: true,
    importStatus: 'PARTIAL',
    analysisStatus: null,
    documentStatus: 'DOCUMENTS_UNREADABLE',
    documentCount: 1,
    readableDocumentCount: 0,
    sourceState: 'CURRENT',
    currentFingerprint: null,
    adviceActuality: null,
    changeTypes: [],
  );

  @override
  Future<MeetingOverview> overview() async {
    final base = await super.overview();
    return MeetingOverview(
      status: base.status,
      meeting: base.meeting,
      lastCheckedAt: base.lastCheckedAt,
      progress: const Progress(0, 0, 0),
    );
  }

  @override
  Future<List<AgendaItemSummary>> agendaItems(String meetingId) async {
    calls.add('agendaItems:$meetingId');
    return [noDocuments, unreadable];
  }

  @override
  Future<AgendaItemDetail> agendaItem(String itemId) async {
    calls.add('agendaItem:$itemId');
    final selected = itemId == unreadable.id ? unreadable : noDocuments;
    return AgendaItemDetail(
      item: selected,
      explanation: null,
      treatmentProposal: null,
      sourceUrl: Uri.parse('https://example.test/item'),
      advice: null,
      adviceActuality: null,
      sources: itemId == unreadable.id
          ? [
              SourceLink(
                'rapport-scan.pdf',
                Uri.parse('https://example.test/rapport-scan.pdf'),
                'OCR_REQUIRED',
              ),
            ]
          : const [],
      warning: 'AI-concept — controleer bronnen en formulering vóór gebruik',
    );
  }
}
