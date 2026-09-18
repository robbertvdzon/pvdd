import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import 'dashboard_api.dart';
import 'external_link.dart';
import 'pvdd_theme.dart';

class MeetingOverviewPage extends StatefulWidget {
  const MeetingOverviewPage({
    required this.gateway,
    this.archivedMeetingId,
    this.readOnly = false,
    this.onBack,
    this.backLabel = 'Terug naar agenda',
    this.onOpenArchive,
    super.key,
  });
  final DashboardGateway gateway;

  /// Is deze gezet, dan toont de pagina die ene vergadering via `gateway.meeting(id)` in plaats van
  /// de eerstvolgende vergadering, en blijft de ververstimer uit.
  final String? archivedMeetingId;

  /// Alleen lezen: geen 'Nu controleren' en geen herstartactie per agendapunt. De agendaweergave
  /// zelf (filters, vergaderkaart, agendapuntkaarten en detailweergave) blijft dezelfde code.
  final bool readOnly;

  /// Terugactie boven aan het scherm; alleen zichtbaar wanneer die is meegegeven.
  final VoidCallback? onBack;

  /// Het label van de terugactie. Standaard de agendaweergave; kwam de gebruiker van het
  /// archiefoverzicht, dan geeft de shell hier het bijbehorende label mee.
  final String backLabel;

  /// Opent het overzicht van eerdere vergaderingen. Is die meegegeven, dan verschijnt naast
  /// 'Nu controleren' de knop 'Eerdere vergaderingen'.
  final VoidCallback? onOpenArchive;

  @override
  State<MeetingOverviewPage> createState() => _MeetingOverviewPageState();
}

