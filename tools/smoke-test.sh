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
auth_response_file="$(mktemp)"
trap 'rm -f "$auth_response_file"' EXIT
auth_status="$(curl --silent --output "$auth_response_file" --write-out '%{http_code}' --connect-timeout 5 --max-time 20 "$base_url/api/auth/me")"

if [[ "$environment_name" = acceptance ]]; then
  test "$auth_status" = 200
  test "$(jq -r '.email' "$auth_response_file")" = 'acceptance-tester@pvdd.invalid'
else
  test "$auth_status" = 401
fi
test "$(jq -r '.gitRevision' <<<"$frontend_version")" = "$expected_revision"
test "$(jq -r '.gitRevision' <<<"$backend_version")" = "$expected_revision"
test "$(jq -r '.environment' <<<"$frontend_version")" = "$environment_name"
test "$(jq -r '.environment' <<<"$backend_version")" = "$environment_name"
grep -qi '^cache-control: no-cache' <<<"$headers"
printf 'smoke-test: %s revisie=%s authenticatie=correct\n' "$environment_name" "$expected_revision"
