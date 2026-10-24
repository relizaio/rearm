#!/bin/sh
# Self-test of trust-gate.sh. Hermetic: no verdict comes from the host's own
# store, so it gives the same answer on a patched and on an unpatched host.
#
#   sh ci/trust-gate/selftest.sh
#
# It builds, beside itself,
#   fixed-bundle.pem  $GATE_BUNDLE (default /etc/ssl/certs/ca-certificates.crt)
#                     with every certificate whose SHA-256 fingerprint is one
#                     of the two roots of roots.json removed
#   bad-bundle.pem    fixed-bundle.pem plus the two vendored root PEMs
# and asserts that the offline check FAILS with the bad bundle and passes with
# the fixed one, then runs the live check against tests/stub-curl exiting
# 0 / 60 / 6 / 35 (FAIL / pass / unreachable / FAIL). Both bundles are removed
# on exit. Needs openssl. Identical in every repository that carries
# ci/trust-gate/.

set -u

here=$(cd "$(dirname "$0")" && pwd)
src=${GATE_BUNDLE:-/etc/ssl/certs/ca-certificates.crt}
fixed="$here/fixed-bundle.pem"
bad="$here/bad-bundle.pem"
work=$(mktemp -d "${TMPDIR:-/tmp}/trust-gate-selftest.XXXXXX") || exit 1
trap 'rm -rf "$work"; rm -f "$fixed" "$bad"' EXIT
trap 'exit 1' INT TERM

failures=0
say() { printf 'selftest: %s\n' "$*"; }
ok() { say "ok: $*"; }
bad_case() { say "FAILED: $*"; failures=$((failures + 1)); }

command -v openssl >/dev/null 2>&1 || { say "FAILED: openssl not found"; exit 1; }
[ -r "$src" ] || { say "FAILED: bundle $src not readable"; exit 1; }