class _MeetingOverviewPageState extends State<MeetingOverviewPage> {
  MeetingOverview? _overview;
  List<AgendaItemSummary> _items = const [];
  String _filter = 'ALLE';
  bool _loading = true;
  bool _checking = false;
  String? _error;
  Timer? _timer;
  Timer? _durationTimer;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    // Een voorbije vergadering verandert niet meer: het archiefscherm ververst daarom niet en
    // veroorzaakt na het laden geen enkel verkeer meer.
    if (widget.archivedMeetingId == null) {
      _timer = Timer.periodic(
        const Duration(seconds: 15),
        (_) => unawaited(_load(silent: true)),
      );
    }
    _durationTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _durationTimer?.cancel();
    super.dispose();
  }

  Future<void> _load({bool silent = false}) async {
    if (!silent && mounted) {
      setState(() => _loading = true);
    }
    final archivedId = widget.archivedMeetingId;
    var headerLoaded = false;
    try {
      final overview = archivedId == null
          ? await widget.gateway.overview()
          : await widget.gateway.meeting(archivedId);
      headerLoaded = true;
      // De vergaderkop wordt meteen vastgelegd, zodat hij blijft staan wanneer het laden van de
      // agendapunten daarna alsnog mislukt.
      if (mounted) {
        setState(() {
          _overview = overview;
          _error = null;
        });
      }
      final items = overview.meeting == null
          ? <AgendaItemSummary>[]
          : await widget.gateway.agendaItems(overview.meeting!.id);
      if (mounted) {
        setState(() {
          _items = items;
          _error = null;
          _loading = false;
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          // De huidige agendaweergave houdt haar bestaande melding; alleen het archiefscherm
          // benoemt welk deel is mislukt, omdat daar de al geladen vergaderkop blijft staan.
          _error = !widget.readOnly
              ? 'Het vergaderingsoverzicht is tijdelijk niet beschikbaar.'
              : headerLoaded
              ? 'De agendapunten van deze vergadering konden niet worden geladen'
              : 'Deze vergadering kon niet worden geladen';
          _loading = false;
        });
      }
    }
  }

  Future<void> _checkNow() async {
    if (_checking) return;
    setState(() => _checking = true);
    try {
      final outcome = await widget.gateway.checkNow();
      await _load(silent: true);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_checkOutcomeLabel(outcome))));
      }
    } on Object {
      if (mounted) {
        setState(
          () =>
              _error = 'Controleren is niet gelukt. Probeer het later opnieuw.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => _checking = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 980),
            child: Align(
              alignment: Alignment.topCenter,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (widget.readOnly)
                    _archiveHeader(context)
                  else
                    _header(context),
                  if (_error != null && !widget.readOnly) ...[
                    const SizedBox(height: 12),
                    _message(
                      Icons.error_outline,
                      _error!,
                      Theme.of(context).colorScheme.error,
                    ),
                  ],
                  const SizedBox(height: 16),
                  if (_overview?.meeting == null) ...[
                    if (!widget.readOnly)
                      _message(
                        Icons.event_busy_outlined,
                        'Er is nog geen toekomstige vergadering gevonden.',
                        PvddColors.primary,
                      ),
                  ] else ...[
                    _meetingCard(context, _overview!.meeting!),
                    // Bij een mislukt agendapunt-laden heeft filteren geen betekenis; de
                    // huidige agendaweergave houdt haar filters onveranderd.
                    if (!widget.readOnly || _items.isNotEmpty) ...[
                      const SizedBox(height: 16),
                      _filters(),
                    ],
                    const SizedBox(height: 8),
                    ..._filteredItems.map(
                      (item) => _AgendaItemCard(
                        key: ValueKey(item.id),
                        item: item,
                        gateway: widget.gateway,
                        readOnly: widget.readOnly,
                        onChanged: () => _load(silent: true),
                      ),
                    ),
                  ],
                  // Alleen-lezen: de melding staat onder de vergaderkop, zodat zichtbaar blijft
                  // welke gegevens gewoon zijn blijven staan.
                  if (_error != null && widget.readOnly) ...[
                    const SizedBox(height: 16),
                    _retryableError(context, _error!),
                  ],
                  if (widget.readOnly && _items.isEmpty) ...[
                    const SizedBox(height: 16),
                    _message(
                      Icons.inbox_outlined,
                      _error == null
                          ? 'Deze vergadering heeft geen agendapunten.'
                          : 'Zodra het laden lukt, verschijnen hier de agendapunten met hun bewaarde adviezen.',
                      PvddColors.primary,
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  // Alleen-lezen kop: terugactie en, wanneer de server de vergadering als voorbij markeert, de
  // duidelijke melding dat bekijken geen analyse start. Geen 'Nu controleren'.
  Widget _archiveHeader(BuildContext context) {
    final meeting = _overview?.meeting;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.onBack != null)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: widget.onBack,
              icon: const Icon(Icons.chevron_left),
              label: Text(widget.backLabel),
            ),
          ),
        if (meeting != null && meeting.past) ...[
          const SizedBox(height: 4),
          _pastMeetingNotice(context, meeting),
        ],
      ],
    );
  }

  Widget _pastMeetingNotice(BuildContext context, MeetingInfo meeting) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: colors.surfaceContainerLow,
          border: Border(left: BorderSide(color: PvddColors.primary, width: 6)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(
              Icons.event_available_outlined,
              color: PvddColors.primary,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Deze vergadering is al geweest — ${_longDate(meeting.startsAt)}',
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    'Je leest bewaarde adviezen terug. Bekijken start geen analyse en verandert niets.',
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Fouttoestand met behoud van wat al geladen is: de vergaderkop blijft staan en alleen het
  // mislukte deel wordt opnieuw geprobeerd.
  Widget _retryableError(BuildContext context, String title) {
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
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      color: colors.error,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _overview?.meeting == null
                        ? 'Er is niets veranderd; probeer het zo opnieuw.'
                        : 'De gegevens van de vergadering hierboven blijven staan. '
                              'Er is geen advies verdwenen of gewijzigd; probeer het zo opnieuw.',
                  ),
                  const SizedBox(height: 12),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: OutlinedButton.icon(
                      onPressed: () => unawaited(_load()),
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

  Widget _header(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final title = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Commissie Ruimte',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          Text(
            _overview?.lastCheckedAt == null
                ? 'Nog niet gecontroleerd'
                : 'Laatst gecontroleerd: ${_dateTime(_overview!.lastCheckedAt!)}',
          ),
        ],
      );
      final archive = OutlinedButton.icon(
        onPressed: widget.onOpenArchive,
        icon: const Icon(Icons.history),
        label: const Text('Eerdere vergaderingen'),
      );
      final checkNow = FilledButton.icon(
        onPressed: _checking ? null : _checkNow,
        icon: _checking
            ? const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.refresh),
        label: Text(_checking ? 'Controleren…' : 'Nu controleren'),
      );
      // De uitleg onder de knoppen: waar de bewaarde adviezen staan en dat terugkijken alleen
      // lezen is. Zonder archieftoegang blijft de regel weg.
      const explanation = Text(
        'Onder Eerdere vergaderingen lees je de bewaarde adviezen terug van vergaderingen die '
        'al zijn geweest. Terugkijken is alleen lezen.',
      );
      // Op mobiel staan de knoppen onder elkaar over de volle breedte, op desktop naast de titel.
      final narrow = constraints.maxWidth < 720;
      final actions = <Widget>[
        if (widget.onOpenArchive != null) archive,
        checkNow,
      ];
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (narrow) ...[
            title,
            const SizedBox(height: 12),
            for (final action in actions) ...[
              SizedBox(width: double.infinity, child: action),
              const SizedBox(height: 8),
            ],
          ] else
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(child: title),
                const SizedBox(width: 16),
                for (final action in actions) ...[
                  action,
                  const SizedBox(width: 12),
                ],
              ],
            ),
          if (widget.onOpenArchive != null) ...[
            const SizedBox(height: 8),
            explanation,
          ],
        ],
      );
    },
  );

  Widget _meetingCard(BuildContext context, MeetingInfo meeting) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _statusChip(meeting.status),
              _statusChip(
                meeting.publicationStatus == 'PREVIEW'
                    ? 'PREVIEW'
                    : (meeting.revisionStatus ?? 'CURRENT'),
              ),
              Text(
                '${_overview!.progress.complete}/${_overview!.progress.total} analyses gereed',
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            meeting.title,
            style: Theme.of(
              context,
            ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 6),
          Text(
            '${_dateTime(meeting.startsAt)}${meeting.location == null ? '' : ' · ${meeting.location}'}',
          ),
          Text(
            'Bronrevisie ${meeting.revisionNumber}${meeting.canonicalFingerprint == null ? '' : ' · ${meeting.canonicalFingerprint!.substring(0, 12)}'}',
          ),
          TextButton.icon(
            onPressed: meeting.sourceUrl.scheme == 'https'
                ? () => openExternalLink(meeting.sourceUrl)
                : null,
            icon: const Icon(Icons.open_in_new, size: 18),
            label: const Text('Open bronagenda'),
          ),
        ],
      ),
    ),
  );

  Widget _filters() => Wrap(
    spacing: 8,
    children: ['ALLE', 'A', 'B', 'C']
        .map(
          (category) => FilterChip(
            label: Text(category == 'ALLE' ? 'Alles' : '$category-agenda'),
            selected: _filter == category,
            onSelected: (_) => setState(() => _filter = category),
          ),
        )
        .toList(),
  );

  Iterable<AgendaItemSummary> get _filteredItems => _items.where(
    (item) =>
        item.substantive &&
        item.sourceState != 'WITHDRAWN' &&
        (_filter == 'ALLE' || item.category == _filter),
  );

  Widget _message(IconData icon, String text, Color color) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          Icon(icon, color: color),
          const SizedBox(width: 12),
          Expanded(child: Text(text)),
        ],
      ),
    ),
  );
}

