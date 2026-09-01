import 'dart:convert';

import 'package:http/http.dart' as http;

class AuthenticatedUser {
  const AuthenticatedUser(this.email);
  final String email;
}

abstract interface class AuthenticationGateway {
  Future<AuthenticatedUser> restore();
  Future<AuthenticatedUser> signIn(String idToken);
  Future<void> signOut();
}

class HttpAuthenticationGateway implements AuthenticationGateway {
  HttpAuthenticationGateway({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;
  @override
  Future<AuthenticatedUser> restore() => _authenticate(
    () => _client.get(
      Uri.parse('/api/auth/me'),
      headers: const {'Cache-Control': 'no-cache'},
    ),
  );

  @override
  Future<AuthenticatedUser> signIn(String idToken) => _authenticate(
    () => _client.post(
      Uri.parse('/api/auth/session'),
      headers: {
        'Authorization': 'Bearer $idToken',
        'Cache-Control': 'no-cache',
      },
    ),
  );

  Future<AuthenticatedUser> _authenticate(
    Future<http.Response> Function() request,
  ) async {
    final response = await request().timeout(const Duration(seconds: 8));
    if (response.statusCode == 401 || response.statusCode == 403) {
      throw const AuthenticationRejected();
    }
    if (response.statusCode != 200) throw const AuthenticationUnavailable();
    final value = jsonDecode(utf8.decode(response.bodyBytes));
    if (value is! Map<String, dynamic> || value['email'] is! String) {
      throw const AuthenticationUnavailable();
    }
    return AuthenticatedUser(value['email']! as String);
  }

  @override
  Future<void> signOut() async {
    final response = await _client
        .delete(Uri.parse('/api/auth/session'))
        .timeout(const Duration(seconds: 8));
    if (response.statusCode != 204) throw const AuthenticationUnavailable();
  }
}

class AuthenticationRejected implements Exception {
  const AuthenticationRejected();
}

class AuthenticationUnavailable implements Exception {
  const AuthenticationUnavailable();
}
