import 'dart:convert';

import 'package:http/http.dart' as http;

import 'csrf_token.dart';

abstract interface class SettingsGateway {
  Future<ApplicationSettings> load();
  Future<ApplicationSettings> updateAnalysisInstructions(String value);
}

class HttpSettingsGateway implements SettingsGateway {
  HttpSettingsGateway({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;

  @override
  Future<ApplicationSettings> load() async => _decode(
    await _client
        .get(Uri.parse('/api/settings'))
        .timeout(const Duration(seconds: 10)),
  );

  @override
  Future<ApplicationSettings> updateAnalysisInstructions(String value) async {
    final headers = <String, String>{'Content-Type': 'application/json'};
    final csrf = readCsrfToken();
    if (csrf != null) headers['X-CSRF-Token'] = csrf;
    return _decode(
      await _client
          .put(
            Uri.parse('/api/settings/analysis-instructions'),
            headers: headers,
            body: jsonEncode({'additionalInstructions': value}),
          )
          .timeout(const Duration(seconds: 10)),
    );
  }

  ApplicationSettings _decode(http.Response response) {
    if (response.statusCode != 200) throw const SettingsUnavailable();
    return ApplicationSettings.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
    );
  }
}

class ApplicationSettings {
  const ApplicationSettings({
    required this.scheduledJobs,
    required this.policySources,
    required this.analysisPrompt,
  });

  factory ApplicationSettings.fromJson(Map<String, dynamic> json) =>
      ApplicationSettings(
        scheduledJobs: (json['scheduledJobs'] as List<dynamic>)
            .map(
              (value) =>
                  ScheduledJobSetting.fromJson(value as Map<String, dynamic>),
            )
            .toList(growable: false),
        policySources: PolicySourceSettings.fromJson(
          json['policySources'] as Map<String, dynamic>,
        ),
        analysisPrompt: AnalysisPromptSettings.fromJson(
          json['analysisPrompt'] as Map<String, dynamic>,
        ),
      );

  final List<ScheduledJobSetting> scheduledJobs;
  final PolicySourceSettings policySources;
  final AnalysisPromptSettings analysisPrompt;
}

class ScheduledJobSetting {
  const ScheduledJobSetting({
    required this.key,
    required this.name,
    required this.kind,
    required this.schedule,
    required this.timeZone,
    required this.explanation,
  });

  factory ScheduledJobSetting.fromJson(Map<String, dynamic> json) =>
      ScheduledJobSetting(
        key: json['key'] as String,
        name: json['name'] as String,
        kind: json['kind'] as String,
        schedule: json['schedule'] as String,
        timeZone: json['timeZone'] as String?,
        explanation: json['explanation'] as String,
      );

  final String key;
  final String name;
  final String kind;
  final String schedule;
  final String? timeZone;
  final String explanation;
}

class PolicySourceSettings {
  const PolicySourceSettings({
    required this.programmeUrl,
    required this.startUrls,
    required this.websiteHost,
    required this.discoveryPaths,
    required this.allowedHosts,
    required this.maximumPages,
  });

  factory PolicySourceSettings.fromJson(Map<String, dynamic> json) =>
      PolicySourceSettings(
        programmeUrl: json['programmeUrl'] as String,
        startUrls: List<String>.from(json['startUrls'] as List<dynamic>),
        websiteHost: json['websiteHost'] as String,
        discoveryPaths: List<String>.from(
          json['discoveryPaths'] as List<dynamic>,
        ),
        allowedHosts: List<String>.from(json['allowedHosts'] as List<dynamic>),
        maximumPages: json['maximumPages'] as int,
      );

  final String programmeUrl;
  final List<String> startUrls;
  final String websiteHost;
  final List<String> discoveryPaths;
  final List<String> allowedHosts;
  final int maximumPages;
}

class AnalysisPromptSettings {
  const AnalysisPromptSettings({
    required this.promptVersion,
    required this.systemPrompt,
    required this.additionalInstructions,
    required this.additionalInstructionsUpdatedAt,
    required this.additionalInstructionsUpdatedBy,
    required this.maximumAdditionalInstructionCharacters,
  });

  factory AnalysisPromptSettings.fromJson(Map<String, dynamic> json) =>
      AnalysisPromptSettings(
        promptVersion: json['promptVersion'] as String,
        systemPrompt: json['systemPrompt'] as String,
        additionalInstructions: json['additionalInstructions'] as String,
        additionalInstructionsUpdatedAt: DateTime.parse(
          json['additionalInstructionsUpdatedAt'] as String,
        ),
        additionalInstructionsUpdatedBy:
            json['additionalInstructionsUpdatedBy'] as String,
        maximumAdditionalInstructionCharacters:
            json['maximumAdditionalInstructionCharacters'] as int,
      );

  final String promptVersion;
  final String systemPrompt;
  final String additionalInstructions;
  final DateTime additionalInstructionsUpdatedAt;
  final String additionalInstructionsUpdatedBy;
  final int maximumAdditionalInstructionCharacters;
}

class SettingsUnavailable implements Exception {
  const SettingsUnavailable();
}