class _AgendaItemCard extends StatefulWidget {
  const _AgendaItemCard({
    required this.item,
    required this.gateway,
    required this.onChanged,
    this.readOnly = false,
    super.key,
  });
  final AgendaItemSummary item;
  final DashboardGateway gateway;
  final Future<void> Function() onChanged;

  /// Alleen lezen: de herstartactie voor dit agendapunt wordt niet gerenderd. De rest van de kaart
  /// en de detailweergave blijven ongewijzigd.
  final bool readOnly;
  @override
  State<_AgendaItemCard> createState() => _AgendaItemCardState();
}

class _AgendaItemCardState extends State<_AgendaItemCard> {
  Future<AgendaItemDetail>? _detail;
  bool _retrying = false;

  /// De bewaarde adviesversies van dit agendapunt, `null` zolang er niets is opgehaald. Ze worden
  /// één keer opgehaald bij het uitklappen van de detailweergave.
  List<AdviceVersion>? _versions;

  /// Het ophalen van de versies is mislukt. Het al getoonde laatste advies blijft staan.
  bool _versionsFailed = false;
  bool _loadingVersions = false;

  /// De getoonde versie; 0 is het laatste advies, hoger is een eerdere versie.
  int _selectedVersion = 0;

  /// Heeft dit agendapunt een bewaard advies? Alleen dan valt er iets te kiezen.
  bool get _hasAdvice => widget.item.adviceActuality != null;

  /// Toont de kaart op dit moment een bewaarde eerdere versie in plaats van het laatste advies?
  bool get _viewingEarlierVersion => _selectedEarlierVersion != null;

  AdviceVersion? get _selectedEarlierVersion {
    final versions = _versions;
    if (versions == null) return null;
    if (_selectedVersion < 1 || _selectedVersion >= versions.length) return null;
    return versions[_selectedVersion];
  }

  /// Haalt de adviesversies op. Uitsluitend leesverkeer, precies één keer per uitklapactie; de
  /// ververstimer van de agendaweergave wordt hier bewust niet mee uitgebreid.
  Future<void> _loadVersions() async {
    if (_loadingVersions) return;
    setState(() {
      _loadingVersions = true;
      _versionsFailed = false;
    });
    try {
      final versions = await widget.gateway.adviceVersions(widget.item.id);
      if (mounted) {
        setState(() {
          _versions = versions;
          _selectedVersion = 0;
          _loadingVersions = false;
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          _versionsFailed = true;
          _loadingVersions = false;
        });
      }
    }
  }

