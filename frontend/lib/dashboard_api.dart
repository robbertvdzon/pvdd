import 'dart:convert';

import 'package:http/http.dart' as http;

import 'csrf_token.dart';

abstract interface class DashboardGateway {
  Future<MeetingOverview> overview();
  Future<List<AgendaItemSummary>> agendaItems(String meetingId);
  Future<AgendaItemDetail> agendaItem(String itemId);
  Future<MeetingCheckOutcome> checkNow();
  Future<void> retryAnalysis(String itemId);
}

class HttpDashboardGateway implements DashboardGateway {
  HttpDashboardGateway({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;

  @override
  Future<MeetingOverview> overview() async => MeetingOverview.fromJson(
    await _get('/api/meetings/next') as Map<String, dynamic>,
  );

  @override
  Future<List<AgendaItemSummary>> agendaItems(String meetingId) async {
    final value = await _get('/api/meetings/$meetingId/agenda-items');
    return (value as List<dynamic>)
        .map((item) => AgendaItemSummary.fromJson(item as Map<String, dynamic>))
        .toList(growable: false);
  }

  @override
  Future<AgendaItemDetail> agendaItem(String itemId) async =>
      AgendaItemDetail.fromJson(await _get('/api/agenda-items/$itemId'));

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
  });
  factory MeetingOverview.fromJson(Map<String, dynamic> json) =>
      MeetingOverview(
        status: json['status'] as String,
        meeting: json['meeting'] == null
            ? null
            : MeetingInfo.fromJson(json['meeting'] as Map<String, dynamic>),
        lastCheckedAt: DateTime.tryParse(
          json['lastCheckedAt'] as String? ?? '',
        ),
        progress: Progress.fromJson(json['progress'] as Map<String, dynamic>),
      );
  final String status;
  final MeetingInfo? meeting;
  final DateTime? lastCheckedAt;
  final Progress progress;
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
    required this.sourceState,
    required this.currentFingerprint,
    required this.adviceActuality,
    required this.changeTypes,
    this.lastDetectedChangeAt,
    this.displayTitle,
    this.shortConclusion,
    this.lastAnalysisRun,
    this.canRetryAnalysis = false,
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
      );
  final String id;
  final int sequence;
  final String? displayNumber;
  final String category;
  final String title;
  final bool substantive;
  final String importStatus;
  final String? analysisStatus;
  final String sourceState;
  final String? currentFingerprint;
  final String? adviceActuality;
  final List<String> changeTypes;
  final DateTime? lastDetectedChangeAt;
  final String? displayTitle;
  final String? shortConclusion;
  final AnalysisRunInfo? lastAnalysisRun;
  final bool canRetryAnalysis;
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
  });
  factory MeetingCheckOutcome.fromJson(Map<String, dynamic> json) =>
      MeetingCheckOutcome(
        status: json['status'] as String,
        revisionNumber: json['revisionNumber'] as int?,
        differences: (json['differences'] as List<dynamic>? ?? const [])
            .cast<String>(),
      );
  final String status;
  final int? revisionNumber;
  final List<String> differences;
}
