import 'dart:convert';

import 'package:http/http.dart' as http;

abstract interface class AiRunsGateway {
  Future<AiRunPage> active();
  Future<AiRunPage> finished({String? cursor});
}

class HttpAiRunsGateway implements AiRunsGateway {
  HttpAiRunsGateway({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;

  @override
  Future<AiRunPage> active() => _load('/api/ai-runs?state=active&limit=50');

  @override
  Future<AiRunPage> finished({String? cursor}) => _load(
    '/api/ai-runs?state=finished&limit=10${cursor == null ? '' : '&cursor=${Uri.encodeQueryComponent(cursor)}'}',
  );

  Future<AiRunPage> _load(String path) async {
    final response = await _client
        .get(Uri.parse(path))
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) throw const AiRunsUnavailable();
    return AiRunPage.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
    );
  }
}

class AiRunPage {
  const AiRunPage(this.items, this.nextCursor);
  factory AiRunPage.fromJson(Map<String, dynamic> json) => AiRunPage(
    (json['items'] as List<dynamic>)
        .map((value) => AiRun.fromJson(value as Map<String, dynamic>))
        .toList(growable: false),
    json['nextCursor'] as String?,
  );
  final List<AiRun> items;
  final String? nextCursor;
}

class AiRun {
  const AiRun({
    required this.id,
    required this.type,
    required this.title,
    required this.explanation,
    required this.status,
    required this.createdAt,
    required this.startedAt,
    required this.updatedAt,
    required this.completedAt,
    required this.phaseCount,
    required this.completedPhases,
    required this.errorCode,
  });
  factory AiRun.fromJson(Map<String, dynamic> json) => AiRun(
    id: json['id'] as String,
    type: json['type'] as String,
    title: json['title'] as String,
    explanation: json['explanation'] as String,
    status: json['status'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    startedAt: DateTime.tryParse(json['startedAt'] as String? ?? ''),
    updatedAt: DateTime.parse(json['updatedAt'] as String),
    completedAt: DateTime.tryParse(json['completedAt'] as String? ?? ''),
    phaseCount: json['phaseCount'] as int,
    completedPhases: json['completedPhases'] as int,
    errorCode: json['errorCode'] as String?,
  );
  final String id;
  final String type;
  final String title;
  final String explanation;
  final String status;
  final DateTime createdAt;
  final DateTime? startedAt;
  final DateTime updatedAt;
  final DateTime? completedAt;
  final int phaseCount;
  final int completedPhases;
  final String? errorCode;
}

class AiRunsUnavailable implements Exception {
  const AiRunsUnavailable();
}
