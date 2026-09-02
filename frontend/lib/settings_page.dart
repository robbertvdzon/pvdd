import 'package:flutter/material.dart';

import 'settings_api.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({required this.gateway, super.key});
  final SettingsGateway gateway;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _instructions = TextEditingController();
  ApplicationSettings? _settings;
  bool _loading = true;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _instructions.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final settings = await widget.gateway.load();
      if (!mounted) return;
      setState(() {
        _settings = settings;
        _instructions.text = settings.analysisPrompt.additionalInstructions;
        _loading = false;
        _error = null;
      });
    } on Object {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'Instellingen konden niet worden geladen.';
        });
      }
    }
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final settings = await widget.gateway.updateAnalysisInstructions(
        _instructions.text,
      );
      if (!mounted) return;
      setState(() {
        _settings = settings;
        _instructions.text = settings.analysisPrompt.additionalInstructions;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Analyse-instructie opgeslagen. Toekomstige vergaderingen worden opnieuw beoordeeld.',
          ),
        ),
      );
    } on Object {
      if (mounted) {
        setState(() => _error = 'Opslaan is niet gelukt.');
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final settings = _settings;
    if (settings == null) {
      return Center(
        child: FilledButton(
          onPressed: _load,
          child: const Text('Opnieuw proberen'),
        ),
      );
    }
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
                  'Instellingen',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 8),
                const Text(
                  'Effectieve applicatie-instellingen. Tokens, wachtwoorden en andere secrets worden hier nooit getoond.',
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
                const SizedBox(height: 20),
                _section(
                  'Geplande jobs',
                  settings.scheduledJobs
                      .map(
                        (job) => _settingCard([
                          ('Job', job.name),
                          (
                            job.kind == 'CRON' ? 'Cron' : 'Interval',
                            job.schedule,
                          ),
                          if (job.timeZone != null) ('Tijdzone', job.timeZone!),
                          ('Werking', job.explanation),
                        ]),
                      )
                      .toList(),
                ),
                _section('Bronnen voor standpunten', [
                  _settingCard([
                    (
                      'Verkiezingsprogramma',
                      settings.policySources.programmeUrl,
                    ),
                    (
                      'Start-URL’s',
                      settings.policySources.startUrls.join('\n'),
                    ),
                    ('Website', settings.policySources.websiteHost),
                    (
                      'Bekeken delen',
                      settings.policySources.discoveryPaths.join(', '),
                    ),
                    (
                      'Toegestane hosts',
                      settings.policySources.allowedHosts.join(', '),
                    ),
                    (
                      'Maximum pagina’s',
                      settings.policySources.maximumPages.toString(),
                    ),
                  ]),
                ]),
                _analysisSection(settings.analysisPrompt),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _section(String title, List<Widget> children) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text(title, style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 8),
      ...children,
      const SizedBox(height: 20),
    ],
  );

  Widget _analysisSection(AnalysisPromptSettings prompt) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('AI-analyse', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 8),
      _settingCard([
        ('Promptversie', prompt.promptVersion),
        (
          'Instructie gewijzigd',
          '${_dateTime(prompt.additionalInstructionsUpdatedAt)} door ${prompt.additionalInstructionsUpdatedBy}',
        ),
      ]),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Aanvullende analyse-instructie',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 6),
              const Text(
                'Deze tekst stuurt onder meer wanneer een C-stuk naar B moet. Een wijziging leidt tot nieuwe analyses voor toekomstige vergaderingen.',
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _instructions,
                minLines: 5,
                maxLines: 12,
                maxLength: prompt.maximumAdditionalInstructionCharacters,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'Bewerkbare instructie',
                  alignLabelWithHint: true,
                ),
              ),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.icon(
                  onPressed: _saving ? null : _save,
                  icon: _saving
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.save_outlined),
                  label: Text(_saving ? 'Opslaan…' : 'Opslaan'),
                ),
              ),
            ],
          ),
        ),
      ),
      Card(
        child: ExpansionTile(
          title: const Text('Vaste systeemprompt bekijken'),
          subtitle: const Text(
            'Alleen-lezen: bevat veiligheids- en uitvoerregels.',
          ),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: SelectableText(prompt.systemPrompt),
            ),
          ],
        ),
      ),
      const SizedBox(height: 20),
    ],
  );

  Widget _settingCard(List<(String, String)> rows) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: LayoutBuilder(
        builder: (context, constraints) => Column(
          children: rows
              .map(
                (row) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 5),
                  child: constraints.maxWidth < 520
                      ? Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text(
                              row.$1,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const SizedBox(height: 2),
                            SelectableText(row.$2),
                          ],
                        )
                      : Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(
                              width: 190,
                              child: Text(
                                row.$1,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                            Expanded(child: SelectableText(row.$2)),
                          ],
                        ),
                ),
              )
              .toList(),
        ),
      ),
    ),
  );

  String _dateTime(DateTime value) {
    final local = value.toLocal();
    String two(int number) => number.toString().padLeft(2, '0');
    return '${two(local.day)}-${two(local.month)}-${local.year} ${two(local.hour)}:${two(local.minute)}';
  }
}
