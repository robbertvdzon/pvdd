import 'dart:async';

import 'package:flutter/material.dart';

import 'ai_runs_api.dart';

class AiRunsPage extends StatefulWidget {
  const AiRunsPage({required this.gateway, super.key});
  final AiRunsGateway gateway;
  @override
  State<AiRunsPage> createState() => _AiRunsPageState();
}

class _AiRunsPageState extends State<AiRunsPage> {
  List<AiRun> _active = const [];
  List<AiRun> _finished = const [];
  String? _cursor;
  bool _loading = true;
  bool _moreLoading = false;
  String? _retryingRunId;
  String? _error;
  Timer? _refreshTimer;
  Timer? _durationTimer;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 10),
      (_) => unawaited(_load(silent: true)),
    );
    _durationTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    _durationTimer?.cancel();
    super.dispose();
  }

  Future<void> _load({bool silent = false}) async {
    if (!silent && mounted) setState(() => _loading = true);
    try {
      final active = await widget.gateway.active();
      final finished = await widget.gateway.finished();
      if (mounted) {
        setState(() {
          _active = active.items;
          _finished = finished.items;
          _cursor = finished.nextCursor;
          _loading = false;
          _error = null;
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'AI-runs konden niet worden geladen.';
        });
      }
    }
  }

  Future<void> _more() async {
    if (_cursor == null || _moreLoading) return;
    setState(() => _moreLoading = true);
    try {
      final page = await widget.gateway.finished(cursor: _cursor);
      if (mounted) {
        setState(() {
          _finished = [
            ..._finished,
            ...page.items.where(
              (item) => !_finished.any((existing) => existing.id == item.id),
            ),
          ];
          _cursor = page.nextCursor;
        });
      }
    } finally {
      if (mounted) setState(() => _moreLoading = false);
    }
  }

  Future<void> _retry(AiRun run) async {
    if (_retryingRunId != null) return;
    setState(() {
      _retryingRunId = run.id;
      _error = null;
    });
    try {
      await widget.gateway.retry(run.id);
      await _load(silent: true);
    } on Object {
      if (mounted) {
        setState(() {
          _error = 'Opnieuw proberen van de AI-run is niet gelukt.';
        });
      }
    } finally {
      if (mounted) setState(() => _retryingRunId = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 980),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'AI-runs',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
                Text('Nu bezig', style: Theme.of(context).textTheme.titleLarge),
                if (_active.isEmpty)
                  const Card(
                    child: ListTile(
                      title: Text('Er zijn nu geen actieve AI-runs.'),
                    ),
                  )
                else
                  ..._active.map((run) => _runCard(run, active: true)),
                const SizedBox(height: 20),
                Text('Afgerond', style: Theme.of(context).textTheme.titleLarge),
                if (_finished.isEmpty)
                  const Card(
                    child: ListTile(
                      title: Text('Er zijn nog geen afgeronde AI-runs.'),
                    ),
                  )
                else
                  ..._finished.map((run) => _runCard(run, active: false)),
                if (_cursor != null)
                  Align(
                    alignment: Alignment.center,
                    child: OutlinedButton(
                      onPressed: _moreLoading ? null : _more,
                      child: Text(_moreLoading ? 'Laden…' : 'Meer laden'),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _runCard(AiRun run, {required bool active}) {
    final start = run.startedAt ?? run.createdAt;
    final end = run.completedAt ?? DateTime.now();
    final duration = end.difference(start);
    final durationText = duration.inHours > 0
        ? '${duration.inHours}u ${duration.inMinutes.remainder(60)}m'
        : '${duration.inMinutes}m ${duration.inSeconds.remainder(60)}s';
    final timestamps = [
      'Aangemaakt: ${_dateTime(run.createdAt)}',
      if (run.startedAt != null) 'Gestart: ${_dateTime(run.startedAt!)}',
      if (!active) 'Afgerond: ${_dateTime(run.completedAt ?? run.updatedAt)}',
    ].join(' · ');
    final canRetry = !active && run.canRetry;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            active
                ? const SizedBox.square(
                    dimension: 28,
                    child: CircularProgressIndicator(strokeWidth: 3),
                  )
                : Icon(
                    run.status == 'SUCCEEDED'
                        ? Icons.check_circle_outline
                        : Icons.error_outline,
                  ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    run.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${run.explanation}\n$timestamps\n${active ? 'Looptijd' : 'Duur'}: $durationText · ${run.status} · ${run.completedPhases}/${run.phaseCount} fasen${run.errorCode == null ? '' : '\nFoutcode: ${run.errorCode}'}',
                  ),
                  if (canRetry) ...[
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      onPressed: _retryingRunId == null
                          ? () => _retry(run)
                          : null,
                      icon: _retryingRunId == run.id
                          ? const SizedBox.square(
                              dimension: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.refresh),
                      label: Text(
                        _retryingRunId == run.id
                            ? 'Opnieuw starten…'
                            : 'Opnieuw proberen',
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _dateTime(DateTime value) {
    final local = value.toLocal();
    String two(int number) => number.toString().padLeft(2, '0');
    return '${two(local.day)}-${two(local.month)}-${local.year} ${two(local.hour)}:${two(local.minute)}';
  }
}
