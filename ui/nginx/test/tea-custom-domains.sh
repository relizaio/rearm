#!/usr/bin/env bash
# TPF-148: runs the UI nginx template through the nginx image ui/Dockerfile ships and through the
# real nginx_start.sh, in front of an echoing upstream stand-in (tea-echo-upstream.conf), and checks
# the TEA-only server block for TEA custom domains: on a listed host only /tea/... and
# /.well-known/tea reach the backend (with the custom host, the raw URI and the client's forwarded
# headers), every other path and every encoded or dot-segment escape is nginx's 404; the ReARM host,
# localhost and unlisted hosts keep the main server block exactly as before.
#
#   bash ui/nginx/test/tea-custom-domains.sh
#
# NGINX_TEMPLATE overrides the template under test, for example to see the rows the old template
# fails: git show origin/main:ui/nginx/default.conf.template > /tmp/main.conf.template, then
# NGINX_TEMPLATE=/tmp/main.conf.template bash ui/nginx/test/tea-custom-domains.sh
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

SUFFIX="tpf148-$$-$RANDOM"
NET="net-$SUFFIX"
STUB="stub-$SUFFIX"
UI_ABSENT="uiabsent-$SUFFIX"
UI_EMPTY="uiempty-$SUFFIX"
UI_TWO="uitwo-$SUFFIX"
UI_LONG="uilong-$SUFFIX"
WORK="$(mktemp -d)"

cleanup() {
    docker rm -f "$STUB" "$UI_ABSENT" "$UI_EMPTY" "$UI_TWO" "$UI_LONG" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# A listed host of 239 characters, the longest the chart renders: labels of 63, 63, 63 and 47.
label() { printf "%${2}s" '' | tr ' ' "$1"; }
H239="$(label a 63).$(label b 63).$(label c 63).$(label d 47)"
[ "${#H239}" -eq 239 ] || { echo "FAIL: H239 is ${#H239} characters" >&2; exit 1; }

# The document root: the branded pages and a stand-in SPA shell, readable by nginx's worker.
SPA='<!doctype html><title>tpf148-spa</title>'
mkdir -p "$WORK/html/error_pages" "$WORK/out"
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

# 1. The stand-in first, so the UI's first upstream connection never fails: a refused connection
#    marks the upstream down for ten seconds (max_fails 1) and would turn rows red.
docker run -d --name "$STUB" --network "$NET" \
    -v "$TEST_DIR/tea-echo-upstream.conf:/etc/nginx/conf.d/default.conf:ro" \
    -p 127.0.0.1::8086 -p 127.0.0.1::9080 "$IMAGE" >/dev/null
wait_for "$STUB" "http://127.0.0.1:$(host_port "$STUB" 8086)/" 200
wait_for "$STUB" "http://127.0.0.1:$(host_port "$STUB" 9080)/" 200

# 2. The UI nginx through the real start script, so its placeholder and its guard run on this image.
#    MAX_BODY_SIZE and REARM_PRODUCT_VERSION are not set: the script's sed -i steps do not run
#    against the read-only mounts. Extra docker run arguments (the TEA_CUSTOM_DOMAINS line) follow
#    the name; with -d the container runs detached, without it the run waits for the exit status.
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
run_ui "$UI_ABSENT"
run_ui "$UI_EMPTY" -e TEA_CUSTOM_DOMAINS=
run_ui "$UI_TWO" -e "TEA_CUSTOM_DOMAINS=tea-a.test tea-b.test"
run_ui "$UI_LONG" -e "TEA_CUSTOM_DOMAINS=$H239 tea-a.test"
declare -A URL
for c in "$UI_ABSENT" "$UI_EMPTY" "$UI_TWO" "$UI_LONG"; do
    URL[$c]="http://127.0.0.1:$(host_port "$c" 80)"
done
for c in "$UI_ABSENT" "$UI_EMPTY" "$UI_TWO" "$UI_LONG"; do
    wait_for "$c" "${URL[$c]}/error_pages/generic.html" 200
done

FAILED=0
pass() { echo "ok   $*"; }
fail() { echo "FAIL $*"; FAILED=1; }

field() { # json file, field name -> the field's value ("" when absent); the stand-in escapes nothing
    sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p" "$1" | head -n 1
}

# probe <row> <container> <host> <method> <path> <rule> [field=value ...] [-- curl args ...]
# Rules: backend (200 from the backend stand-in), keycloak (200 from the Keycloak stand-in), head
# (200 with the stand-in's JSON type, no body read), spa (200, the stand-in shell), page (200, the
# branded generic.html), nginx404 (404, nginx's built-in page, neither the shell nor a stand-in),
# generic400 (400, the main block's branded generic.html). field=value pairs are compared with the
# backend stand-in's echo (host, uri, xfh, xfp).
probe() {
    local row="$1" c="$2" host="$3" method="$4" path="$5" rule="$6"
    shift 6
    local fields=() extra=()
    while [ $# -gt 0 ]; do
        if [ "$1" = "--" ]; then shift; extra=("$@"); break; fi
        fields+=("$1"); shift
    done
    local tag="${c%%-*} $row Host:$host $method $path"
    local n="$WORK/out/$row.$RANDOM" code problems=""
    local how=(-X "$method")
    [ "$method" = HEAD ] && how=(-I)
    if ! code="$(curl -sS --path-as-is -D "$n.h" -o "$n.b" -w '%{http_code}' "${how[@]}" \
            -H "Host: $host" ${extra[@]+"${extra[@]}"} "${URL[$c]}$path" 2>"$n.err")"; then
        fail "$tag: curl error: $(tr '\n' ' ' < "$n.err")"
        return 0
    fi
    local want_status=200
    case "$rule" in nginx404) want_status=404 ;; generic400) want_status=400 ;; esac
    [ "$code" = "$want_status" ] || problems="$problems status $code, want $want_status;"
    case "$rule" in
        backend|keycloak)
            [ "$(field "$n.b" stub)" = "$rule" ] || problems="$problems stub [$(field "$n.b" stub)], want [$rule];" ;;
        head)
            tr -d '\r' < "$n.h" | grep -qi '^content-type: application/json$' \
                || problems="$problems no application/json content type;" ;;
        spa)
            [ "$(cat "$n.b")" = "$SPA" ] || problems="$problems not the SPA shell;" ;;
        page|generic400)
            cmp -s "$n.b" "$PAGES/generic.html" || problems="$problems not generic.html;" ;;
        nginx404)
            grep -q '<title>404 Not Found</title>' "$n.b" || problems="$problems not nginx's 404 page;"
            if grep -q -e 'tpf148-spa' -e 'stub' "$n.b"; then problems="$problems the shell or a stand-in answered;"; fi ;;
        *) problems="$problems unknown rule $rule;" ;;
    esac
    local kv k v got
    for kv in ${fields[@]+"${fields[@]}"}; do
        k="${kv%%=*}"
        v="${kv#*=}"
        got="$(field "$n.b" "$k")"
        [ "$got" = "$v" ] || problems="$problems $k [$got], want [$v];"
    done
    if [ -z "$problems" ]; then pass "$tag"; else fail "$tag:$problems"; fi
}

