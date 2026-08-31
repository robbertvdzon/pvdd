import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/main.dart';

void main() {
  testWidgets('technical shell reads backend version', (tester) async {
    await tester.pumpWidget(PvddApp(versionLoader: () async => '0.1.0+abcdef123456'));
    await tester.pumpAndSettle();
    expect(find.text('Technische basis gereed'), findsOneWidget);
    expect(find.text('De functionele module is nog niet geïnstalleerd.'), findsOneWidget);
    expect(find.text('Backend: 0.1.0+abcdef123456'), findsOneWidget);
  });
}
