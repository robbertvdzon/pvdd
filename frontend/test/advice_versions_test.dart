// De versiekeuze in de gedeelde detailweergave: kiezen tussen het laatste advies en een eerdere
// versie, de meldingen daarbij, het ontbreken van een bronnenlijst bij een eerdere versie en het
// bewijs dat er uitsluitend leesverkeer ontstaat. Alles draait op de gedeelde
// `FakeDashboardGateway`, die elke aanroep registreert en geen enkel netwerkverkeer doet.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/dashboard_api.dart';
import 'package:pvdd_frontend/meeting_overview.dart';

import 'support/fakes.dart';

const String itemTitle = 'Natuurinclusief wonen';
const String earlierVersionSources =
    'Bij deze eerdere versie is niet apart bewaard welke stukken toen zijn gebruikt';
const String aiReservation =
    'AI-concept — controleer bronnen en formulering vóór gebruik';
const String unreadableNotice =
    'Niet leesbaar en daarom niet gebruikt in de analyse';

void main() {
  testWidgets('two saved versions offer a choice with their dates', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    expect(find.text(adviceVersionLabel(versions[0], 0)), findsOneWidget);
    expect(find.text(adviceVersionLabel(versions[1], 1)), findsOneWidget);
    // De vaste woorden uit de UX-mockups, los van de datumopmaak.
    expect(find.textContaining('Laatste advies ·'), findsOneWidget);
    expect(find.textContaining('Eerdere versie ·'), findsOneWidget);

    // Bij het laatste advies staat onder de keuze welke versie dit advies verving en waarom.
    expect(
      find.text(
        'Dit advies verving de versie van '
        '${adviceVersionDate(earlierAdviceAt)}. '
        'Reden: de bron- of beleidscontext veranderde.',
      ),
      findsOneWidget,
    );
    expect(find.text('De afweging van toen.'), findsNothing);

    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    // Wisselen toont de inhoud van díe versie, en niet meer die van het laatste advies.
    expect(find.text('De afweging van toen.'), findsOneWidget);
    expect(find.textContaining('Dit advies verving de versie van'), findsNothing);

    await selectVersion(tester, adviceVersionLabel(versions[0], 0));
    expect(find.text('De afweging van toen.'), findsNothing);
  });

  testWidgets('an earlier version says when it was made and replaced', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);
    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    expect(
      find.text(
        'Je bekijkt een eerdere versie van ${adviceVersionDate(earlierAdviceAt)}',
      ),
      findsOneWidget,
    );
    expect(
      find.text(
        'Vervangen op ${adviceVersionDate(latestAdviceAt)}. '
        'Reden: de bron- of beleidscontext veranderde.',
      ),
      findsOneWidget,
    );

    // De statusbadge van het laatste advies maakt plaats voor de markering van de eerdere versie,
    // en de metadatatabel van het laatste advies blijft weg. De andere agendapunten op het scherm
    // houden hun eigen tabel, dus de controle kijkt alleen in deze kaart.
    expect(find.text('EERDERE VERSIE'), findsOneWidget);
    expect(inItemCard(find.text('Gereed')), findsNothing);
    expect(inItemCard(find.text('AI-titel')), findsNothing);
    expect(inItemCard(find.text('Laatste AI-analyse')), findsNothing);

    await selectVersion(tester, adviceVersionLabel(versions[0], 0));
    expect(find.text('EERDERE VERSIE'), findsNothing);
    expect(inItemCard(find.text('AI-titel')), findsOneWidget);
    expect(inItemCard(find.text('Laatste AI-analyse')), findsOneWidget);
  });

  testWidgets('collapsing the card returns it to the latest advice', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);
    await selectVersion(tester, adviceVersionLabel(versions[1], 1));
    expect(find.text('EERDERE VERSIE'), findsOneWidget);

    // Dichtklappen en opnieuw openen: de kaart beschrijft weer het agendapunt zelf.
    await tester.tap(find.text(itemTitle));
    await tester.pumpAndSettle();
    expect(find.text('EERDERE VERSIE'), findsNothing);
    expect(inItemCard(find.text('AI-titel')), findsOneWidget);

    await tester.tap(find.text(itemTitle));
    await tester.pumpAndSettle();
    expect(find.text(adviceVersionLabel(versions[1], 1)), findsOneWidget);
    expect(find.text('De afweging van toen.'), findsNothing);
    // De lijst was al opgehaald, dus opnieuw openen leest niets bij.
    expect(
      gateway.calls.where((call) => call.startsWith('adviceVersions')).length,
      1,
    );
  });

  testWidgets('a manually restarted advice names that reason', (tester) async {
    final versions = syntheticAdviceVersions(refreshReason: 'MANUAL_RETRY');
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    expect(
      find.text(
        'Dit advies verving de versie van '
        '${adviceVersionDate(earlierAdviceAt)}. '
        'Reden: handmatig opnieuw gestart.',
      ),
      findsOneWidget,
    );

    await selectVersion(tester, adviceVersionLabel(versions[1], 1));
    expect(
      find.text(
        'Vervangen op ${adviceVersionDate(latestAdviceAt)}. '
        'Reden: handmatig opnieuw gestart.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('one saved version says so instead of offering a choice', (
    tester,
  ) async {
    final versions = [
      syntheticAdviceVersion(createdAt: latestAdviceAt, latest: true),
    ];
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    expect(
      find.text(
        'Van dit agendapunt is geen eerdere versie bewaard. '
        'Je ziet het enige advies, gemaakt op '
        '${adviceVersionDate(latestAdviceAt)}.',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('Laatste advies ·'), findsNothing);
    expect(find.textContaining('Eerdere versie ·'), findsNothing);
    expect(find.byType(ChoiceChip), findsNothing);
    // Het laatste advies blijft volledig staan.
    expect(find.text('AI-titel'), findsWidgets);
    expect(find.text('Bronnen'), findsOneWidget);
  });

  testWidgets('without a saved version nothing about the view changes', (
    tester,
  ) async {
    final gateway = versionGateway(const []);
    await pumpDetail(tester, gateway);

    expect(find.textContaining('geen eerdere versie bewaard'), findsNothing);
    expect(find.byType(ChoiceChip), findsNothing);
    expect(find.text('AI-titel'), findsWidgets);
    expect(find.text('Bronnen'), findsOneWidget);
    expect(find.textContaining(aiReservation), findsOneWidget);
  });

  testWidgets('an earlier version without guidance shows it was not recorded', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions(
      latestGuidance: 'Let extra op de stikstofonderbouwing',
    );
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);
    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    expect(
      find.text('Aanvullende analyse-instructie van toen: niet vastgelegd.'),
      findsOneWidget,
    );
    // De instructie van het laatste advies hoort hier nergens te staan.
    expect(
      find.textContaining('Let extra op de stikstofonderbouwing'),
      findsNothing,
    );
  });

  testWidgets('an earlier version shows the guidance that applied then', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions(
      earlierGuidance: 'Weeg de natuurnormen zwaarder',
      latestGuidance: 'Let extra op de stikstofonderbouwing',
    );
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);
    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    expect(
      find.text(
        'Aanvullende analyse-instructie van toen: '
        'Weeg de natuurnormen zwaarder.',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Let extra op de stikstofonderbouwing'),
      findsNothing,
    );
  });

  testWidgets('an earlier version has no source list but says why', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    // Bij het laatste advies staat de bronnenlijst met de leesbaarheid per stuk.
    expect(find.text('Bronnen'), findsOneWidget);
    expect(find.text('voordracht.pdf · Gereed'), findsOneWidget);
    expect(
      find.text('bijlage-2-toelichting-woningbouw.pdf · Scan — OCR nodig'),
      findsOneWidget,
    );

    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    expect(find.text('Bronnen'), findsNothing);
    expect(find.text('voordracht.pdf · Gereed'), findsNothing);
    expect(
      find.text('bijlage-2-toelichting-woningbouw.pdf · Scan — OCR nodig'),
      findsNothing,
    );
    expect(find.textContaining(earlierVersionSources), findsOneWidget);
    expect(
      find.textContaining('hoort daarom alleen bij het laatste advies'),
      findsOneWidget,
    );
  });

  testWidgets('AI reservation and unreadable notice stay on every version', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    expect(find.textContaining(aiReservation), findsOneWidget);
    expect(find.textContaining(unreadableNotice), findsOneWidget);

    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    expect(find.textContaining(aiReservation), findsOneWidget);
    expect(find.textContaining(unreadableNotice), findsOneWidget);
  });

  for (final readOnly in const [false, true]) {
    testWidgets(
      'the same detail view offers the choice with readOnly $readOnly',
      (tester) async {
        final versions = syntheticAdviceVersions();
        final gateway = versionGateway(versions);
        await pumpDetail(tester, gateway, readOnly: readOnly);

        expect(find.text(adviceVersionLabel(versions[0], 0)), findsOneWidget);
        expect(find.text(adviceVersionLabel(versions[1], 1)), findsOneWidget);

        await selectVersion(tester, adviceVersionLabel(versions[1], 1));

        expect(find.text('EERDERE VERSIE'), findsOneWidget);
        expect(find.text('De afweging van toen.'), findsOneWidget);
        expect(find.textContaining(earlierVersionSources), findsOneWidget);
      },
    );
  }

  testWidgets('three saved versions each get their own option', (tester) async {
    final oldest = DateTime(2026, 8, 24, 11);
    final versions = [
      ...syntheticAdviceVersions(),
      syntheticAdviceVersion(
        createdAt: oldest,
        latest: false,
        actuality: 'WITHDRAWN',
        refreshReason: 'FIRST_ANALYSIS',
        summary: 'De oudste afweging.',
      ),
    ];
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    expect(find.byType(ChoiceChip), findsNWidgets(3));
    expect(find.textContaining('Eerdere versie ·'), findsNWidgets(2));

    await selectVersion(tester, adviceVersionLabel(versions[2], 2));

    expect(find.text('De oudste afweging.'), findsOneWidget);
    // Vervangen door de eerstvolgende nieuwere versie, niet door het laatste advies.
    expect(
      find.text(
        'Je bekijkt een eerdere versie van ${adviceVersionDate(oldest)}',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Vervangen op ${adviceVersionDate(earlierAdviceAt)}'),
      findsOneWidget,
    );
  });

  testWidgets('a first analysis leaves out the reason instead of a placeholder', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions(refreshReason: 'FIRST_ANALYSIS');
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    expect(
      find.text(
        'Dit advies verving de versie van '
        '${adviceVersionDate(earlierAdviceAt)}.',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('Reden:'), findsNothing);

    await selectVersion(tester, adviceVersionLabel(versions[1], 1));
    expect(
      find.text('Vervangen op ${adviceVersionDate(latestAdviceAt)}.'),
      findsOneWidget,
    );
    expect(find.textContaining('Reden:'), findsNothing);
  });

  testWidgets('a failing version request keeps the advice and offers a retry', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions)
      ..failingAdviceVersionItems.add('item-a');
    await pumpDetail(tester, gateway);

    // Het laatste advies blijft volledig staan; er verdwijnt geen advies.
    expect(find.text('AI-titel'), findsWidgets);
    expect(find.text('Bronnen'), findsOneWidget);
    expect(find.textContaining(aiReservation), findsOneWidget);
    expect(
      find.textContaining('De adviesversies konden niet worden geladen'),
      findsOneWidget,
    );
    expect(find.byType(ChoiceChip), findsNothing);

    gateway.failingAdviceVersionItems.remove('item-a');
    await tester.ensureVisible(find.text('Versies opnieuw laden'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Versies opnieuw laden'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('De adviesversies konden niet worden geladen'),
      findsNothing,
    );
    expect(find.text(adviceVersionLabel(versions[1], 1)), findsOneWidget);
    expect(
      gateway.calls.where((call) => call == 'adviceVersions:item-a').length,
      2,
    );
  });

  testWidgets('reading and switching versions causes read traffic only', (
    tester,
  ) async {
    final versions = syntheticAdviceVersions();
    final gateway = versionGateway(versions);
    await pumpDetail(tester, gateway);

    // Na het uitklappen is er precies één versieaanvraag gedaan.
    expect(
      gateway.calls.where((call) => call.startsWith('adviceVersions')).length,
      1,
    );

    await selectVersion(tester, adviceVersionLabel(versions[1], 1));
    await selectVersion(tester, adviceVersionLabel(versions[0], 0));
    await selectVersion(tester, adviceVersionLabel(versions[1], 1));

    // Wisselen leest uit de al opgehaalde lijst en doet geen enkele extra aanroep.
    expect(
      gateway.calls.where((call) => call.startsWith('adviceVersions')).length,
      1,
    );
    expect(gateway.startingCalls, isEmpty);
    expect(gateway.retriedItemId, isNull);
    expect(
      gateway.calls.every(
        (call) => const [
          'overview',
          'meeting',
          'agendaItems',
          'agendaItem',
          'adviceVersions',
        ].any((read) => call == read || call.startsWith('$read:')),
      ),
      isTrue,
      reason: gateway.calls.toString(),
    );
  });

  for (final size in const [Size(400, 3000), Size(1280, 3000)]) {
    testWidgets('choice and notices stay usable at ${size.width}', (
      tester,
    ) async {
      final versions = syntheticAdviceVersions();
      final gateway = versionGateway(versions);
      await pumpDetail(tester, gateway, size: size);

      expect(find.text(adviceVersionLabel(versions[0], 0)), findsOneWidget);
      expect(find.text(adviceVersionLabel(versions[1], 1)), findsOneWidget);
      expect(
        find.textContaining('Dit advies verving de versie van'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);

      await selectVersion(tester, adviceVersionLabel(versions[1], 1));

      expect(find.text('EERDERE VERSIE'), findsOneWidget);
      expect(
        find.textContaining('Je bekijkt een eerdere versie van'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Aanvullende analyse-instructie van toen'),
        findsOneWidget,
      );
      expect(find.textContaining(earlierVersionSources), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}

/// Een gateway met een vergadering, bronnen met leesbaarheidsstatus en [versions] als bewaarde
/// adviesversies van `item-a`.
FakeDashboardGateway versionGateway(List<AdviceVersion> versions) =>
    FakeDashboardGateway(withMeeting: true, withSources: true)
      ..adviceVersionsByItem['item-a'] = versions;

/// Bouwt de gedeelde detailweergave op en klapt het eerste agendapunt uit.
///
/// Met [readOnly] komt dezelfde weergave als alleen-lezen archiefscherm in beeld, zodat de tests de
/// keuze in beide varianten kunnen toetsen zonder een archiefroute nodig te hebben.
Future<void> pumpDetail(
  WidgetTester tester,
  FakeDashboardGateway gateway, {
  bool readOnly = false,
  Size size = const Size(1280, 3000),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: readOnly
            ? MeetingOverviewPage(
                gateway: gateway,
                archivedMeetingId: 'meeting-id',
                readOnly: true,
                onBack: () {},
              )
            : MeetingOverviewPage(gateway: gateway),
      ),
    ),
  );
  await tester.pumpAndSettle();
  // Eerst in beeld brengen en pompen, dan tikken: `ensureVisible` scrollt alleen en de tik zou
  // anders op de oude positie landen.
  await tester.ensureVisible(find.text(itemTitle));
  await tester.pumpAndSettle();
  await tester.tap(find.text(itemTitle));
  await tester.pumpAndSettle();
}

/// Beperkt een zoekactie tot de kaart van het uitgeklapte agendapunt; de andere agendapunten op
/// hetzelfde scherm houden hun eigen metadatatabel en statusbadges.
Finder inItemCard(Finder matching) => find.descendant(
  of: find.ancestor(of: find.text(itemTitle), matching: find.byType(Card)).first,
  matching: matching,
);

/// Kiest een versie in de keuze boven de analyse.
Future<void> selectVersion(WidgetTester tester, String label) async {
  await tester.ensureVisible(find.text(label));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
}
