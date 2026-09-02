import 'dart:async';

import 'package:flutter/material.dart';

import 'authentication.dart';
import 'ai_runs_api.dart';
import 'ai_runs_page.dart';
import 'app_path.dart';
import 'build_identity.dart';
import 'configuration.dart';
import 'dashboard_api.dart';
import 'frontend_version_monitor.dart';
import 'google_login_button.dart';
import 'meeting_overview.dart';
import 'policy_api.dart';
import 'policy_page.dart';
import 'page_reload.dart';
import 'pvdd_theme.dart';
import 'settings_api.dart';
import 'settings_page.dart';

void main() => runApp(const PvddApp());

class PvddApp extends StatelessWidget {
  const PvddApp({
    super.key,
    this.authenticationGateway,
    this.loginBuilder,
    this.versionGateway,
    this.frontendVersionSource,
    this.dashboardGateway,
    this.aiRunsGateway,
    this.settingsGateway,
    this.acceptanceBypass,
  });

  final AuthenticationGateway? authenticationGateway;
  final Widget Function(ValueChanged<String>)? loginBuilder;
  final VersionGateway? versionGateway;
  final FrontendVersionSource? frontendVersionSource;
  final DashboardGateway? dashboardGateway;
  final AiRunsGateway? aiRunsGateway;
  final SettingsGateway? settingsGateway;
  final bool? acceptanceBypass;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'PvdD Commissie-assistent',
    debugShowCheckedModeBanner: false,
    theme: pvddTheme(),
    home: AuthenticationGate(
      gateway: authenticationGateway ?? HttpAuthenticationGateway(),
      loginBuilder: loginBuilder,
      versionGateway: versionGateway ?? HttpVersionGateway(),
      frontendVersionSource: frontendVersionSource,
      dashboardGateway: dashboardGateway,
      aiRunsGateway: aiRunsGateway,
      settingsGateway: settingsGateway,
      acceptanceBypass: acceptanceBypass ?? AppConfiguration.acceptanceBypass,
    ),
  );
}

enum _AuthenticationState { loading, login, authenticated, error }

class AuthenticationGate extends StatefulWidget {
  const AuthenticationGate({
    required this.gateway,
    required this.versionGateway,
    this.loginBuilder,
    this.frontendVersionSource,
    this.dashboardGateway,
    this.aiRunsGateway,
    this.settingsGateway,
    required this.acceptanceBypass,
    super.key,
  });
  final AuthenticationGateway gateway;
  final Widget Function(ValueChanged<String>)? loginBuilder;
  final VersionGateway versionGateway;
  final FrontendVersionSource? frontendVersionSource;
  final DashboardGateway? dashboardGateway;
  final AiRunsGateway? aiRunsGateway;
  final SettingsGateway? settingsGateway;
  final bool acceptanceBypass;

  @override
  State<AuthenticationGate> createState() => _AuthenticationGateState();
}

class _AuthenticationGateState extends State<AuthenticationGate> {
  _AuthenticationState _state = _AuthenticationState.loading;
  String? _email;
  String? _message;
  bool _explicitlySignedOut = false;

  @override
  void initState() {
    super.initState();
    unawaited(_restore());
  }

  Future<void> _restore() async {
    if (widget.acceptanceBypass) {
      if (mounted) {
        setState(() {
          _email = 'acceptance-tester@pvdd.invalid';
          _state = _AuthenticationState.authenticated;
        });
      }
      return;
    }
    try {
      final user = await widget.gateway.restore();
      if (mounted) {
        setState(() {
          _email = user.email;
          _explicitlySignedOut = false;
          _state = _AuthenticationState.authenticated;
        });
      }
    } on AuthenticationRejected {
      if (mounted) setState(() => _state = _AuthenticationState.login);
    } on Object {
      if (mounted) {
        setState(() {
          _state = _AuthenticationState.error;
          _message = 'De beveiligde backend is tijdelijk niet bereikbaar.';
        });
      }
    }
  }

  Future<void> _authenticate(String token) async {
    setState(() {
      _state = _AuthenticationState.loading;
      _message = null;
    });
    try {
      final user = await widget.gateway.signIn(token);
      if (mounted) {
        setState(() {
          _email = user.email;
          _state = _AuthenticationState.authenticated;
        });
      }
    } on AuthenticationRejected {
      if (mounted) {
        setState(() {
          _state = _AuthenticationState.login;
          _message = 'Dit Google-account heeft geen toegang.';
        });
      }
    } on Object {
      if (mounted) {
        setState(() {
          _state = _AuthenticationState.error;
          _message = 'De beveiligde backend is tijdelijk niet bereikbaar.';
        });
      }
    }
  }

