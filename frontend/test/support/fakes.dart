// Gedeelde testvoorzieningen voor widgettests. Deze fakes bevatten uitsluitend synthetische
// gegevens en doen geen enkel netwerkverkeer.
import 'package:pvdd_frontend/ai_runs_api.dart';
import 'package:pvdd_frontend/authentication.dart';
import 'package:pvdd_frontend/build_identity.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/frontend_version_monitor.dart';
import 'package:pvdd_frontend/settings_api.dart';

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

  /// De pagina's voorbije vergaderingen die deze fake achtereenvolgens teruggeeft, met als sleutel
  /// de cursor waarmee ze wordt opgevraagd (`null` voor de eerste pagina). Standaard leeg, zodat
  /// bestaande tests een leeg archief zien.
  Map<String?, PastMeetingPage> pastMeetingPages = {
    null: const PastMeetingPage(items: [], nextCursor: null, total: 0),
  };

  /// De cursors waarvoor `pastMeetings` faalt. Verwijder een cursor uit deze verzameling om
  /// dezelfde aanvraag daarna te laten slagen.
  Set<String?> failingPastMeetingCursors = <String?>{};

  @override
  Future<PastMeetingPage> pastMeetings({String? cursor}) async {
    calls.add('pastMeetings:${cursor ?? 'first'}');
    if (failingPastMeetingCursors.contains(cursor)) {
      throw const DashboardUnavailable();
    }
    return pastMeetingPages[cursor] ??
        const PastMeetingPage(items: [], nextCursor: null, total: 0);
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

  /// De adviesversies die deze fake per agendapunt-id teruggeeft. Standaard leeg, zodat bestaande
  /// tests geen versiekeuze en geen melding in beeld krijgen; een test die de keuze wil toetsen zet
  /// hier zelf versies neer, bijvoorbeeld met [syntheticAdviceVersions].
  Map<String, List<AdviceVersion>> adviceVersionsByItem =
      <String, List<AdviceVersion>>{};

  /// De agendapunt-id's waarvoor `adviceVersions` faalt. Haal een id eruit om dezelfde aanvraag
  /// daarna te laten slagen, precies zoals de knop 'Versies opnieuw laden' die herhaalt.
  Set<String> failingAdviceVersionItems = <String>{};

  @override
  Future<List<AdviceVersion>> adviceVersions(String itemId) async {
    calls.add('adviceVersions:$itemId');
    if (failingAdviceVersionItems.contains(itemId)) {
      throw const DashboardUnavailable();
    }
    return adviceVersionsByItem[itemId] ?? const <AdviceVersion>[];
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

/// De twee tijdstippen uit de UX-mockups: een laatste advies van 7 september 2026 dat de versie van
/// 3 september 2026 verving. Lokale tijden, zodat de datumweergave niet van de tijdzone van de
/// testomgeving afhangt.
final DateTime latestAdviceAt = DateTime(2026, 9, 7, 5, 41);
final DateTime earlierAdviceAt = DateTime(2026, 9, 3, 9, 12);

/// Eén synthetische adviesversie.
///
/// De adviesinhoud gebruikt de gestructureerde A/B-secties (en niet vrije Markdown), zodat de tekst
/// van díe versie met `find.text` te vinden is.
AdviceVersion syntheticAdviceVersion({
  required DateTime createdAt,
  required bool latest,
  String refreshReason = 'CONTEXT_CHANGED',
  String actuality = 'CURRENT',
  String? analysisGuidance,
  String summary = 'De afweging van toen.',
  String displayTitle = 'Natuur en wonen combineren',
  String shortConclusion = 'Kansrijk met harde natuurnormen.',
}) {
  final stamp = createdAt.toIso8601String();
  return AdviceVersion(
    adviceId: 'advice-$stamp',
    analysisRunId: 'run-$stamp',
    createdAt: createdAt,
    actuality: actuality,
    latest: latest,
    advice: {
      'displayTitle': displayTitle,
      'shortConclusion': shortConclusion,
      'waarGaatHetOver': summary,
    },
    displayTitle: displayTitle,
    shortConclusion: shortConclusion,
    provider: 'MOCKED',
    model: 'mock-model',
    promptVersion: 'advice-v1',
    analysisGuidance: analysisGuidance,
    refreshReason: refreshReason,
  );
}

/// De twee bewaarde versies uit de UX-mockups, in de ordening van de server (nieuwste eerst).
///
/// [refreshReason] is de reden van de vervangende (nieuwste) versie; die reden hoort in de melding
/// bij de eerdere versie. De eerdere versie houdt haar eigen reden `FIRST_ANALYSIS`, zodat een test
/// die de verkeerde versie uitleest meteen opvalt.
List<AdviceVersion> syntheticAdviceVersions({
  String refreshReason = 'CONTEXT_CHANGED',
  String? latestGuidance,
  String? earlierGuidance,
}) => [
  syntheticAdviceVersion(
    createdAt: latestAdviceAt,
    latest: true,
    refreshReason: refreshReason,
    analysisGuidance: latestGuidance,
    summary: 'De nieuwste afweging.',
  ),
  syntheticAdviceVersion(
    createdAt: earlierAdviceAt,
    latest: false,
    actuality: 'STALE',
    refreshReason: 'FIRST_ANALYSIS',
    analysisGuidance: earlierGuidance,
    summary: 'De afweging van toen.',
  ),
];

/// Een voorspelbare synthetische vergadering-id; index 0 is de meest recente.
String syntheticMeetingId(int index) =>
    '00000000-0000-4000-8000-${index.toString().padLeft(12, '0')}';

/// Eén synthetische voorbije vergadering; oplopende index betekent verder terug in de tijd, zoals
/// de server ze sorteert (meest recente bovenaan).
PastMeeting syntheticPastMeeting(int index) => PastMeeting(
  id: syntheticMeetingId(index),
  title: 'Commissie Ruimte vergadering $index',
  startsAt: DateTime(2026, 9, 7, 19, 30).subtract(Duration(days: 7 * index)),
  location: index.isEven ? 'Dreef 3, Haarlem' : 'Provinciehuis, Haarlem',
  substantiveItemCount: 6,
  completedAdviceCount: 5,
);

/// Een pagina met [count] opeenvolgende synthetische vergaderingen vanaf [first].
PastMeetingPage syntheticPastMeetingPage({
  required int first,
  required int count,
  required int total,
  String? nextCursor,
}) => PastMeetingPage(
  items: [
    for (var index = first; index < first + count; index++)
      syntheticPastMeeting(index),
  ],
  nextCursor: nextCursor,
  total: total,
);

/// Gateways die een test bewust niet gebruikt: elke aanroep is een fout in de test zelf.
class UnusedAiRunsGateway implements AiRunsGateway {
  @override
  Future<AiRunPage> active() => throw UnimplementedError();
  @override
  Future<AiRunPage> finished({String? cursor}) => throw UnimplementedError();
}

class UnusedSettingsGateway implements SettingsGateway {
  @override
  Future<ApplicationSettings> load() => throw UnimplementedError();
  @override
  Future<ApplicationSettings> updateAnalysisInstructions(String value) =>
      throw UnimplementedError();
  @override
  Future<int> retryAllFailedAnalyses() => throw UnimplementedError();
}
