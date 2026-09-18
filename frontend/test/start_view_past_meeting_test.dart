// De startweergave wanneer er geen toekomstige vergadering meer is en de server terugvalt op de
// meest recente vergadering die al is geweest: de melding boven de vergaderkaart, de markering op
// de kaart, een werkende 'Nu controleren', de doorstap naar het overzicht van eerdere
// vergaderingen, en het uitblijven van elke actie die nieuw werk zou starten.
//
// Alle tests draaien op de gedeelde `FakeDashboardGateway`, die elke aanroep registreert en geen
// enkel netwerkverkeer doet. De markering komt uit het antwoordveld `past`, nooit uit de klok van
// de testmachine: de gezaaide vergaderdatum is in alle gevallen dezelfde.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pvdd_frontend/main.dart';
import 'package:pvdd_frontend/meeting_overview.dart';

import 'support/fakes.dart';

// Lokale tijd, zodat de weergegeven datum niet van de tijdzone van de testmachine afhangt.
final meetingStart = DateTime(2026, 9, 7, 19, 30);
final meetingDate = meetingLongDate(meetingStart);

const noticeHeading = 'Er is nog geen nieuwe agenda';
const stepToArchive = 'Alle eerdere vergaderingen';
const emptyNotice = 'Er is nog geen toekomstige vergadering gevonden.';

