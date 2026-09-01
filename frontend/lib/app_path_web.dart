import 'package:web/web.dart' as web;

String currentAppPath() => web.window.location.pathname;

void navigateToAppPath(String path) {
  if (web.window.location.pathname == path) return;
  web.window.history.pushState(null, '', path);
}
