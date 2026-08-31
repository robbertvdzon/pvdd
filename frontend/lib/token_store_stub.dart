abstract interface class TokenStore {
  String? read();
  void write(String value);
  void clear();
}

class BrowserTokenStore implements TokenStore {
  String? _value;
  @override
  String? read() => _value;
  @override
  void write(String value) => _value = value;
  @override
  void clear() => _value = null;
}
