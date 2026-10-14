#!/usr/bin/env bash
# TPF-49: runs the UI nginx template through the nginx image ui/Dockerfile ships, in front of a
# stub upstream (stub-upstream.conf), and checks that every upstream answer passes as sent while
# nginx's own errors keep the branded pages under ui/public/error_pages/.
#
#   bash ui/nginx/test/error-passthrough.sh
#
# NGINX_TEMPLATE overrides the template under test, for example to see the rows the old template
# fails: git show origin/main:ui/nginx/default.conf.template > /tmp/main.conf.template, then
# NGINX_TEMPLATE=/tmp/main.conf.template bash ui/nginx/test/error-passthrough.sh
# Needs docker and curl. Host ports are picked by docker, on 127.0.0.1 only.
set -eu

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$(cd "$TEST_DIR/../.." && pwd)"
TEMPLATE="${NGINX_TEMPLATE:-$UI_DIR/nginx/default.conf.template}"
TEMPLATE="$(cd "$(dirname "$TEMPLATE")" && pwd)/$(basename "$TEMPLATE")"
PAGES="$UI_DIR/public/error_pages"

IMAGE="$(sed -n 's/^FROM \(nginx:[^ ]*\) AS artifact-stage$/\1/p' "$UI_DIR/Dockerfile")"
if [ -z "$IMAGE" ]; then
    echo "FAIL: no 'FROM nginx:... AS artifact-stage' line in $UI_DIR/Dockerfile" >&2
    exit 1
fi
echo "image:    $IMAGE"
echo "template: $TEMPLATE"

SUFFIX="tpf49-$$-$RANDOM"
NET="net-$SUFFIX"
STUB="stub-$SUFFIX"
UI="ui-$SUFFIX"
UI_DOWN="uidown-$SUFFIX"
WORK="$(mktemp -d)"

