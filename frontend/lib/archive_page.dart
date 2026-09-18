import 'dart:async';

import 'package:flutter/material.dart';

import 'dashboard_api.dart';
import 'pvdd_theme.dart';

/// Het alleen-lezen overzicht van bewaarde vergaderingen die al zijn geweest, op `/archief`.
///
/// Het scherm doet uitsluitend GET-verkeer naar de lijstroute: er is geen actie die nieuw werk
/// start en geen ververstimer. Bijladen gebeurt op verzoek, met ontdubbeling op vergadering-id
/// volgens hetzelfde patroon als de AI-runlijst.
class ArchivePage extends StatefulWidget {
  const ArchivePage({
    required this.gateway,
    required this.onBack,
    required this.onOpen,
    super.key,
  });

  final DashboardGateway gateway;

  /// Terug naar de agendaweergave.
  final VoidCallback onBack;

  /// Opent één bewaarde vergadering; de shell navigeert naar `/archief/<vergadering-id>`.
  final void Function(String meetingId) onOpen;

  @override
  State<ArchivePage> createState() => _ArchivePageState();
}

class _ArchivePageState extends State<ArchivePage> {
  List<PastMeeting> _meetings = const [];

  /// De cursor voor de volgende pagina; `null` betekent dat er geen volgende pagina is.
  String? _cursor;
  int _total = 0;
  bool _loading = true;
  bool _loadingMore = false;
  String? _error;

  /// De cursor van de mislukte aanvraag, zodat 'Opnieuw proberen' exact dezelfde aanvraag herhaalt.
  String? _failedCursor;

  @override
  void initState() {
    super.initState();
    unawaited(_loadPage());
  }

  /// Haalt één pagina op. Geslaagde pagina's worden samengevoegd met ontdubbeling op
  /// vergadering-id, zodat al geladen rijen nooit worden weggegooid of verdubbeld.
  Future<void> _loadPage({String? cursor}) async {
    if (mounted) {
      setState(() {
        if (cursor == null) {
          _loading = _meetings.isEmpty;
        } else {
          _loadingMore = true;
        }
      });
    }
    try {
      final page = await widget.gateway.pastMeetings(cursor: cursor);
      if (!mounted) return;
      setState(() {
        _meetings = [
          ..._meetings,
          ...page.items.where(
            (item) => !_meetings.any((existing) => existing.id == item.id),
          ),
        ];
        _cursor = page.nextCursor;
        _total = page.total;
        _error = null;
        _failedCursor = null;
        _loading = false;
        _loadingMore = false;
      });
    } on Object {
      if (!mounted) return;
      setState(() {
        _error = 'Niet alle eerdere vergaderingen konden worden geladen';
        _failedCursor = cursor;
        _loading = false;
        _loadingMore = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 980),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: widget.onBack,
                  icon: const Icon(Icons.chevron_left),
                  label: const Text('Terug naar de agenda'),
                ),
              ),
              Text(
                'Eerdere vergaderingen',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 4),
              const Text(
                'Vergaderingen die al zijn geweest, de meest recente bovenaan.',
              ),
              if (_error != null) ...[
                const SizedBox(height: 16),
                _errorNotice(context),
              ],
              const SizedBox(height: 16),
              _readOnlyNotice(context),
              const SizedBox(height: 16),
              if (_loading)
                const Padding(
                  padding: EdgeInsets.all(40),
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (_meetings.isEmpty && _error == null)
                _emptyState(context)
              else ...[
                if (_meetings.isNotEmpty) _list(context),
                if (_cursor != null && _error == null) ...[
                  const SizedBox(height: 16),
                  Align(
                    alignment: Alignment.center,
                    child: OutlinedButton(
                      onPressed: _loadingMore
                          ? null
                          : () => unawaited(_loadPage(cursor: _cursor)),
                      child: Text(
                        _loadingMore ? 'Laden…' : 'Meer vergaderingen laden',
                      ),
                    ),
                  ),
                ],
                if (_meetings.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(
                    '${_meetings.length} van $_total vergaderingen '
                    '${_error == null ? 'getoond' : 'geladen'}',
                    textAlign: TextAlign.center,
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    ],
  );

  // Alleen-lezen mededeling, bovenaan het overzicht: openen leest terug en start nooit werk.
  Widget _readOnlyNotice(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerLow,
      border: Border.all(color: PvddColors.outline),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Icon(Icons.lock_outline, color: PvddColors.ink),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: const [
              Text(
                'Alleen lezen',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              SizedBox(height: 4),
              Text(
                'Je leest hier bewaarde adviezen terug. Openen start nooit een nieuwe analyse '
                'en verandert geen bestaand advies.',
              ),
            ],
          ),
        ),
      ],
    ),
  );

