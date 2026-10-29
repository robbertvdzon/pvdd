import 'dart:convert';

import 'package:http/http.dart' as http;

import 'csrf_token.dart';

/// De vaste paginagrootte van het archiefoverzicht.
const int pastMeetingPageSize = 20;

/// De waarden van de `commissie`-queryparameter van de leesroutes.
const String commissieRuimte = 'RUIMTE';
const String commissieBestuur = 'BESTUUR';
const List<String> commissies = [commissieRuimte, commissieBestuur];

/// De weergavenaam van een commissiecode: 'Ruimte' of 'Bestuur'.
String commissieShortName(String commissie) =>
    commissie == commissieBestuur ? 'Bestuur' : 'Ruimte';

/// De volledige naam: 'Commissie Ruimte' of 'Commissie Bestuur'.
String commissieFullName(String commissie) =>
    'Commissie ${commissieShortName(commissie)}';

abstract interface class DashboardGateway {
  /// Alle leesroutes krijgen de gekozen [commissie] mee; zonder keuze is dat Ruimte.
  Future<MeetingOverview> overview({String commissie = commissieRuimte});

  /// Leest één bekende vergadering, met hetzelfde antwoordmodel als [overview].
  Future<MeetingOverview> meeting(
    String meetingId, {
    String commissie = commissieRuimte,
  });

  /// Eén pagina bewaarde vergaderingen die al zijn geweest, de meest recente bovenaan.
  ///
  /// De paginagrootte ligt vast op [pastMeetingPageSize] en is niet instelbaar in de UI; [cursor]
  /// is de ondoorzichtige cursor uit de vorige pagina.
  Future<PastMeetingPage> pastMeetings({
    String? cursor,
    String commissie = commissieRuimte,
  });

  Future<List<AgendaItemSummary>> agendaItems(
    String meetingId, {
    String commissie = commissieRuimte,
  });
  Future<AgendaItemDetail> agendaItem(
    String itemId, {
    String commissie = commissieRuimte,
  });

  /// De bewaarde adviesversies van één agendapunt, nieuwste eerst zoals de server ze ordent.
  ///
  /// Leest alleen; een agendapunt zonder geslaagde adviesrun levert een lege lijst. Bij een eerdere
  /// versie hoort bewust geen bronnenlijst, dus het antwoord bevat die ook niet.
  Future<List<AdviceVersion>> adviceVersions(
    String itemId, {
    String commissie = commissieRuimte,
  });

  Future<MeetingCheckOutcome> checkNow();
  Future<void> retryAnalysis(String itemId);
}

class HttpDashboardGateway implements DashboardGateway {
  HttpDashboardGateway({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;

  @override
  Future<MeetingOverview> overview({
    String commissie = commissieRuimte,
  }) async => MeetingOverview.fromJson(
    await _get('/api/meetings/next?commissie=$commissie')
        as Map<String, dynamic>,
  );

  @override
  Future<MeetingOverview> meeting(
    String meetingId, {
    String commissie = commissieRuimte,
  }) async => MeetingOverview.fromJson(
    await _get('/api/meetings/$meetingId?commissie=$commissie')
        as Map<String, dynamic>,
  );

  @override
  Future<PastMeetingPage> pastMeetings({
    String? cursor,
    String commissie = commissieRuimte,
  }) async => PastMeetingPage.fromJson(
    await _get(
          '/api/meetings?state=past&limit=$pastMeetingPageSize&commissie=$commissie'
          '${cursor == null ? '' : '&cursor=${Uri.encodeQueryComponent(cursor)}'}',
        )
        as Map<String, dynamic>,
  );

  @override
  Future<List<AgendaItemSummary>> agendaItems(
    String meetingId, {
    String commissie = commissieRuimte,
  }) async {
    final value = await _get(
      '/api/meetings/$meetingId/agenda-items?commissie=$commissie',
    );
    return (value as List<dynamic>)
        .map((item) => AgendaItemSummary.fromJson(item as Map<String, dynamic>))
        .toList(growable: false);
  }

  @override
  Future<AgendaItemDetail> agendaItem(
    String itemId, {
    String commissie = commissieRuimte,
  }) async => AgendaItemDetail.fromJson(
    await _get('/api/agenda-items/$itemId?commissie=$commissie'),
  );

  @override
  Future<List<AdviceVersion>> adviceVersions(
    String itemId, {
    String commissie = commissieRuimte,
  }) async {
    final value =
        await _get(
              '/api/agenda-items/$itemId/advice-versions?commissie=$commissie',
            )
            as Map<String, dynamic>;
    return (value['versions'] as List<dynamic>)
        .map(
          (version) => AdviceVersion.fromJson(version as Map<String, dynamic>),
        )
        .toList(growable: false);
  }

  @override
  Future<MeetingCheckOutcome> checkNow() async {
    final response = await _client
        .post(
          Uri.parse('/api/meetings/check-now'),
          headers: _headers(
            idempotencyKey:
                'web-${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}',
          ),
        )
        .timeout(const Duration(seconds: 90));
    if (response.statusCode != 200 && response.statusCode != 409) {
      throw const DashboardUnavailable();
    }
    return MeetingCheckOutcome.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
    );
  }

  @override
  Future<void> retryAnalysis(String itemId) async {
    final response = await _client
        .post(
          Uri.parse('/api/agenda-items/$itemId/retry-analysis'),
          headers: _headers(
            idempotencyKey:
                'agenda-retry-$itemId-${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}',
          ),
        )
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 202) throw const DashboardUnavailable();
  }