  Future<void> _retryAnalysis() async {
    if (_retrying) return;
    setState(() => _retrying = true);
    try {
      await widget.gateway.retryAnalysis(widget.item.id);
      await widget.onChanged();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('De AI-analyse is opnieuw gestart.')),
        );
      }
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Opnieuw starten is niet gelukt. Mogelijk is er inmiddels een nieuwere analyse.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _retrying = false);
    }
  }

  Future<void> _copyAdvice(Map<String, dynamic> advice, String category) async {
    try {
      await Clipboard.setData(
        ClipboardData(text: _adviceAsText(advice, category)),
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Het volledige advies is gekopieerd.')),
        );
      }
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Kopiëren is niet gelukt.')),
        );
      }
    }
  }

  @override
  void didUpdateWidget(covariant _AgendaItemCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_detail != null &&
        (oldWidget.item.analysisStatus != widget.item.analysisStatus ||
            oldWidget.item.adviceActuality != widget.item.adviceActuality ||
            oldWidget.item.currentFingerprint !=
                widget.item.currentFingerprint)) {
      _detail = widget.gateway.agendaItem(widget.item.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final primaryStatus = item.sourceState == 'PREVIEW'
        ? 'PREVIEW'
        : item.sourceState == 'WITHDRAWN'
        ? 'WITHDRAWN'
        : item.documentStatus == 'NO_DOCUMENTS' ||
              item.documentStatus == 'DOCUMENTS_UNREADABLE'
        ? item.documentStatus
        : item.adviceActuality == 'STALE'
        ? 'STALE'
        : item.analysisStatus ??
              (item.documentStatus == 'DOCUMENTS_READY'
                  ? item.importStatus
                  : item.documentStatus);
    final secondaryStatus = item.sourceState == 'PREVIEW'
        ? item.adviceActuality == 'STALE'
              ? 'STALE'
              : item.analysisStatus ??
                    (item.documentStatus == 'DOCUMENTS_READY'
                        ? item.importStatus
                        : item.documentStatus)
        : item.documentStatus == 'DOCUMENTS_PARTIALLY_READABLE'
        ? item.documentStatus
        : (item.documentStatus == 'NO_DOCUMENTS' ||
                  item.documentStatus == 'DOCUMENTS_UNREADABLE') &&
              item.adviceActuality == 'STALE'
        ? 'STALE'
        : null;
    final unavailableAnalysisLabel = switch (item.documentStatus) {
      'NO_DOCUMENTS' => 'Niet van toepassing — geen direct gekoppelde stukken',
      'DOCUMENTS_UNREADABLE' =>
        'Niet beschikbaar — gekoppelde stukken zijn niet leesbaar',
      _ => 'Nog niet beschikbaar',
    };
    final facts = [
      _AgendaFact('AI-titel', item.displayTitle ?? unavailableAnalysisLabel),
      _AgendaFact(
        'Korte conclusie',
        item.shortConclusion ?? unavailableAnalysisLabel,
      ),
      _AgendaFact(
        'Laatste wijziging',
        item.lastDetectedChangeAt == null
            ? 'Geen wijziging sinds eerste import'
            : [
                _dateTime(item.lastDetectedChangeAt!),
                if (item.changeTypes.isNotEmpty)
                  item.changeTypes.map(_changeLabel).join(', '),
              ].join('\n'),
      ),
      _AgendaFact(
        'Laatste AI-analyse',
        item.lastAnalysisRun == null
            ? item.documentStatus == 'NO_DOCUMENTS'
                  ? 'Overgeslagen — geen direct gekoppelde stukken'
                  : item.documentStatus == 'DOCUMENTS_UNREADABLE'
                  ? 'Niet uitgevoerd — gekoppelde stukken zijn niet leesbaar'
                  : 'Nog niet uitgevoerd'
            : _analysisRunDateTimeLabel(item.lastAnalysisRun!),
      ),
    ];
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        tilePadding: const EdgeInsets.fromLTRB(20, 16, 16, 16),
        childrenPadding: EdgeInsets.zero,
        onExpansionChanged: (open) {
          if (open && _detail == null) {
            final detail = widget.gateway.agendaItem(widget.item.id);
            setState(() {
              _detail = detail;
            });
          }
          // Eén keer bij het uitklappen, en alleen wanneer er een advies is. Wisselen tussen
          // versies leest daarna uit deze lijst en doet geen nieuwe aanroep.
          if (open && _versions == null && !_versionsFailed && _hasAdvice) {
            unawaited(_loadVersions());
          }
          // Een dichtgeklapte kaart hoort altijd het agendapunt zelf te beschrijven: de keuze voor
          // een eerdere versie vervalt, zodat badge en metadatatabel weer die van het laatste
          // advies zijn. De al opgehaalde lijst blijft staan, dus opnieuw uitklappen leest niets.
          if (!open && _selectedVersion != 0) {
            setState(() => _selectedVersion = 0);
          }
        },
        title: LayoutBuilder(
          builder: (context, constraints) {
            final heading = Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    _agendaBadge(
                      item.displayNumber == null
                          ? 'Agendapunt'
                          : 'Agendapunt ${item.displayNumber}',
                      highlighted: true,
                    ),
                    _agendaBadge('${item.category}-stuk'),
                  ],
                ),
                const SizedBox(height: 10),
                Text(
                  item.title,
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            );
            // Bij een eerdere versie beschrijft de status van het laatste advies niet wat er in
            // beeld staat; de kaartkop meldt daarom uitsluitend dat dit een eerdere versie is.
            final statuses = Wrap(
              spacing: 8,
              runSpacing: 6,
              children: _viewingEarlierVersion
                  ? [_statusChip('EARLIER_VERSION')]
                  : [
                      _statusChip(primaryStatus),
                      if (secondaryStatus != null) _statusChip(secondaryStatus),
                    ],
            );
            if (constraints.maxWidth < 560) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [heading, const SizedBox(height: 10), statuses],
              );
            }
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: heading),
                const SizedBox(width: 16),
                statuses,
              ],
            );
          },
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // De tabel hoort bij het laatste advies (AI-titel, korte conclusie, laatste
              // AI-analyse) en zou bij een eerdere versie misleidend zijn.
              if (!_viewingEarlierVersion) _AgendaFactsTable(facts: facts),
              if (item.canRetryAnalysis && !widget.readOnly) ...[
                const SizedBox(height: 12),
                Align(
                  alignment: Alignment.centerRight,
                  child: OutlinedButton.icon(
                    onPressed: _retrying ? null : _retryAnalysis,
                    icon: _retrying
                        ? const SizedBox.square(
                            dimension: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.refresh),
                    label: Text(
                      _retrying ? 'Opnieuw starten…' : 'Opnieuw proberen',
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        children: [
          if (_detail != null)
            FutureBuilder<AgendaItemDetail>(
              future: _detail,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return const Padding(
                    padding: EdgeInsets.all(20),
                    child: Text('Details konden niet worden geladen.'),
                  );
                }
                if (!snapshot.hasData) {
                  return const Padding(
                    padding: EdgeInsets.all(20),
                    child: CircularProgressIndicator(),
                  );
                }
                return _detailView(context, snapshot.data!);
              },
            ),
        ],
      ),
    );
  }

  Widget _detailView(BuildContext context, AgendaItemDetail detail) {
    final versions = _versions ?? const <AdviceVersion>[];
    final earlier = _selectedEarlierVersion;
    // Bij een eerdere versie komt de adviesinhoud uit die versie; het laatste advies blijft
    // ongewijzigd uit het itemdetail komen.
    final advice = earlier?.advice ?? detail.advice;
    final unreadableSources = detail.sources
        .where((source) => source.status != 'EXTRACTED')
        .toList(growable: false);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ..._versionChoice(context, versions),
          if (earlier != null) _earlierVersionNotice(context, versions, earlier),
          if (detail.explanation != null) Text(detail.explanation!),
          if (detail.adviceActuality == 'STALE')
            _actualityWarning(
              context,
              'Dit advies hoort bij een eerdere bronversie. De analyse wordt vernieuwd.',
            ),
          if (detail.item.sourceState == 'PREVIEW')
            _actualityWarning(
              context,
              'Voorlopige bronversie — dit beschikbare stuk is geanalyseerd en wordt bij nieuwe broninformatie opnieuw verwerkt.',
            ),
          if (unreadableSources.isNotEmpty)
            _actualityWarning(
              context,
              'Niet leesbaar en daarom niet gebruikt in de analyse: '
              '${unreadableSources.map((source) => '${source.name} (${_statusLabel(source.status)})').join(', ')}.',
            ),
          if (advice == null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Text(switch (detail.item.documentStatus) {
                'NO_DOCUMENTS' =>
                  'Geen AI-analyse: aan dit agendapunt zijn geen stukken direct gekoppeld.',
                'DOCUMENTS_UNREADABLE' =>
                  'Geen inhoudelijke AI-analyse mogelijk: geen van de direct gekoppelde stukken is leesbaar.',
                _ => 'De analyse is nog niet beschikbaar.',
              }),
            )
          else ...[
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton.icon(
                  onPressed: () => _copyAdvice(advice, widget.item.category),
                  icon: const Icon(Icons.copy_all_outlined),
                  label: const Text('Volledig advies kopiëren'),
                ),
              ),
            ),
            if (advice['content'] is String)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: SelectionArea(
                  child: MarkdownBody(
                    data: advice['content'] as String,
                    selectable: false,
                    imageBuilder: (_, _, alt) => Text(
                      alt?.isNotEmpty == true
                          ? '[Afbeelding niet geladen: $alt]'
                          : '[Afbeelding niet geladen]',
                    ),
                  ),
                ),
              )
            else if (widget.item.category == 'C')
              SelectionArea(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: _cAdvice(advice),
                ),
              )
            else
              SelectionArea(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: _abAdvice(advice),
                ),
              ),
          ],
          const Divider(height: 28),
          Text(
            detail.warning,
            style: TextStyle(
              color: Theme.of(context).colorScheme.error,
              fontWeight: FontWeight.w700,
            ),
          ),
          // Bij een eerdere versie is niet bewaard welke stukken toen zijn gebruikt, dus daar hoort
          // geen bronnenlijst maar de expliciete melding daarover.
          if (earlier != null) ...[
            const SizedBox(height: 12),
            _outlinedNotice(
              context,
              const Text(
                'Bij deze eerdere versie is niet apart bewaard welke stukken toen zijn gebruikt. '
                'De bronnenlijst hoort daarom alleen bij het laatste advies.',
              ),
            ),
          ] else if (detail.sources.isNotEmpty) ...[
            const SizedBox(height: 12),
            const Text(
              'Bronnen',
              style: TextStyle(fontWeight: FontWeight.w800),
            ),
            ...detail.sources.map(
              (source) => TextButton.icon(
                onPressed: source.url.scheme == 'https'
                    ? () => openExternalLink(source.url)
                    : null,
                icon: const Icon(Icons.description_outlined),
                label: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    '${source.name} · ${_statusLabel(source.status)}',
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  /// De versiekeuze boven de analyse.
  ///
  /// Bij meer dan één bewaarde versie één optie per versie, nieuwste eerst, met daaronder de regel
  /// die zegt welke versie het laatste advies verving. Bij precies één bewaarde versie staat op
  /// dezelfde plek de expliciete melding dat er geen eerdere versie is bewaard. Bij nul bewaarde
  /// versies verandert er niets aan de weergave; de bestaande toestand blijft leidend.
  List<Widget> _versionChoice(BuildContext context, List<AdviceVersion> versions) {
    if (_versionsFailed) return [_versionsErrorNotice(context)];
    if (versions.length == 1) {
      return [
        Padding(
          padding: const EdgeInsets.only(top: 16),
          child: _outlinedNotice(
            context,
            Text(
              'Van dit agendapunt is geen eerdere versie bewaard. '
              'Je ziet het enige advies, gemaakt op '
              '${adviceVersionDate(versions.single.createdAt)}.',
            ),
          ),
        ),
      ];
    }
    if (versions.length < 2) return const [];
    // De regel hoort bij het laatste advies: die versie verving de eerstvolgende oudere.
    final replacement = _selectedVersion == 0 ? _replacementLine(versions) : null;
    return [
      Padding(
        padding: const EdgeInsets.only(top: 16),
        // Een Wrap in plaats van één rij: op mobiel stapelen de opties en loopt niets over, ook
        // niet bij drie of meer bewaarde versies.
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (var index = 0; index < versions.length; index++)
              ChoiceChip(
                label: Text(adviceVersionLabel(versions[index], index)),
                selected: _selectedVersion == index,
                // Wisselen leest uit de al opgehaalde lijst; geen enkele nieuwe aanroep.
                onSelected: (_) => setState(() => _selectedVersion = index),
              ),
          ],
        ),
      ),
      if (replacement != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(replacement),
        ),
    ];
  }

  /// `Dit advies verving de versie van <datum>. Reden: <reden>.`
  ///
  /// De reden hoort bij de vervangende (nieuwste) versie. Levert die afleiding geen zinnige reden —
  /// een eerste analyse kan niets vervangen — dan blijft de redenzin weg in plaats van een
  /// placeholder.
  String _replacementLine(List<AdviceVersion> versions) {
    final previous = adviceVersionDate(versions[1].createdAt);
    final reason = adviceRefreshReasonSentence(versions.first.refreshReason);
    return reason == null
        ? 'Dit advies verving de versie van $previous.'
        : 'Dit advies verving de versie van $previous. Reden: $reason.';
  }

  /// De melding bij een eerdere versie: wanneer die is gemaakt, wanneer en waarom zij is vervangen,
  /// en welke aanvullende analyse-instructie er toen gold.
  ///
  /// 'Vervangen op' en de reden komen van de eerstvolgende nieuwere versie: het item direct boven
  /// de getoonde versie in de serverordening is de versie die haar verving. De huidige instelling
  /// wordt hier nooit getoond; is er niets bewaard, dan staat er `niet vastgelegd`.
  Widget _earlierVersionNotice(
    BuildContext context,
    List<AdviceVersion> versions,
    AdviceVersion earlier,
  ) {
    final newer = versions[_selectedVersion - 1];
    final reason = adviceRefreshReasonSentence(newer.refreshReason);
    final replaced = adviceVersionDate(newer.createdAt);
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: _filledNotice(
        context,
        Icons.lock_outline,
        [
          Text(
            'Je bekijkt een eerdere versie van '
            '${adviceVersionDate(earlier.createdAt)}',
            style: const TextStyle(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 4),
          Text(
            reason == null
                ? 'Vervangen op $replaced.'
                : 'Vervangen op $replaced. Reden: $reason.',
          ),
          Text(
            'Aanvullende analyse-instructie van toen: '
            '${earlier.analysisGuidance ?? 'niet vastgelegd'}.',
          ),
        ],
      ),
    );
  }

  /// Het ophalen van de versies is mislukt. Het al getoonde laatste advies blijft volledig staan;
  /// er verdwijnt geen advies. De actie herhaalt exact dezelfde leesaanvraag.
  Widget _versionsErrorNotice(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Semantics(
        liveRegion: true,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: colors.errorContainer,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'De adviesversies konden niet worden geladen. Het advies hieronder blijft staan.',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: _loadingVersions
                    ? null
                    : () => unawaited(_loadVersions()),
                icon: const Icon(Icons.refresh),
                label: const Text('Versies opnieuw laden'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Een rustige, omlijnde melding rond de versiekeuze: dezelfde vorm op desktop en mobiel.
  Widget _outlinedNotice(BuildContext context, Widget child) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
      borderRadius: BorderRadius.circular(8),
    ),
    child: child,
  );

  /// Een opvallende melding met pictogram, in dezelfde vorm als de markering boven een voorbije
  /// vergadering, zodat de meldingen ook op een smal scherm even prominent blijven.
  Widget _filledNotice(
    BuildContext context,
    IconData icon,
    List<Widget> lines,
  ) => Semantics(
    liveRegion: true,
    child: Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: PvddColors.primary),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: lines,
            ),
          ),
        ],
      ),
    ),
  );

  Widget _actualityWarning(BuildContext context, String text) => Semantics(
    liveRegion: true,
    child: Container(
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(text, style: const TextStyle(fontWeight: FontWeight.w700)),
    ),
  );

  List<Widget> _abAdvice(Map<String, dynamic> advice) => const [
    ('Waar gaat het over?', 'waarGaatHetOver'),
    ('Wat vinden we ervan?', 'watVindenWeErvan'),
    ('Wat kunnen/willen we ermee in de commissie?', 'commissieInzet'),
    (
      'Welke punten willen we maken en wat willen we van de gedeputeerde?',
      'puntenVoorGedeputeerde',
    ),
    ('Welke technische vragen gaan we stellen?', 'technischeVragen'),
  ].map((entry) => _adviceSection(entry.$1, advice[entry.$2])).toList();

  List<Widget> _cAdvice(Map<String, dynamic> advice) => [
    _adviceSection(
      'Bespreken en verplaatsen naar B',
      advice['besprekenEnNaarB'] == true ? 'Ja' : 'Nee',
    ),
    _adviceSection('Urgentie', advice['urgentie']),
    _adviceSection('Motivering', advice['motivering']),
    _adviceSection('Commissiedoel', advice['commissieDoel']),
    _adviceSection('Kernvraag', advice['kernvraag']),
  ];

  Widget _adviceSection(String title, dynamic value) {
    final text = value is Map<String, dynamic>
        ? value['text']?.toString()
        : value?.toString();
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w800)),
          const SizedBox(height: 4),
          Text(text?.isNotEmpty == true ? text! : 'Niet beschikbaar'),
        ],
      ),
    );
  }
}

