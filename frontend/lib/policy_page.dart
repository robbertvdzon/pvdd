import 'dart:async';

import 'package:flutter/material.dart';

import 'external_link.dart';
import 'policy_api.dart';

class PolicyPage extends StatefulWidget {
  const PolicyPage({required this.gateway, super.key});
  final PolicyGateway gateway;

  @override
  State<PolicyPage> createState() => _PolicyPageState();
}

class _PolicyPageState extends State<PolicyPage> {
  PolicyOverview? _overview;
  bool _loading = true;
  bool _refreshing = false;
  String _query = '';
  String? _theme;
  String? _sourceType;
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
    if (!silent && mounted) setState(() => _loading = true);
    try {
      final overview = await widget.gateway.overview();
      if (mounted) {
        setState(() {
          _overview = overview;
          _loading = false;
          _error = null;
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'De actuele standpunten zijn tijdelijk niet beschikbaar.';
        });
      }
    }
  }

  Future<void> _refresh() async {
    if (_refreshing) return;
    setState(() => _refreshing = true);
    try {
      await widget.gateway.refresh();
      await _load(silent: true);
    } on Object {
      if (mounted) {
        setState(() => _error = 'Actualiseren kon niet worden gestart.');
      }
    } finally {
      if (mounted) setState(() => _refreshing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final overview = _overview;
    final themes = overview == null
        ? <String>[]
        : overview.positions
              .expand((position) => position.themes)
              .toSet()
              .toList();
    themes.sort();
    final sourceTypes = overview == null
        ? <String>[]
        : overview.positions
              .expand(
                (position) => position.references.map(
                  (reference) => reference.sourceType,
                ),
              )
              .toSet()
              .toList();
    sourceTypes.sort();
    final positions =
        overview?.positions.where((position) {
          final queryMatches =
              _query.isEmpty ||
              '${position.title} ${position.summary} ${position.direction}'
                  .toLowerCase()
                  .contains(_query.toLowerCase());
          return queryMatches &&
              (_theme == null || position.themes.contains(_theme)) &&
              (_sourceType == null ||
                  position.references.any(
                    (reference) => reference.sourceType == _sourceType,
                  ));
        }).toList() ??
        const <PolicyPosition>[];
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 980),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: 16,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Actuele PvdD-standpunten',
                          style: Theme.of(context).textTheme.headlineMedium,
                        ),
                        Text(
                          overview?.lastSuccessfulAt == null
                              ? 'Nog niet succesvol gecontroleerd'
                              : 'Laatst gecontroleerd: ${_date(overview!.lastSuccessfulAt!)}',
                        ),
                        if (overview != null)
                          Text(
                            'Volgende controle: ${_date(overview.nextScheduledAt)}',
                          ),
                      ],
                    ),
                    FilledButton.icon(
                      onPressed: _refreshing || overview?.currentRun != null
                          ? null
                          : _refresh,
                      icon: _refreshing
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.refresh),
                      label: const Text('Standpunten nu actualiseren'),
                    ),
                  ],
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                if (overview?.currentRun != null) ...[
                  const SizedBox(height: 12),
                  Card(
                    child: ListTile(
                      leading: const CircularProgressIndicator(),
                      title: const Text('Standpunten worden bijgewerkt'),
                      subtitle: Text(
                        '${overview!.currentRun!.status} · gestart ${_date(overview.currentRun!.startedAt ?? overview.currentRun!.createdAt)}',
                      ),
                    ),
                  ),
                ],
                if (overview?.snapshot != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    'Bronversie ${overview!.snapshot!.version} · ${overview.snapshot!.fingerprint.substring(0, 12)}',
                  ),
                ],
                const SizedBox(height: 16),
                Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    SizedBox(
                      width: 360,
                      child: TextField(
                        decoration: const InputDecoration(
                          prefixIcon: Icon(Icons.search),
                          labelText: 'Zoek standpunt',
                        ),
                        onChanged: (value) => setState(() => _query = value),
                      ),
                    ),
                    DropdownButton<String?>(
                      value: _theme,
                      hint: const Text('Alle thema’s'),
                      items: [
                        const DropdownMenuItem<String?>(
                          value: null,
                          child: Text('Alle thema’s'),
                        ),
                        ...themes.map(
                          (theme) => DropdownMenuItem<String?>(
                            value: theme,
                            child: Text(theme),
                          ),
                        ),
                      ],
                      onChanged: (value) => setState(() => _theme = value),
                    ),
                    DropdownButton<String?>(
                      value: _sourceType,
                      hint: const Text('Alle brontypen'),
                      items: [
                        const DropdownMenuItem<String?>(
                          value: null,
                          child: Text('Alle brontypen'),
                        ),
                        ...sourceTypes.map(
                          (type) => DropdownMenuItem<String?>(
                            value: type,
                            child: Text(type),
                          ),
                        ),
                      ],
                      onChanged: (value) => setState(() => _sourceType = value),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (positions.isEmpty)
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(20),
                      child: Text(
                        'Er zijn nog geen actuele standpunten beschikbaar.',
                      ),
                    ),
                  )
                else
                  ...positions.map(
                    (position) => _positionCard(context, position),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _positionCard(BuildContext context, PolicyPosition position) => Card(
    child: ExpansionTile(
      title: Text(position.title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(position.summary),
          const SizedBox(height: 4),
          Text(
            '${position.status} · laatst gewijzigd ${_date(position.lastChangedAt)}',
          ),
          Wrap(
            spacing: 6,
            children: position.themes
                .map((theme) => Chip(label: Text(theme)))
                .toList(),
          ),
        ],
      ),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(position.direction),
              const SizedBox(height: 12),
              const Text(
                'Officiële referenties',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              ...position.references.map(
                (reference) => TextButton.icon(
                  onPressed: () => openExternalLink(reference.url),
                  icon: const Icon(Icons.open_in_new),
                  label: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      '${reference.title}${reference.pageNumber == null ? '' : ' · pagina ${reference.pageNumber}'}',
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

String _date(DateTime value) => value.toLocal().toString().substring(0, 16);
