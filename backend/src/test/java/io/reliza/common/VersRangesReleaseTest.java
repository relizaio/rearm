/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.nscuro.versatile.Vers;
import io.github.nscuro.versatile.spi.InvalidVersionException;
import io.reliza.common.VersRanges.ReleaseScheme;

/**
 * A product's own release versions as vers: the {@code semver} ordering
 * versatile lacks, the scheme a version schema selects, and runs of releases
 * named as one range.
 */
class VersRangesReleaseTest {

	// ---- the semver ordering ----

	@Test
	void aPreReleaseOrdersBeforeItsRelease() {
		// generic, versatile's fallback without a provider, puts 1.0.0-rc.1 above 1.0.0
		assertEquals(VersRanges.SCHEME_SEMVER, Vers.parse("vers:semver/<1.0.0").scheme());
		assertTrue(Vers.parse("vers:semver/<1.0.0").contains("1.0.0-rc.1"));
		assertFalse(Vers.parse("vers:semver/<1.0.0").contains("1.0.0"));
		assertTrue(Vers.parse("vers:semver/<1.0.0-rc.10").contains("1.0.0-rc.2"), "numeric identifiers compare as numbers");
		assertTrue(Vers.parse("vers:semver/>=1.0.0-alpha|<1.0.0-beta").contains("1.0.0-alpha.1"));
	}

	@Test
	void buildMetadataDoesNotOrder() {
		assertTrue(Vers.parse("vers:semver/1.0.0").contains("1.0.0+build.5"));
		assertFalse(Vers.parse("vers:semver/<1.0.0").contains("1.0.0+build.5"));
	}

	/** Decision: a version that is not a semantic version is refused, never normalised. */
	@ParameterizedTest
	@ValueSource(strings = {"v1.2.3", "V1.2.3", "1.2", "1.2.3.4", "01.2.3", "1.2.3-01", "1.2.3-", "99999999999.0.0"})
	void notASemanticVersionIsRefused(String version) {
		assertThrows(InvalidVersionException.class, () -> Vers.parse("vers:semver/<2.0.0").contains(version));
	}

	// ---- the scheme of a version schema ----

	@ParameterizedTest
	@CsvSource({
		"semver, SEMVER",
		"Semver, SEMVER",
		"Major.Minor.Patch, SEMVER",
		"Major.Minor.Patch-Modifier, SEMVER",
		"YYYY.0M.Micro, GENERIC",
		"Major.Minor.Patch.Nano, GENERIC",
		"Branch.Micro, GENERIC",
	})
	void theVersionSchemaSelectsTheScheme(String schema, ReleaseScheme expected) {
		assertEquals(expected, ReleaseScheme.ofVersionSchema(schema));
	}

	@Test
	void noSchemaIsSemverWhereTheVersionIsOne() {
		assertEquals(ReleaseScheme.SEMVER, ReleaseScheme.ofVersionSchema(null));
		assertEquals("vers:semver/1.2.3", VersRanges.releaseVers(ReleaseScheme.ofVersionSchema(""), "1.2.3"));
	}

	// ---- one version ----

	@Test
	void aReleaseVersionIsWrittenInItsSchemesScheme() {
		assertEquals("vers:semver/1.2.3", VersRanges.releaseVers(ReleaseScheme.SEMVER, "1.2.3"));
		assertEquals("vers:semver/1.0.0-rc.1+b5", VersRanges.releaseVers(ReleaseScheme.SEMVER, "1.0.0-rc.1+b5"));
		// a semver-looking version under another schema is not guessed into semver
		assertEquals("vers:generic/1.2.3", VersRanges.releaseVers(ReleaseScheme.GENERIC, "1.2.3"));
		assertEquals("vers:generic/2026.10.1", VersRanges.releaseVers(ReleaseScheme.GENERIC, "2026.10.1"));
	}

	@Test
	void aVersionThatIsNotOneUnderTheSchemeIsGeneric() {
		// a feature branch of a semver product, and a leading v
		assertEquals("vers:generic/feature-x.0", VersRanges.releaseVers(ReleaseScheme.SEMVER, "feature-x.0"));
		assertEquals("vers:generic/v1.2.3", VersRanges.releaseVers(ReleaseScheme.SEMVER, "v1.2.3"));
		assertEquals("vers:generic/1.0%7Cx", VersRanges.releaseVers(ReleaseScheme.SEMVER, "1.0|x"),
				"the vers separator is encoded");
		assertNull(VersRanges.releaseVers(ReleaseScheme.SEMVER, null));
	}

	// ---- runs of releases ----

