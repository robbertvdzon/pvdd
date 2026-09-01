import 'package:web/web.dart' as web;

String? readCsrfToken() {
  for (final part in web.document.cookie.split(';')) {
    final pair = part.trim().split('=');
    if (pair.length >= 2 && pair.first == 'pvdd_csrf') {
      return pair.sublist(1).join('=');
    }
  }
  return null;
}
