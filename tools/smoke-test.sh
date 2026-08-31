#!/usr/bin/env bash
set -euo pipefail

environment_name="${1:-}"
expected_revision="${2:-}"
case "$environment_name" in
  acceptance) base_url="https://pvdd-acceptance.vdzonsoftware.nl" ;;
  production) base_url="https://pvdd.vdzonsoftware.nl" ;;
  *) echo 'Gebruik: tools/smoke-test.sh {acceptance|production} <volledige-git-revisie>' >&2; exit 2 ;;
esac
[[ "$expected_revision" =~ ^[0-9a-f]{40}$ ]] || { echo 'Een volledige Git-revisie is verplicht.' >&2; exit 2; }

curl_options=(--fail --silent --show-error --connect-timeout 5 --max-time 20)
headers="$(curl "${curl_options[@]}" --head "$base_url/")"
frontend_version="$(curl "${curl_options[@]}" "$base_url/version.json")"
backend_version="$(curl "${curl_options[@]}" "$base_url/api/version")"
curl "${curl_options[@]}" "$base_url/actuator/health/liveness" >/dev/null
curl "${curl_options[@]}" "$base_url/actuator/health/readiness" >/dev/null
auth_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --connect-timeout 5 --max-time 20 "$base_url/api/auth/me")"

test "$auth_status" = 401
test "$(jq -r '.gitRevision' <<<"$frontend_version")" = "$expected_revision"
test "$(jq -r '.gitRevision' <<<"$backend_version")" = "$expected_revision"
test "$(jq -r '.environment' <<<"$backend_version")" = "$environment_name"
grep -qi '^cache-control: no-cache' <<<"$headers"
printf 'smoke-test: %s revisie=%s login=afgeschermd\n' "$environment_name" "$expected_revision"
