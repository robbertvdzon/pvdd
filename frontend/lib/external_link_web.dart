import 'package:web/web.dart' as web;

void openExternalLink(Uri uri) {
  if (uri.scheme == 'https') web.window.open(uri.toString(), '_blank');
}