String _adviceAsText(Map<String, dynamic> advice, String category) {
  final content = advice['content'];
  if (content is String) return content;

  final sections = category == 'C'
      ? <(String, dynamic)>[
          (
            'Bespreken en verplaatsen naar B',
            advice['besprekenEnNaarB'] == true ? 'Ja' : 'Nee',
          ),
          ('Urgentie', advice['urgentie']),
          ('Motivering', advice['motivering']),
          ('Commissiedoel', advice['commissieDoel']),
          ('Kernvraag', advice['kernvraag']),
        ]
      : <(String, dynamic)>[
          ('Waar gaat het over?', advice['waarGaatHetOver']),
          ('Wat vinden we ervan?', advice['watVindenWeErvan']),
          (
            'Wat kunnen/willen we ermee in de commissie?',
            advice['commissieInzet'],
          ),
          (
            'Welke punten willen we maken en wat willen we van de gedeputeerde?',
            advice['puntenVoorGedeputeerde'],
          ),
          (
            'Welke technische vragen gaan we stellen?',
            advice['technischeVragen'],
          ),
        ];
  return sections
      .map((section) => '${section.$1}\n${_adviceValue(section.$2)}')
      .join('\n\n');
}

String _adviceValue(dynamic value) {
  final text = value is Map<String, dynamic>
      ? value['text']?.toString()
      : value?.toString();
  return text?.isNotEmpty == true ? text! : 'Niet beschikbaar';
}

