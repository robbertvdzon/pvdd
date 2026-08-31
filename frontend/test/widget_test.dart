import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/authentication.dart';
import 'package:pvdd_frontend/build_identity.dart';
import 'package:pvdd_frontend/frontend_version_monitor.dart';
import 'package:pvdd_frontend/main.dart';
import 'package:pvdd_frontend/token_store.dart';

void main() {
  testWidgets('restores session and shows secured technical shell', (
    tester,
  ) async {
    final store = MemoryTokenStore()..write('valid-token');
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        tokenStore: store,
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Technische basis gereed'), findsOneWidget);
    expect(find.text('robbertvdzon@gmail.com'), findsOneWidget);
    expect(find.textContaining('verzonnen vergaderdata'), findsNothing);

    await tester.tap(find.text('Over deze versie'));
    await tester.pumpAndSettle();
    expect(find.text('Backend'), findsOneWidget);
    expect(find.textContaining('0.1.0+abcdef123456'), findsOneWidget);
  });

  testWidgets('shows Google login state without a stored session', (
    tester,
  ) async {
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        tokenStore: MemoryTokenStore(),
        loginBuilder: (onToken) => FilledButton(
          onPressed: () => onToken('new-token'),
          child: const Text('Test Google-login'),
        ),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('Log in met een toegestaan Google-account.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Test Google-login'));
    await tester.pumpAndSettle();
    expect(find.text('Technische basis gereed'), findsOneWidget);
  });

  testWidgets('shell remains usable at 320 pixels', (tester) async {
    tester.view.physicalSize = const Size(320, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      PvddApp(
        authenticationGateway: FakeAuthenticationGateway(),
        tokenStore: MemoryTokenStore()..write('valid-token'),
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byTooltip('Menu openen'), findsOneWidget);
  });
}

class MemoryTokenStore implements TokenStore {
  String? value;
  @override
  void clear() => value = null;
  @override
  String? read() => value;
  @override
  void write(String value) => this.value = value;
}

class FakeAuthenticationGateway implements AuthenticationGateway {
  @override
  Future<AuthenticatedUser> me(String idToken) async =>
      const AuthenticatedUser('robbertvdzon@gmail.com');
}

class FakeVersionGateway implements VersionGateway {
  @override
  Future<BuildIdentity> backend() async => const BuildIdentity(
    version: '0.1.0',
    gitRevision: 'abcdef123456abcdef123456abcdef123456abcd',
    buildTime: '2026-08-31T10:00:00Z',
    environment: 'local',
    identity: '0.1.0+abcdef123456',
  );
}

class FakeFrontendVersionSource implements FrontendVersionSource {
  @override
  Future<BuildIdentity> latest() async => BuildIdentity.frontend();
}
