#!/usr/bin/env bash
set -euo pipefail

required_files=(
  README.md
  docs/microservice-specificatie.md
  docs/stappenplannen/01-technische-fundering.md
  docs/stappenplannen/02-functionele-mvp.md
  docs/stappenplannen/03-software-factory-aansluiting.md
  docs/factory/README.md
  docs/factory/functional-spec.md
  docs/factory/technical-spec.md
  docs/factory/development.md
  docs/factory/deployment.md
  docs/factory/secrets-local.md
  docs/factory/agent-runtime.md
  docs/adr/README.md
  docs/adr/0001-kotlin-spring-backend.md
  docs/adr/0002-flutter-webfrontend.md
  docs/adr/0003-google-sso.md
  docs/adr/0004-postgresql-flyway.md
  docs/adr/0005-agent-runtime.md
  docs/adr/0006-openshift-gitops.md
)

for required_file in "${required_files[@]}"; do
  if [[ ! -s "$required_file" ]]; then
    echo "Ontbrekend of leeg verplicht document: $required_file" >&2
    exit 1
  fi
done

if grep -RInE --exclude='pubspec.lock' --exclude-dir='.dart_tool' --exclude-dir='build' --exclude-dir='target' \
  '(AIza[0-9A-Za-z_-]{20,}|gh[pousr]_[0-9A-Za-z]{20,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)' \
  README.md docs .factory .github tools backend frontend secrets.env.example docker-compose.yml; then
  echo 'Mogelijk geheim aangetroffen in getrackte documentatie/configuratie.' >&2
  exit 1
fi

if grep -RInE 'github[- ]?(oauth|login)|inloggen via github' README.md docs; then
  echo 'Verouderde GitHub-loginrequirement aangetroffen; PvdD gebruikt Google SSO.' >&2
  exit 1
fi

echo 'Documentatie en repositoryhygiëne zijn in orde.'