# The two root fingerprints, from the sha256 fields of roots.json.
fp_of() {
	awk -v want="$1" '
		/"name":/ { n = $0; sub(/^[^:]*: *"/, "", n); sub(/".*$/, "", n) }
		/"sha256":/ && n == want { s = $0; sub(/^[^:]*: *"/, "", s); sub(/".*$/, "", s); print toupper(s); exit }
	' "$here/roots.json"
}
FP_EMSIGN=$(fp_of "emSign ECC Root CA - G3")
FP_AAA=$(fp_of "AAA Certificate Services")
[ ${#FP_EMSIGN} -eq 64 ] && [ ${#FP_AAA} -eq 64 ] || { say "FAILED: cannot read the two root fingerprints from roots.json"; exit 1; }

fingerprint() {
	openssl x509 -in "$1" -outform DER 2>/dev/null | openssl dgst -sha256 | awk '{ print toupper($NF) }'
}

# The vendored root PEMs must be the roots.json roots.
[ "$(fingerprint "$here/roots/emSign_ECC_Root_CA_-_G3.pem")" = "$FP_EMSIGN" ] &&
	ok "roots/emSign_ECC_Root_CA_-_G3.pem is $FP_EMSIGN" ||
	bad_case "roots/emSign_ECC_Root_CA_-_G3.pem does not match roots.json"
[ "$(fingerprint "$here/roots/AAA_Certificate_Services.pem")" = "$FP_AAA" ] &&
	ok "roots/AAA_Certificate_Services.pem is $FP_AAA" ||
	bad_case "roots/AAA_Certificate_Services.pem does not match roots.json"

# fixed-bundle.pem: the source bundle without the two roots.
awk -v dir="$work" '
	/-----BEGIN CERTIFICATE-----/ { n++; f = sprintf("%s/cert-%05d.pem", dir, n); inblock = 1 }
	inblock { print > f }
	/-----END CERTIFICATE-----/ { if (inblock) close(f); inblock = 0 }
' "$src"
: >"$fixed" || { say "FAILED: cannot write $fixed"; exit 1; }
total=0
dropped=0
for cert in "$work"/cert-*.pem; do
	[ -e "$cert" ] || continue
	total=$((total + 1))
	fp=$(fingerprint "$cert")
	if [ "$fp" = "$FP_EMSIGN" ] || [ "$fp" = "$FP_AAA" ]; then
		dropped=$((dropped + 1))
		continue
	fi
	cat "$cert" >>"$fixed" || { say "FAILED: cannot write $fixed"; exit 1; }
done
[ "$total" -gt 0 ] || { say "FAILED: no certificate in $src"; exit 1; }
say "fixed bundle: $src, $total certificates, $dropped of the two roots dropped"
cat "$fixed" "$here/roots/emSign_ECC_Root_CA_-_G3.pem" "$here/roots/AAA_Certificate_Services.pem" >"$bad" || { say "FAILED: cannot write $bad"; exit 1; }

# The raw openssl verdicts the gate reads.
ATTIME=1792800000
for chain in "$here"/chains/*.pem; do
	name=$(basename "$chain")
	out=$(openssl verify -no-CApath -no-CAfile -no-CAstore -CAfile "$bad" -attime "$ATTIME" -untrusted "$chain" "$chain" 2>&1) && rc=0 || rc=$?
	[ "$rc" -eq 0 ] && ok "bad bundle, $name: $out" || bad_case "bad bundle, $name: expected OK, got exit $rc: $out"
	out=$(openssl verify -no-CApath -no-CAfile -no-CAstore -CAfile "$fixed" -attime "$ATTIME" -untrusted "$chain" "$chain" 2>&1) && rc=0 || rc=$?
	if [ "$rc" -eq 2 ] && printf '%s\n' "$out" | grep -q 'error 20 .*unable to get local issuer certificate'; then
		ok "fixed bundle, $name: exit 2, $(printf '%s\n' "$out" | grep 'error 20' | head -n 1)"
	else
		bad_case "fixed bundle, $name: expected exit 2 with error 20, got exit $rc: $out"
	fi
done

# gate LABEL WANT PATTERN [VAR=value...]: run trust-gate.sh with the given
# environment; WANT 0 expects exit 0, WANT 1 a non-zero exit; check the exit
# status and that PATTERN matches exactly two lines (one per host or chain).
# The Alpine package check is kept out of every run (GATE_MIN_ALPINE_BUNDLE=0):
# it reads the host's package, which would make the self-test host-dependent.
gate() {
	label=$1 want=$2 pattern=$3
	shift 3
	out=$(env GATE_MIN_ALPINE_BUNDLE=0 "$@" sh "$here/trust-gate.sh" 2>&1) && rc=0 || rc=$?
	hits=$(printf '%s\n' "$out" | grep -cE "$pattern")
	if { [ "$want" = 0 ] && [ "$rc" -eq 0 ]; } || { [ "$want" = 1 ] && [ "$rc" -ne 0 ]; }; then
		if [ "$hits" -eq 2 ]; then
			ok "$label: exit $rc, $(printf '%s\n' "$out" | tail -n 1)"
			return
		fi
	fi
	bad_case "$label: exit $rc, $hits lines matching '$pattern'; output:"
	printf '%s\n' "$out" | sed 's/^/    /'
}

# Offline half.
gate "offline, bad bundle (must FAIL)" 1 'FAIL \(offline: chain verifies against' GATE_SKIP_LIVE=1 GATE_BUNDLE="$bad"
gate "offline, fixed bundle (must pass)" 0 'offline: not trusted by .*unable to get local issuer certificate.*: pass$' GATE_SKIP_LIVE=1 GATE_BUNDLE="$fixed"

# Live half, against the stub; the fixed bundle keeps the offline half passing,
# so only the stubbed live lines decide.
stub="$here/tests/stub-curl"
gate "live, curl exit 0 (must FAIL)" 1 'TLS handshake succeeded, HTTP answered: FAIL \(chain trusted\)$' GATE_CURL="$stub" STUB_CURL_EXIT=0 GATE_BUNDLE="$fixed"
gate "live, curl exit 60 (must pass)" 0 'TLS verification error \(curl 60\): pass$' GATE_CURL="$stub" STUB_CURL_EXIT=60 GATE_BUNDLE="$fixed"
gate "live, curl exit 6 (unreachable, offline decides)" 0 'unreachable \(curl 6\): no verdict$' GATE_CURL="$stub" STUB_CURL_EXIT=6 GATE_BUNDLE="$fixed"
gate "live, curl exit 35 (must FAIL)" 1 'curl exit 35, not a TLS verification error: FAIL$' GATE_CURL="$stub" STUB_CURL_EXIT=35 GATE_BUNDLE="$fixed"

if [ "$failures" -eq 0 ]; then
	say "PASS"
	exit 0
fi
say "FAIL ($failures)"
exit 1
