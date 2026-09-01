#!/usr/bin/env bash
set -euo pipefail

runtime_url="${AGENT_RUNTIME_ACCEPTANCE_URL:-https://agent-runtime-acceptance.vdzonsoftware.nl}"
admin_token="${AGENT_RUNTIME_ADMIN_TOKEN:-}"
[[ -n "$admin_token" ]] || { echo 'AGENT_RUNTIME_ADMIN_TOKEN is verplicht.' >&2; exit 2; }

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixtures="$root_dir/meeting-source-mock/fixtures/runtime"
curl_options=(--fail --silent --show-error --connect-timeout 5 --max-time 20)
authorization="Authorization: Bearer $admin_token"

existing="$(curl "${curl_options[@]}" -H "$authorization" "$runtime_url/v1/test-control/mocks")"
jq -r '.[] | select(.tenantId == "pvdd") | .id' <<<"$existing" | while IFS= read -r id; do
  [[ -z "$id" ]] || curl "${curl_options[@]}" -X DELETE -H "$authorization" "$runtime_url/v1/test-control/mocks/$id" >/dev/null
done

register_result() {
  local result_file="$1" idempotency_key="${2:-}"
  jq -n --arg tenantId pvdd --arg key "$idempotency_key" --slurpfile result "$result_file" \
    '{tenantId:$tenantId,result:$result[0]} + (if $key == "" then {} else {idempotencyKey:$key} end)' |
    curl "${curl_options[@]}" -X POST -H "$authorization" -H 'Content-Type: application/json' \
      --data-binary @- "$runtime_url/v1/test-control/mocks" >/dev/null
}

register_result "$fixtures/ab-housing.json"
register_result "$fixtures/ab-mobility.json"
register_result "$fixtures/c-nature.json"
# De bronrevisiereeks verwerkt wijzigingen één voor één. De centrale Runtime-mocks
# zijn bewust eenmalig, dus registreer de verwachte nieuwe puntfingerprints in
# dezelfde deterministische volgorde als de scenario-driver.
register_result "$fixtures/ab-green.json"
register_result "$fixtures/c-mobility.json"
for _ in 1 2 3 4; do register_result "$fixtures/ab-housing.json"; done
register_result "$fixtures/large-notes.json" 'pvdd-acceptance-large-notes-1'

jq -n --arg tenantId pvdd --arg key 'pvdd-acceptance-error' --slurpfile failure "$fixtures/mock-error.json" \
  '{tenantId:$tenantId,idempotencyKey:$key,errorCode:$failure[0].errorCode,errorMessage:$failure[0].errorMessage}' |
  curl "${curl_options[@]}" -X POST -H "$authorization" -H 'Content-Type: application/json' \
    --data-binary @- "$runtime_url/v1/test-control/mocks" >/dev/null

printf 'Agent Runtime-acceptatiemocks geregistreerd voor tenant pvdd.\n'
