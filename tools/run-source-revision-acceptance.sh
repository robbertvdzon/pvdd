#!/usr/bin/env bash
set -euo pipefail

app_url="${PVDD_ACCEPTANCE_URL:-https://pvdd-acceptance.vdzonsoftware.nl}"
mock_url="${PVDD_SOURCE_MOCK_URL:-http://127.0.0.1:18081}"
check_interval_seconds="${PVDD_CHECK_INTERVAL_SECONDS:-11}"

command -v curl >/dev/null || { echo 'curl ontbreekt.' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq ontbreekt.' >&2; exit 1; }

set_scenario() {
  curl --fail --silent --show-error --request POST "$mock_url/fixtures/control/$1" >/dev/null
}

check_now() {
  curl --fail --silent --show-error --request POST \
    --header "Idempotency-Key: plan4-$1-$(date +%s%N)" \
    "$app_url/api/meetings/check-now"
}

wait_until_settled() {
  local expected_revision="$1" deadline=$((SECONDS + 90)) overview=''
  while (( SECONDS < deadline )); do
    overview="$(curl --fail --silent --show-error "$app_url/api/meetings/next")"
    if [[ "$(jq -r '.meeting.revisionNumber // 0' <<<"$overview")" -ge "$expected_revision" ]] &&
       [[ "$(jq -r '.meeting.status // ""' <<<"$overview")" = COMPLETE ]] &&
       [[ "$(jq -r '.meeting.revisionStatus // ""' <<<"$overview")" = CURRENT ]]; then
      return 0
    fi
    sleep 2
  done
  echo "revisie $expected_revision werd niet binnen 90 seconden actueel" >&2
  return 1
}

expect_status() {
  scenario_name="$1"
  expected_status="$2"
  expected_difference="${3:-}"
  set_scenario "$scenario_name"
  response="$(check_now "$scenario_name")"
  actual_status="$(jq -r '.status' <<<"$response")"
  [[ "$actual_status" == "$expected_status" ]] || {
    echo "$scenario_name: verwacht $expected_status, kreeg $actual_status" >&2
    exit 1
  }
  if [[ -n "$expected_difference" ]]; then
    jq -e --arg difference "$expected_difference" '.differences | index($difference) != null' <<<"$response" >/dev/null || {
      echo "$scenario_name: verschil $expected_difference ontbreekt" >&2
      exit 1
    }
  fi
  revision_number="$(jq -r '.revisionNumber // 0' <<<"$response")"
  if [[ "$actual_status" = IMPORTED ]]; then wait_until_settled "$revision_number"; fi
  printf '%-24s %s revision=%s\n' "$scenario_name" "$actual_status" "$revision_number"
  sleep "$check_interval_seconds"
}

expect_status preview AGENDA_UNPUBLISHED
expect_status published IMPORTED PUBLICATION_STATUS
expect_status formatting-only UNCHANGED
expect_status item-added IMPORTED ITEM_ADDED
expect_status published IMPORTED ITEM_WITHDRAWN
expect_status item-withdrawn IMPORTED ITEM_WITHDRAWN
expect_status published IMPORTED ITEM_ADDED
expect_status item-moved IMPORTED ITEM_MOVED
expect_status published IMPORTED ITEM_MOVED
expect_status category-changed IMPORTED CATEGORY_CHANGED
expect_status published IMPORTED CATEGORY_CHANGED
expect_status metadata-changed IMPORTED METADATA_CHANGED
expect_status published IMPORTED METADATA_CHANGED
expect_status document-added IMPORTED DOCUMENT_ADDED
expect_status published IMPORTED DOCUMENT_REMOVED
expect_status document-removed IMPORTED DOCUMENT_REMOVED
expect_status published IMPORTED DOCUMENT_ADDED
expect_status same-url-new-bytes IMPORTED DOCUMENT_CONTENT_CHANGED
expect_status published IMPORTED DOCUMENT_CONTENT_CHANGED

echo 'Alle synthetische bronrevisies zijn door de acceptance-API verwerkt.'
