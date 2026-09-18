/// Padherkenning voor het alleen-lezen archiefscherm.
///
/// Bewust een losse, pure functie zonder Flutter-afhankelijkheid, zodat de routering te testen is
/// zonder de app te starten. Alleen `/archief/<vergadering-id>` met een geldige UUID telt als
/// archiefpad; alles anders levert `null` en valt daarmee terug op de agendaweergave.
library;

final RegExp _archivePath = RegExp(
  r'^/archief/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/?$',
);

/// De vergadering-id in [path], of `null` als dit geen archiefpad is.
String? archivedMeetingIdFromPath(String path) =>
    _archivePath.firstMatch(path)?.group(1)?.toLowerCase();
