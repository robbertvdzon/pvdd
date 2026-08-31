import 'dart:convert';

import 'package:http/http.dart' as http;

import 'configuration.dart';

class BuildIdentity {
  const BuildIdentity({
    required this.version,
    required this.gitRevision,
    required this.buildTime,
    required this.environment,
    required this.identity,
  });

  factory BuildIdentity.validated({
    required String version,
    required String gitRevision,
    required String buildTime,
    required String environment,
    required String identity,
  }) {
    final validVersion =
        RegExp(r'^\d+\.\d+\.\d+(?:-SNAPSHOT)?$').hasMatch(version)
        ? version
        : unknown;
    final revision =
        RegExp(r'^[0-9a-f]{40}$').hasMatch(gitRevision.toLowerCase())
        ? gitRevision.toLowerCase()
        : unknown;
    final parsedTime = DateTime.tryParse(buildTime);
    final time = buildTime.endsWith('Z') && parsedTime?.isUtc == true
        ? buildTime
        : unknown;
    final runtimeEnvironment =
        const {'local', 'acceptance', 'production'}.contains(environment)
        ? environment
        : unknown;
    final validIdentity =
        RegExp(
          r'^\d+\.\d+\.\d+(?:-SNAPSHOT)?\+[0-9a-f]{12}$',
        ).hasMatch(identity)
        ? identity
        : unknown;
    return BuildIdentity(
      version: validVersion,
      gitRevision: revision,
      buildTime: time,
      environment: runtimeEnvironment,
      identity: validIdentity,
    );
  }

  factory BuildIdentity.frontend() {
    final revision = AppConfiguration.gitRevision.toLowerCase();
    final shortRevision = RegExp(r'^[0-9a-f]{40}$').hasMatch(revision)
        ? revision.substring(0, 12)
        : unknown;
    return BuildIdentity.validated(
      version: AppConfiguration.applicationVersion,
      gitRevision: revision,
      buildTime: AppConfiguration.buildTime,
      environment: AppConfiguration.environment,
      identity: shortRevision == unknown
          ? unknown
          : '${AppConfiguration.applicationVersion}+$shortRevision',
    );
  }

  factory BuildIdentity.fromBackendJson(Map<String, dynamic> json) =>
      BuildIdentity.validated(
        version: json['applicationVersion'] as String? ?? '',
        gitRevision: json['gitRevision'] as String? ?? '',
        buildTime: json['buildTime'] as String? ?? '',
        environment: json['environment'] as String? ?? '',
        identity: json['backendBuildIdentity'] as String? ?? '',
      );

  final String version;
  final String gitRevision;
  final String buildTime;
  final String environment;
  final String identity;
  static const unknown = 'Onbekend';
}

abstract interface class VersionGateway {
  Future<BuildIdentity> backend();
}

class HttpVersionGateway implements VersionGateway {
  HttpVersionGateway({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;
  @override
  Future<BuildIdentity> backend() async {
    final response = await _client
        .get(
          Uri.parse('/api/version'),
          headers: const {'Cache-Control': 'no-cache'},
        )
        .timeout(const Duration(seconds: 5));
    if (response.statusCode != 200) throw const VersionFailure();
    final value = jsonDecode(utf8.decode(response.bodyBytes));
    if (value is! Map<String, dynamic>) throw const VersionFailure();
    return BuildIdentity.fromBackendJson(value);
  }
}

class VersionFailure implements Exception {
  const VersionFailure();
}