  Future<void> _logout() async {
    try {
      await widget.gateway.signOut();
    } on Object {
      // The local session is cleared in the UI even when the backend is unavailable.
    }
    if (!mounted) return;
    setState(() {
      _email = null;
      _message = null;
      _explicitlySignedOut = true;
      _state = _AuthenticationState.login;
    });
  }

  @override
  Widget build(BuildContext context) => switch (_state) {
    _AuthenticationState.loading => const _StatusPage(
      label: 'Beveiligde omgeving laden…',
    ),
    _AuthenticationState.error => _StatusPage(
      label: _message!,
      action: TextButton(
        onPressed: _restore,
        child: const Text('Opnieuw proberen'),
      ),
    ),
    _AuthenticationState.login => _LoginPage(
      message: _message,
      login:
          widget.loginBuilder?.call(
            (token) => unawaited(_authenticate(token)),
          ) ??
          GoogleLoginButton(
            clientId: AppConfiguration.googleClientId,
            onIdToken: (token) => unawaited(_authenticate(token)),
            attemptLightweightAuthentication: !_explicitlySignedOut,
          ),
    ),
    _AuthenticationState.authenticated => TechnicalApplicationShell(
      email: _email!,
      onLogout: () => unawaited(_logout()),
      versionGateway: widget.versionGateway,
      frontendVersionSource:
          widget.frontendVersionSource ?? HttpFrontendVersionSource(),
      dashboardGateway: widget.dashboardGateway ?? HttpDashboardGateway(),
      aiRunsGateway: widget.aiRunsGateway ?? HttpAiRunsGateway(),
      settingsGateway: widget.settingsGateway ?? HttpSettingsGateway(),
      acceptanceBypass: widget.acceptanceBypass,
    ),
  };
}

class _LoginPage extends StatelessWidget {
  const _LoginPage({required this.login, this.message});
  final Widget login;
  final String? message;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Image.asset(
                    'assets/brand/pvdd-commissie-assistent.png',
                    width: 92,
                    height: 92,
                    semanticLabel: 'Logo PvdD Commissie-assistent',
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'PvdD Commissie-assistent',
                    style: Theme.of(context).textTheme.headlineMedium,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Log in met een toegestaan Google-account.',
                    textAlign: TextAlign.center,
                  ),
                  if (message != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        message!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  const SizedBox(height: 24),
                  login,
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _StatusPage extends StatelessWidget {
  const _StatusPage({required this.label, this.action});
  final String label;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: Semantics(
        liveRegion: true,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text(label),
            action ?? const SizedBox.shrink(),
          ],
        ),
      ),
    ),
  );
}

class TechnicalApplicationShell extends StatefulWidget {
  const TechnicalApplicationShell({
    required this.email,
    required this.onLogout,
    required this.versionGateway,
    required this.frontendVersionSource,
    required this.dashboardGateway,
    required this.aiRunsGateway,
    required this.settingsGateway,
    required this.acceptanceBypass,
    super.key,
  });
  final String email;
  final VoidCallback onLogout;
  final VersionGateway versionGateway;
  final FrontendVersionSource frontendVersionSource;
  final DashboardGateway dashboardGateway;
  final AiRunsGateway aiRunsGateway;
  final SettingsGateway settingsGateway;
  final bool acceptanceBypass;
  @override
  State<TechnicalApplicationShell> createState() =>
      _TechnicalApplicationShellState();
}

class _TechnicalApplicationShellState extends State<TechnicalApplicationShell> {
  late int _selected;
  bool _updateAvailable = false;
  Timer? _timer;
  final _current = BuildIdentity.frontend();
  final _tracker = VersionUpdateTracker();

  @override
  void initState() {
    super.initState();
    _selected = switch (currentAppPath()) {
      '/standpunten' => 1,
      '/ai-runs' => 2,
      '/instellingen' => 3,
      '/versie' => 4,
      _ => 0,
    };
    unawaited(_checkUpdate());
    _timer = Timer.periodic(
      const Duration(minutes: 5),
      (_) => unawaited(_checkUpdate()),
    );
  }