  Future<dynamic> _get(String path) async {
    final response = await _client
        .get(Uri.parse(path), headers: _headers())
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) throw const DashboardUnavailable();
    final value = jsonDecode(utf8.decode(response.bodyBytes));
    if (value is! Map<String, dynamic> && value is! List<dynamic>) {
      throw const DashboardUnavailable();
    }
    return value;
  }

  Map<String, String> _headers({String? idempotencyKey}) {
    final headers = <String, String>{'Cache-Control': 'no-cache'};
    if (idempotencyKey != null) {
      headers['Idempotency-Key'] = idempotencyKey;
    }
    final csrf = readCsrfToken();
    if (csrf != null) headers['X-CSRF-Token'] = csrf;
    return headers;
  }
}

class MeetingOverview {
  const MeetingOverview({
    required this.status,
    required this.meeting,
    required this.lastCheckedAt,
    required this.progress,
    this.series = const [],
    this.agendaStatus,
  });
  factory MeetingOverview.fromJson(
    Map<String, dynamic> json,
  ) => MeetingOverview(
    status: json['status'] as String,
    meeting: json['meeting'] == null
        ? null
        : MeetingInfo.fromJson(json['meeting'] as Map<String, dynamic>),
    lastCheckedAt: DateTime.tryParse(json['lastCheckedAt'] as String? ?? ''),
    progress: Progress.fromJson(json['progress'] as Map<String, dynamic>),
    // Ontbrekend veld (oudere backend tijdens de uitrol) telt als een lege reeks.
    series: ((json['series'] as List<dynamic>?) ?? const [])
        .map((e) => SeriesMeeting.fromJson(e as Map<String, dynamic>))
        .toList(growable: false),
    agendaStatus: json['agendaStatus'] as String?,
  );
  final String status;
  final MeetingInfo? meeting;

  /// PUBLISHED, UNPUBLISHED of NONE; `null` bij een oudere backend.
  final String? agendaStatus;
  final DateTime? lastCheckedAt;
  final Progress progress;

  /// De vergaderingen die bij elkaar horen, de eerstvolgende vooraan. Leeg als er geen is.
  final List<SeriesMeeting> series;
}

/// Eén vergadering uit het reeks-overzicht: genoeg voor een tegel; de rest laadt via de meeting-id.
class SeriesMeeting {
  const SeriesMeeting({
    required this.id,
    required this.startsAt,
    required this.place,
    required this.status,
  });
  factory SeriesMeeting.fromJson(Map<String, dynamic> json) => SeriesMeeting(
    id: json['id'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String),
    place: json['place'] as String?,
    status: json['status'] as String,
  );
  final String id;
  final DateTime startsAt;
  final String? place;
  final String status;
}

class MeetingInfo {
  const MeetingInfo({
    required this.id,
    required this.title,
    required this.startsAt,
    required this.endsAt,
    required this.location,
    required this.sourceUrl,
    required this.status,
    required this.publicationStatus,
    required this.revisionNumber,
    required this.canonicalFingerprint,
    required this.revisionStatus,
    this.past = false,
    this.commissieName = 'Ruimte',
  });
  factory MeetingInfo.fromJson(Map<String, dynamic> json) => MeetingInfo(
    id: json['id'] as String,
    title: json['title'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String),
    endsAt: DateTime.tryParse(json['endsAt'] as String? ?? ''),
    location: json['location'] as String?,
    sourceUrl: Uri.parse(json['sourceUrl'] as String),
    status: json['status'] as String,
    publicationStatus: json['publicationStatus'] as String,
    revisionNumber: json['revisionNumber'] as int,
    canonicalFingerprint: json['canonicalFingerprint'] as String?,
    revisionStatus: json['revisionStatus'] as String?,
    // Tolerant: een antwoord zonder `past` (oudere backend, bestaande fixture) telt als
    // 'nog niet geweest'. De waarde komt van de server, nooit van de browserklok.
    past: json['past'] as bool? ?? false,
    commissieName: json['commissieNaam'] as String? ?? 'Ruimte',
  );
  final String id;
  final String title;
  final DateTime startsAt;
  final DateTime? endsAt;
  final String? location;
  final Uri sourceUrl;
  final String status;
  final String publicationStatus;
  final int revisionNumber;
  final String? canonicalFingerprint;
  final String? revisionStatus;
  final bool past;