class _AgendaFact {
  const _AgendaFact(this.label, this.value);
  final String label;
  final String value;
}

class _AgendaFactsTable extends StatelessWidget {
  const _AgendaFactsTable({required this.facts});
  final List<_AgendaFact> facts;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final colors = Theme.of(context).colorScheme;
      final labelWidth = constraints.maxWidth < 520 ? 126.0 : 190.0;
      return Table(
        columnWidths: {
          0: FixedColumnWidth(labelWidth),
          1: const FlexColumnWidth(),
        },
        border: TableBorder(
          top: BorderSide(color: colors.outlineVariant),
          bottom: BorderSide(color: colors.outlineVariant),
          horizontalInside: BorderSide(color: colors.outlineVariant),
        ),
        defaultVerticalAlignment: TableCellVerticalAlignment.top,
        children: facts
            .map(
              (fact) => TableRow(
                children: [
                  Container(
                    color: colors.surfaceContainerLow,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 13,
                    ),
                    child: Text(
                      fact.label,
                      style: TextStyle(
                        color: colors.onSurfaceVariant,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 13,
                    ),
                    child: Text(fact.value),
                  ),
                ],
              ),
            )
            .toList(),
      );
    },
  );
}

Widget _agendaBadge(String label, {bool highlighted = false}) => Builder(
  builder: (context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: highlighted ? colors.primaryContainer : colors.surface,
        border: highlighted ? null : Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: highlighted
              ? colors.onPrimaryContainer
              : colors.onSurfaceVariant,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  },
);