  Future<void> _checkUpdate() async {
    try {
      final latest = await widget.frontendVersionSource.latest();
      if (mounted && _tracker.shouldNotify(_current, latest)) {
        setState(() => _updateAvailable = true);
      }
    } on Object {
      /* Een updatecheck mag de app nooit blokkeren. */
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final desktop = constraints.maxWidth >= 800;
      final content = switch (_selected) {
        0 => MeetingOverviewPage(gateway: widget.dashboardGateway),
        1 => PolicyPage(gateway: HttpPolicyGateway()),
        2 => AiRunsPage(gateway: widget.aiRunsGateway),
        3 => SettingsPage(gateway: widget.settingsGateway),
        _ => _VersionPage(frontend: _current, gateway: widget.versionGateway),
      };
      return Scaffold(
        drawer: desktop
            ? null
            : Drawer(child: SafeArea(child: _navigation(close: true))),
        appBar: AppBar(
          leading: desktop
              ? null
              : Builder(
                  builder: (context) => IconButton(
                    tooltip: 'Menu openen',
                    onPressed: () => Scaffold.of(context).openDrawer(),
                    icon: const Icon(Icons.menu),
                  ),
                ),
          title: Row(
            children: [
              Image.asset(
                'assets/brand/pvdd-commissie-assistent.png',
                width: 36,
                height: 36,
                semanticLabel: 'Logo PvdD Commissie-assistent',
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Text(
                  constraints.maxWidth < 420
                      ? 'PvdD'
                      : 'PvdD Commissie-assistent',
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          actions: [
            if (constraints.maxWidth >= 560)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Center(child: Text(widget.email)),
              ),
            if (!widget.acceptanceBypass)
              IconButton(
                tooltip: 'Uitloggen',
                onPressed: widget.onLogout,
                icon: const Icon(Icons.logout),
              ),
          ],
        ),
        body: Row(
          children: [
            if (desktop) SizedBox(width: 250, child: _navigation()),
            Expanded(
              child: Column(
                children: [
                  if (widget.acceptanceBypass)
                    const MaterialBanner(
                      leading: Icon(Icons.science_outlined),
                      content: Text(
                        'ACCEPTANCE — gemockte gegevens — geen authenticatie',
                      ),
                      actions: [SizedBox.shrink()],
                    ),
                  if (_updateAvailable)
                    MaterialBanner(
                      content: const Text(
                        'Een nieuwe frontendversie is beschikbaar.',
                      ),
                      actions: [
                        TextButton(
                          onPressed: reloadPage,
                          child: const Text('Vernieuwen'),
                        ),
                      ],
                    ),
                  Expanded(child: content),
                ],
              ),
            ),
          ],
        ),
      );
    },
  );

  Widget _navigation({bool close = false}) => Material(
    color: PvddColors.sidebar,
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 8, 20, 20),
            child: Text(
              'COMMISSIE RUIMTE',
              style: TextStyle(
                color: Colors.white70,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          _destination(0, Icons.dashboard_outlined, 'Agenda', close),
          _destination(1, Icons.policy_outlined, 'Standpunten', close),
          _destination(2, Icons.smart_toy_outlined, 'AI-runs', close),
          _destination(3, Icons.settings_outlined, 'Instellingen', close),
          _destination(4, Icons.info_outline, 'Over deze versie', close),
        ],
      ),
    ),
  );

  Widget _destination(int index, IconData icon, String label, bool close) =>
      Semantics(
        selected: _selected == index,
        child: ListTile(
          selected: _selected == index,
          selectedTileColor: PvddColors.sidebarSelected,
          textColor: Colors.white,
          iconColor: Colors.white,
          leading: Icon(icon),
          title: Text(label),
          onTap: () {
            setState(() => _selected = index);
            navigateToAppPath(switch (index) {
              1 => '/standpunten',
              2 => '/ai-runs',
              3 => '/instellingen',
              4 => '/versie',
              _ => '/agenda',
            });
            if (close) Navigator.of(context).pop();
          },
        ),
      );
}

class _VersionPage extends StatelessWidget {
  const _VersionPage({required this.frontend, required this.gateway});
  final BuildIdentity frontend;
  final VersionGateway gateway;
  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    padding: const EdgeInsets.all(24),
    child: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 850),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Over deze versie',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 16),
            _identityCard('Frontend', frontend),
            const SizedBox(height: 16),
            FutureBuilder<BuildIdentity>(
              future: gateway.backend(),
              builder: (context, snapshot) => _identityCard(
                'Backend',
                snapshot.data ??
                    const BuildIdentity(
                      version: BuildIdentity.unknown,
                      gitRevision: BuildIdentity.unknown,
                      buildTime: BuildIdentity.unknown,
                      environment: BuildIdentity.unknown,
                      identity: BuildIdentity.unknown,
                    ),
              ),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _identityCard(String title, BuildIdentity identity) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
          ),
          const SizedBox(height: 8),
          SelectableText(
            'Build: ${identity.identity}\nSHA: ${identity.gitRevision}\nGebouwd: ${identity.buildTime}\nOmgeving: ${identity.environment}',
          ),
        ],
      ),
    ),
  );
}
