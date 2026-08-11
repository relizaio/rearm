/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Utils#purlCoordinateBase} and {@link Utils#purlVersion}, the two
 * helpers backing version-agnostic purl search.
 *
 * <p>The behaviour that matters downstream: the coordinate base is used as a
 * LIKE prefix against {@code canonical_purl}, so it must drop everything that
 * sorts after the version (qualifiers, subpath) or the prefix stops matching.
 */
public class UtilsPurlCoordinateTest {

	@Test
	public void stripsVersionFromCoordinate() {
		assertEquals("pkg:npm/lodash", Utils.purlCoordinateBase("pkg:npm/lodash@4.17.21"));
	}

	@Test
	public void keepsNamespaceInCoordinate() {
		assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core",
				Utils.purlCoordinateBase("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"));
	}

	@Test
	public void versionlessPurlIsAlreadyItsOwnCoordinate() {
		assertEquals("pkg:npm/lodash", Utils.purlCoordinateBase("pkg:npm/lodash"));
	}

	/**
	 * Qualifiers sort AFTER the version in a purl, so a coordinate that kept
	 * them could never prefix-match a stored {@code coordinate@version?quals}.
	 * This is the one place the coordinate base deliberately diverges from
	 * {@link Utils#canonicalizePurl}, which preserves identity qualifiers.
	 */
	@Test
	public void dropsQualifiersAndSubpathSoThePrefixStillMatches() {
		assertEquals("pkg:deb/debian/attr",
				Utils.purlCoordinateBase("pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13"));
		assertEquals("pkg:golang/github.com/foo/bar",
				Utils.purlCoordinateBase("pkg:golang/github.com/foo/bar@v1.2.3#sub/dir"));
	}

	@Test
	public void rejectsNonPurlInput() {
		assertNull(Utils.purlCoordinateBase("lodash"));
		assertNull(Utils.purlCoordinateBase(""));
		assertNull(Utils.purlCoordinateBase(null));
		assertNull(Utils.purlCoordinateBase("pkg:"));
	}

	@Test
	public void extractsPinnedVersion() {
		assertEquals("4.17.21", Utils.purlVersion("pkg:npm/lodash@4.17.21"));
		assertEquals("1:2.5.2-3", Utils.purlVersion("pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13"));
	}

	@Test
	public void reportsNullVersionForVersionlessPurl() {
		assertNull(Utils.purlVersion("pkg:npm/lodash"));
		assertNull(Utils.purlVersion("pkg:npm/lodash?arch=amd64"));
	}

	@Test
	public void reportsNullVersionForNonPurl() {
		assertNull(Utils.purlVersion("lodash"));
		assertNull(Utils.purlVersion(null));
	}
}
