#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd)"
frontend_root="$repository_root/frontend"
revision="${PVDD_GIT_REVISION:-$(git -C "$repository_root" rev-parse HEAD 2>/dev/null || printf '0000000000000000000000000000000000000000')}"

cd "$frontend_root"
bundle_a="$(PVDD_GIT_REVISION="$revision" PVDD_BUILD_TIME=2026-08-31T10:00:00Z ./build-web.sh | tail -1)"
bundle_b="$(PVDD_GIT_REVISION="$revision" PVDD_BUILD_TIME=2026-08-31T10:00:01Z ./build-web.sh | tail -1)"
[[ "$bundle_a" != "$bundle_b" ]]

# Zonder Docker is de nginx-container niet te starten. Het vangnet valt dan niet om, maar controleert
# het cachecontract statisch op `nginx.conf` — dezelfde regels die de container-asserties afdwingen.
# Dit volgt de lijn die `docs/factory/development.md` al voor de databasetests vastlegt: overslaan in
# plaats van het hele vangnet laten falen. Overgeslagen asserties zijn geen bewijs; met Docker (CI en
# lokaal) draait het volledige contract ongewijzigd.
if ! docker info >/dev/null 2>&1; then
  [[ "$bundle_b" =~ ^main\.[0-9a-f]{16}\.js$ ]]
  test -f "build/web/$bundle_b"
  test -f build/web/index.html
  test -f build/web/version.json
  [[ ! -e build/web/flutter_service_worker.js ]]
  for configuration in nginx.conf nginx-acceptance.conf; do
    grep -qF 'location ~* "^/main\.[0-9a-f]{16}\.js$"' "$configuration"
    grep -qF 'add_header Cache-Control "public, max-age=31536000, immutable" always;' "$configuration"
    grep -qF 'location = /version.json' "$configuration"
    grep -qF 'location = /flutter_service_worker.js' "$configuration"
    grep -qF 'add_header Cache-Control "no-cache" always;' "$configuration"
    grep -qF 'add_header Cache-Control "no-store" always;' "$configuration"
    grep -qF 'try_files $uri =404;' "$configuration"
    grep -qF 'try_files $uri $uri/ /index.html;' "$configuration"
    grep -qF 'caches.keys' "$configuration"
    grep -qF 'unregister' "$configuration"
  done
  printf 'cachecontract: %s -> %s (statisch; geen Docker voor de nginx-container)\n' "$bundle_a" "$bundle_b"
  exit 0
fi

test_directory="$(mktemp -d)"
container_id="$(docker run -d --rm -p 127.0.0.1::8080 --add-host backend:127.0.0.1 \
  -v "$frontend_root/build/web:/usr/share/nginx/html:ro" \
  -v "$frontend_root/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginxinc/nginx-unprivileged:1.27-alpine)"
cleanup() {
  docker stop "$container_id" >/dev/null 2>&1 || true
  rm -rf "$test_directory"
}
trap cleanup EXIT
published_port="$(docker port "$container_id" 8080/tcp | awk -F: '{print $NF}')"
base_url="http://127.0.0.1:$published_port"
for attempt in {1..20}; do curl -fsS "$base_url/" >/dev/null && break; sleep 0.25; done

curl -sS -D "$test_directory/index" -o /dev/null "$base_url/"
curl -sS -D "$test_directory/bundle" -o /dev/null "$base_url/$bundle_b"
curl -sS -D "$test_directory/version" -o /dev/null "$base_url/version.json"
curl -sS -D "$test_directory/worker" -o "$test_directory/worker.js" "$base_url/flutter_service_worker.js"
curl -sS -D "$test_directory/old" -o /dev/null "$base_url/$bundle_a"

grep -qi '^Cache-Control: no-cache' "$test_directory/index"
grep -qi '^Cache-Control: public, max-age=31536000, immutable' "$test_directory/bundle"
grep -qi '^Cache-Control: no-store' "$test_directory/version"
grep -qi '^Cache-Control: no-store' "$test_directory/worker"
grep -q 'caches.keys' "$test_directory/worker.js"
grep -q 'unregister' "$test_directory/worker.js"
grep -q '^HTTP/1.1 404' "$test_directory/old"
printf 'cachecontract: %s -> %s\n' "$bundle_a" "$bundle_b"
