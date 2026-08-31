import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/build_identity.dart';
import 'package:pvdd_frontend/frontend_version_monitor.dart';

void main() {
  test('invalid build metadata is displayed as unknown', () {
    final identity = BuildIdentity.validated(
      version: 'latest',
      gitRevision: 'main',
      buildTime: 'today',
      environment: 'prod',
      identity: 'latest',
    );
    expect(identity.version, BuildIdentity.unknown);
    expect(identity.identity, BuildIdentity.unknown);
  });

  test('update tracker notifies only once for a new valid build', () {
    const current = BuildIdentity(
      version: '0.1.0',
      gitRevision: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      buildTime: '2026-08-31T10:00:00Z',
      environment: 'production',
      identity: '0.1.0+aaaaaaaaaaaa',
    );
    const latest = BuildIdentity(
      version: '0.1.0',
      gitRevision: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
      buildTime: '2026-08-31T10:01:00Z',
      environment: 'production',
      identity: '0.1.0+bbbbbbbbbbbb',
    );
    final tracker = VersionUpdateTracker();
    expect(tracker.shouldNotify(current, latest), isTrue);
    expect(tracker.shouldNotify(current, latest), isFalse);
  });
}