  /// De weergavenaam van de commissie ('Ruimte' of 'Bestuur'); zonder veld in het antwoord Ruimte.
  final String commissieName;
}

/// Eén pagina van het archiefoverzicht: de rijen, de cursor voor de volgende pagina en het totaal
/// aantal bewaarde voorbije vergaderingen (ongeacht cursor en limiet).
class PastMeetingPage {
  const PastMeetingPage({
    required this.items,
    required this.nextCursor,
    required this.total,
  });
  factory PastMeetingPage.fromJson(Map<String, dynamic> json) =>
      PastMeetingPage(
        items: (json['items'] as List<dynamic>)
            .map((value) => PastMeeting.fromJson(value as Map<String, dynamic>))
            .toList(growable: false),
        nextCursor: json['nextCursor'] as String?,
        total: json['total'] as int,
      );
  final List<PastMeeting> items;
  final String? nextCursor;
  final int total;
}

class PastMeeting {
  const PastMeeting({
    required this.id,
    required this.title,
    required this.startsAt,
    required this.location,
    required this.substantiveItemCount,
    required this.completedAdviceCount,
  });
  factory PastMeeting.fromJson(Map<String, dynamic> json) => PastMeeting(
    id: json['id'] as String,
    title: json['title'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String),
    location: json['location'] as String?,
    substantiveItemCount: json['substantiveItemCount'] as int,
    completedAdviceCount: json['completedAdviceCount'] as int,
  );
  final String id;
  final String title;
  final DateTime startsAt;
  final String? location;
  final int substantiveItemCount;
  final int completedAdviceCount;
}

class Progress {
  const Progress(this.total, this.complete, this.failed);
  factory Progress.fromJson(Map<String, dynamic> json) => Progress(
    json['total'] as int,
    json['complete'] as int,
    json['failed'] as int,
  );
  final int total;
  final int complete;
  final int failed;
}

class AgendaItemSummary {
  const AgendaItemSummary({
    required this.id,
    required this.sequence,
    required this.displayNumber,
    required this.category,
    required this.title,
    required this.substantive,
    required this.importStatus,
    required this.analysisStatus,
    this.documentStatus = 'DOCUMENTS_READY',
    this.documentCount = 1,
    this.readableDocumentCount = 1,
    required this.sourceState,
    required this.currentFingerprint,
    required this.adviceActuality,
    required this.changeTypes,
    this.lastDetectedChangeAt,
    this.displayTitle,
    this.shortConclusion,
    this.lastAnalysisRun,
    this.canRetryAnalysis = false,
    this.commissieName = 'Ruimte',
  });
  factory AgendaItemSummary.fromJson(Map<String, dynamic> json) =>
      AgendaItemSummary(
        id: json['id'] as String,
        sequence: json['sequence'] as int,
        displayNumber: json['displayNumber'] as String?,
        category: json['category'] as String,
        title: json['title'] as String,
        substantive: json['substantive'] as bool,
        importStatus: json['importStatus'] as String,
        analysisStatus: json['analysisStatus'] as String?,
        documentStatus: json['documentStatus'] as String,
        documentCount: json['documentCount'] as int,
        readableDocumentCount: json['readableDocumentCount'] as int,
        sourceState: json['sourceState'] as String,
        currentFingerprint: json['currentFingerprint'] as String?,
        adviceActuality: json['adviceActuality'] as String?,
        changeTypes: (json['changeTypes'] as List<dynamic>).cast<String>(),
        lastDetectedChangeAt: DateTime.tryParse(
          json['lastDetectedChangeAt'] as String? ?? '',
        ),
        displayTitle: json['displayTitle'] as String?,
        shortConclusion: json['shortConclusion'] as String?,
        lastAnalysisRun: json['lastAnalysisRun'] == null
            ? null
            : AnalysisRunInfo.fromJson(
                json['lastAnalysisRun'] as Map<String, dynamic>,
              ),
        canRetryAnalysis: json['canRetryAnalysis'] as bool,
        commissieName: json['commissieNaam'] as String? ?? 'Ruimte',
      );
  final String id;
  final int sequence;
  final String? displayNumber;
  final String category;
  final String title;
  final bool substantive;
  final String importStatus;
  final String? analysisStatus;
  final String documentStatus;
  final int documentCount;
  final int readableDocumentCount;
  final String sourceState;
  final String? currentFingerprint;
  final String? adviceActuality;
  final List<String> changeTypes;
  final DateTime? lastDetectedChangeAt;
  final String? displayTitle;
  final String? shortConclusion;
  final AnalysisRunInfo? lastAnalysisRun;
  final bool canRetryAnalysis;
  final String commissieName;
}

