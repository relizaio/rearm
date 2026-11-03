/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

	// ---- Utils.purlVersionlessKey (TPF-146, design 6.1) ----

	/** 6.1 row 1: maven, with and without version and type qualifier; namespace is part of the key. */
	@Test
	public void versionlessKeyMavenDropsVersionAndQualifiers() {
		String key = "pkg:maven/io.reliza/rearm-pro-backend";
		assertEquals(key, Utils.purlVersionlessKey(
				"pkg:maven/io.reliza/rearm-pro-backend@2026-10-tpf-142-tea-custom-domain.3?type=jar"));
		assertEquals(key, Utils.purlVersionlessKey("pkg:maven/io.reliza/rearm-pro-backend@2026-10-pro-cve-sweep.0"));
		assertEquals(key, Utils.purlVersionlessKey("pkg:maven/io.reliza/rearm-pro-backend"));
		assertNotEquals(key, Utils.purlVersionlessKey("pkg:maven/io.reliza.other/rearm-pro-backend@1"));
	}

	/** 6.1 row 2: oci with repository_url and tag; the preserved qualifier is dropped too. */
	@Test
	public void versionlessKeyOciDropsRepositoryUrl() {
		String key = "pkg:oci/rearm-backend";
		assertEquals(key, Utils.purlVersionlessKey(
				"pkg:oci/rearm-backend@sha256%3Aabc123?repository_url=registry.relizahub.com/reliza/rearm-backend&tag=2026.10.3"));
		assertEquals(key, Utils.purlVersionlessKey(
				"pkg:oci/rearm-backend?repository_url=registry.relizahub.com/reliza/rearm-backend"));
		assertEquals(key, Utils.purlVersionlessKey("pkg:oci/rearm-backend@sha256:abc123"));
	}

	/** 6.1 row 3: types without a namespace. */
	@Test
	public void versionlessKeyWithoutNamespace() {
		assertEquals("pkg:npm/lodash", Utils.purlVersionlessKey("pkg:npm/lodash@4.17.21"));
		assertEquals("pkg:generic/rearm-cli", Utils.purlVersionlessKey("pkg:generic/rearm-cli@26.10.17"));
	}

	/** 6.1 row 4: other preserved qualifiers, subpath, encoding and type normalisation. */
	@Test
	public void versionlessKeyNormalisesEncodingAndType() {
		assertEquals("pkg:julia/Example",
				Utils.purlVersionlessKey("pkg:julia/Example@0.5.1?uuid=7876af07-990d-54b4-ab0e-23690620f79a"));
		assertEquals("pkg:deb/debian/libstdc%2B%2B6",
				Utils.purlVersionlessKey("pkg:deb/debian/libstdc%2B%2B6@12.2.0-14?distro=debian-12&arch=amd64"));
		assertEquals("pkg:deb/debian/libstdc%2B%2B6",
				Utils.purlVersionlessKey("pkg:deb/debian/libstdc++6@12.2.0-14+deb12u1"));
		assertEquals("pkg:golang/github.com/foo/bar",
				Utils.purlVersionlessKey("pkg:golang/github.com/foo/bar@v1.2.3#sub/dir"));
		assertEquals("pkg:npm/%40angular/core", Utils.purlVersionlessKey("pkg:npm/%40angular/core@17.0.0"));
		assertEquals("pkg:maven/io.reliza/Rearm-Pro-Backend",
				Utils.purlVersionlessKey("pkg:MAVEN/io.reliza/Rearm-Pro-Backend@1"));
		assertEquals("pkg:pypi/django-foo", Utils.purlVersionlessKey("pkg:pypi/Django_Foo@1.0"));
	}

	/** 6.1 row 5: CPE, non-purls and malformed purls give null and never throw. */
	@Test
	public void versionlessKeyNullForCpeAndMalformed() {
		String[] inputs = {"cpe:2.3:a:reliza:rearm:26.10.17:*:*:*:*:*:*:*", null, "", "lodash", "pkg:",
				"pkg:maven", "pkg:maven/%ZZbad@1", "pkg:maven/io.reliza/rearm-pro-backend@1%ZZ"};
		for (String input : inputs) {
			assertDoesNotThrow(() -> Utils.purlVersionlessKey(input), String.valueOf(input));
			assertNull(Utils.purlVersionlessKey(input), String.valueOf(input));
		}
	}
}