UUID=0a8b9a3e-7c1f-4b0e-9d2a-5f6e7d8c9b0a
FORM=(-H 'Content-Type: application/x-www-form-urlencoded' --data 'grant_type=client_credentials')
XF=(-H 'X-Forwarded-Host: tea-a.test' -H 'X-Forwarded-Proto: https')

echo "-- custom host tea-a.test on the two-host render (C rows)"
C="$UI_TWO"
H=tea-a.test
probe C1 "$C" $H GET /tea/v1.0.0/products backend host=$H uri=/tea/v1.0.0/products
probe C2 "$C" $H HEAD /tea/v1.0.0/products head
probe C3 "$C" $H POST /tea/v1.0.0/token backend host=$H uri=/tea/v1.0.0/token -- "${FORM[@]}"
probe C4 "$C" $H GET "/tea/$UUID/v1.0.0/products" backend host=$H "uri=/tea/$UUID/v1.0.0/products"
probe C5 "$C" $H GET /tea/ backend host=$H uri=/tea/
probe C6 "$C" $H GET /.well-known/tea backend host=$H uri=/.well-known/tea
probe C6 "$C" $H GET '/.well-known/tea?x=1' backend host=$H 'uri=/.well-known/tea?x=1'
probe C7 "$C" $H GET /.well-known/tea/ nginx404
probe C8 "$C" $H GET /.well-known/teapot nginx404
probe C9 "$C" $H GET /tea nginx404
for p in /teapot /TEA/v1.0.0/products; do probe C10 "$C" $H GET "$p" nginx404; done
for p in / /index.html /some/spa/route /version.html /error_pages/404.html; do probe C11 "$C" $H GET "$p" nginx404; done
for p in /kauth/realms/Reliza /kauth/admin/Reliza/x; do probe C12 "$C" $H GET "$p" nginx404; done
probe C13 "$C" $H GET /api/programmatic/graphql nginx404
probe C13 "$C" $H POST /graphql nginx404 -- -H 'Content-Type: application/json' --data '{"query":"{ __typename }"}'
probe C13 "$C" $H GET /v1/x nginx404
probe C20 "$C" $H GET /tea/../index.html nginx404
probe C21 "$C" $H GET /tea/%2e%2e/index.html nginx404
probe C22 "$C" $H GET /tea/..%2findex.html nginx404
probe C23 "$C" $H GET /tea/..%2f nginx404
probe C24 "$C" $H GET /tea/x/..%2f..%2findex.html nginx404
probe C25 "$C" $H GET /tea/..%2fkauth/realms/Reliza nginx404
probe C26 "$C" $H GET /tea/%2e%2e%2fkauth/realms/Reliza nginx404
probe C27 "$C" $H GET /tea/..%2Fkauth/realms/Reliza/account nginx404
probe C28 "$C" $H GET /tea/..%2fapi/programmatic/graphql nginx404
probe C29 "$C" $H GET /tea//..%2findex.html nginx404
probe C29 "$C" $H GET /tea%2f..%2findex.html nginx404
probe C30 "$C" $H GET /.well-known/tea/..%2f..%2findex.html nginx404
probe C31 "$C" $H GET /tea/..%2f..%2ftea/v1.0.0/products generic400
probe C31 "$C" $H GET /tea/v1.0.0/products%00 generic400
for p in /tea/v1.0.0/..%2fproducts /tea/%2e%2e%2f.well-known/tea '/tea/..;/index.html' /%74ea/v1.0.0/products //tea/v1.0.0/products; do
    probe C32 "$C" $H GET "$p" backend host=$H "uri=$p"