void main() {
  testWidgets(
    'a past meeting is announced with its date above the meeting card',
    (tester) async {
      final gateway = pastMeetingGateway();
      await pumpStartView(tester, gateway, navigated: []);

      expect(find.text(noticeHeading), findsOneWidget);
      expect(
        find.textContaining(
          'Je ziet de meest recente vergadering, en die is al geweest: '
          '$meetingDate.',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Zodra de provincie een nieuwe agenda publiceert, staat die hier.',
        ),
        findsOneWidget,
      );

      // De markering naast de bestaande statusbadges op de vergaderkaart.
      expect(find.text('AL GEWEEST'), findsOneWidget);

      // De melding staat boven de vergaderkaart, niet eronder.
      expect(
        tester.getTopLeft(find.text(noticeHeading)).dy,
        lessThan(tester.getTopLeft(find.text('AL GEWEEST')).dy),
      );
      expect(
        tester.getTopLeft(find.text(noticeHeading)).dy,
        lessThan(
          tester.getTopLeft(find.text('Commissie Ruimte 7 september 2026')).dy,
        ),
      );

      // De voortgangstelling van de getoonde vergadering blijft ongewijzigd berekend.
      expect(find.text('1/3 analyses gereed'), findsOneWidget);
    },
  );

  testWidgets('check now stays available and performs exactly one check', (
    tester,
  ) async {
    final gateway = pastMeetingGateway();
    await pumpStartView(tester, gateway, navigated: []);

    final checkNow = find.widgetWithText(FilledButton, 'Nu controleren');
    expect(checkNow, findsOneWidget);
    expect(tester.widget<FilledButton>(checkNow).onPressed, isNotNull);

    await tester.tap(checkNow);
    await tester.pumpAndSettle();

    expect(gateway.calls.where((call) => call == 'checkNow'), hasLength(1));
    expect(
      find.text('De bron is gecontroleerd en ongewijzigd.'),
      findsOneWidget,
    );
  });

  testWidgets('offers no action that starts new work and only reads', (
    tester,
  ) async {
    // `failedAnalysis` geeft het eerste agendapunt `canRetryAnalysis`; op een toekomstige
    // vergadering levert dat de knop 'Opnieuw proberen' op.
    final gateway = pastMeetingGateway(failedAnalysis: true);
    await pumpStartView(tester, gateway, navigated: []);

    expect(find.text('Opnieuw proberen'), findsNothing);

    await tester.ensureVisible(find.text('Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Natuurinclusief wonen'));
    await tester.pumpAndSettle();
    expect(find.text('Opnieuw proberen'), findsNothing);

    // De volledige aanroeplijst is het bewijs: uitsluitend leesroutes, geen analyseaanvraag.
    expect(gateway.calls, [
      'overview',
      'agendaItems:meeting-id',
      'agendaItem:item-a',
      'adviceVersions:item-a',
    ]);
    expect(gateway.startingCalls, isEmpty);
  });

  testWidgets('the notice steps through to the earlier meetings in one action', (
    tester,
  ) async {
    final gateway = pastMeetingGateway();
    final navigated = <String>[];
    await pumpStartView(tester, gateway, navigated: navigated);

    expect(find.text(stepToArchive), findsOneWidget);
    await tester.tap(find.text(stepToArchive));
    await tester.pumpAndSettle();

    expect(navigated, ['/archief']);
    expect(
      find.text('Vergaderingen die al zijn geweest, de meest recente bovenaan.'),
      findsOneWidget,
    );
    expect(gateway.calls, contains('pastMeetings:first'));
  });

  testWidgets('an upcoming meeting leaves the start view unchanged', (
    tester,
  ) async {
    // Dezelfde vergaderdatum als in de voorbije gevallen; alleen het antwoordveld `past` verschilt,
    // zodat bewezen is dat de melding niet uit de klok van de testmachine komt.
    final gateway = FakeDashboardGateway(
      withMeeting: true,
      failedAnalysis: true,
      startsAt: meetingStart,
      title: 'Commissie Ruimte 7 september 2026',
    );
    await pumpStartView(tester, gateway, navigated: []);

    expect(find.text(noticeHeading), findsNothing);
    expect(find.text(stepToArchive), findsNothing);
    expect(find.text('AL GEWEEST'), findsNothing);

    // De bestaande acties staan er onveranderd.
    expect(find.text('Nu controleren'), findsOneWidget);
    expect(find.text('Eerdere vergaderingen'), findsOneWidget);
    expect(find.text('Opnieuw proberen'), findsOneWidget);
  });

  testWidgets('without any meeting the existing empty notice stays alone', (
    tester,
  ) async {
    final gateway = FakeDashboardGateway();
    await pumpStartView(tester, gateway, navigated: []);

    expect(find.text(emptyNotice), findsOneWidget);
    expect(find.text(noticeHeading), findsNothing);
    expect(find.text(stepToArchive), findsNothing);
    expect(find.text('AL GEWEEST'), findsNothing);
    expect(find.text('Nu controleren'), findsOneWidget);
  });

  for (final size in const [Size(360, 1400), Size(1400, 1200)]) {
    testWidgets('stays usable and complete at ${size.width.toInt()} pixels', (
      tester,
    ) async {
      final gateway = pastMeetingGateway();
      await pumpStartView(tester, gateway, navigated: [], size: size);

      expect(find.text(noticeHeading), findsOneWidget);
      expect(
        find.textContaining('en die is al geweest: $meetingDate.'),
        findsOneWidget,
      );
      expect(find.text('AL GEWEEST'), findsOneWidget);
      expect(find.text('Nu controleren'), findsOneWidget);
      expect(find.text(stepToArchive), findsOneWidget);
      // Geen inklapper en geen afgekorte tekst: de uitleg staat er voluit.
      expect(
        find.textContaining(
          'Zodra de provincie een nieuwe agenda publiceert, staat die hier.',
        ),
        findsOneWidget,
      );
      // Op mobiel loopt de doorstap over de volle breedte mee met de knoppen erboven; op desktop
      // houdt hij zijn eigen breedte. In beide gevallen past hij zonder overflow.
      final stepWidth = tester
          .getSize(find.widgetWithText(OutlinedButton, stepToArchive))
          .width;
      expect(
        stepWidth,
        size.width < 600
            ? greaterThan(size.width * 0.6)
            : lessThan(size.width * 0.5),
      );
      expect(tester.takeException(), isNull);
    });
  }
}

FakeDashboardGateway pastMeetingGateway({bool failedAnalysis = false}) =>
    FakeDashboardGateway(
      withMeeting: true,
      past: true,
      failedAnalysis: failedAnalysis,
      startsAt: meetingStart,
      title: 'Commissie Ruimte 7 september 2026',
    );

/// De startweergave binnen de gewone shell, zodat de doorstap dezelfde navigatie gebruikt als de
/// knop in de kopregel. [navigated] legt de aangeroepen routes vast; er draait geen browser.
Future<void> pumpStartView(
  WidgetTester tester,
  FakeDashboardGateway gateway, {
  required List<String> navigated,
  Size size = const Size(1200, 2400),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      home: TechnicalApplicationShell(
        email: 'robbertvdzon@gmail.com',
        onLogout: () {},
        versionGateway: FakeVersionGateway(),
        frontendVersionSource: FakeFrontendVersionSource(),
        dashboardGateway: gateway,
        aiRunsGateway: UnusedAiRunsGateway(),
        settingsGateway: UnusedSettingsGateway(),
        acceptanceBypass: false,
        appPath: () => '/agenda',
        navigate: navigated.add,
      ),
    ),
  );
  await tester.pumpAndSettle();
}
