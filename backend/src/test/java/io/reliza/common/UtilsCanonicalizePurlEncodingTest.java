/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Utils#canonicalizePurlPreservingEncoding} to the byte form
 * packageurl-js (rebom) persists, and {@link Utils#purlsSemanticallyEqual} as
 * the repair-trigger guard.
 *
 * <p>The regression these exist for: {@link Utils#canonicalizePurl} round-trips
 * through Java {@code PackageURL}, whose {@code toString()} percent-encodes a
 * Debian epoch colon ({@code 1:2.5.2-3} becomes {@code 1%3A2.5.2-3}) where
 * packageurl-js keeps it raw. On the canonical-qualifier sweep's first live run
 * that drift declared every deb-with-epoch row stale and minted encoding-variant
 * duplicate canonicals (95 components, 393 repointed mappings on the validation
 * instance). These inputs are copied from that incident's real rows.
 */
public class UtilsCanonicalizePurlEncodingTest {

	// Real row from the incident: epoch colon raw, + as %2B, non-preserved
	// qualifiers (arch, distro_name, epoch -- epoch is preserved for rpm, NOT deb).
	private static final String DEB_EXACT =
			"pkg:deb/debian/attr@1:2.5.2-3?arch=amd64&distro=debian-13&distro_name=trixie&epoch=1";
	private static final String DEB_REBOM_CANONICAL = "pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13";
	private static final String DEB_JAVA_DRIFTED = "pkg:deb/debian/attr@1%3A2.5.2-3?distro=debian-13";

	@Test
	public void keepsEpochColonRawExactlyAsRebomDoes() {
		assertEquals(DEB_REBOM_CANONICAL, Utils.canonicalizePurlPreservingEncoding(DEB_EXACT),
				"canonical must be byte-identical to rebom's form -- a %3A here re-creates the incident");
	}

	@Test
	public void preservesPlusEncodingVerbatim() {
		assertEquals("pkg:deb/debian/base-files@12.4%2Bdeb12u13?distro=debian-12",
				Utils.canonicalizePurlPreservingEncoding(
						"pkg:deb/debian/base-files@12.4%2Bdeb12u13?arch=amd64&distro=debian-12&distro_name=bookworm"));
	}

	@Test
	public void dropsAllQualifiersForTypesWithNoPreservedSet() {
		assertEquals("pkg:npm/lodash@4.17.21",
				Utils.canonicalizePurlPreservingEncoding("pkg:npm/lodash@4.17.21?checksum=sha1&foo=bar"));
	}

	@Test
	public void stripsSubpathAndHandlesBarePurl() {
		assertEquals("pkg:npm/lodash@4.17.21",
				Utils.canonicalizePurlPreservingEncoding("pkg:npm/lodash@4.17.21#lib/index.js"));
		assertEquals("pkg:npm/lodash@4.17.21",
				Utils.canonicalizePurlPreservingEncoding("pkg:npm/lodash@4.17.21"));
		assertNull(Utils.canonicalizePurlPreservingEncoding("not-a-purl"));
		assertNull(Utils.canonicalizePurlPreservingEncoding(null));
	}

	@Test
	public void rpmKeepsBothDistroAndEpochQualifiers() {
		assertEquals("pkg:rpm/redhat/openssl@3.0.7-27.el9?distro=rhel-9.3&epoch=1",
				Utils.canonicalizePurlPreservingEncoding(
						"pkg:rpm/redhat/openssl@3.0.7-27.el9?arch=x86_64&distro=rhel-9.3&epoch=1"));
	}

	@Test
	public void encodingVariantsCompareSemanticallyEqual() {
		assertTrue(Utils.purlsSemanticallyEqual(DEB_REBOM_CANONICAL, DEB_JAVA_DRIFTED),
				"encoding-variant pair must count as EQUAL or the sweep mints duplicates");
		assertTrue(Utils.purlsSemanticallyEqual(DEB_REBOM_CANONICAL, DEB_REBOM_CANONICAL));
	}

	@Test
	public void preservesEncodedAtSignInScopedNamespaces() {
		// %40 namespaces are pervasive in real data (1,118 rows measured live);
		// the surgery must pass them through untouched.
		assertEquals("pkg:npm/%40fortawesome/fontawesome-free@5.15.4",
				Utils.canonicalizePurlPreservingEncoding(
						"pkg:npm/%40fortawesome/fontawesome-free@5.15.4?checksum=sha1"));
	}

	@Test
	public void atSignEncodingVariantsCompareSemanticallyEqual() {
		assertTrue(Utils.purlsSemanticallyEqual(
				"pkg:npm/%40types/minimatch@3.0.5", "pkg:npm/@types/minimatch@3.0.5"),
				"%40 and raw @ in a namespace are the same identity");
	}

	@Test
	public void mixedPlusEncodingErasCompareSemanticallyEqual() {
		// rebom's own canonicals carry BOTH forms across eras (measured live:
		// 12.4%2Bdeb12u13 vs 12.2.0-14+deb12u1) -- the comparison must be
		// independent of which era wrote the row.
		assertTrue(Utils.purlsSemanticallyEqual(
				"pkg:deb/debian/libstdc%2B%2B6@12.2.0-14+deb12u1?distro=debian-12",
				"pkg:deb/debian/libstdc%2B%2B6@12.2.0-14%2Bdeb12u1?distro=debian-12"));
	}

	@Test
	public void realIdentityDifferencesStayUnequal() {
		assertFalse(Utils.purlsSemanticallyEqual(
				"pkg:apk/alpine/musl@1.2.4?distro=alpine-3.18", "pkg:apk/alpine/musl@1.2.4"),
				"a genuinely missing preserved qualifier is a different identity");
		assertFalse(Utils.purlsSemanticallyEqual(
				"pkg:apk/alpine/musl@1.2.4?distro=alpine-3.18",
				"pkg:apk/alpine/musl@1.2.4?distro=alpine-3.19"));
		assertFalse(Utils.purlsSemanticallyEqual("pkg:npm/lodash@4.17.21", "not-parseable"),
				"unparseable input must never compare equal to anything but itself");
	}
}