Widget _statusChip(String status) => Chip(label: Text(_statusLabel(status)));

String _statusLabel(String status) => switch (status) {
  'PREVIEW' => 'Voorlopige agenda',
  'CURRENT' => 'Actueel',
  'CHANGED' => 'Bron gewijzigd',
  'REPROCESSING' => 'Analyse wordt vernieuwd',
  'STALE' => 'Oud advies — analyse wordt vernieuwd',
  'WITHDRAWN' => 'Ingetrokken',
  'AGENDA_UNPUBLISHED' => 'Agenda nog niet gepubliceerd',
  'IMPORTING' => 'Stukken worden ingelezen',
  'ANALYSING' ||
  'RUNNING' ||
  'QUEUED' ||
  'WAITING_FOR_WORKER' => 'Analyse bezig',
  'COMPLETE' || 'SUCCEEDED' || 'EXTRACTED' => 'Gereed',
  'PARTIAL' => 'Onvolledig — controle nodig',
  'FAILED' => 'Mislukt',
  'OCR_REQUIRED' => 'Scan — OCR nodig',
  'NO_DOCUMENTS' => 'Geen stukken — analyse overgeslagen',
  'DOCUMENTS_UNREADABLE' => 'Stukken niet leesbaar',
  'DOCUMENTS_PARTIALLY_READABLE' => 'Een of meer stukken niet leesbaar',
  'DOCUMENTS_READY' => 'Stukken leesbaar',
  'EARLIER_VERSION' => 'EERDERE VERSIE',
  _ => status.toLowerCase().replaceAll('_', ' '),
};