class AnalysisRunInfo {
  const AnalysisRunInfo({
    required this.id,
    required this.status,
    required this.createdAt,
    required this.updatedAt,
    required this.completedAt,
  });
  factory AnalysisRunInfo.fromJson(Map<String, dynamic> json) =>
      AnalysisRunInfo(
        id: json['id'] as String,
        status: json['status'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
        completedAt: DateTime.tryParse(json['completedAt'] as String? ?? ''),
      );
  final String id;
  final String status;
  final DateTime createdAt;
  final DateTime updatedAt;
  final DateTime? completedAt;
}

class AgendaItemDetail {
  const AgendaItemDetail({
    required this.item,
    required this.explanation,
    required this.treatmentProposal,
    required this.sourceUrl,
    required this.advice,
    required this.adviceActuality,
    required this.sources,
    required this.warning,
  });
  factory AgendaItemDetail.fromJson(Map<String, dynamic> json) =>
      AgendaItemDetail(
        item: AgendaItemSummary.fromJson(json['item'] as Map<String, dynamic>),
        explanation: json['explanation'] as String?,
        treatmentProposal: json['treatmentProposal'] as String?,
        sourceUrl: Uri.parse(json['sourceUrl'] as String),
        advice: json['advice'] as Map<String, dynamic>?,
        adviceActuality: json['adviceActuality'] as String?,
        sources: (json['sources'] as List<dynamic>)
            .map((value) => SourceLink.fromJson(value as Map<String, dynamic>))
            .toList(growable: false),
        warning: json['warning'] as String,
      );
  final AgendaItemSummary item;
  final String? explanation;
  final String? treatmentProposal;
  final Uri sourceUrl;
  final Map<String, dynamic>? advice;
  final String? adviceActuality;
  final List<SourceLink> sources;
  final String warning;
}

/// Eén bewaarde adviesversie van een agendapunt.
///
/// [latest] is uitsluitend `true` voor de eerste versie in de serverordening: dat is het advies dat
/// de detailweergave als 'laatste advies' toont. [analysisGuidance] is `null` wanneer er bij die run
/// geen aanvullende analyse-instructie is bewaard; de huidige instructie hoort daar niet te komen.
/// [refreshReason] is de code waarom díe run is gedaan (`MANUAL_RETRY`, `CONTEXT_CHANGED` of
/// `FIRST_ANALYSIS`). Bewust geen bronnenlijst: die hoort alleen bij het laatste advies.
class AdviceVersion {
  const AdviceVersion({
    required this.adviceId,
    required this.analysisRunId,
    required this.createdAt,
    required this.actuality,
    required this.latest,
    required this.advice,
    this.displayTitle,
    this.shortConclusion,
    this.provider = '',
    this.model = '',
    this.promptVersion = '',
    this.analysisGuidance,
    required this.refreshReason,
  });
  factory AdviceVersion.fromJson(Map<String, dynamic> json) => AdviceVersion(
    adviceId: json['adviceId'] as String,
    analysisRunId: json['analysisRunId'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    actuality: json['actuality'] as String,
    latest: json['latest'] as bool,
    advice: json['advice'] as Map<String, dynamic>,
    displayTitle: json['displayTitle'] as String?,
    shortConclusion: json['shortConclusion'] as String?,
    provider: json['provider'] as String? ?? '',
    model: json['model'] as String? ?? '',
    promptVersion: json['promptVersion'] as String? ?? '',
    analysisGuidance: json['analysisGuidance'] as String?,
    refreshReason: json['refreshReason'] as String,
  );
  final String adviceId;
  final String analysisRunId;
  final DateTime createdAt;
  final String actuality;
  final bool latest;
  final Map<String, dynamic> advice;
  final String? displayTitle;
  final String? shortConclusion;
  final String provider;
  final String model;
  final String promptVersion;
  final String? analysisGuidance;
  final String refreshReason;
}

class SourceLink {
  const SourceLink(this.name, this.url, this.status);
  factory SourceLink.fromJson(Map<String, dynamic> json) => SourceLink(
    json['name'] as String,
    Uri.parse(json['url'] as String),
    json['status'] as String,
  );
  final String name;
  final Uri url;
  final String status;
}

class DashboardUnavailable implements Exception {
  const DashboardUnavailable();
}

class MeetingCheckOutcome {
  const MeetingCheckOutcome({
    required this.status,
    required this.revisionNumber,
    required this.differences,
    this.meetings = const [],
    this.commissies = const [],
  });
  factory MeetingCheckOutcome.fromJson(Map<String, dynamic> json) =>
      MeetingCheckOutcome(
        status: json['status'] as String,
        revisionNumber: json['revisionNumber'] as int?,
        differences: (json['differences'] as List<dynamic>? ?? const [])
            .cast<String>(),
        meetings: (json['meetings'] as List<dynamic>? ?? const [])
            .map((m) => MeetingCheckResult.fromJson(m as Map<String, dynamic>))
            .toList(growable: false),
        commissies: (json['commissies'] as List<dynamic>? ?? const [])
            .map((c) => CommissieCheck.fromJson(c as Map<String, dynamic>))
            .toList(growable: false),
      );
  final String status;
  final int? revisionNumber;
  final List<String> differences;

