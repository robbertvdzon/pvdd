#!/usr/bin/env bash
set -euo pipefail

# Optional operator helper. The acceptance backend normally prepares each exact job itself.
# Never clear a tenant/global fixture store or use a wildcard/registration-order response.
runtime_url="${AGENT_RUNTIME_ACCEPTANCE_URL:-https://agent-runtime-acceptance.vdzonsoftware.nl}"
case "$runtime_url" in
  https://agent-runtime-acceptance.vdzonsoftware.nl|http://agent-runtime-server.agent-runtime-acceptance.svc.cluster.local) ;;
  *) echo 'Alleen de bekende Agent Runtime-acceptatieorigin is toegestaan.' >&2; exit 2 ;;
esac
[[ $# -eq 2 ]] || { echo "Gebruik: $0 EXACTE_JOB_IDEMPOTENCY_KEY RESULTAAT_JSON" >&2; exit 2; }
job_key="$1"
result_file="$2"
[[ "$job_key" =~ ^[A-Za-z0-9_-]{1,160}$ && -f "$result_file" ]] || { echo 'Een exacte jobsleutel en bestaand resultaatbestand zijn verplicht.' >&2; exit 2; }
[[ -n "${AGENT_RUNTIME_TEST_CONTROL_TOKEN:-}" ]] || { echo 'AGENT_RUNTIME_TEST_CONTROL_TOKEN is verplicht; geen consument- of admincredential.' >&2; exit 2; }
[[ "$AGENT_RUNTIME_TEST_CONTROL_TOKEN" != *$'\n'* && "$AGENT_RUNTIME_TEST_CONTROL_TOKEN" != *$'\r'* && "$AGENT_RUNTIME_TEST_CONTROL_TOKEN" != *'"'* && "$AGENT_RUNTIME_TEST_CONTROL_TOKEN" != *'\'* ]] || { echo 'Ongeldig credentialformaat.' >&2; exit 2; }

# Credential only via a private pipe/config descriptor, not curl arguments, prompt or artifacts.
jq -e -n --arg key "$job_key" --slurpfile result "$result_file" \
  'if ($result|length)==1 and ($result[0]|type)=="object" then {tenantId:"pvdd",idempotencyKey:$key,result:$result[0]} else error("Een JSON-resultaatobject is verplicht") end' |
  curl --fail --silent --show-error --connect-timeout 5 --max-time 20 \
    --config <(printf 'header = "Authorization: Bearer %s"\n' "$AGENT_RUNTIME_TEST_CONTROL_TOKEN") \
    -X POST -H 'Content-Type: application/json' --data-binary @- \
    "$runtime_url/v2/test-control/mocks" >/dev/null
printf 'Exacte v2-acceptatiefixture geregistreerd voor tenant pvdd.\n'
