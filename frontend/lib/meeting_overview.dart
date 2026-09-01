import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import 'dashboard_api.dart';
import 'external_link.dart';
import 'pvdd_theme.dart';

class MeetingOverviewPage extends StatefulWidget {
  const MeetingOverviewPage({required this.gateway, super.key});
  final DashboardGateway gateway;

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
    _timer = Timer.periodic(
      const Duration(seconds: 15),
      (_) => unawaited(_load(silent: true)),
    );
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
    try {
      final overview = await widget.gateway.overview();
      final items = overview.meeting == null
          ? <AgendaItemSummary>[]
          : await widget.gateway.agendaItems(overview.meeting!.id);
      if (mounted) {
        setState(() {
          _overview = overview;
          _items = items;
          _error = null;
          _loading = false;
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          _error = 'Het vergaderingsoverzicht is tijdelijk niet beschikbaar.';
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
                  _header(context),
                  if (_error != null) ...[
                    const SizedBox(height: 12),
                    _message(
                      Icons.error_outline,
                      _error!,
                      Theme.of(context).colorScheme.error,
                    ),
                  ],
                  const SizedBox(height: 16),
                  if (_overview?.meeting == null)
                    _message(
                      Icons.event_busy_outlined,
                      'Er is nog geen toekomstige vergadering gevonden.',
                      PvddColors.primary,
                    )
                  else ...[
                    _meetingCard(context, _overview!.meeting!),
                    const SizedBox(height: 16),
                    _filters(),
                    const SizedBox(height: 8),
                    ..._filteredItems.map(
                      (item) => _AgendaItemCard(
                        key: ValueKey(item.id),
                        item: item,
                        gateway: widget.gateway,
                      ),
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

  Widget _header(BuildContext context) => Wrap(
    alignment: WrapAlignment.spaceBetween,
    crossAxisAlignment: WrapCrossAlignment.center,
    spacing: 16,
    runSpacing: 12,
    children: [
      Column(
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
      ),
      FilledButton.icon(
        onPressed: _checking ? null : _checkNow,
        icon: _checking
            ? const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.refresh),
        label: Text(_checking ? 'Controleren…' : 'Nu controleren'),
      ),
    ],
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
  const _AgendaItemCard({required this.item, required this.gateway, super.key});
  final AgendaItemSummary item;
  final DashboardGateway gateway;
  @override
  State<_AgendaItemCard> createState() => _AgendaItemCardState();
}

class _AgendaItemCardState extends State<_AgendaItemCard> {
  Future<AgendaItemDetail>? _detail;

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
        : item.adviceActuality == 'STALE'
        ? 'STALE'
        : item.analysisStatus ?? item.importStatus;
    final secondaryStatus = item.sourceState == 'PREVIEW'
        ? item.adviceActuality == 'STALE'
              ? 'STALE'
              : item.analysisStatus ?? item.importStatus
        : null;
    final facts = [
      _AgendaFact('AI-titel', item.displayTitle ?? 'Nog niet beschikbaar'),
      _AgendaFact(
        'Korte conclusie',
        item.shortConclusion ?? 'Nog niet beschikbaar',
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
            ? 'Nog niet uitgevoerd'
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
            final statuses = Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
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
          child: _AgendaFactsTable(facts: facts),
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
    final advice = detail.advice;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
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
          if (advice == null)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Text('De analyse is nog niet beschikbaar.'),
            )
          else if (advice['content'] is String)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: MarkdownBody(
                data: advice['content'] as String,
                selectable: true,
                imageBuilder: (_, _, alt) => Text(
                  alt?.isNotEmpty == true
                      ? '[Afbeelding niet geladen: $alt]'
                      : '[Afbeelding niet geladen]',
                ),
              ),
            )
          else if (widget.item.category == 'C')
            ..._cAdvice(advice)
          else
            ..._abAdvice(advice),
          const Divider(height: 28),
          Text(
            detail.warning,
            style: TextStyle(
              color: Theme.of(context).colorScheme.error,
              fontWeight: FontWeight.w700,
            ),
          ),
          if (detail.sources.isNotEmpty) ...[
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

String _dateTime(DateTime value) {
  final local = value.toLocal();
  String two(int number) => number.toString().padLeft(2, '0');
  return '${two(local.day)}-${two(local.month)}-${local.year} ${two(local.hour)}:${two(local.minute)}';
}