String _changeLabel(String change) => switch (change) {
  'PUBLICATION_STATUS' => 'agenda gepubliceerd',
  'ITEM_ADDED' => 'punt toegevoegd',
  'ITEM_WITHDRAWN' => 'punt ingetrokken',
  'ITEM_MOVED' => 'punt verplaatst',
  'CATEGORY_CHANGED' => 'agenda-categorie gewijzigd',
  'METADATA_CHANGED' => 'toelichting of behandelvoorstel gewijzigd',
  'DOCUMENT_ADDED' => 'document toegevoegd',
  'DOCUMENT_REMOVED' => 'document verwijderd',
  'DOCUMENT_CONTENT_CHANGED' => 'documentinhoud gewijzigd',
  _ => change.toLowerCase().replaceAll('_', ' '),
};

String _analysisRunDateTimeLabel(AnalysisRunInfo run) {
  final active = const {
    'PENDING',
    'QUEUED',
    'WAITING_FOR_WORKER',
    'RUNNING',
  }.contains(run.status);
  final end = run.completedAt ?? DateTime.now();
  final duration = end.difference(run.createdAt);
  final label = duration.inHours > 0
      ? '${duration.inHours}u ${duration.inMinutes.remainder(60)}m'
      : '${duration.inMinutes}m ${duration.inSeconds.remainder(60)}s';
  return active
      ? 'Gestart ${_dateTime(run.createdAt)} · $label bezig'
      : '${_dateTime(run.completedAt ?? run.updatedAt)} · ${_statusLabel(run.status)}';
}

String _checkOutcomeLabel(
  MeetingCheckOutcome outcome,
) => switch (outcome.status) {
  'UNCHANGED' => 'De bron is gecontroleerd en ongewijzigd.',
  'AGENDA_UNPUBLISHED' =>
    'De volledige agenda is nog niet gepubliceerd en bevat nog geen verwerkbare stukken.',
  'IMPORTED' when outcome.differences.isNotEmpty =>
    'Bronwijziging gevonden. De gerichte heranalyse is gestart.',
  'IMPORTED' => 'De agenda is verwerkt.',
  'SOURCE_FAILURE' || 'FAILED' =>
    'De broncontrole is mislukt; de laatst geldige gegevens blijven bewaard.',
  'ALREADY_RUNNING' => 'Er loopt al een broncontrole.',
  'NO_FUTURE_MEETING' => 'Er is geen toekomstige vergadering gevonden.',
  _ => 'De broncontrole is afgerond.',
};

const _dutchMonths = [
  'januari',
  'februari',
  'maart',
  'april',
  'mei',
  'juni',
  'juli',
  'augustus',
  'september',
  'oktober',
  'november',
  'december',
];

const _dutchShortMonths = [
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

// Lange Nederlandse datum voor de markering 'al geweest', bijvoorbeeld 'maandag 7 september 2026'.
String _longDate(DateTime value) {
  const days = [
    'maandag',
    'dinsdag',
    'woensdag',
    'donderdag',
    'vrijdag',
    'zaterdag',
    'zondag',
  ];
  final local = value.toLocal();
  return '${days[local.weekday - 1]} ${local.day} ${_dutchMonths[local.month - 1]} ${local.year}';
}

/// De datum van een adviesversie in de meldingen, bijvoorbeeld '3 september 2026'.
///
/// Publiek zodat tests de datums via dezelfde helper asserteren als de weergave gebruikt; een
/// latere opmaakwijziging levert dan geen valse rode test op. De weergave is lokaal
/// (Europe/Amsterdam in de app), consistent met de rest van het scherm.
String adviceVersionDate(DateTime value) {
  final local = value.toLocal();
  return '${local.day} ${_dutchMonths[local.month - 1]} ${local.year}';
}

/// De compacte datum in de versiekeuze, bijvoorbeeld '7 sep 2026'.
String adviceVersionShortDate(DateTime value) {
  final local = value.toLocal();
  return '${local.day} ${_dutchShortMonths[local.month - 1]} ${local.year}';
}

/// Het label van één optie in de versiekeuze; de eerste optie is altijd het laatste advies.
String adviceVersionLabel(AdviceVersion version, int index) => index == 0
    ? 'Laatste advies · ${adviceVersionShortDate(version.createdAt)}'
    : 'Eerdere versie · ${adviceVersionShortDate(version.createdAt)}';

/// De redenzin bij een vernieuwd advies, of `null` wanneer er geen vervangreden te geven is.
///
/// `FIRST_ANALYSIS` kan per definitie niets vervangen; in dat geval blijft de redenzin weg in plaats
/// van dat er een placeholder wordt getoond.
String? adviceRefreshReasonSentence(String refreshReason) =>
    switch (refreshReason) {
      'MANUAL_RETRY' => 'handmatig opnieuw gestart',
      'CONTEXT_CHANGED' => 'de bron- of beleidscontext veranderde',
      _ => null,
    };

String _dateTime(DateTime value) {
  final local = value.toLocal();
  String two(int number) => number.toString().padLeft(2, '0');
  return '${two(local.day)}-${two(local.month)}-${local.year} ${two(local.hour)}:${two(local.minute)}';
}
