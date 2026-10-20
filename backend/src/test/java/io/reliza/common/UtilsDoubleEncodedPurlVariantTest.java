/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * SCORE-29 (design T-6, T-12): a release-node purl that rebom used to write with its group and name
 * encoded twice ({@code %2520} for a space) still keys the same stored rows. The canonicaliser
 * leaves both forms byte for byte, and {@link Utils#doubleEncodedNamespaceNameVariant} gives the
 * legacy key of a single-encoded purl.
 */
public class UtilsDoubleEncodedPurlVariantTest {

	private static final String RELEASE_NODE = "pkg:generic/Reliza/ReARM%20Pro@1?tldonly=true#x";
	private static final String LEGACY_RELEASE_NODE = "pkg:generic/Reliza/ReARM%2520Pro@1?tldonly=true#x";

	@Test
	public void theCanonicaliserKeepsTheSingleEncodedReleaseNodeByteForByte() {
		String once = Utils.canonicalizePurlPreservingEncoding(RELEASE_NODE);
		assertEquals("pkg:generic/Reliza/ReARM%20Pro@1", once, "only the subpath and the generic qualifiers go");
		assertEquals(once, Utils.canonicalizePurlPreservingEncoding(once), "idempotent");
	}

	@Test
	public void theCanonicaliserKeepsTheLegacyReleaseNodeByteForByte() {
		String once = Utils.canonicalizePurlPreservingEncoding(LEGACY_RELEASE_NODE);
		assertEquals("pkg:generic/Reliza/ReARM%2520Pro@1", once, "never decodes the legacy form");
		assertEquals(once, Utils.canonicalizePurlPreservingEncoding(once), "idempotent");
	}

	@Test
	public void aSpaceInTheNameGivesThePercent2520VariantAndLeavesTheRestAlone() {
		assertEquals(Optional.of("pkg:generic/Reliza/ReARM%2520Pro%2520Backend@26.10.38%2Bb1?tldonly=true#a%20b"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/Reliza/ReARM%20Pro%20Backend@26.10.38%2Bb1?tldonly=true#a%20b"));
	}

	@Test
	public void aSpaceInTheNamespaceGivesTheVariantOfEverySegment() {
		assertEquals(Optional.of("pkg:generic/SBOM%2520Score%2520Walkthrough/a%252Fb@1.0.0"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/SBOM%20Score%20Walkthrough/a%2Fb@1.0.0"));
		assertEquals(Optional.of("pkg:generic/me%2540host/x%2520y"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/me%40host/x%20y"), "no version");
	}

	@Test
	public void anEncodedPlusInADebName() {
		assertEquals(Optional.of("pkg:deb/debian/g%252B%252B@12?distro=debian-12"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:deb/debian/g%2B%2B@12?distro=debian-12"));
	}

	@Test
	public void aSlashOrAColonInTheNamespaceGivesTheLegacyEscapes() {
		assertEquals(Optional.of("pkg:generic/a%252Fb/rearm@1.0.0"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/a/b/rearm@1.0.0"));
		assertEquals(Optional.of("pkg:generic/a%253Ab/rearm@1.0.0"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/a:b/rearm@1.0.0"));
		assertEquals(Optional.of("pkg:golang/github.com%252Ffoo/bar@v1.2.3"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:golang/github.com/foo/bar@v1.2.3"));
	}

	@Test
	public void anEncodedSlashOrColonInTheNameTakesOnlyThePercentRule() {
		assertEquals(Optional.of("pkg:generic/x%2520y/a%252Fb@1?tldonly=true"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/x%20y/a%2Fb@1?tldonly=true"),
				"qualifiers untouched");
		assertEquals(Optional.of("pkg:generic/a%253Ab/c%253Ad@1"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/a:b/c%3Ad@1"));
	}

	@Test
	public void withoutANamespaceOnlyThePercentRuleApplies() {
		assertEquals(Optional.of("pkg:generic/x%2520y@1"), Utils.doubleEncodedNamespaceNameVariant("pkg:generic/x%20y@1"));
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:generic/a:b@1"),
				"a raw colon in a name is never rewritten");
	}

	@Test
	public void aPlainNamespaceAndAnEncodedScopeBehaveAsBefore() {
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:generic/plain/name@1"));
		assertEquals(Optional.of("pkg:npm/%2540scope/name@1"), Utils.doubleEncodedNamespaceNameVariant("pkg:npm/%40scope/name@1"));
	}

	@Test
	public void aSlashOrAColonInTheQualifiersOnlyIsLeftAlone() {
		assertEquals(Optional.of("pkg:generic/a%252Fb/c@1?x=y/z:w"),
				Utils.doubleEncodedNamespaceNameVariant("pkg:generic/a/b/c@1?x=y/z:w"));
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:generic/a/c@1?x=y/z:w#p/q:r"),
				"nor in the subpath");
	}

	@Test
	public void nothingToVaryWithoutAPercentInTheNamespaceOrName() {
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:generic/Reliza/rearm@1.0.0?tldonly=true"));
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:deb/debian/base-files@12.4%2Bdeb12u13?distro=debian-12"),
				"a percent in the version only");
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:npm/@scope/name"),
				"a raw at sign in the namespace is not a version separator");
	}

	@Test
	public void nothingToVaryForAnythingButAPkgPurl() {
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("cpe:2.3:a:reliza:rearm%20pro:1:*:*:*:*:*:*:*"));
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("urn:rearm:release:x%20y"));
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant("pkg:generic"));
		assertEquals(Optional.empty(), Utils.doubleEncodedNamespaceNameVariant(null));
	}
}
