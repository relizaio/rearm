#!/bin/sh
# Trust-store gate: fails while this machine's TLS clients still accept the
# chains of the eMudhra intermediates compromised in October 2026
# (https://emudhra.we-have-a-problem.com/).
#
# Identical in every repository that carries ci/trust-gate/; change every copy
# together. POSIX sh (busybox and dash).
#
# Inputs (environment):
#   GATE_BUNDLE             CA bundle for the offline check
#                           (default /etc/ssl/certs/ca-certificates.crt)
#   GATE_CHAINS             directory of the vendored chains
#                           (default the chains/ directory beside this script)
#   GATE_CURL               command the live check runs (default curl)
#   GATE_MIN_ALPINE_BUNDLE  lowest acceptable ca-certificates-bundle, checked
#                           when apk exists (default 20261020-r0)
#   GATE_SKIP_LIVE=1        skip the live check
#   GATE_SKIP_OFFLINE=1     skip the offline check
#
# A pass is only ever a trust failure: an HTTP answer of any status, a chain
# that verifies, or any unexpected error is a FAIL; DNS, connect and timeout
# failures give no verdict and leave the decision to the offline check.
# Output is line-oriented; the last line is "trust-gate: PASS" or
# "trust-gate: FAIL (<n> findings)", and the exit status is 0 only on PASS.

here=$(cd "$(dirname "$0")" && pwd)

GATE_BUNDLE=${GATE_BUNDLE:-/etc/ssl/certs/ca-certificates.crt}
GATE_CHAINS=${GATE_CHAINS:-$here/chains}
GATE_CURL=${GATE_CURL:-curl}
GATE_MIN_ALPINE_BUNDLE=${GATE_MIN_ALPINE_BUNDLE:-20261020-r0}
GATE_SKIP_LIVE=${GATE_SKIP_LIVE:-0}
GATE_SKIP_OFFLINE=${GATE_SKIP_OFFLINE:-0}

# Evaluation time of the offline check, 2026-10-24T00:00:00Z: the vendored
# leaves expire on 2027-05-03, and an expired leaf must never read as a pass.
ATTIME=1792800000

HOSTS="dv-g3b-emudhra.we-have-a-problem.com dv-g3b-aaa-emudhra.we-have-a-problem.com"

findings=0
verdicts=" "
unreachable=0

say() { printf 'trust-gate: %s\n' "$*"; }
finding() { say "$*"; findings=$((findings + 1)); }
verdict() { verdicts="$verdicts$1 "; }

have() {
	case "$1" in
	*/*) [ -x "$1" ] ;;
	*) command -v "$1" >/dev/null 2>&1 ;;
	esac
}

say "bundle $GATE_BUNDLE, chains $GATE_CHAINS"

if [ "$GATE_SKIP_LIVE" != 1 ] && ! have "$GATE_CURL"; then
	finding "live: $GATE_CURL not found: FAIL (set GATE_SKIP_LIVE=1 only where a language probe replaces the live check)"
	GATE_SKIP_LIVE=1
fi
if [ "$GATE_SKIP_OFFLINE" != 1 ] && ! have openssl; then
	finding "offline: openssl not found: FAIL"
	GATE_SKIP_OFFLINE=1
fi

# Live check.
if [ "$GATE_SKIP_LIVE" = 1 ]; then
	say "live: skipped"
else
	for host in $HOSTS; do
		"$GATE_CURL" -sS --max-time 20 -o /dev/null "https://$host/" >/dev/null 2>&1 && rc=0 || rc=$?
		case "$rc" in
		60)
			say "$host: TLS verification error (curl 60): pass"
			verdict "$host"
			;;
		0)
			finding "$host: TLS handshake succeeded, HTTP answered: FAIL (chain trusted)"
			verdict "$host"
			;;
		6 | 7 | 28)
			say "$host: unreachable (curl $rc): no verdict"
			unreachable=$((unreachable + 1))
			;;
		*)
			finding "$host: curl exit $rc, not a TLS verification error: FAIL"
			verdict "$host"
			;;
		esac
	done
	if [ "$unreachable" -eq 2 ]; then
		say "live: unreachable (both hosts); the offline result decides"
	fi
fi

# Offline check against the vendored chains. -no-CAstore as well as -no-CAfile
# and -no-CApath: OpenSSL 3.0 (Ubuntu 24.04, ubuntu-latest) otherwise also
# loads its default CA store, so the host store would decide the verdict.
if [ "$GATE_SKIP_OFFLINE" = 1 ]; then
	say "offline: skipped"
else
	say "offline: $(openssl version 2>&1)"
	for host in $HOSTS; do
		chain="$GATE_CHAINS/$host.pem"
		if [ ! -r "$chain" ]; then
			finding "$host: offline: chain file $chain missing: FAIL"
			continue
		fi
		out=$(openssl verify -no-CApath -no-CAfile -no-CAstore -CAfile "$GATE_BUNDLE" -attime "$ATTIME" -untrusted "$chain" "$chain" 2>&1) && rc=0 || rc=$?
		reason=$(printf '%s\n' "$out" | grep -E '^error|unable to|unknown authority' | head -n 1)
		if [ "$rc" -eq 0 ]; then
			finding "$host: FAIL (offline: chain verifies against $GATE_BUNDLE)"
		elif [ "$rc" -eq 2 ] && printf '%s\n' "$out" | grep -qE 'unable to get local issuer certificate|certificate signed by unknown authority'; then
			say "$host: offline: not trusted by $GATE_BUNDLE ($reason): pass"
		else
			finding "$host: offline: openssl verify exit $rc, not a trust rejection ($(printf '%s' "$out" | tr '\n' ' ')): FAIL"
		fi
		verdict "$host"
	done
fi

# Every host needs a verdict from at least one of the two checks.
for host in $HOSTS; do
	case "$verdicts" in
	*" $host "*) ;;
	*) finding "$host: no verdict (live gave none and offline did not run): FAIL" ;;
	esac
done

# Alpine: the bundle package must be the fixed one. -e (installed only): with
# apk-tools 3 (Alpine 3.24 ships it) a bare "apk info -v <pkg>" prints the
# description lines of the installed and the available package instead of
# <name>-<version>.
if command -v apk >/dev/null 2>&1; then
	pkg=$(apk info -e -v ca-certificates-bundle 2>/dev/null | head -n 1)
	ver=${pkg#ca-certificates-bundle-}
	if [ -z "$pkg" ] || [ "$ver" = "$pkg" ]; then
		finding "alpine: ca-certificates-bundle not installed: FAIL"
	else
		cmp=$(apk version -t "$ver" "$GATE_MIN_ALPINE_BUNDLE" 2>/dev/null)
		case "$cmp" in
		'>' | '=') say "alpine: ca-certificates-bundle $ver >= $GATE_MIN_ALPINE_BUNDLE: pass" ;;
		*) finding "alpine: ca-certificates-bundle $ver older than $GATE_MIN_ALPINE_BUNDLE: FAIL" ;;
		esac
	fi
fi

if [ "$findings" -eq 0 ]; then
	say "PASS"
	exit 0
fi
say "FAIL ($findings findings)"
exit 1
