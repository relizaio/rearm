#!/usr/bin/env bash
# PFA-2: runs the UI nginx template through the nginx image ui/Dockerfile ships and through the real
# nginx_start.sh, in front of a backend stand-in that answers after 4 seconds, and checks the
# programmatic API timeout: location /api/programmatic waits PROGRAMMATIC_API_TIMEOUT seconds (600
# when unset or empty) for the backend, every other /api route keeps its 90, nginx's own 504 keeps
# the server-down page, and a value that is not a whole number of seconds from 1 to 99999 stops the
# start with one line.
#
#   bash ui/nginx/test/programmatic-timeout.sh
#
# NGINX_TEMPLATE overrides the template under test, for example to see the rows the old template
# fails: git show origin/main:ui/nginx/default.conf.template > /tmp/main.conf.template, then
# NGINX_TEMPLATE=/tmp/main.conf.template bash ui/nginx/test/programmatic-timeout.sh
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
NODE_IMAGE="$(sed -n 's/^FROM \(node:[^ ]*\) AS build-stage$/\1/p' "$UI_DIR/Dockerfile")"
if [ -z "$NODE_IMAGE" ]; then
    echo "FAIL: no 'FROM node:... AS build-stage' line in $UI_DIR/Dockerfile" >&2
    exit 1
fi
echo "image:    $IMAGE"
echo "node:     $NODE_IMAGE"
echo "template: $TEMPLATE"

SUFFIX="pfa2-$$-$RANDOM"
NET="net-$SUFFIX"
STUB="stub-$SUFFIX"
UI_DEFAULT="uidefault-$SUFFIX"
UI_EMPTY="uiempty-$SUFFIX"
UI_SHORT="uishort-$SUFFIX"
UI_MIN="uimin-$SUFFIX"
UI_MAX="uimax-$SUFFIX"
REFUSED="uirefused-$SUFFIX"
WORK="$(mktemp -d)"

