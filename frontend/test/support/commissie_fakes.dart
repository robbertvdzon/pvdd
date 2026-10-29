// Commissie-bewuste testvoorziening voor de widgettests van de commissiekeuze.
//
// Geen netwerk, uitsluitend synthetische gegevens. De fake registreert elke leesaanroep mét de
// meegegeven commissie in [calls] (bijvoorbeeld `overview:BESTUUR`), zodat een test kan aantonen
// dat alleen de gekozen commissie is geladen en dat er geen schrijfverkeer was. Antwoorden kunnen
// per aanroep worden vertraagd ([hold]) en per commissie worden laten falen.
import 'dart:async';

import 'package:pvdd_frontend/dashboard_api.dart';

enum BestuurAgenda { unpublished, published }

final DateTime ruimteStart = DateTime(2026, 11, 2, 10);
final DateTime bestuurStart = DateTime(2026, 11, 30);

class CommissieDashboardGateway implements DashboardGateway {
  CommissieDashboardGateway({this.bestuur = BestuurAgenda.published});

  /// Toestand van de Bestuur-agenda.
  BestuurAgenda bestuur;

  /// Alle leesaanroepen met commissie: `overview:RUIMTE`, `agendaItems:<meeting>:BESTUUR`, ...
  final List<String> calls = <String>[];

  /// Aanroepen die nieuw werk zouden starten; hoort leeg te blijven bij wisselen en terugkijken.
  final List<String> writes = <String>[];

  /// Sleutels (bv. `overview:RUIMTE`) waarvan het antwoord pas komt na `release`.
  final Map<String, Completer<void>> _gates = {};

  /// Commissies waarvoor een leesaanroep faalt.
  final Set<String> failing = <String>{};

  /// Het antwoord van `checkNow`.
  MeetingCheckOutcome? checkNowOutcome;

  /// Laat de eerstvolgende aanroep met deze sleutel wachten tot [release] is aangeroepen.
  void hold(String key) => _gates[key] = Completer<void>();

  void release(String key) => _gates.remove(key)?.complete();

  Future<void> _enter(String call, String commissie) async {
    calls.add(call);
    final gate = _gates[call];
    if (gate != null) await gate.future;
    if (failing.contains(commissie)) throw const DashboardUnavailable();
  }

  static String _prefix(String commissie) =>
      commissie == commissieBestuur ? 'bestuur' : 'ruimte';

  MeetingInfo _meeting(String commissie, {required bool unpublished}) {
    final bestuurSide = commissie == commissieBestuur;
    return MeetingInfo(
      id: '${_prefix(commissie)}-m',
      title: bestuurSide ? 'Vergadering Bestuur' : 'Vergadering Ruimte',
      startsAt: bestuurSide ? bestuurStart : ruimteStart,
      endsAt: unpublished
          ? null
          : (bestuurSide
                ? bestuurStart.add(const Duration(hours: 17))
                : ruimteStart.add(const Duration(hours: 6))),
      location: 'Provinciehuis Haarlem',
      sourceUrl: Uri.parse('https://example.test/agenda'),
      status: unpublished ? 'AGENDA_UNPUBLISHED' : 'ANALYSING',
      publicationStatus: 'CURRENT',
      revisionNumber: 1,
      canonicalFingerprint: null,
      revisionStatus: 'CURRENT',
      commissieName: commissieShortName(commissie),
    );
  }

  bool _unpublished(String commissie) =>
      commissie == commissieBestuur && bestuur == BestuurAgenda.unpublished;

  MeetingOverview _overview(String commissie) {
    final unpublished = _unpublished(commissie);
    return MeetingOverview(
      status: unpublished ? 'AGENDA_UNPUBLISHED' : 'ANALYSING',
      meeting: _meeting(commissie, unpublished: unpublished),
      lastCheckedAt: DateTime.utc(2026, 10, 29),
      progress: Progress(unpublished ? 0 : 3, 1, 0),
      agendaStatus: unpublished ? 'UNPUBLISHED' : 'PUBLISHED',
    );
  }

  @override
  Future<MeetingOverview> overview({String commissie = commissieRuimte}) async {
    await _enter('overview:$commissie', commissie);
    return _overview(commissie);
  }

  @override
  Future<MeetingOverview> meeting(
    String meetingId, {
    String commissie = commissieRuimte,
  }) async {
    await _enter('meeting:$meetingId:$commissie', commissie);
    return _overview(commissie);
  }

  @override
  Future<PastMeetingPage> pastMeetings({
    String? cursor,
    String commissie = commissieRuimte,
  }) async {
    await _enter('pastMeetings:$commissie', commissie);
    final items = pastByCommissie[commissie] ?? const <PastMeeting>[];
    return PastMeetingPage(items: items, nextCursor: null, total: items.length);
  }

  /// Eerdere vergaderingen per commissie; standaard leeg.
  final Map<String, List<PastMeeting>> pastByCommissie = {};