  // Fouttoestand voor zowel de eerste laadpoging als het bijladen. Al geladen rijen blijven staan;
  // 'Opnieuw proberen' herhaalt exact dezelfde aanvraag.
  Widget _errorNotice(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: colors.errorContainer,
          border: Border(left: BorderSide(color: colors.error, width: 6)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.error_outline, color: colors.error),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _error!,
                    style: TextStyle(
                      color: colors.error,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _meetings.isEmpty
                        ? 'Er is niets veranderd en er is geen advies verdwenen; probeer het zo opnieuw.'
                        : 'De vergaderingen hieronder zijn wel geladen en blijven staan. '
                              'Er is geen advies verdwenen.',
                  ),
                  const SizedBox(height: 12),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: OutlinedButton.icon(
                      onPressed: () =>
                          unawaited(_loadPage(cursor: _failedCursor)),
                      icon: const Icon(Icons.refresh),
                      label: const Text('Opnieuw proberen'),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Lege toestand: geen lege lijst, maar uitleg waarom er nog niets te zien is.
  Widget _emptyState(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        children: [
          const Icon(
            Icons.inventory_2_outlined,
            size: 44,
            color: PvddColors.primary,
          ),
          const SizedBox(height: 16),
          Text(
            'Er is nog geen vergadering voorbij',
            style: Theme.of(
              context,
            ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          const Text(
            'De assistent bewaart elke vergadering met haar agendapunten en adviezen. Zodra de '
            'eerstvolgende vergadering is geweest, vind je haar hier terug — met dezelfde '
            'weergave als bij de huidige agenda.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 20),
          OutlinedButton(
            onPressed: widget.onBack,
            child: const Text('Naar de huidige vergadering'),
          ),
        ],
      ),
    ),
  );

  Widget _list(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var index = 0; index < _meetings.length; index++) ...[
            if (index > 0)
              const Divider(height: 1, indent: 20, endIndent: 20),
            _row(context, _meetings[index]),
          ],
        ],
      ),
    ),
  );

  Widget _row(BuildContext context, PastMeeting meeting) => Padding(
    padding: const EdgeInsets.all(16),
    child: LayoutBuilder(
      builder: (context, constraints) {
        final details = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              meeting.title,
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 2),
            Text(meeting.location ?? 'Locatie onbekend'),
            const SizedBox(height: 2),
            Text(
              _countsLabel(meeting),
              style: const TextStyle(
                color: PvddColors.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        );
        final open = OutlinedButton(
          onPressed: () => widget.onOpen(meeting.id),
          child: const Text('Openen'),
        );
        // Op een smal scherm stapelen datumblok, titel, locatie en telling, en loopt 'Openen'
        // over de volle breedte.
        if (constraints.maxWidth < 620) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _dateBlock(context, meeting.startsAt),
                  const SizedBox(width: 16),
                  Expanded(child: details),
                ],
              ),
              const SizedBox(height: 12),
              SizedBox(width: double.infinity, child: open),
            ],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            _dateBlock(context, meeting.startsAt),
            const SizedBox(width: 16),
            Expanded(child: details),
            const SizedBox(width: 16),
            open,
          ],
        );
      },
    ),
  );

  Widget _dateBlock(BuildContext context, DateTime startsAt) {
    final local = startsAt.toLocal();
    return Container(
      width: 72,
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        border: Border.all(color: PvddColors.outline),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            local.day.toString().padLeft(2, '0'),
            style: const TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w800,
              color: PvddColors.ink,
            ),
          ),
          Text(
            _shortMonths[local.month - 1],
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          Text(
            '${local.year}',
            style: const TextStyle(fontSize: 12, color: Colors.black54),
          ),
        ],
      ),
    );
  }
}

const _shortMonths = [
  'jan',
  'feb',
  'mrt',
  'apr',
  'mei',
  'jun',
  'jul',
  'aug',
  'sep',
  'okt',
  'nov',
  'dec',
];

// De telling per vergadering, met exact dezelfde betekenis als de voortgangstelling op de agenda.
String _countsLabel(PastMeeting meeting) {
  final items = meeting.substantiveItemCount == 1
      ? '1 inhoudelijk agendapunt'
      : '${meeting.substantiveItemCount} inhoudelijke agendapunten';
  return '$items · ${meeting.completedAdviceCount} met afgerond advies';
}