cleanup() {
    docker rm -f "$STUB" "$UI_DEFAULT" "$UI_EMPTY" "$UI_SHORT" "$UI_MIN" "$UI_MAX" "$REFUSED" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# The document root: the branded pages and a stand-in SPA shell, readable by nginx's worker.
SPA='<!doctype html><title>pfa2-spa</title>'
mkdir -p "$WORK/html/error_pages" "$WORK/out"
cp "$PAGES"/* "$WORK/html/error_pages/"
printf '%s' "$SPA" > "$WORK/html/index.html"
chmod -R a+rX "$WORK"

docker pull -q "$IMAGE" >/dev/null
docker pull -q "$NODE_IMAGE" >/dev/null
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

# 1. The backend stand-in first, so the UI's first upstream connection never fails. nginx cannot
#    answer late by itself, so a few lines of node do: read the request to its end, wait 4 seconds,
#    answer 200 with a fixed JSON body. A client that went away meanwhile (the UI nginx after its
#    own timeout) gets nothing, and the stand-in keeps serving. Keycloak is not exercised; the UI
#    only needs its host to resolve, so KEYCLOAK_HOST names the stand-in too.
SLOW_JS='
const http = require("http");
http.createServer((req, res) => {
    req.on("error", () => {});
    res.on("error", () => {});
    req.resume();
    req.on("end", () => setTimeout(() => {
        if (res.destroyed) return;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end("{\"stub\":\"slow\"}");
    }, 4000));
}).listen(8086);
'
docker run -d --name "$STUB" --network "$NET" -p 127.0.0.1::8086 "$NODE_IMAGE" node -e "$SLOW_JS" >/dev/null
wait_for "$STUB" "http://127.0.0.1:$(host_port "$STUB" 8086)/" 200

# 2. The UI nginx through the real start script, so its default and its guard run on this image.
#    MAX_BODY_SIZE and REARM_PRODUCT_VERSION are not set: the script's sed -i steps do not run
#    against the read-only mounts. Extra docker run arguments (the PROGRAMMATIC_API_TIMEOUT line)
#    follow the name; with -d the container runs detached, without it the run waits for the exit.
ui_args() { # extra docker run arguments ...
    UI_ARGS=(--network "$NET"
        -v "$UI_DIR/nginx/nginx.conf:/etc/nginx/nginx.conf:ro"
        -v "$TEMPLATE:/etc/nginx/templates/default.conf.template:ro"
        -v "$UI_DIR/nginx/nginx_start.sh:/nginx_start.sh:ro"
        -v "$WORK/html:/usr/share/nginx/html:ro"
        -e HUB_HOST="$STUB" -e HUB_PORT=8086 -e KEYCLOAK_HOST="$STUB" -e KEYCLOAK_PORT=9080
        -e KEYCLOAK_ADMIN_ACCESS= -e KEYCLOAK_RELIZA_ADMIN_ACCESS=
        "$@" --entrypoint /bin/sh)
}
run_ui() { # name, extra docker run arguments ...
    local name="$1"
    shift
    ui_args "$@"
    docker run -d --name "$name" "${UI_ARGS[@]}" -p 127.0.0.1::80 "$IMAGE" /nginx_start.sh >/dev/null
}
run_ui "$UI_DEFAULT"
run_ui "$UI_EMPTY" -e PROGRAMMATIC_API_TIMEOUT=
run_ui "$UI_SHORT" -e PROGRAMMATIC_API_TIMEOUT=2
run_ui "$UI_MIN" -e PROGRAMMATIC_API_TIMEOUT=1
run_ui "$UI_MAX" -e PROGRAMMATIC_API_TIMEOUT=99999
declare -A URL
for c in "$UI_DEFAULT" "$UI_EMPTY" "$UI_SHORT" "$UI_MIN" "$UI_MAX"; do
    URL[$c]="http://127.0.0.1:$(host_port "$c" 80)"
done
for c in "$UI_DEFAULT" "$UI_EMPTY" "$UI_SHORT" "$UI_MIN" "$UI_MAX"; do
    wait_for "$c" "${URL[$c]}/error_pages/generic.html" 200
done

FAILED=0
pass() { echo "ok   $*"; }
fail() { echo "FAIL $*"; FAILED=1; }

echo "-- the rendered configuration (T-3, T-6)"
for c in "$UI_DEFAULT" "$UI_EMPTY" "$UI_SHORT" "$UI_MIN" "$UI_MAX"; do
    docker exec "$c" nginx -T > "$WORK/out/${c%%-*}.T" 2>/dev/null
    docker exec "$c" cat /etc/nginx/conf.d/default.conf > "$WORK/out/${c%%-*}.conf"
done
count() { grep -cxF -- "$2" "$1" || true; }
READ90='        proxy_read_timeout 90;'
WANT90="$(count "$TEMPLATE" "$READ90")"
timeouts() { # container short name, seconds, rows
    local c="$1" s="$2" rows="$3" line n
    for line in "        proxy_read_timeout $s;" "        proxy_send_timeout $s;"; do
        n="$(count "$WORK/out/$c.T" "$line")"
        if [ "$n" = 1 ]; then pass "$rows $c nginx -T has [${line#        }] once"; else fail "$rows $c nginx -T has [${line#        }] $n times"; fi
    done
}
for c in uidefault uiempty; do timeouts "$c" 600 T-3; done
timeouts uishort 2 T-3
n="$(grep -cE '^ *proxy_(read|send)_timeout 600;$' "$WORK/out/uishort.T" || true)"
if [ "$n" = 0 ]; then pass "T-3 uishort nginx -T has no 600 timeout line"; else fail "T-3 uishort nginx -T has $n 600 timeout lines"; fi
for c in uidefault uiempty uishort; do
    n="$(count "$WORK/out/$c.T" "$READ90")"
    if [ "$n" = "$WANT90" ]; then
        pass "T-3 $c nginx -T keeps [${READ90#        }] $n times, as the template"
    else
        fail "T-3 $c nginx -T has [${READ90#        }] $n times, the template $WANT90"
    fi
done
if cmp -s "$WORK/out/uidefault.conf" "$WORK/out/uiempty.conf"; then
    pass "T-3 the empty render is byte-identical to the default one"
else
    fail "T-3 the empty render differs from the default one"
fi
timeouts uimin 1 T-6
timeouts uimax 99999 T-6
for c in "$UI_MIN" "$UI_MAX"; do
    pass "T-6 ${c%%-*} started and answered 200 on /error_pages/generic.html"
done

# request <row> <container> <method> <path> <status> <body file> <content type> [max seconds]
request() {
    local row="$1" c="$2" method="$3" path="$4" want_status="$5" want_body="$6" want_type="$7" max="${8:-}"
    local n="$WORK/out/$row.$RANDOM" out code ctype took problems=""
    local data=()
    [ "$method" = POST ] && data=(-H 'Content-Type: application/json' --data '{"query":"{ __typename }"}')
    if ! out="$(curl -sS -D "$n.h" -o "$n.b" -w '%{http_code} %{time_total}' -X "$method" \
            ${data[@]+"${data[@]}"} "${URL[$c]}$path" 2>"$n.err")"; then
        fail "$row ${c%%-*} $method $path: curl error: $(tr '\n' ' ' < "$n.err")"
        return 0
    fi
    code="${out%% *}"
    took="${out#* }"
    ctype="$(tr -d '\r' < "$n.h" | sed -n 's/^[Cc]ontent-[Tt]ype: *//p' | tail -n 1)"
    [ "$code" = "$want_status" ] || problems="$problems status $code, want $want_status;"
    [ "$ctype" = "$want_type" ] || problems="$problems content type [$ctype], want [$want_type];"
    cmp -s "$n.b" "$want_body" || problems="$problems body [$(head -c 120 "$n.b" | tr '\n' ' ')];"
    if [ -n "$max" ] && ! awk -v t="$took" -v m="$max" 'BEGIN { exit (t < m) ? 0 : 1 }'; then
        problems="$problems took ${took}s, want under ${max}s;"
    fi
    if [ -z "$problems" ]; then pass "$row ${c%%-*} $method $path ($code in ${took}s)"; else fail "$row ${c%%-*} $method $path:$problems"; fi
}

