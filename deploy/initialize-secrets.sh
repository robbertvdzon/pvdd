#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$deploy_dir/.." && pwd)"
target="$root_dir/secrets.env"
runtime_source="${PVDD_RUNTIME_SECRET_SOURCE:-$root_dir/../agent-runtime/secrets.env}"
software_factory_source="${PVDD_SOFTWARE_FACTORY_SECRET_SOURCE:-$root_dir/../softwarefactory/secrets.env}"

[[ -f "$runtime_source" && -f "$software_factory_source" ]] || { echo "Agent Runtime- of Software Factory-secretbron ontbreekt." >&2; exit 1; }
value_for() { awk -v key="$2" 'index($0,key "=")==1 {print substr($0,length(key)+2)}' "$1" | tail -1; }
random_secret() { openssl rand -base64 48 | tr -d '\n'; }

google_client_id="$(value_for "$software_factory_source" SF_GOOGLE_CLIENT_ID)"
production_runtime_token="$(value_for "$runtime_source" AR_PVDD_TOKEN)"
acceptance_runtime_token="$(value_for "$runtime_source" AR_PVDD_ACCEPTANCE_TOKEN)"
[[ -n "$google_client_id" && -n "$production_runtime_token" && -n "$acceptance_runtime_token" ]] || { echo "Vereiste bronwaarden ontbreken." >&2; exit 1; }

if [[ -f "$target" ]]; then
  existing_production_password="$(value_for "$target" PVDD_PRODUCTION_DATABASE_PASSWORD)"
  existing_acceptance_password="$(value_for "$target" PVDD_ACCEPTANCE_DATABASE_PASSWORD)"
  existing_tooling_token="$(value_for "$target" PVDD_PRODUCTION_TOOLING_TOKEN)"
else
  existing_production_password=""
  existing_acceptance_password=""
  existing_tooling_token=""
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
chmod 600 "$tmp"
{
  printf 'PVDD_GOOGLE_CLIENT_ID=%s\n' "$google_client_id"
  printf 'PVDD_PRODUCTION_AGENT_RUNTIME_TOKEN=%s\n' "$production_runtime_token"
  printf 'PVDD_PRODUCTION_TOOLING_TOKEN=%s\n' "${existing_tooling_token:-$(random_secret)}"
  printf 'PVDD_ACCEPTANCE_AGENT_RUNTIME_TOKEN=%s\n' "$acceptance_runtime_token"
  printf 'PVDD_PRODUCTION_DATABASE_PASSWORD=%s\n' "${existing_production_password:-$(random_secret)}"
  printf 'PVDD_ACCEPTANCE_DATABASE_PASSWORD=%s\n' "${existing_acceptance_password:-$(random_secret)}"
} > "$tmp"
mv "$tmp" "$target"
chmod 600 "$target"
trap - EXIT
echo "PvdD-secretbron zonder weergave aangemaakt of bijgewerkt." >&2
