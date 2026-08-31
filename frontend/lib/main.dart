import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

void main() => runApp(const PvddApp());

class PvddApp extends StatelessWidget {
  const PvddApp({super.key, this.versionLoader});

  final Future<String> Function()? versionLoader;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'PvdD Commissie-assistent',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff3f8f77)),
      useMaterial3: true,
    ),
    home: TechnicalFoundationPage(versionLoader: versionLoader),
  );
}

class TechnicalFoundationPage extends StatefulWidget {
  const TechnicalFoundationPage({super.key, this.versionLoader});

  final Future<String> Function()? versionLoader;

  @override
  State<TechnicalFoundationPage> createState() => _TechnicalFoundationPageState();
}

class _TechnicalFoundationPageState extends State<TechnicalFoundationPage> {
  late final Future<String> _version = (widget.versionLoader ?? _loadBackendVersion)();

  Future<String> _loadBackendVersion() async {
    final response = await http.get(Uri.parse('/api/version')).timeout(const Duration(seconds: 5));
    if (response.statusCode != 200) throw StateError('Backend niet beschikbaar');
    final value = jsonDecode(utf8.decode(response.bodyBytes));
    if (value is! Map<String, dynamic>) throw StateError('Ongeldig antwoord');
    return value['backendBuildIdentity'] as String? ?? 'Onbekend';
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('PvdD Commissie-assistent')),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Technische basis gereed', style: Theme.of(context).textTheme.headlineMedium),
                  const SizedBox(height: 12),
                  const Text('De functionele module is nog niet geïnstalleerd.'),
                  const SizedBox(height: 16),
                  FutureBuilder<String>(
                    future: _version,
                    builder: (context, snapshot) => Text(
                      snapshot.hasData
                          ? 'Backend: ${snapshot.data}'
                          : snapshot.hasError
                          ? 'Backend: niet beschikbaar'
                          : 'Backend controleren…',
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