echo "-- the stand-in answering after 4 seconds (T-4)"
SLOW="$WORK/out/slow.json"
printf '%s' '{"stub":"slow"}' > "$SLOW"
request T-4 "$UI_DEFAULT" POST /api/programmatic/graphql 200 "$SLOW" application/json
request T-4 "$UI_SHORT" GET /api/stub 200 "$SLOW" application/json
# Last request to uishort: nginx's own 504 after 2 seconds, with the inherited server-down page.
request T-4 "$UI_SHORT" POST /api/programmatic/graphql 504 "$PAGES/server_down.html" text/html 4

echo "-- refused values (T-5)"
for v in 0 090 10m 600s -5 6.5 1e+06 100000 ' 600' '600;'; do
    ui_args -e "PROGRAMMATIC_API_TIMEOUT=$v"
    status=0
    timeout 60 docker run --rm --name "$REFUSED" "${UI_ARGS[@]}" "$IMAGE" /nginx_start.sh \
        >"$WORK/out/t5.out" 2>"$WORK/out/t5.err" || status=$?
    docker rm -f "$REFUSED" >/dev/null 2>&1 || true
    want="nginx_start.sh: PROGRAMMATIC_API_TIMEOUT must be a whole number of seconds from 1 to 99999, digits only; got: $v"
    if [ "$status" = 1 ] && [ "$(cat "$WORK/out/t5.err")" = "$want" ] && [ ! -s "$WORK/out/t5.out" ]; then
        pass "T-5 [$v] refused: exit 1 and the one-line message"
    else
        fail "T-5 [$v]: exit $status, stdout [$(head -c 120 "$WORK/out/t5.out" | tr '\n' ' ')], stderr [$(tr '\n' ' ' < "$WORK/out/t5.err")]"
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "programmatic-timeout: FAILED"
    exit 1
fi
echo "programmatic-timeout: all rows ok"