  static PastMeeting past(String commissie, String title, {int day = 5}) =>
      PastMeeting(
        id: '${_prefix(commissie)}-past-$day',
        title: title,
        startsAt: DateTime(2026, 9, day, 19, 30),
        location: 'Provinciehuis Haarlem',
        substantiveItemCount: 4,
        completedAdviceCount: 3,
      );

  static AgendaItemSummary item(
    String id,
    String category,
    String title,
    String commissie, {
    String documentStatus = 'DOCUMENTS_READY',
    bool advice = true,
  }) => AgendaItemSummary(
    id: id,
    sequence: 1,
    displayNumber: id.substring(id.length - 1),
    category: category,
    title: title,
    substantive: true,
    importStatus: 'COMPLETE',
    analysisStatus: advice ? 'SUCCEEDED' : null,
    documentStatus: documentStatus,
    documentCount: documentStatus == 'NO_DOCUMENTS' ? 0 : 1,
    readableDocumentCount: documentStatus == 'NO_DOCUMENTS' ? 0 : 1,
    sourceState: 'CURRENT',
    currentFingerprint: null,
    adviceActuality: advice ? 'CURRENT' : null,
    changeTypes: const [],
    commissieName: commissieShortName(commissie),
  );

  static List<AgendaItemSummary> itemsOf(String commissie) =>
      commissie == commissieBestuur
      ? [
          item('b-a-3', 'A', 'Jaarrekening 2025 provincie', commissie),
          item('b-b-5', 'B', 'Evaluatie bestuurlijke samenwerking', commissie),
          item('b-c-8', 'C', 'Ingekomen brief gemeente', commissie),
          item(
            'b-d-9',
            'A',
            'Statenvoorstel vergaderstructuur',
            commissie,
            documentStatus: 'NO_DOCUMENTS',
            advice: false,
          ),
        ]
      : [
          item('r-a-4', 'A', 'Programma Natuurnetwerk', commissie),
          item('r-b-6', 'B', 'Woningbouwafspraken', commissie),
        ];

  @override
  Future<List<AgendaItemSummary>> agendaItems(
    String meetingId, {
    String commissie = commissieRuimte,
  }) async {
    await _enter('agendaItems:$meetingId:$commissie', commissie);
    return _unpublished(commissie) ? const [] : itemsOf(commissie);
  }

  static const bestuurConcept =
      '## Samenvatting\n\nBestuursstuk over de jaarrekening.\n\n'
      '## Aansluiting bij PvdD-bronnen\n\nSluit aan bij het verkiezingsprogramma.\n\n'
      '## Aandachtspunten\n\nControleer de reserveposities.\n\n'
      '## Mogelijke inzet\n\nStel vragen over de reserves.\n\n'
      '## Stukken\n\nJaarrekening.pdf\n\n'
      '## Bronverwijzingen\n\nProgramma p. 12';

  @override
  Future<AgendaItemDetail> agendaItem(
    String itemId, {
    String commissie = commissieRuimte,
  }) async {
    await _enter('agendaItem:$itemId:$commissie', commissie);
    final summary = itemsOf(commissie).firstWhere((i) => i.id == itemId);
    final bestuurSide = commissie == commissieBestuur;
    return AgendaItemDetail(
      item: summary,
      explanation: null,
      treatmentProposal: null,
      sourceUrl: Uri.parse('https://example.test/item'),
      advice: summary.adviceActuality == null
          ? null
          : {
              'content': summary.category == 'C'
                  ? '## Beoordeling\n\nBespreking te overwegen: ja.'
                  : bestuurSide
                  ? bestuurConcept
                  : '## Ruimteadvies\n\nAdvies voor Ruimte.',
            },
      adviceActuality: summary.adviceActuality,
      sources: bestuurSide && summary.documentCount > 0
          ? [
              SourceLink(
                'Jaarrekening.pdf',
                Uri.parse('https://example.test/jaarrekening.pdf'),
                'EXTRACTED',
              ),
            ]
          : const [],
      warning: 'AI-concept — controleer bronnen en formulering vóór gebruik',
    );
  }

  /// Adviesversies per agendapunt; leeg tenzij een test ze zet.
  final Map<String, List<AdviceVersion>> versions = {};

  @override
  Future<List<AdviceVersion>> adviceVersions(
    String itemId, {
    String commissie = commissieRuimte,
  }) async {
    await _enter('adviceVersions:$itemId:$commissie', commissie);
    return versions[itemId] ?? const [];
  }

  @override
  Future<MeetingCheckOutcome> checkNow() async {
    writes.add('checkNow');
    return checkNowOutcome ??
        const MeetingCheckOutcome(
          status: 'UNCHANGED',
          revisionNumber: 1,
          differences: [],
        );
  }

  @override
  Future<void> retryAnalysis(String itemId) async =>
      writes.add('retryAnalysis:$itemId');
}
