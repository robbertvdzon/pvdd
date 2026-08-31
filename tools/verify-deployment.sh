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
grep -q 'name: pvdd-meeting-source-mock' "$acceptance"
grep -q 'PVDD_AGENT_RUNTIME_PROVIDER: MOCKED' "$acceptance"
grep -q 'PVDD_MEETING_SOURCE_BASE_URL: http://pvdd-meeting-source-mock:8080' "$acceptance"
! grep -Eq 'noordholland.bestuurlijkeinformatie.nl|gpt-5.6-sol|PRODUCTION_AGENT_RUNTIME' "$acceptance"

! grep -Eq 'pvdd-meeting-source-mock|MOCKED|mock-model|ACCEPTANCE_AGENT_RUNTIME' "$production"
grep -q 'host: pvdd.vdzonsoftware.nl' "$production"
grep -q 'PVDD_AGENT_RUNTIME_PROVIDER: CODEX' "$production"
grep -q 'PVDD_MEETING_SOURCE_BASE_URL: https://noordholland.bestuurlijkeinformatie.nl' "$production"

for manifest in "$acceptance" "$production"; do
  test "$(grep -c 'allowPrivilegeEscalation: false' "$manifest")" -ge 3
  test "$(grep -c 'runAsNonRoot: true' "$manifest")" -ge 3
  test "$(grep -c 'resources:' "$manifest")" -ge 3
  grep -q 'startupProbe:' "$manifest"
  grep -q 'readinessProbe:' "$manifest"
  grep -q 'livenessProbe:' "$manifest"
done

echo 'OpenShift-overlays en omgevingsgrenzen zijn geldig.'
