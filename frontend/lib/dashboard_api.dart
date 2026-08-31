import 'dart:convert';

import 'package:http/http.dart' as http;

typedef TokenProvider = String? Function();

abstract interface class DashboardGateway {
  Future<MeetingOverview> overview();
  Future<List<AgendaItemSummary>> agendaItems(String meetingId);
  Future<AgendaItemDetail> agendaItem(String itemId);
  Future<void> checkNow();
}

class HttpDashboardGateway implements DashboardGateway {
  HttpDashboardGateway(this._token, {http.Client? client})
    : _client = client ?? http.Client();
  final TokenProvider _token;
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
  Future<void> checkNow() async {
    final response = await _client
        .post(
          Uri.parse('/api/meetings/check-now'),
          headers: _headers(
            idempotencyKey:
                'web-${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}',
          ),
        )
        .timeout(const Duration(seconds: 30));
    if (response.statusCode != 200 && response.statusCode != 409) {
      throw const DashboardUnavailable();
    }
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
    final token = _token();
    if (token == null || token.isEmpty) throw const DashboardUnavailable();
    final headers = {
      'Authorization': 'Bearer $token',
      'Cache-Control': 'no-cache',
    };
    if (idempotencyKey != null) {
      headers['Idempotency-Key'] = idempotencyKey;
    }
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
  });
  factory MeetingInfo.fromJson(Map<String, dynamic> json) => MeetingInfo(
    id: json['id'] as String,
    title: json['title'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String),
    endsAt: DateTime.tryParse(json['endsAt'] as String? ?? ''),
    location: json['location'] as String?,
    sourceUrl: Uri.parse(json['sourceUrl'] as String),
    status: json['status'] as String,
  );
  final String id;
  final String title;
  final DateTime startsAt;
  final DateTime? endsAt;
  final String? location;
  final Uri sourceUrl;
  final String status;
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
      );
  final String id;
  final int sequence;
  final String? displayNumber;
  final String category;
  final String title;
  final bool substantive;
  final String importStatus;
  final String? analysisStatus;
}

class AgendaItemDetail {
  const AgendaItemDetail({
    required this.item,
    required this.explanation,
    required this.treatmentProposal,
    required this.sourceUrl,
    required this.advice,
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
