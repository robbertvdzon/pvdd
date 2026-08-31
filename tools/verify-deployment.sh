#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
renderer=()
if command -v kustomize >/dev/null; then renderer=(kustomize build); else renderer=(kubectl kustomize); fi
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT

"${renderer[@]}" "$root_dir/deploy/overlays/acceptance" > "$temporary/acceptance.yaml"
"${renderer[@]}" "$root_dir/deploy/overlays/production" > "$temporary/production.yaml"

acceptance="$temporary/acceptance.yaml"
production="$temporary/production.yaml"
rg -q 'name: pvdd-meeting-source-mock' "$acceptance"
rg -q 'PVDD_AGENT_RUNTIME_PROVIDER: MOCKED' "$acceptance"
rg -q 'PVDD_MEETING_SOURCE_BASE_URL: http://pvdd-meeting-source-mock:8080' "$acceptance"
! rg -q 'noordholland.bestuurlijkeinformatie.nl|gpt-5.6-sol|PRODUCTION_AGENT_RUNTIME' "$acceptance"

! rg -q 'pvdd-meeting-source-mock|MOCKED|mock-model|ACCEPTANCE_AGENT_RUNTIME' "$production"
rg -q 'host: pvdd.vdzonsoftware.nl' "$production"
rg -q 'PVDD_AGENT_RUNTIME_PROVIDER: CODEX' "$production"
rg -q 'PVDD_MEETING_SOURCE_BASE_URL: https://noordholland.bestuurlijkeinformatie.nl' "$production"

for manifest in "$acceptance" "$production"; do
  test "$(rg -c 'allowPrivilegeEscalation: false' "$manifest")" -ge 3
  test "$(rg -c 'runAsNonRoot: true' "$manifest")" -ge 3
  test "$(rg -c 'resources:' "$manifest")" -ge 3
  rg -q 'startupProbe:' "$manifest"
  rg -q 'readinessProbe:' "$manifest"
  rg -q 'livenessProbe:' "$manifest"
done

echo 'OpenShift-overlays en omgevingsgrenzen zijn geldig.'
