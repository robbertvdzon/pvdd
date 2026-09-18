import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/archive_route.dart';

void main() {
  test('recognises /archief/<vergadering-id> without starting the app', () {
    expect(
      archivedMeetingIdFromPath(
        '/archief/6c9ad377-5837-41b7-9f68-573ccf58c859',
      ),
      '6c9ad377-5837-41b7-9f68-573ccf58c859',
    );
    expect(
      archivedMeetingIdFromPath(
        '/archief/6C9AD377-5837-41B7-9F68-573CCF58C859/',
      ),
      '6c9ad377-5837-41b7-9f68-573ccf58c859',
    );
  });

  test('the overview path is recognised on its own', () {
    expect(isArchiveOverviewPath('/archief'), isTrue);
    expect(isArchiveOverviewPath('/archief/'), isTrue);
    for (final path in [
      '/agenda',
      '/archief/6c9ad377-5837-41b7-9f68-573ccf58c859',
      '/archief/niet-een-uuid',
      '/standpunten',
      'archief',
    ]) {
      expect(isArchiveOverviewPath(path), isFalse, reason: path);
    }
  });

  test('every other path falls back to the agenda view', () {
    for (final path in [
      '/agenda',
      '/archief',
      '/archief/',
      '/archief/niet-een-uuid',
      '/archief/6c9ad377-5837-41b7-9f68-573ccf58c859/extra',
      '/standpunten',
      '/instellingen',
      'archief/6c9ad377-5837-41b7-9f68-573ccf58c859',
    ]) {
      expect(archivedMeetingIdFromPath(path), isNull, reason: path);
    }
  });
}
