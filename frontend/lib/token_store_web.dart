import 'package:web/web.dart' as web;

abstract interface class TokenStore {
  String? read();
  void write(String value);
  void clear();
}

class BrowserTokenStore implements TokenStore {
  static const _key = 'pvdd.google.id_token';
  @override
  String? read() => web.window.sessionStorage.getItem(_key);
  @override
  void write(String value) => web.window.sessionStorage.setItem(_key, value);
  @override
  void clear() => web.window.sessionStorage.removeItem(_key);
}
