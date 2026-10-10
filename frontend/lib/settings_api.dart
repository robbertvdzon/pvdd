import 'dart:convert';

import 'package:http/http.dart' as http;

import 'csrf_token.dart';

abstract interface class SettingsGateway {
  Future<ApplicationSettings> load();
  Future<ApplicationSettings> updateAnalysisInstructions(String value);
  Future<ApplicationSettings> selectAnalysisModel(
    String task,
    ModelExecution execution,
  );
  Future<ApplicationSettings> resetAnalysisModel(String task);
  Future<int> retryAllFailedAnalyses();
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

  @override
  Future<ApplicationSettings> selectAnalysisModel(
    String task,
    ModelExecution execution,
  ) async {
    final headers = <String, String>{'Content-Type': 'application/json'};
    final csrf = readCsrfToken();
    if (csrf != null) headers['X-CSRF-Token'] = csrf;
    return _decode(
      await _client
          .put(
            Uri.parse('/api/settings/analysis-models/$task'),
            headers: headers,
            body: jsonEncode({
              'vendorId': execution.vendorId,
              'model': execution.model,
              'mode': execution.mode,
            }),
          )
          .timeout(const Duration(seconds: 15)),
    );
  }

  @override
  Future<ApplicationSettings> resetAnalysisModel(String task) async {
    final headers = <String, String>{};
    final csrf = readCsrfToken();
    if (csrf != null) headers['X-CSRF-Token'] = csrf;
    return _decode(
      await _client
          .delete(
            Uri.parse('/api/settings/analysis-models/$task'),
            headers: headers,
          )
          .timeout(const Duration(seconds: 15)),
    );
  }

  @override
  Future<int> retryAllFailedAnalyses() async {
    final headers = <String, String>{
      'Idempotency-Key':
          'retry-all-failed-${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}',
    };
    final csrf = readCsrfToken();
    if (csrf != null) headers['X-CSRF-Token'] = csrf;
    final response = await _client
        .post(
          Uri.parse('/api/settings/retry-failed-analyses'),
          headers: headers,
        )
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) throw const SettingsUnavailable();
    final value = jsonDecode(utf8.decode(response.bodyBytes));
    if (value is! Map<String, dynamic>) throw const SettingsUnavailable();
    return value['retriedCount'] as int;
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
    required this.analysisModels,
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
        analysisModels: AnalysisModelSettings.fromJson(
          json['analysisModels'] as Map<String, dynamic>,
        ),
      );

  final List<ScheduledJobSetting> scheduledJobs;
  final PolicySourceSettings policySources;
  final AnalysisPromptSettings analysisPrompt;
  final AnalysisModelSettings analysisModels;
}

/// De twee AI-taken waarvoor een model te kiezen is.
const analysisModelTasks = <String>['SOURCE_NOTES', 'FINAL_ADVICE'];

class ModelExecution {
  const ModelExecution({
    required this.vendorId,
    required this.model,
    required this.mode,
  });

  factory ModelExecution.fromJson(Map<String, dynamic> json) => ModelExecution(
    vendorId: json['vendorId'] as String,
    model: json['model'] as String,
    mode: json['mode'] as String,
  );

  final String vendorId;
  final String model;
  final String mode;

  String get label => '$model · $vendorId';

  @override
  bool operator ==(Object other) =>
      other is ModelExecution &&
      other.vendorId == vendorId &&
      other.model == model &&
      other.mode == mode;

  @override
  int get hashCode => Object.hash(vendorId, model, mode);
}

class AnalysisModelChoice {
  const AnalysisModelChoice({
    required this.task,
    required this.execution,
    required this.fromSetting,
    required this.updatedAt,
    required this.updatedBy,
  });

  factory AnalysisModelChoice.fromJson(Map<String, dynamic> json) =>
      AnalysisModelChoice(
        task: json['task'] as String,
        execution: ModelExecution.fromJson(
          json['execution'] as Map<String, dynamic>,
        ),
        fromSetting: json['fromSetting'] as bool,
        updatedAt: json['updatedAt'] == null
            ? null
            : DateTime.parse(json['updatedAt'] as String),
        updatedBy: json['updatedBy'] as String?,
      );

  final String task;
  final ModelExecution execution;

  /// false: de geconfigureerde standaard geldt.
  final bool fromSetting;
  final DateTime? updatedAt;
  final String? updatedBy;
}

class AnalysisModelOption {
  const AnalysisModelOption({
    required this.execution,
    required this.available,
    required this.onlineWorkers,
  });

  factory AnalysisModelOption.fromJson(Map<String, dynamic> json) =>
      AnalysisModelOption(
        execution: ModelExecution.fromJson(
          json['execution'] as Map<String, dynamic>,
        ),
        available: json['available'] as bool,
        onlineWorkers: json['onlineWorkers'] as int,
      );

  final ModelExecution execution;
  final bool available;
  final int onlineWorkers;
}

class AnalysisModelSettings {
  const AnalysisModelSettings({
    required this.choices,
    required this.options,
    required this.catalogUnavailable,
  });

  factory AnalysisModelSettings.fromJson(Map<String, dynamic> json) =>
      AnalysisModelSettings(
        choices: (json['choices'] as List<dynamic>)
            .map(
              (value) =>
                  AnalysisModelChoice.fromJson(value as Map<String, dynamic>),
            )
            .toList(growable: false),
        options: (json['options'] as List<dynamic>)
            .map(
              (value) =>
                  AnalysisModelOption.fromJson(value as Map<String, dynamic>),
            )
            .toList(growable: false),
        catalogUnavailable: json['catalogUnavailable'] as bool,
      );

  final List<AnalysisModelChoice> choices;
  final List<AnalysisModelOption> options;
  final bool catalogUnavailable;

  AnalysisModelChoice? choiceFor(String task) {
    for (final choice in choices) {
      if (choice.task == task) return choice;
    }
    return null;
  }
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
