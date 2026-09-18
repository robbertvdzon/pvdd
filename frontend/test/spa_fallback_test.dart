import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

// Een rechtstreekse aanroep van /archief/<vergadering-id> komt alleen bij de app terecht wanneer de
// webserver onbekende paden naar index.html stuurt. Deze test legt vast dat die SPA-fallback in
// beide nginx-configuraties aanwezig blijft; er is geen serverwijziging voor het archiefscherm nodig.
void main() {
  test('both nginx configurations keep the SPA fallback for deep links', () {
    for (final name in ['nginx.conf', 'nginx-acceptance.conf']) {
      final file = File(name);
      expect(file.existsSync(), isTrue, reason: '$name ontbreekt');
      expect(
        file.readAsStringSync(),
        contains(r'try_files $uri $uri/ /index.html'),
        reason: '$name mist de SPA-fallback',
      );
    }
  });
}
