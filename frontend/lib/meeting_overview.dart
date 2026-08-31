import 'dart:async';

import 'package:flutter/material.dart';

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

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    _timer = Timer.periodic(
      const Duration(seconds: 15),
      (_) => unawaited(_load(silent: true)),
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
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
      await widget.gateway.checkNow();
      await _load(silent: true);
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
                      (item) =>
                          _AgendaItemCard(item: item, gateway: widget.gateway),
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

  Iterable<AgendaItemSummary> get _filteredItems =>
      _items.where((item) => _filter == 'ALLE' || item.category == _filter);

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
  const _AgendaItemCard({required this.item, required this.gateway});
  final AgendaItemSummary item;
  final DashboardGateway gateway;
  @override
  State<_AgendaItemCard> createState() => _AgendaItemCardState();
}

class _AgendaItemCardState extends State<_AgendaItemCard> {
  Future<AgendaItemDetail>? _detail;

  @override
  Widget build(BuildContext context) => Card(
    child: ExpansionTile(
      onExpansionChanged: (open) {
        if (open && _detail == null) {
          final detail = widget.gateway.agendaItem(widget.item.id);
          setState(() {
            _detail = detail;
          });
        }
      },
      leading: CircleAvatar(child: Text(widget.item.category)),
      title: Text(
        '${widget.item.displayNumber ?? ''} ${widget.item.title}'.trim(),
      ),
      subtitle: Text(
        _statusLabel(widget.item.analysisStatus ?? widget.item.importStatus),
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

  Widget _detailView(BuildContext context, AgendaItemDetail detail) {
    final advice = detail.advice;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (detail.explanation != null) Text(detail.explanation!),
          if (advice == null)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Text('De analyse is nog niet beschikbaar.'),
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

Widget _statusChip(String status) => Chip(label: Text(_statusLabel(status)));

String _statusLabel(String status) => switch (status) {
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

String _dateTime(DateTime value) {
  final local = value.toLocal();
  String two(int number) => number.toString().padLeft(2, '0');
  return '${two(local.day)}-${two(local.month)}-${local.year} ${two(local.hour)}:${two(local.minute)}';
}
