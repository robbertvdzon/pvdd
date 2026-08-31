#!/usr/bin/env bash
set -euo pipefail

application_version="${PVDD_APP_VERSION:-0.1.0}"
git_revision="${PVDD_GIT_REVISION:-$(git rev-parse HEAD 2>/dev/null || printf '0000000000000000000000000000000000000000')}"
build_time="${PVDD_BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
runtime_environment="${PVDD_ENVIRONMENT:-local}"
google_client_id="${PVDD_GOOGLE_CLIENT_ID:-}"

[[ "$application_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Ongeldige applicatieversie.' >&2; exit 1; }
[[ "$git_revision" =~ ^[0-9a-f]{40}$ ]] || { echo 'Ongeldige Git-revisie.' >&2; exit 1; }
[[ "$build_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || { echo 'Ongeldige UTC-buildtijd.' >&2; exit 1; }
[[ "$runtime_environment" =~ ^(local|acceptance|production)$ ]] || { echo 'Ongeldige omgeving.' >&2; exit 1; }

rm -f build/web/main.*.js build/web/flutter_service_worker.js
flutter build web --release --pwa-strategy=none --no-web-resources-cdn \
  --dart-define="APP_VERSION=$application_version" \
  --dart-define="GIT_REVISION=$git_revision" \
  --dart-define="BUILD_TIME=$build_time" \
  --dart-define="ENVIRONMENT=$runtime_environment" \
  --dart-define="GOOGLE_CLIENT_ID=$google_client_id"

rm -f build/web/flutter_service_worker.js
bundle_hash="$(shasum -a 256 build/web/main.dart.js | awk '{print substr($1, 1, 16)}')"
bundle_name="main.$bundle_hash.js"
mv build/web/main.dart.js "build/web/$bundle_name"
perl -pi -e "s/main\\.dart\\.js/$bundle_name/g" build/web/flutter_bootstrap.js
frontend_identity="$application_version+${git_revision:0:12}"
printf '{"applicationVersion":"%s","gitRevision":"%s","buildTime":"%s","environment":"%s","frontendBuildIdentity":"%s"}\n' \
  "$application_version" "$git_revision" "$build_time" "$runtime_environment" "$frontend_identity" > build/web/version.json

test -f "build/web/$bundle_name"
grep -q "$bundle_name" build/web/flutter_bootstrap.js
! grep -q 'main\.dart\.js' build/web/flutter_bootstrap.js
printf '%s\n' "$bundle_name"