	private static Optional<String> range(ReleaseScheme scheme, List<String> all, List<String> named) {
		return VersRanges.releaseOrder(scheme, all).rangeOf(named);
	}

	@Test
	void aRunOfReleasesIsOneRange() {
		List<String> all = List.of("1.0.0", "1.0.1", "1.0.2", "1.1.0", "2.0.0");
		assertEquals(Optional.of("vers:semver/>=1.0.0|<=1.0.2"),
				range(ReleaseScheme.SEMVER, all, List.of("1.0.2", "1.0.0", "1.0.1")), "in any order");
		assertEquals(Optional.of("vers:semver/>=1.1.0|<=2.0.0"), range(ReleaseScheme.SEMVER, all, List.of("1.1.0", "2.0.0")));
	}

	@Test
	void releasesWithAnotherBetweenThemAreNoRange() {
		List<String> all = List.of("1.0.0", "1.0.1", "1.0.2");
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, all, List.of("1.0.0", "1.0.2")));
	}

	@Test
	void oneReleaseIsNoRange() {
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, List.of("1.0.0", "1.0.1"), List.of("1.0.0")));
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, List.of("1.0.0", "1.0.1"), List.of("1.0.0", "1.0.0")));
	}

	@Test
	void aPreReleaseBetweenIsAnotherRelease() {
		List<String> all = List.of("0.9.0", "1.0.0-rc.1", "1.0.0", "1.0.1");
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, all, List.of("0.9.0", "1.0.0")),
				"the rc sorts between them");
		assertEquals(Optional.of("vers:semver/>=1.0.0-rc.1|<=1.0.1"),
				range(ReleaseScheme.SEMVER, all, List.of("1.0.0-rc.1", "1.0.0", "1.0.1")));
	}

	@Test
	void anotherBuildOfTheSameVersionIsInsideTheRange() {
		List<String> all = List.of("1.0.0+b1", "1.0.0+b2", "1.1.0");
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, all, List.of("1.0.0+b1", "1.1.0")));
		// and the two builds alone are one version, which a vers range names once: no range
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, all, List.of("1.0.0+b1", "1.0.0+b2")));
	}

	@Test
	void releasesThatAreNotVersionsUnderTheSchemeAreNeverInside() {
		List<String> all = List.of("1.0.0", "feature-x.0", "1.0.1", "1.0.2");
		assertEquals(Optional.of("vers:semver/>=1.0.0|<=1.0.2"),
				range(ReleaseScheme.SEMVER, all, List.of("1.0.0", "1.0.1", "1.0.2")));
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, all, List.of("1.0.0", "feature-x.0")),
				"a named version that is not one: no range");
	}

	@Test
	void aReleaseThatIsNotASemanticVersionButStartsWithNumbersInsideKeepsTheRangeFromForming() {
		// a lenient reader would put v1.2.5 and 1.2.5.1 between 1.2.3 and 1.2.6
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, List.of("1.2.3", "v1.2.5", "1.2.6"),
				List.of("1.2.3", "1.2.6")));
		assertEquals(Optional.empty(), range(ReleaseScheme.SEMVER, List.of("1.2.3", "1.2.5.1", "1.2.6"),
				List.of("1.2.3", "1.2.6")));
		assertEquals(Optional.of("vers:semver/>=1.2.3|<=1.2.6"), range(ReleaseScheme.SEMVER,
				List.of("1.2.3", "1.2.6", "v1.3.0"), List.of("1.2.3", "1.2.6")), "outside the range it does not matter");
	}

	@Test
	void aGenericRunIsARangeWhenItsNumbersOrderIt() {
		List<String> all = List.of("2026.01.1", "2026.01.2", "2026.02.1", "2026.10.1");
		assertEquals(Optional.of("vers:generic/>=2026.01.1|<=2026.02.1"),
				range(ReleaseScheme.GENERIC, all, List.of("2026.01.1", "2026.01.2", "2026.02.1")));
		assertEquals(Optional.empty(), range(ReleaseScheme.GENERIC, all, List.of("2026.01.1", "2026.10.1")));
	}

	@Test
	void aGenericRunIsNoRangeWhereGenericOrdersByMoreThanNumbers() {
		// generic puts a pre-release above its release: refused, not reproduced
		assertEquals(Optional.empty(), range(ReleaseScheme.GENERIC, List.of("1.0.0-rc1", "1.0.0", "1.0.1"),
				List.of("1.0.0", "1.0.1")), "the run's neighbour ties with it on its numbers");
		// a release that does not start with numbers: generic's order of it is its own
		assertEquals(Optional.empty(), range(ReleaseScheme.GENERIC, List.of("1.0", "1.1", "main"),
				List.of("1.0", "1.1")));
	}
}