done
for v in TEA-A.TEST tea-a.test:443 tea-a.test. tea-b.test; do
    want=tea-a.test
    [ "$v" = tea-b.test ] && want=tea-b.test
    probe C33 "$C" "$v" GET / nginx404
    probe C33 "$C" "$v" GET /tea/v1.0.0/products backend host=$want uri=/tea/v1.0.0/products
    probe C33 "$C" "$v" GET /.well-known/tea backend host=$want uri=/.well-known/tea
    probe C33 "$C" "$v" GET /tea/..%2findex.html nginx404
done
probe F1 "$C" $H GET /tea/v1.0.0/products backend host=$H xfh=$H xfp=https uri=/tea/v1.0.0/products -- "${XF[@]}"
probe F2 "$C" $H GET /.well-known/tea backend host=$H xfh=$H xfp=https uri=/.well-known/tea -- "${XF[@]}"
probe F3 "$C" $H GET /tea/v1.0.0/products backend host=$H xfh= xfp= uri=/tea/v1.0.0/products

echo "-- a listed host of 239 characters beside a short one (G rows)"
C="$UI_LONG"
if docker exec "$C" nginx -t >"$WORK/out/g1" 2>&1; then
    if grep -q -e '\[warn\]' -e '\[emerg\]' "$WORK/out/g1"; then
        fail "G1 nginx -t on the long render warns: $(tr '\n' ' ' < "$WORK/out/g1")"
    else
        pass "G1 the long render started (readiness 200) and nginx -t is successful"
    fi
else
    fail "G1 nginx -t on the long render: $(tr '\n' ' ' < "$WORK/out/g1")"
fi
probe G2 "$C" "$H239" GET / nginx404
probe G3 "$C" "$H239" GET /tea/v1.0.0/products backend "host=$H239" uri=/tea/v1.0.0/products
probe G4 "$C" "$H239" GET /tea/..%2findex.html nginx404
probe G5 "$C" tea-a.test GET /.well-known/tea backend host=tea-a.test uri=/.well-known/tea
probe G5 "$C" tea-a.test GET /index.html nginx404
probe G6 "$C" rearm.test GET /index.html spa

# The main block's answers, on any host it serves (M1-M9).
main_rows() { # container, host
    local c="$1" h="$2" p
    for p in / /index.html /some/spa/route; do probe M1 "$c" "$h" GET "$p" spa; done
    probe M2 "$c" "$h" GET /error_pages/generic.html page
    for p in /kauth/realms/Reliza /kauth/admin/Reliza/x /kauth/js/x; do probe M3 "$c" "$h" GET "$p" keycloak; done
    probe M4 "$c" "$h" GET /api/programmatic/graphql backend
    probe M4 "$c" "$h" POST /graphql backend -- -H 'Content-Type: application/json' --data '{"query":"{ __typename }"}'
    probe M4 "$c" "$h" GET /v1/x backend
    for p in /tea /tea/v1.0.0/products "/tea/$UUID/v1.0.0/products" /teapot; do probe M5 "$c" "$h" GET "$p" backend; done
    probe M5 "$c" "$h" POST /tea/v1.0.0/token backend -- "${FORM[@]}"
    for p in /.well-known/tea /.well-known/tea/ /.well-known/teapot; do probe M6 "$c" "$h" GET "$p" backend; done
    probe M7 "$c" "$h" GET /tea/..%2findex.html spa
    probe M8 "$c" "$h" GET /tea/..%2fkauth/realms/Reliza keycloak
    probe M9 "$c" "$h" GET /tea/..%2f..%2ftea/v1.0.0/products generic400
}

