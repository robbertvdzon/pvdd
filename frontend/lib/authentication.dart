import 'dart:convert';

import 'package:http/http.dart' as http;

class AuthenticatedUser {
  const AuthenticatedUser(this.email);
  final String email;
}

abstract interface class AuthenticationGateway {
  Future<AuthenticatedUser> me(String idToken);
}

class HttpAuthenticationGateway implements AuthenticationGateway {
  HttpAuthenticationGateway({http.Client? client})
    : _client = client ?? http.Client();
  final http.Client _client;
  @override
  Future<AuthenticatedUser> me(String idToken) async {
    final response = await _client
        .get(
          Uri.parse('/api/auth/me'),
          headers: {
            'Authorization': 'Bearer $idToken',
            'Cache-Control': 'no-cache',
          },
        )
        .timeout(const Duration(seconds: 8));
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
}

class AuthenticationRejected implements Exception {
  const AuthenticationRejected();
}

class AuthenticationUnavailable implements Exception {
  const AuthenticationUnavailable();
}
