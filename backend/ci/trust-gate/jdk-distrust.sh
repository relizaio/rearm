#!/bin/sh
# Removes emSign ECC Root CA - G3 and AAA Certificate Services from the cacerts
# of the JDK on PATH, by SHA-256 fingerprint (aliases differ between Temurin,
# Red Hat and Debian). Run as root in the image's build stage:
#
#   sh /gate/jdk-distrust.sh
#
# A fingerprint already absent is logged, not an error, so the script keeps
# working once the JDK's own store drops the roots. Exit 1 if either root is
# still listed afterwards. Identical in every repository that carries
# ci/trust-gate/.

set -eu

FINGERPRINTS="86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B D7:A7:A0:FB:5D:7E:27:31:D7:71:E9:48:4E:BC:DE:F7:1D:5F:0C:3E:0A:29:48:78:2B:C8:3E:E0:EA:69:9E:F4"

say() { printf 'jdk-distrust: %s\n' "$*"; }

java_home=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^[[:space:]]*java\.home =/ { print $2; exit }')
[ -n "$java_home" ] || { say "cannot read java.home from java -XshowSettings:properties"; exit 1; }
keytool="$java_home/bin/keytool"
[ -x "$keytool" ] || keytool=keytool
say "java.home $java_home, keytool $keytool"

# Prints "<alias>\t<SHA256 fingerprint>" for every entry of the JDK's cacerts.
list() {
	"$keytool" -list -cacerts -storepass changeit -v 2>/dev/null | awk '
		/^Alias name: / { alias = substr($0, 13) }
		/^[[:space:]]*SHA256: / { fp = $0; sub(/^[[:space:]]*SHA256: */, "", fp); print alias "\t" toupper(fp) }
	'
}

entries=$(list)
[ -n "$entries" ] || { say "keytool -list -cacerts printed no entries"; exit 1; }

for fp in $FINGERPRINTS; do
	aliases=$(printf '%s\n' "$entries" | awk -F'\t' -v fp="$fp" '$2 == fp { print $1 }')
	if [ -z "$aliases" ]; then
		say "$fp: not in cacerts (already removed)"
		continue
	fi
	printf '%s\n' "$aliases" | while IFS= read -r alias; do
		"$keytool" -delete -cacerts -storepass changeit -alias "$alias"
		say "$fp: removed alias $alias"
	done
done

left=$(list | awk -F'\t' -v fps="$FINGERPRINTS" 'BEGIN { n = split(fps, f, " "); for (i = 1; i <= n; i++) want[f[i]] = 1 } want[$2] { print $1 }')
if [ -n "$left" ]; then
	say "still in cacerts after deletion: $left"
	exit 1
fi
say "neither root is in cacerts"