  /// De uitkomst per vergadering; leeg bij een oudere backend, dan geldt [status].
  final List<MeetingCheckResult> meetings;

  /// De hele controle is mislukt: de bron was onbereikbaar, of de lijst bestaat uit één mislukt
  /// element zonder vergadergegevens (oud foutgedrag).
  bool get wholeCheckFailed {
    // Met een uitkomst per commissie is de hele controle pas mislukt als álle commissies mislukten.
    if (commissies.isNotEmpty) return commissies.every((c) => !c.success);
    if (meetings.isEmpty) {
      return status == 'SOURCE_FAILURE' || status == 'FAILED';
    }
    return meetings.length == 1 && meetings.first.isUnidentifiedFailure;
  }

  /// Uitkomst per commissie; leeg bij een oudere backend.
  final List<CommissieCheck> commissies;

  /// Minstens één commissie is mislukt terwijl er per-commissieresultaten zijn.
  bool get hasCommissieFailure => commissies.any((c) => !c.success);

  /// De rijen die getoond worden: de lijst, of het oude enkelvoudige veld als één element.
  List<MeetingCheckResult> get rows => meetings.isNotEmpty
      ? meetings
      : [
          MeetingCheckResult(
            outcome: switch (status) {
              'UNCHANGED' || 'NO_FUTURE_MEETING' => 'UNCHANGED',
              'AGENDA_UNPUBLISHED' => 'AGENDA_UNPUBLISHED',
              'IMPORTED' => 'NEW_DOCUMENTS',
              _ => status,
            },
          ),
        ];
}

class MeetingCheckResult {
  const MeetingCheckResult({
    this.meetingId,
    this.title,
    this.startsAt,
    this.location,
    required this.outcome,
    this.newAnalyses = 0,
    this.errorDescription,
  });
  factory MeetingCheckResult.fromJson(Map<String, dynamic> json) =>
      MeetingCheckResult(
        meetingId: json['meetingId'] as String?,
        title: json['title'] as String?,
        startsAt: json['startsAt'] is String
            ? DateTime.tryParse(json['startsAt'] as String)
            : null,
        location: json['location'] as String?,
        outcome: json['outcome'] as String? ?? 'UNCHANGED',
        newAnalyses: (json['newAnalyses'] as num?)?.toInt() ?? 0,
        errorDescription: json['errorDescription'] as String?,
      );
  final String? meetingId;
  final String? title;
  final DateTime? startsAt;
  final String? location;

  /// UNCHANGED, NEW_DOCUMENTS, AGENDA_UNPUBLISHED of FAILED.
  final String outcome;
  final int newAnalyses;
  final String? errorDescription;

  bool get isUnidentifiedFailure =>
      outcome == 'FAILED' && startsAt == null && location == null;
}

/// De uitkomst van de controle voor één commissie.
class CommissieCheck {
  const CommissieCheck({
    required this.commissie,
    required this.name,
    required this.success,
    this.melding = '',
  });
  factory CommissieCheck.fromJson(Map<String, dynamic> json) => CommissieCheck(
    commissie: json['commissie'] as String? ?? commissieRuimte,
    name: json['naam'] as String? ?? 'Ruimte',
    success: json['success'] as bool? ?? true,
    melding: json['melding'] as String? ?? '',
  );
  final String commissie;
  final String name;
  final bool success;
  final String melding;
}
