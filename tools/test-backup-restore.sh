#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"
docker compose up -d --wait database >/dev/null
temporary="$(mktemp -d)"
database_name="pvdd_restore_test"
cleanup() {
  docker compose exec -T database dropdb --if-exists -U pvdd "$database_name" >/dev/null 2>&1 || true
  rm -rf "$temporary"
}
trap cleanup EXIT

dump="$temporary/pvdd.dump"
docker compose exec -T database pg_dump -U pvdd -d pvdd --format=custom --no-owner --no-acl > "$dump"
test -s "$dump"
shasum -a 256 "$dump" > "$dump.sha256"
(cd "$temporary" && shasum -a 256 --check "$(basename "$dump.sha256")")

docker compose exec -T database dropdb --if-exists -U pvdd "$database_name" >/dev/null
docker compose exec -T database createdb -U pvdd "$database_name"
docker compose exec -T database pg_restore -U pvdd -d "$database_name" --no-owner --no-acl < "$dump"
table_count="$(docker compose exec -T database psql -U pvdd -d "$database_name" -Atc "select count(*) from information_schema.tables where table_schema='public' and table_name='application_metadata'")"
test "$table_count" = 1
echo 'Backuphash en restore naar een lege testdatabase zijn geslaagd.'
