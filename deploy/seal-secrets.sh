#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$deploy_dir/.." && pwd)"
source_file="${PVDD_SEAL_SOURCE:-$root_dir/secrets.env}"
cert_file="${PVDD_SEAL_CERT:-$root_dir/../robberts-infrastructure/manifests/cluster-bootstrap/cluster-cert.pem}"

command -v kubeseal >/dev/null || { echo "kubeseal ontbreekt." >&2; exit 1; }
[[ -f "$source_file" && -f "$cert_file" ]] || { echo "Secretbron of clustercertificaat ontbreekt." >&2; exit 1; }
value_for() { awk -v key="$1" 'index($0,key "=")==1 {print substr($0,length(key)+2)}' "$source_file" | tail -1; }

for environment in acceptance production; do
  if [[ "$environment" == acceptance ]]; then namespace="pvdd-acceptance"; else namespace="pvdd"; fi
  environment_upper="$(printf '%s' "$environment" | tr '[:lower:]' '[:upper:]')"
  runtime_key="PVDD_${environment_upper}_AGENT_RUNTIME_TOKEN"
  database_key="PVDD_${environment_upper}_DATABASE_PASSWORD"
  for key in PVDD_GOOGLE_CLIENT_ID "$runtime_key" "$database_key"; do
    [[ -n "$(value_for "$key")" ]] || { echo "Verplichte key ontbreekt: $key" >&2; exit 1; }
  done
  plain="$(mktemp)"
  sealed="$(mktemp)"
  trap 'rm -f "${plain:-}" "${sealed:-}"' EXIT
  chmod 600 "$plain" "$sealed"
  {
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: pvdd-secrets\n  namespace: %s\ntype: Opaque\nstringData:\n' "$namespace"
    printf '  PVDD_DATABASE_USER: pvdd\n'
    printf '  PVDD_DATABASE_PASSWORD: |-\n    %s\n' "$(value_for "$database_key")"
    printf '  PVDD_GOOGLE_CLIENT_ID: |-\n    %s\n' "$(value_for PVDD_GOOGLE_CLIENT_ID)"
    printf '  PVDD_AGENT_RUNTIME_TOKEN: |-\n    %s\n' "$(value_for "$runtime_key")"
  } > "$plain"
  kubeseal --cert "$cert_file" --format yaml < "$plain" > "$sealed"
  mv "$sealed" "$deploy_dir/overlays/$environment/sealed-secret.yaml"
  rm -f "$plain"
  trap - EXIT
  echo "SealedSecret voor $namespace geschreven." >&2
done