echo "-- the ReARM host, localhost and an unlisted host on every render (M rows)"
for c in "$UI_ABSENT" "$UI_EMPTY" "$UI_TWO"; do
    for h in rearm.test localhost unlisted.test; do main_rows "$c" "$h"; done
done
echo "-- tea-a.test where it is not listed (M10) and the placeholder host (M11)"
main_rows "$UI_ABSENT" tea-a.test
P=tea-custom-domains.invalid
probe M11 "$UI_ABSENT" $P GET / nginx404
probe M11 "$UI_ABSENT" $P GET /kauth/realms/Reliza nginx404
probe M11 "$UI_ABSENT" $P GET /tea/v1.0.0/products backend host=$P uri=/tea/v1.0.0/products
probe M11 "$UI_ABSENT" $P GET /.well-known/tea backend host=$P uri=/.well-known/tea

echo "-- the rendered configuration (N1, R1, R2, R4)"
for c in "$UI_ABSENT" "$UI_EMPTY" "$UI_TWO" "$UI_LONG"; do
    docker exec "$c" nginx -T > "$WORK/out/${c%%-*}.T" 2>/dev/null
    docker exec "$c" cat /etc/nginx/conf.d/default.conf > "$WORK/out/${c%%-*}.conf"
done
count() { grep -cxF -- "$2" "$1" || true; }
T="$WORK/out/uitwo.T"
for want in '    server_name  tea-a.test tea-b.test;' '    listen       80 default_server;' 'server_names_hash_bucket_size 512;'; do
    n="$(count "$T" "$want")"
    if [ "$n" = 1 ]; then pass "N1 two-host nginx -T has [$want] once"; else fail "N1 two-host nginx -T has [$want] $n times"; fi
done
for c in uiabsent uiempty; do
    n="$(count "$WORK/out/$c.T" '    server_name  tea-custom-domains.invalid;')"
    if [ "$n" = 1 ]; then pass "N1 $c nginx -T names the placeholder"; else fail "N1 $c nginx -T has the placeholder $n times"; fi
done
if cmp -s "$WORK/out/uiabsent.conf" "$WORK/out/uiempty.conf"; then
    pass "R2 the empty render is byte-identical to the absent one"
else
    fail "R2 the empty render differs from the absent one"
fi
sed 's/^    server_name  tea-a\.test tea-b\.test;$/    server_name  tea-custom-domains.invalid;/' "$WORK/out/uitwo.conf" > "$WORK/out/uitwo.norm"
if ! cmp -s "$WORK/out/uitwo.conf" "$WORK/out/uitwo.norm" && cmp -s "$WORK/out/uitwo.norm" "$WORK/out/uiabsent.conf"; then
    pass "R4 the two-host render differs from the absent one only in the server_name value"
else
    fail "R4 the two-host render differs from the absent one in more than the server_name value"
fi

echo "-- refused lists (N2)"
for v in tea-a.test,tea-b.test tea-a.test. localhost; do
    ui_args -e "TEA_CUSTOM_DOMAINS=$v"
    status=0
    docker run --rm "${UI_ARGS[@]}" "$IMAGE" /nginx_start.sh >"$WORK/out/n2.out" 2>"$WORK/out/n2.err" || status=$?
    want="nginx_start.sh: TEA_CUSTOM_DOMAINS must be lowercase hostnames separated by spaces (no leading or trailing dot, not localhost); got: $v"
    if [ "$status" = 1 ] && [ "$(cat "$WORK/out/n2.err")" = "$want" ] && [ ! -s "$WORK/out/n2.out" ]; then
        pass "N2 [$v] refused: exit 1 and the one-line message"
    else
        fail "N2 [$v]: exit $status, stderr [$(tr '\n' ' ' < "$WORK/out/n2.err")]"
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "tea-custom-domains: FAILED"
    exit 1
fi
echo "tea-custom-domains: all rows ok"
