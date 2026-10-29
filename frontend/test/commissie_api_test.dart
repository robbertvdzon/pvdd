// De api-client geeft de commissie mee aan alle leesroutes, en leest de per-commissie
// controle-uitkomst en de commissienaam uit de antwoorden.
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pvdd_frontend/dashboard_api.dart';

void main() {
  test('every read route carries the commissie parameter', () async {
    final urls = <String>[];
    final client = MockClient((request) async {
      urls.add(request.url.toString());
      final path = request.url.path;
      final body = path.endsWith('/advice-versions')
          ? '{"versions":[]}'
          : path.endsWith('/agenda-items')
          ? '[]'
          : path == '/api/meetings'
          ? '{"items":[],"nextCursor":null,"total":0}'
          : '{"status":"NO_MEETING","meeting":null,"lastCheckedAt":null,'
                '"progress":{"total":0,"complete":0,"failed":0}}';
      return http.Response(body, 200);
    });
    final gateway = HttpDashboardGateway(client: client);
    await gateway.overview(commissie: commissieBestuur);
    await gateway.meeting('m1', commissie: commissieBestuur);
    await gateway.agendaItems('m1', commissie: commissieBestuur);
    await gateway.adviceVersions('i1', commissie: commissieBestuur);
    await gateway.pastMeetings(commissie: commissieBestuur);
    await gateway.overview();
    expect(urls.take(5), everyElement(contains('commissie=BESTUUR')));
    expect(urls.last, endsWith('commissie=RUIMTE'));
  });

  test('check outcome reads the result per commissie', () {
    final outcome = MeetingCheckOutcome.fromJson({
      'status': 'UNCHANGED',
      'revisionNumber': 1,
      'commissies': [
        {'commissie': 'RUIMTE', 'naam': 'Ruimte', 'success': true},
        {'commissie': 'BESTUUR', 'naam': 'Bestuur', 'success': false},
      ],
    });
    expect(outcome.hasCommissieFailure, isTrue);
    expect(outcome.wholeCheckFailed, isFalse);
    expect(outcome.commissies.last.name, 'Bestuur');
    expect(
      MeetingCheckOutcome.fromJson({'status': 'UNCHANGED'}).commissies,
      isEmpty,
    );
  });
}
