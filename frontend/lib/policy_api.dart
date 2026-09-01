import 'dart:convert';

import 'package:http/http.dart' as http;

import 'csrf_token.dart';

abstract interface class PolicyGateway {
  Future<PolicyOverview> overview();
  Future<PolicyRun> refresh();
}

class HttpPolicyGateway implements PolicyGateway {
  HttpPolicyGateway({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;

  @override
  Future<PolicyOverview> overview() async {
    final response = await _client
        .get(Uri.parse('/api/policy/overview'))
        .timeout(const Duration(seconds: 15));
    if (response.statusCode != 200) throw const PolicyUnavailable();
    return PolicyOverview.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
    );
  }

  @override
  Future<PolicyRun> refresh() async {
    final csrf = readCsrfToken();
    final headers = <String, String>{
      'Idempotency-Key':
          'policy-web-${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}',
    };
    if (csrf != null) headers['X-CSRF-Token'] = csrf;
    final response = await _client
        .post(Uri.parse('/api/policy/refresh'), headers: headers)
        .timeout(const Duration(seconds: 15));
    if (response.statusCode != 202 && response.statusCode != 409) {
      throw const PolicyUnavailable();
    }
    return PolicyRun.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
    );
  }
}

class PolicyOverview {
  const PolicyOverview({
    required this.snapshot,
    required this.positions,
    required this.currentRun,
    required this.latestRun,
    required this.lastSuccessfulAt,
    required this.nextScheduledAt,
  });
  factory PolicyOverview.fromJson(Map<String, dynamic> json) => PolicyOverview(
    snapshot: json['snapshot'] == null
        ? null
        : PolicySnapshot.fromJson(json['snapshot'] as Map<String, dynamic>),
    positions: (json['positions'] as List<dynamic>)
        .map((value) => PolicyPosition.fromJson(value as Map<String, dynamic>))
        .toList(growable: false),
    currentRun: json['currentRun'] == null
        ? null
        : PolicyRun.fromJson(json['currentRun'] as Map<String, dynamic>),
    latestRun: json['latestRun'] == null
        ? null
        : PolicyRun.fromJson(json['latestRun'] as Map<String, dynamic>),
    lastSuccessfulAt: DateTime.tryParse(
      json['lastSuccessfulAt'] as String? ?? '',
    ),
    nextScheduledAt: DateTime.parse(json['nextScheduledAt'] as String),
  );
  final PolicySnapshot? snapshot;
  final List<PolicyPosition> positions;
  final PolicyRun? currentRun;
  final PolicyRun? latestRun;
  final DateTime? lastSuccessfulAt;
  final DateTime nextScheduledAt;
}

class PolicySnapshot {
  const PolicySnapshot(this.version, this.fingerprint, this.activatedAt);
  factory PolicySnapshot.fromJson(Map<String, dynamic> json) => PolicySnapshot(
    json['version'] as int,
    json['fingerprint'] as String,
    DateTime.parse(json['activatedAt'] as String),
  );
  final int version;
  final String fingerprint;
  final DateTime activatedAt;
}

class PolicyRun {
  const PolicyRun({
    required this.id,
    required this.status,
    required this.createdAt,
    required this.startedAt,
    required this.completedAt,
    required this.sourceCount,
    required this.newCount,
    required this.changedCount,
    required this.unchangedCount,
    required this.disappearedCount,
    required this.errorCode,
  });
  factory PolicyRun.fromJson(Map<String, dynamic> json) => PolicyRun(
    id: json['id'] as String,
    status: json['status'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    startedAt: DateTime.tryParse(json['startedAt'] as String? ?? ''),
    completedAt: DateTime.tryParse(json['completedAt'] as String? ?? ''),
    sourceCount: json['sourceCount'] as int,
    newCount: json['newCount'] as int,
    changedCount: json['changedCount'] as int,
    unchangedCount: json['unchangedCount'] as int,
    disappearedCount: json['disappearedCount'] as int,
    errorCode: json['errorCode'] as String?,
  );
  final String id;
  final String status;
  final DateTime createdAt;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final int sourceCount;
  final int newCount;
  final int changedCount;
  final int unchangedCount;
  final int disappearedCount;
  final String? errorCode;
}

class PolicyPosition {
  const PolicyPosition({
    required this.id,
    required this.title,
    required this.summary,
    required this.themes,
    required this.direction,
    required this.status,
    required this.sourceDate,
    required this.lastChangedAt,
    required this.references,
  });
  factory PolicyPosition.fromJson(Map<String, dynamic> json) => PolicyPosition(
    id: json['id'] as String,
    title: json['title'] as String,
    summary: json['summary'] as String,
    themes: (json['themes'] as List<dynamic>).cast<String>(),
    direction: json['direction'] as String,
    status: json['status'] as String,
    sourceDate: DateTime.tryParse(json['sourceDate'] as String? ?? ''),
    lastChangedAt: DateTime.parse(json['lastChangedAt'] as String),
    references: (json['references'] as List<dynamic>)
        .map((value) => PolicyReference.fromJson(value as Map<String, dynamic>))
        .toList(growable: false),
  );
  final String id;
  final String title;
  final String summary;
  final List<String> themes;
  final String direction;
  final String status;
  final DateTime? sourceDate;
  final DateTime lastChangedAt;
  final List<PolicyReference> references;
}

class PolicyReference {
  const PolicyReference(
    this.url,
    this.sourceType,
    this.title,
    this.pageNumber,
    this.section,
  );
  factory PolicyReference.fromJson(Map<String, dynamic> json) =>
      PolicyReference(
        Uri.parse(json['url'] as String),
        json['sourceType'] as String,
        json['title'] as String,
        json['pageNumber'] as int?,
        json['section'] as String?,
      );
  final Uri url;
  final String sourceType;
  final String title;
  final int? pageNumber;
  final String? section;
}

class PolicyUnavailable implements Exception {
  const PolicyUnavailable();
}
