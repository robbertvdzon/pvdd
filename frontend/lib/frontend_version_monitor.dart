import 'dart:convert';
import 'package:http/http.dart' as http;
import 'build_identity.dart';

abstract interface class FrontendVersionSource {
  Future<BuildIdentity> latest();
}

class HttpFrontendVersionSource implements FrontendVersionSource {
  HttpFrontendVersionSource({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;
  @override
  Future<BuildIdentity> latest() async {
    final response = await _client.get(
      Uri.parse('/version.json'),
      headers: const {'Cache-Control': 'no-cache'},
    );
    if (response.statusCode != 200) throw const VersionFailure();
    final value = jsonDecode(utf8.decode(response.bodyBytes));
    if (value is! Map<String, dynamic>) throw const VersionFailure();
    return BuildIdentity.validated(
      version: value['applicationVersion'] as String? ?? '',
      gitRevision: value['gitRevision'] as String? ?? '',
      buildTime: value['buildTime'] as String? ?? '',
      environment: value['environment'] as String? ?? '',
      identity: value['frontendBuildIdentity'] as String? ?? '',
    );
  }
}

class VersionUpdateTracker {
  bool _notified = false;
  bool shouldNotify(BuildIdentity current, BuildIdentity latest) {
    if (_notified ||
        current.identity == BuildIdentity.unknown ||
        latest.identity == BuildIdentity.unknown ||
        current.identity == latest.identity) {
      return false;
    }
    _notified = true;
    return true;
  }
}