cleanup() {
    docker rm -f "$STUB" "$UI" "$UI_DOWN" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# The document root: the branded pages and a stand-in SPA shell, readable by nginx's worker.
SPA='<!doctype html><title>tpf49-spa</title>'
mkdir -p "$WORK/html/error_pages" "$WORK/expect" "$WORK/out"
cp "$PAGES"/* "$WORK/html/error_pages/"
printf '%s' "$SPA" > "$WORK/html/index.html"
chmod -R a+rX "$WORK"

docker pull -q "$IMAGE" >/dev/null
docker network create "$NET" >/dev/null

host_port() { # container, container port -> the 127.0.0.1 port docker published it on
    docker port "$1" "$2/tcp" | sed -n 's/^127\.0\.0\.1:\([0-9]*\)$/\1/p' | head -n 1
}

# Waits until url answers status: 20 tries, half a second apart. Prints the logs and exits otherwise.
wait_for() { # container, url, status
    local i code
    for i in $(seq 1 20); do
        code="$(curl -s -o /dev/null -w '%{http_code}' "$2" || true)"
        if [ "$code" = "$3" ]; then return 0; fi
        sleep 0.5
    done
    echo "FAIL: $1 did not answer $3 on $2 (last: $code)" >&2
    docker logs "$1" >&2 || true
    exit 1
}

# 1. The stub first, so the UI's first upstream connection never fails: a refused connection
#    marks the upstream down for ten seconds (max_fails 1) and would turn rows red.
docker run -d --name "$STUB" --network "$NET" \
    -v "$TEST_DIR/stub-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    -p 127.0.0.1::8086 -p 127.0.0.1::9080 "$IMAGE" >/dev/null
STUB_BACKEND="$(host_port "$STUB" 8086)"
STUB_KEYCLOAK="$(host_port "$STUB" 9080)"
wait_for "$STUB" "http://127.0.0.1:$STUB_BACKEND/" 409
wait_for "$STUB" "http://127.0.0.1:$STUB_KEYCLOAK/" 409

# 2. The UI nginx as both charts and compose run it: the image's entrypoint renders the template
#    with these variables. nginx_start.sh does not run, so client_max_body_size stays 1m.
run_ui() { # name, backend port, keycloak port
    docker run -d --name "$1" --network "$NET" \
        -v "$UI_DIR/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
        -v "$TEMPLATE:/etc/nginx/templates/default.conf.template:ro" \
        -v "$WORK/html:/usr/share/nginx/html:ro" \
        -e HUB_HOST="$STUB" -e HUB_PORT="$2" \
        -e KEYCLOAK_HOST="$STUB" -e KEYCLOAK_PORT="$3" \
        -e KEYCLOAK_ADMIN_ACCESS= -e KEYCLOAK_RELIZA_ADMIN_ACCESS= \
        -p 127.0.0.1::80 "$IMAGE" >/dev/null
}
run_ui "$UI" 8086 9080
# Nothing listens on 8099 and 9099: the connection is refused, as for a stopped backend pod.
run_ui "$UI_DOWN" 8099 9099
UI_URL="http://127.0.0.1:$(host_port "$UI" 80)"
DOWN_URL="http://127.0.0.1:$(host_port "$UI_DOWN" 80)"
wait_for "$UI" "$UI_URL/error_pages/generic.html" 200
wait_for "$UI_DOWN" "$DOWN_URL/error_pages/generic.html" 200

# Expected bodies.
lit() { printf '%s' "$2" > "$WORK/expect/$1"; echo "$WORK/expect/$1"; }
SPA_BODY="$(lit spa "$SPA")"
GENERIC="$PAGES/generic.html"
SERVER_DOWN="$PAGES/server_down.html"
TOKEN_401='{"error":"invalid_client","error_description":"client authentication required (HTTP Basic with the API key id and secret)"}'
BIG="$WORK/big.bin"
head -c 2097152 /dev/zero > "$BIG"

FAILED=0
# check <row> <base url> <method> <path> <status> <body file> [header: value ...] [-- curl args ...]
check() {
    local row="$1" base="$2" method="$3" path="$4" want_status="$5" want_body="$6"
    shift 6
    local want_headers=() extra=()
    while [ $# -gt 0 ]; do
        if [ "$1" = "--" ]; then shift; extra=("$@"); break; fi
        want_headers+=("$1"); shift
    done
    local h="$WORK/out/$row.h" b="$WORK/out/$row.b" problems="" code
    rm -f "$h" "$b"
    if ! code="$(curl -sS -D "$h" -o "$b" -w '%{http_code}' -X "$method" ${extra[@]+"${extra[@]}"} "$base$path" 2>"$WORK/out/$row.err")"; then
        echo "FAIL $row $method $path: curl error: $(tr '\n' ' ' < "$WORK/out/$row.err")"
        FAILED=1
        return 0
    fi
    [ "$code" = "$want_status" ] || problems="$problems status $code, want $want_status;"
    # The last header block only (a 100 Continue may come first), names lower-cased, CR removed.
    local block="$WORK/out/$row.hb"
    awk '/^HTTP\//{ n++ } { sub(/\r$/, ""); lines[n] = lines[n] $0 "\n" } END { printf "%s", lines[n] }' "$h" > "$block"
    local want name value
    for want in ${want_headers[@]+"${want_headers[@]}"}; do
        name="$(printf '%s' "${want%%:*}" | tr 'A-Z' 'a-z')"
        value="${want#*: }"
        if ! awk -v n="$name" -v v="$value" '
                { i = index($0, ":"); if (i == 0) next
                  k = tolower(substr($0, 1, i - 1)); val = substr($0, i + 1); sub(/^[ \t]+/, "", val)
                  if (k == n && val == v) found = 1 }
                END { exit found ? 0 : 1 }' "$block"; then
            local got
            got="$(awk -v n="$name" '{ i = index($0, ":"); if (i && tolower(substr($0, 1, i - 1)) == n) print substr($0, i + 1) }' "$block" | tr '\n' '|')"
            problems="$problems header $name: got [${got:- none}], want [ $value];"
        fi
    done
    if ! cmp -s "$b" "$want_body"; then
        problems="$problems body: got [$(head -c 120 "$b" | tr '\n' ' ')], want [$(head -c 120 "$want_body" | tr '\n' ' ')];"
    fi
    if [ -z "$problems" ]; then
        echo "ok   $row $method $path"
    else
        echo "FAIL $row $method $path:$problems"
        FAILED=1
    fi
}

JSON=(-H 'Content-Type: application/json' --data '{"query":"{ __typename }"}')
BACKEND_PATHS=(/v1/stub /api/stub /tea/stub /.well-known/tea/stub /graphql/stub)
KEYCLOAK_PATHS=(/kauth/stub /kauth/admin/Reliza/stub /kauth/admin/realms/Reliza/stub /kauth/admin/serverinfo /kauth/realms/Reliza/stub /kauth/resources/stub /kauth/js/stub)

echo "-- through the UI nginx, the stub answering"
check 1 "$UI_URL" POST /graphql 429 "$(lit r1 '{"error":"Rate limit exceeded"}')" \
    'Retry-After: 7' 'Content-Type: application/json' -- "${JSON[@]}"
check 2 "$UI_URL" POST /api/programmatic/token 401 "$(lit r2 "$TOKEN_401")" \
    'WWW-Authenticate: Basic realm="rearm"' 'Cache-Control: no-store' 'Content-Type: application/json' \
    -- -H 'Content-Type: application/x-www-form-urlencoded' --data 'grant_type=client_credentials'
check 3 "$UI_URL" POST /api/programmatic/graphql 401 "$(lit r3 '{"error":"invalid_token"}')" \
    'WWW-Authenticate: Bearer error="invalid_token"' 'X-Rate-Limit-Remaining: 42' -- "${JSON[@]}"
check 4 "$UI_URL" GET /api/stub/500 500 "$(lit r4 '{"stub":"backend 500"}')"
# An upstream's 503 is its answer, not the server-down page.
check 5 "$UI_URL" GET /api/stub/503 503 "$(lit r5 '{"stub":"backend 503"}')" 'Retry-After: 30'
check 6 "$UI_URL" POST /kauth/realms/Reliza/protocol/openid-connect/token 400 \
    "$(lit r6 '{"error":"invalid_grant","error_description":"Token is not active"}')" \
    -- -H 'Content-Type: application/x-www-form-urlencoded' --data 'grant_type=refresh_token&refresh_token=x'
row=7
for p in "${BACKEND_PATHS[@]}"; do
    check "$row" "$UI_URL" GET "$p" 409 "$(lit backend '{"stub":"backend"}')" 'Content-Type: application/json'
    row=$((row + 1))
done
for p in "${KEYCLOAK_PATHS[@]}"; do
    check "$row" "$UI_URL" GET "$p" 409 "$(lit keycloak '{"stub":"keycloak"}')" 'Content-Type: application/json'
    row=$((row + 1))
done
check 19 "$UI_URL" GET /no/such/ui/route 200 "$SPA_BODY" 'Content-Type: text/html'
check 20 "$UI_URL" GET /error_pages/generic.html 200 "$GENERIC"
# nginx's own error (client_max_body_size 1m) stays branded.
check 21 "$UI_URL" POST /api/stub/upload 413 "$GENERIC" 'Content-Type: text/html' \
    -- -H 'Content-Type: application/octet-stream' --data-binary "@$BIG"

echo "-- through the UI nginx, nothing listening upstream"
check 22 "$DOWN_URL" POST /graphql 502 "$SERVER_DOWN" 'Content-Type: text/html' -- "${JSON[@]}"
row=23
for p in "${BACKEND_PATHS[@]}" "${KEYCLOAK_PATHS[@]}"; do
    check "$row" "$DOWN_URL" GET "$p" 502 "$SERVER_DOWN" 'Content-Type: text/html'
    row=$((row + 1))
done
check 35 "$DOWN_URL" GET /no/such/ui/route 200 "$SPA_BODY"

if [ "$FAILED" -ne 0 ]; then
    echo "error-passthrough: FAILED"
    exit 1
fi
echo "error-passthrough: all 35 rows ok"
