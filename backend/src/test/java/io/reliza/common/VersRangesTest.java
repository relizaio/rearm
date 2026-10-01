/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.common.VersRanges.FailureKey;
import io.reliza.common.VersRanges.Placement;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.AffectedRangeType;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;

/**
 * The ranges below are the live Dependency-Track shapes after F1's
 * normalization (see {@code affected-components-live-shapes*.json}):
 * GHSA-f2jv-r9rf-7988 (handlebars, one open-start range), PYSEC-2018-5
 * (django, three ranges) and DEBIAN-CVE-2015-3276 (no fix: a range without
 * bounds).
 */
class VersRangesTest {

	private static final String VULN = "TEST-1";

	private static AffectedRange range(String identity, String startIncl, String endExcl, UpstreamSource... sources) {
		AffectedRange r = new AffectedRange();
		r.setIdentityType(AffectedIdentityType.PURL);
		r.setIdentity(identity);
		r.setRangeType(AffectedRangeType.RANGE);
		r.setVersionStartIncluding(startIncl);
		r.setVersionEndExcluding(endExcl);
		r.setSources(new ArrayList<>(List.of(sources)));
		return r;
	}

	private static AffectedRange exact(String identity, String version) {
		AffectedRange r = new AffectedRange();
		r.setIdentityType(AffectedIdentityType.PURL);
		r.setIdentity(identity);
		r.setRangeType(AffectedRangeType.EXACT);
		r.setExactVersion(version);
		return r;
	}

	private static List<AffectedRange> django() {
		return List.of(range("pkg:pypi/django", "1.11", "1.11.11", UpstreamSource.OSV),
				range("pkg:pypi/django", "1.8", "1.8.19", UpstreamSource.OSV),
				range("pkg:pypi/django", "2.0", "2.0.3", UpstreamSource.OSV));
	}

	private static Optional<String> vers(String purl, AffectedRange... ranges) {
		return VersRanges.toVersString(purl, List.of(ranges), VULN);
	}

	@Test
	void handlebarsIsOneOpenStartRange() {
		assertEquals(Optional.of("vers:npm/<4.7.7"),
				vers("pkg:npm/a", range("pkg:npm/handlebars", null, "4.7.7", UpstreamSource.GITHUB)));
	}

	@Test
	void djangoIsOneLineInVersionOrder() {
		assertEquals(Optional.of("vers:pypi/>=1.8|<1.8.19|>=1.11|<1.11.11|>=2.0|<2.0.3"),
				VersRanges.toVersString("pkg:pypi/django", django(), VULN));
	}

	@Test
	void aRangeWithoutBoundsIsEveryVersion() {
		assertEquals(Optional.of("vers:deb/*"),
				vers("pkg:deb/debian/a", range("pkg:deb/debian/openldap?arch=source&distro=bookworm", null, null, UpstreamSource.OSV)));
	}

	@Test
	void everyVersionSwallowsTheOtherRanges() {
		assertEquals(Optional.of("vers:deb/*"), vers("pkg:deb/debian/a",
				range("pkg:deb/debian/zlib", null, "1:1.3.dfsg-2"),
				range("pkg:deb/debian/zlib", null, null)));
	}

	@Test
	void anExactRangeIsAPlainVersion() {
		assertEquals(Optional.of("vers:maven/6.3.2.1"), vers("pkg:maven/g/a", exact("pkg:maven/org.xbib.elasticsearch/log4j", "6.3.2.1")));
	}

	@Test
	void endIncludingAndStartExcludingKeepTheirOperators() {
		AffectedRange r = range("pkg:maven/com.guicedee.services/log4j-core", null, null);
		r.setVersionStartExcluding("1.0");
		r.setVersionEndIncluding("1.2.1.2-jre17");
		assertEquals(Optional.of("vers:maven/>1.0|<=1.2.1.2-jre17"), vers("pkg:maven/g/a", r));
	}

	@Test
	void overlappingRangesMergeIntoTheirUnion() {
		assertEquals(Optional.of("vers:npm/>=1.0.0|<2.5.0"), vers("pkg:npm/a",
				range("pkg:npm/a", "1.0.0", "2.0.0"),
				range("pkg:npm/a", "1.5.0", "2.5.0")));
	}

	@Test
	void anOpenStartRangeIsNotLostWhenItOverlapsABoundedOne() {
		// Sorting the four bounds by version and simplifying would read
		// ">=1.0|<1.5|<2.0" and lose everything below 1.0.
		assertEquals(Optional.of("vers:npm/<2.0.0"), vers("pkg:npm/a",
				range("pkg:npm/a", null, "1.5.0"),
				range("pkg:npm/a", "1.0.0", "2.0.0")));
	}

	@Test
	void adjoiningRangesJoin() {
		assertEquals(Optional.of("vers:npm/>=1.0.0|<3.0.0"), vers("pkg:npm/a",
				range("pkg:npm/a", "1.0.0", "2.0.0"),
				range("pkg:npm/a", "2.0.0", "3.0.0")));
	}

	@Test
	void versionsCompareByTheSchemeNotAsText() {
		// "1.10.0" < "1.9.0" as text; by semver it is the larger
		assertEquals(Optional.of("vers:npm/>=1.9.0|<1.10.0"), vers("pkg:npm/a", range("pkg:npm/a", "1.9.0", "1.10.0")));
		assertEquals(Optional.of("vers:maven/>=2.0-beta9|<2.3.1|>=2.4|<2.12.2|>=2.13.0|<2.15.0"), vers("pkg:maven/g/a",
				range("pkg:maven/org.apache.logging.log4j/log4j-core", "2.13.0", "2.15.0"),
				range("pkg:maven/org.apache.logging.log4j/log4j-core", "2.0-beta9", "2.3.1"),
				range("pkg:maven/org.apache.logging.log4j/log4j-core", "2.4", "2.12.2")));
	}

	@Test
	void anEmptyRangeIsDropped() {
		assertEquals(Optional.of("vers:npm/<1.0.0"), vers("pkg:npm/a",
				range("pkg:npm/a", "2.0.0", "1.0.0"),
				range("pkg:npm/a", null, "1.0.0")));
	}

	@Test
	void anUnparseableBoundDropsOnlyItsOwnRange() {
		assertEquals(Optional.of("vers:npm/<4.7.7"), vers("pkg:npm/a",
				range("pkg:npm/a", null, "not a version"),
				range("pkg:npm/a", null, "4.7.7")));
	}

	@Test
	void noRangeLeftMeansNoVers() {
		assertEquals(Optional.empty(), vers("pkg:npm/a", range("pkg:npm/a", null, "not a version")));
		assertEquals(Optional.empty(), VersRanges.toVersString("pkg:npm/a", List.of(), VULN));
		assertEquals(Optional.empty(), VersRanges.toVersString("pkg:npm/a", null, VULN));
	}

	@Test
	void aRangeOfUnknownTypeIsLeftOut() {
		AffectedRange unknown = range("pkg:npm/a", null, "1.0.0");
		unknown.setRangeType(null);
		assertEquals(Optional.empty(), vers("pkg:npm/a", unknown));
		assertEquals(Optional.of("vers:npm/<2.0.0"), vers("pkg:npm/a", unknown, range("pkg:npm/a", null, "2.0.0")));
	}

	@Test
	void debianEpochsAndRevisionsAreNotEscaped() {
		// vers percent-encodes '|', '%' and the operator characters, not ':' or '+'
		assertEquals(Optional.of("vers:deb/<1:1.3.dfsg-2"), vers("pkg:deb/debian/a", range("pkg:deb/debian/zlib", null, "1:1.3.dfsg-2")));
		assertEquals(Optional.of("vers:deb/<1.1-8+deb12u1"), vers("pkg:deb/debian/a", range("pkg:deb/debian/minizip", null, "1.1-8+deb12u1")));
	}

	@Test
	void packagesWithoutAnOrderingOfTheirOwnUseTheGenericScheme() {
		assertEquals("generic", VersRanges.schemeFor("hackage"));
		assertEquals("generic", VersRanges.schemeFor(null));
		// versatile names cpan but orders it as generic
		assertEquals("generic", VersRanges.schemeFor("cpan"));
		assertEquals("maven", VersRanges.schemeFor("gradle"));
		assertEquals("deb", VersRanges.schemeFor("deb"));
		assertEquals(Optional.of("vers:generic/<2.1"), vers("pkg:hackage/text", range("pkg:hackage/text", null, "2.1")));
		assertEquals(Optional.empty(), vers("cpe:2.3:a:vendor:product:*:*:*:*:*:*:*:*", range("pkg:npm/a", null, "2.1")));
	}

	@Test
	void versByIdentityGroupsByTheIdentityAsStored() {
		// DEBIAN-CVE-2023-45853 on Dependency-Track 5: one row per Debian release
		List<AffectedRange> ranges = List.of(
				range("pkg:deb/debian/minizip?arch=source&distro=bookworm", null, "1.1-8+deb12u1", UpstreamSource.OSV),
				range("pkg:deb/debian/zlib?arch=source&distro=bookworm", null, null, UpstreamSource.OSV),
				range("pkg:deb/debian/zlib?arch=source&distro=forky", null, "1:1.3.dfsg-2", UpstreamSource.OSV),
				range("pkg:deb/debian/zlib?arch=source&distro=trixie", null, "1:1.3.dfsg-2", UpstreamSource.OSV));
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("pkg:deb/debian/minizip?arch=source&distro=bookworm", "vers:deb/<1.1-8+deb12u1");
		expected.put("pkg:deb/debian/zlib?arch=source&distro=bookworm", "vers:deb/*");
		expected.put("pkg:deb/debian/zlib?arch=source&distro=forky", "vers:deb/<1:1.3.dfsg-2");
		expected.put("pkg:deb/debian/zlib?arch=source&distro=trixie", "vers:deb/<1:1.3.dfsg-2");
		assertEquals(expected, VersRanges.versByIdentity(ranges, VULN));
	}

	@Test
	void versByIdentitySkipsCpesAndIdentitiesWithoutAVers() {
		AffectedRange cpe = exact("cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*", "1.0");
		cpe.setIdentityType(AffectedIdentityType.CPE);
		Map<String, String> byIdentity = VersRanges.versByIdentity(List.of(cpe,
				range("pkg:npm/broken", null, "not a version"),
				range("pkg:npm/handlebars", null, "4.7.7")), VULN);
		assertEquals(Map.of("pkg:npm/handlebars", "vers:npm/<4.7.7"), byIdentity);
		assertTrue(VersRanges.versByIdentity(null, VULN).isEmpty());
	}

	// ---- place ----

	private static Placement place(String purl, List<AffectedRange> ranges) {
		return VersRanges.place(purl, ranges, VULN);
	}

	private static Placement at(Placement.Position position, String bound, UpstreamSource... sources) {
		return new Placement(position, bound, List.of(sources));
	}

	@Test
	void theContainingIntervalGivesTheBound() {
		assertEquals(at(Placement.Position.ENDS_BEFORE, "1.11.11", UpstreamSource.OSV), place("pkg:pypi/django@1.11", django()));
	}

	@Test
	void aVersionBetweenIntervalsIsOutside() {
		for (String v : List.of("1.8.19", "1.10", "2.0.3")) {
			assertEquals(Placement.of(Placement.Position.OUTSIDE), place("pkg:pypi/django@" + v, django()), v);
		}
	}

	@Test
	void anOpenEndIsUnbounded() {
		assertEquals(at(Placement.Position.UNBOUNDED, null, UpstreamSource.GITHUB),
				place("pkg:npm/a@5.0.0", List.of(range("pkg:npm/a", "1.0.0", null, UpstreamSource.GITHUB))));
		assertEquals(Placement.Position.UNBOUNDED,
				place("pkg:deb/debian/zlib@1:1.2.13.dfsg-1", List.of(range("pkg:deb/debian/zlib", null, null))).position());
	}

	@Test
	void anInclusiveEndIsTheBound() {
		AffectedRange r = range("pkg:maven/com.guicedee.services/log4j-core", null, null, UpstreamSource.GITHUB);
		r.setVersionEndIncluding("1.2.1.2-jre17");
		assertEquals(at(Placement.Position.ENDS_AT, "1.2.1.2-jre17", UpstreamSource.GITHUB),
				place("pkg:maven/com.guicedee.services/log4j-core@1.2.1.2-jre17", List.of(r)));
	}

	@Test
	void anExactVersionEndsAtItself() {
		List<AffectedRange> exact = List.of(exact("pkg:maven/org.xbib.elasticsearch/log4j", "6.3.2.1"));
		assertEquals(at(Placement.Position.ENDS_AT, "6.3.2.1"), place("pkg:maven/org.xbib.elasticsearch/log4j@6.3.2.1", exact));
		assertEquals(Placement.Position.OUTSIDE, place("pkg:maven/org.xbib.elasticsearch/log4j@6.3.2.2", exact).position());
	}

	@Test
	void anExactVersionAdjoiningARangeExtendsIt() {
		List<AffectedRange> ranges = List.of(range("pkg:maven/g/a", "1.0", "2.0"), exact("pkg:maven/g/a", "2.0"));
		assertEquals(Optional.of("vers:maven/>=1.0|<=2.0"), VersRanges.toVersString("pkg:maven/g/a", ranges, VULN));
		assertEquals(at(Placement.Position.ENDS_AT, "2.0"), place("pkg:maven/g/a@1.5", ranges));
	}

	@Test
	void startExcludingRangesJoinOnlyWhereAVersionIsCovered() {
		AffectedRange low = range("pkg:npm/a", null, "2.0.0");
		low.setVersionStartExcluding("1.0.0");
		AffectedRange high = range("pkg:npm/a", "2.0.0", "3.0.0");
		assertEquals(Optional.of("vers:npm/>1.0.0|<3.0.0"), vers("pkg:npm/a", low, high));
		// both exclude 2.0.0: two intervals, and 2.0.0 is unaffected
		high.setVersionStartIncluding(null);
		high.setVersionStartExcluding("2.0.0");
		assertEquals(Optional.of("vers:npm/>1.0.0|<2.0.0|>2.0.0|<3.0.0"), vers("pkg:npm/a", low, high));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "2.0.0"), place("pkg:npm/a@1.5.0", List.of(low, high)));
		assertEquals(Placement.Position.OUTSIDE, place("pkg:npm/a@2.0.0", List.of(low, high)).position());
	}

	@Test
	void overlappingSourcesGiveTheLaterBoundAndBothSources() {
		// GitHub and OSV disagree on the fix; the union is affected until the later one
		assertEquals(at(Placement.Position.ENDS_BEFORE, "2.1.0", UpstreamSource.GITHUB, UpstreamSource.OSV), place("pkg:npm/a@1.5.0",
				List.of(range("pkg:npm/a", "1.0.0", "2.0.0", UpstreamSource.GITHUB), range("pkg:npm/a", "1.2.0", "2.1.0", UpstreamSource.OSV))));
	}

	@Test
	void onlyRangesContainingTheVersionContributeSources() {
		assertEquals(at(Placement.Position.ENDS_BEFORE, "2.1.0", UpstreamSource.GITHUB), place("pkg:npm/a@1.9.0",
				List.of(range("pkg:npm/a", "1.0.0", "2.0.0", UpstreamSource.GITHUB), range("pkg:npm/a", "1.95.0", "2.1.0", UpstreamSource.OSV))));
	}

	@Test
	void anRpmEpochQualifierIsPartOfTheVersion() {
		List<AffectedRange> ranges = List.of(range("pkg:rpm/redhat/openssl", null, "1:1.1.1k-12.el8_9"));
		// without the epoch, 1.1.1k-13 would read as epoch 0, below the fix
		assertEquals(Placement.Position.OUTSIDE, place("pkg:rpm/redhat/openssl@1.1.1k-13.el8_9?epoch=1", ranges).position());
		assertEquals(at(Placement.Position.ENDS_BEFORE, "1:1.1.1k-12.el8_9"), place("pkg:rpm/redhat/openssl@1.1.1k-11.el8_9?epoch=1", ranges));
		// an epoch already in the version is not added twice
		assertEquals(Placement.Position.OUTSIDE, place("pkg:rpm/redhat/openssl@1:1.1.1k-13.el8_9?epoch=1", ranges).position());
	}

	@Test
	void aVersionThatCannotBePlacedIsUnplaceable() {
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:npm/a@latest", django()));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:npm/a", django()));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place(null, django()));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:npm/a@1.0.0", List.of(range("pkg:npm/a", null, "not a version"))));
	}

	@Test
	void aBoundTheParserCannotReadLeavesOutOnlyItsRange() {
		// versatile's golang parser throws StringIndexOutOfBoundsException on these
		String purl = "pkg:golang/example.com/a";
		AffectedRange good = range(purl, null, "v1.3.0");
		List<AffectedRange> ranges = List.of(range(purl, null, "v"), range(purl, null, "v2.1+"), good);
		assertEquals(at(Placement.Position.ENDS_BEFORE, "v1.3.0"), place(purl + "@v1.2.3", ranges));
		assertEquals(VersRanges.toVersString(purl, List.of(good), VULN), VersRanges.toVersString(purl, ranges, VULN));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place(purl + "@v", ranges));
		// an open end looks up no spelling
		assertEquals(Placement.Position.UNBOUNDED,
				place(purl + "@v1.5.0", List.of(range(purl, "v1.0.0", null), range(purl, null, "v2.1+"))).position());
	}

	@Test
	void aComparisonThatFailsIsNotTakenForAnUnreadableBound() {
		// npm's ordering reads 1.0+local but cannot compare it with 1.0.0: not a range left out, a package not placed
		AffectedRange broken = range("pkg:npm/a", "1.0.0", "1.0+local");
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE),
				place("pkg:npm/a@2.0.0", List.of(broken, range("pkg:npm/a", null, "3.0.0"))));
		// golang reads a 20-digit number but cannot compare it: left out, v1.0.5 would be OUTSIDE
		String go = "pkg:golang/example.com/a";
		List<AffectedRange> goRanges = List.of(range(go, "v1.0.0", "v1.0.99999999999999999999"), range(go, null, "v0.5.0"));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place(go + "@v1.0.5", goRanges));
		// nor a vers line that would leave the range out
		assertEquals(Optional.empty(), VersRanges.toVersString(go, goRanges, VULN));
		// past 2^31, not only past 19 digits
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE),
				place(go + "@v1.0.5", List.of(range(go, "v1.0.0", "v1.0.2147483648"), range(go, null, "v0.5.0"))));
	}

	@Test
	void aFailureIsLoggedAtErrorOncePerKeyUpToTheLimit() {
		VersRanges.forgetLoggedFailures();
		try {
			FailureKey key = new FailureKey("generic", "TEST-ONCE", "pkg:github/acme/tool");
			assertTrue(VersRanges.firstFailure(key));
			assertFalse(VersRanges.firstFailure(key));
			for (int i = 1; i < VersRanges.FAILURES_LOGGED_LIMIT; i++) {
				assertTrue(VersRanges.firstFailure(new FailureKey("generic", "TEST-" + i, "pkg:github/acme/tool")));
			}
			assertFalse(VersRanges.firstFailure(new FailureKey("generic", "TEST-PAST", "pkg:github/acme/tool")));
			assertFalse(VersRanges.firstFailure(key));
		} finally {
			VersRanges.forgetLoggedFailures();
		}
	}

	@Test
	void theBoundIsAlwaysAboveTheVersion() {
		List<AffectedRange> ranges = List.of(
				range("pkg:npm/a", null, "1.0.0"),
				range("pkg:npm/a", "1.0.0", "1.2.0"),
				range("pkg:npm/a", "1.5.0", "2.0.0"),
				range("pkg:npm/a", "3.0.0", null));
		for (String v : List.of("0.0.1", "0.9.9", "1.0.0", "1.1.9", "1.2.0", "1.5.0", "1.9.9", "2.0.0", "3.0.0", "9.0.0")) {
			Placement p = place("pkg:npm/a@" + v, ranges);
			if (p.position() != Placement.Position.ENDS_BEFORE) continue;
			assertEquals(Placement.Position.OUTSIDE, place("pkg:npm/a@" + p.bound(), ranges).position(),
					v + " -> " + p.bound() + " is itself affected");
			assertEquals(Placement.Position.ENDS_BEFORE, place("pkg:npm/a@" + v, List.of(range("pkg:npm/a", null, p.bound()))).position(),
					v + " -> " + p.bound() + " is not above " + v);
		}
		assertEquals("1.2.0", place("pkg:npm/a@0.0.1", ranges).bound());
		assertEquals("2.0.0", place("pkg:npm/a@1.5.0", ranges).bound());
		assertEquals(Placement.Position.OUTSIDE, place("pkg:npm/a@1.2.0", ranges).position());
		assertEquals(Placement.Position.UNBOUNDED, place("pkg:npm/a@3.0.0", ranges).position());
	}

	// ---- the generic scheme ----

	private static List<AffectedRange> tool(String startIncl, String endExcl) {
		return List.of(range("pkg:github/acme/tool", startIncl, endExcl));
	}

	@Test
	void aLeadingVIsNotReadAsTextUnderGeneric() {
		// as written, generic orders v45.0.7 above 46.0.1 and v9 above v10
		assertEquals(at(Placement.Position.ENDS_BEFORE, "46.0.1"), place("pkg:github/acme/tool@v45.0.7", tool(null, "46.0.1")));
		// the fix keeps the source's spelling
		assertEquals(at(Placement.Position.ENDS_BEFORE, "v46.0.1"), place("pkg:github/acme/tool@v45.0.7", tool(null, "v46.0.1")));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "v10.0"), place("pkg:github/acme/tool@v9.0", tool("v1.0", "v10.0")));
		assertEquals(Placement.Position.OUTSIDE, place("pkg:github/acme/tool@V46.0.1", tool(null, "46.0.1")).position());
		assertEquals(Optional.of("vers:generic/<46.0.1"), vers("pkg:github/acme/tool", range("pkg:github/acme/tool", null, "v46.0.1")));
	}

	@ParameterizedTest
	@CsvSource({"1.0.0-rc1, 1.0.0", "1.0.0-beta2, 1.0.0-beta10", "1.0.0-a1, 1.0.0", "1.0.0-SNAPSHOT, 1.0.0",
			"1.0.0-EAP, 1.0.0", "1.0.0-edge, 1.0.0", "1.0.0-develop, 1.0.0", "1.0.0-early-access, 1.0.0", "2.0.0-M.1, 2.0.0",
			"1.0.0-CR.1, 1.0.0", "1.0.0-x.7.z.92, 1.0.0", "1.0.0-0, 1.0.0", "1.0-r9, 1.0-r10", "2.0-1ubuntu1, 2.0-1ubuntu2",
			"1.5-alpha.10, 1.5.2", "2.4-1, 2.4.1", "1.2_20, 1.2.5", "1.8.0_202, 1.8.0_212", "3.19-alpine3.20, 3.19"})
	void whatFollowsTheNumbersDoesNotDecideUnderGeneric(String a, String b) {
		// generic reads it its own way: 1.0.0-rc1 above 1.0.0, 1.0.0-edge equal to it, 1.5-alpha.10 above 1.5.2,
		// and 1.2_20 (a build of 1.2) above 1.2.5
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@" + a, tool(null, b)));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@" + b, tool(null, a)));
	}

	@ParameterizedTest
	@CsvSource({"1.0.0-rc1, 2.0.0", "3.19-alpine3.20, 3.20", "1.8.0-b132, 1.8.1", "5.0.1-r3, 5.0.2", "1.5-4, 1.6-0",
			"1.0-alpha.10, 1.5.2", "1.0, 1.0.1-rc1", "12.2.0-devel-ubuntu22.04, 12.3", "2.0-1ubuntu1, 2.1", "1.25-bookworm, 1.26",
			"1.2-testing, 1.3", "2024-01-05, 2025-01-01"})
	void aNumberDecidesWhateverFollowsItUnderGeneric(String lower, String higher) {
		assertEquals(at(Placement.Position.ENDS_BEFORE, higher), place("pkg:github/acme/tool@" + lower, tool(null, higher)));
		assertEquals(Placement.Position.OUTSIDE, place("pkg:github/acme/tool@" + higher, tool(null, lower)).position());
	}

	@ParameterizedTest
	@CsvSource({"8u92, 8u212", "9.3p9, 9.3p10", "1.0.9b, 1.0.10b", "1.0rc1, 2.0", "3.12.0b1, 3.13", "1.0.RELEASE, 2.0",
			"2.0.M1, 3.0", "4f2a9c1, 5.0", "jdk8u92-b14, jdk8u212-b04", "latest, 3.0"})
	void aVersionGenericDoesNotReadAsNumbersIsNotPlaced(String version, String other) {
		// generic compares 8u92 as text, above 8u212; a commit or a name has no numbers to compare
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@" + version, tool(null, other)));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@" + other, tool(null, version)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"1.*", "1.x"})
	void aWildcardBoundIsNotReadAsItsNumbers(String wildcard) {
		// generic reads 1.* as 1
		AffectedRange throughSeries = range("pkg:github/acme/tool", null, null);
		throughSeries.setVersionEndIncluding(wildcard);
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@1.5", List.of(throughSeries)));
	}

	@Test
	void anEpochBuildMetadataOrUbuntuRevisionIsNotPlacedUnderGeneric() {
		// generic drops the Ubuntu revision, and reads an epoch or build metadata as one more part
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@2.0-1ubuntu1", tool(null, "2.0-1ubuntu2")));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@1%3A2.0", tool(null, "3.0")));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@2.4~1", tool(null, "2.4")));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@0.9", tool(null, "1:3.0")));
		AffectedRange throughRelease = range("pkg:github/acme/tool", null, null);
		throughRelease.setVersionEndIncluding("1.0.0");
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@1.0.0%2B5", List.of(throughRelease)));
	}

	@Test
	void versionsToldApartByTrailingZerosAloneAreNotPlaced() {
		// generic holds 4 below 4.0.0, and 1.0 below 1.0.0.0
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@v4", tool("4.0.0", "4.1.2")));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@1.0", tool(null, "1.0.0.0")));
		// two bounds are compared too
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@5.0",
				List.of(range("pkg:github/acme/tool", "1", "2.0"), range("pkg:github/acme/tool", "1.0.0", "3.0"))));
		// each bound one zero from the version, so held equal to it, but two zeros from the other
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@1.0.0", tool("1.0", "1.0.0.0")));
		// one trailing zero apart, generic holds them equal
		assertEquals(Placement.Position.OUTSIDE, place("pkg:github/acme/tool@1.0", tool(null, "1.0.0")).position());
	}

	@Test
	void aGenericNumberOfTenDigitsIsNotPlaced() {
		// generic compares a number past 2^31 as text: 20260928123456 below 3
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@20260928123456", tool(null, "3")));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place("pkg:github/acme/tool@1.3", tool(null, "1.2147483648")));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "987654321"), place("pkg:github/acme/tool@123456789", tool(null, "987654321")));
		// maven orders long numbers itself
		assertEquals(Placement.Position.OUTSIDE,
				place("pkg:maven/g/a@20260928123456", List.of(range("pkg:maven/g/a", null, "3"))).position());
	}

	@Test
	void numbersAreComparedAsNumbersUnderGeneric() {
		// 01.2 is 1.2
		assertEquals(Placement.Position.OUTSIDE, place("pkg:github/acme/tool@01.2", tool(null, "1.2")).position());
		// a version is not compared with itself: an exact pre-release ends at itself
		assertEquals(at(Placement.Position.ENDS_AT, "1.0.0-rc1"),
				place("pkg:github/acme/tool@1.0.0-rc1", List.of(exact("pkg:github/acme/tool", "1.0.0-rc1"))));
	}

	@Test
	void boundsGenericMisordersHaveNoVersLine() {
		// generic would put 1.0.0-beta2 above 1.0.0-beta10, and compare 2147483648 as text
		assertEquals(Optional.empty(), vers("pkg:github/acme/tool", range("pkg:github/acme/tool", "1.0.0-beta2", "1.0.0-beta10")));
		assertEquals(Optional.empty(), vers("pkg:github/acme/tool", range("pkg:github/acme/tool", "1.0", "1.2147483648")));
		assertEquals(Optional.empty(), vers("pkg:github/acme/tool", range("pkg:github/acme/tool", null, "vnext")));
		// where the numbers decide, or nothing is compared, the line stands
		assertEquals(Optional.of("vers:generic/>=1.0.0-rc1|<2.0.0"),
				vers("pkg:github/acme/tool", range("pkg:github/acme/tool", "1.0.0-rc1", "2.0.0")));
		assertEquals(Optional.of("vers:generic/*"), vers("pkg:github/acme/tool", range("pkg:github/acme/tool", null, null)));
	}

	@Test
	void aVersionGenericMisordersIsPlacedWhereNothingIsCompared() {
		// every version affected: the version is in, whatever its order
		assertEquals(Placement.Position.UNBOUNDED, place("pkg:github/acme/tool@v1.0.0-beta.2", tool(null, null)).position());
		assertEquals(Placement.Position.UNBOUNDED, place("pkg:github/acme/tool@8u92", tool(null, null)).position());
		assertEquals(Placement.Position.UNBOUNDED, place("pkg:github/acme/tool@20260928123456", tool(null, null)).position());
	}

	@Test
	void aBoundOfAnUnreadableRangeIsNotCompared() {
		// counted, 1.0-rc1 would leave 1.0 unplaced
		AffectedRange unknown = range("pkg:github/acme/tool", null, "1.0-rc1");
		unknown.setRangeType(null);
		assertEquals(at(Placement.Position.ENDS_BEFORE, "3.0"),
				place("pkg:github/acme/tool@1.0", List.of(unknown, range("pkg:github/acme/tool", null, "3.0"))));
	}

	@Test
	void theFixIsSpelledAsItsOwnRangeEndWroteIt() {
		// the start v1.0 of another range is the same version, spelled otherwise
		AffectedRange later = range("pkg:github/acme/tool", null, "2.0");
		later.setVersionStartExcluding("v1.0");
		List<AffectedRange> ranges = List.of(later, range("pkg:github/acme/tool", "0.5", "1.0"));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "1.0"), place("pkg:github/acme/tool@0.7", ranges));
		List<AffectedRange> exact = List.of(exact("pkg:github/acme/tool", "v1.0"));
		assertEquals(at(Placement.Position.ENDS_AT, "v1.0"), place("pkg:github/acme/tool@1.0", exact));
		assertEquals(at(Placement.Position.ENDS_AT, "v1.0"), place("pkg:github/acme/tool@v1.0", exact));
	}

	@Test
	void aOneVersionRangeIsSpelledAsItsEnd() {
		AffectedRange startV = range("pkg:github/acme/tool", "v1.0.0", null);
		startV.setVersionEndIncluding("1.0");
		assertEquals(at(Placement.Position.ENDS_AT, "1.0"), place("pkg:github/acme/tool@1.0", List.of(startV)));
		AffectedRange endV = range("pkg:github/acme/tool", "1.0.0", null);
		endV.setVersionEndIncluding("v1.0");
		assertEquals(at(Placement.Position.ENDS_AT, "v1.0"), place("pkg:github/acme/tool@1.0", List.of(endV)));
	}

	@Test
	void theVersLineDropsTheVOnEveryBound() {
		assertEquals(Optional.of("vers:generic/>=1.0|<2.0"),
				vers("pkg:github/acme/tool", range("pkg:github/acme/tool", "v1.0", "V2.0")));
	}

	@Test
	void knownSchemesKeepTheirOwnOrdering() {
		// no v stripped, nothing refused: these schemes order both themselves
		assertEquals(at(Placement.Position.ENDS_BEFORE, "1.0.0"), place("pkg:npm/a@1.0.0-rc.1", List.of(range("pkg:npm/a", null, "1.0.0"))));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "1.0-beta10"),
				place("pkg:maven/g/a@1.0-beta2", List.of(range("pkg:maven/g/a", null, "1.0-beta10"))));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "1.0"), place("pkg:pypi/a@1.0rc1", List.of(range("pkg:pypi/a", null, "1.0"))));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "v1.3.0"),
				place("pkg:golang/example.com/a@v1.2.3", List.of(range("pkg:golang/example.com/a", null, "v1.3.0"))));
	}

	// ---- ordering ----

	@Test
	void versionsAreOrderedByTheirSchemeOneSpellingEach() {
		assertEquals(Optional.of(List.of("1.2.0", "1.9.0", "1.10.0")), VersRanges.ascending("pkg:npm/a@1.0.0", List.of("1.10.0", "1.2.0", "1.9.0")));
		assertEquals(Optional.of(List.of("1.0", "2.0-beta1", "2.0")), VersRanges.ascending("pkg:maven/g/a@1", List.of("2.0", "2.0-beta1", "1.0")));
		// an epoch outranks the rest
		assertEquals(Optional.of(List.of("2.0-1", "1:1.0-1")), VersRanges.ascending("pkg:deb/debian/a@1", List.of("1:1.0-1", "2.0-1")));
		assertEquals(Optional.of(List.of("9.0", "10.0")), VersRanges.ascending("pkg:github/acme/tool@1", List.of("10.0", "9.0")));
		// composer holds 3.0 and 3.0.0 the same version: the first spelling given
		assertEquals(Optional.of(List.of("2.0", "3.0")), VersRanges.ascending("pkg:composer/a/b@1", List.of("3.0", "3.0.0", "2.0")));
	}

	@Test
	void versionsThatCannotBeOrderedHaveNoOrder() {
		// generic tells these apart by what follows the numbers
		assertEquals(Optional.empty(), VersRanges.ascending("pkg:github/acme/tool@1", List.of("1.0.0-rc1", "1.0.0")));
		assertEquals(Optional.empty(), VersRanges.ascending("pkg:npm/a@1.0.0", List.of("1.0.0", "latest")));
		// golang reads a 20-digit number but cannot compare it
		assertEquals(Optional.empty(), VersRanges.ascending("pkg:golang/example.com/a@v1", List.of("v1.0.99999999999999999999", "v1.0.1")));
		assertEquals(Optional.empty(), VersRanges.ascending("not a purl", List.of("1.0")));
		assertEquals(Optional.empty(), VersRanges.ascending("pkg:npm/a@1.0.0", Arrays.asList("1.0.0", null)));
	}

	@Test
	void boundsAreReadableWhenEveryRangeHasATypeAndVersions() {
		assertTrue(VersRanges.boundsReadable("pkg:npm/a@1.0.0", List.of(range("pkg:npm/a", "1.0.0", "2.0.0"))));
		assertFalse(VersRanges.boundsReadable("pkg:npm/a@1.0.0", List.of(range("pkg:npm/a", null, "not a version"))));
		AffectedRange untyped = range("pkg:npm/a", null, "2.0.0");
		untyped.setRangeType(null);
		assertFalse(VersRanges.boundsReadable("pkg:npm/a@1.0.0", List.of(untyped)));
		assertFalse(VersRanges.boundsReadable("not a purl", List.of()));
	}

	@Test
	void theMajorVersionIsTheFirstNumber() {
		assertEquals("1", VersRanges.majorOf("v1.2.3"));
		assertEquals("9", VersRanges.majorOf("1:9.2p1-2+deb12u2"));
		assertEquals("0", VersRanges.majorOf("0.9"));
		assertEquals("7", VersRanges.majorOf("007.1"));
		assertEquals("20260928", VersRanges.majorOf("20260928-1"));
		assertNull(VersRanges.majorOf("latest"));
		assertNull(VersRanges.majorOf("r09"));
		assertNull(VersRanges.majorOf(null));
	}

	// ---- spelling ----

	@ParameterizedTest
	@CsvSource({"composer, 3.0.0", "nuget, 1.0", "pypi, 1.0-rc1"})
	void aFixIsSpelledAsTheSourceWroteItWhereVersatileRespellsIt(String type, String fix) {
		// versatile writes these 3.0.0.0, 1.0.0 and 1.0rc1
		String purl = "pkg:" + type + "/a";
		assertEquals(at(Placement.Position.ENDS_BEFORE, fix), place(purl + "@0.9", List.of(range(purl, null, fix))));
		AffectedRange inclusive = range(purl, null, null);
		inclusive.setVersionEndIncluding(fix);
		assertEquals(at(Placement.Position.ENDS_AT, fix), place(purl + "@0.9", List.of(inclusive)));
		assertEquals(at(Placement.Position.ENDS_AT, fix), place(purl + "@" + fix, List.of(exact(purl, fix))));
	}

	@Test
	void anInclusiveEndIsSpelledFromAnInclusiveField() {
		// both are 3.0.0.0 to composer; the union's end is the inclusive one
		AffectedRange inclusive = range("pkg:composer/a", null, null);
		inclusive.setVersionEndIncluding("3.0");
		List<AffectedRange> ranges = List.of(range("pkg:composer/a", null, "3.0.0"), inclusive);
		assertEquals(at(Placement.Position.ENDS_AT, "3.0"), place("pkg:composer/a@2.0", ranges));
	}

	@Test
	void aFixIsNeverSpelledAsAStartBound() {
		// 3.0 starts the later range and is 3.0.0.0 to composer, as is the fix 3.0.0
		AffectedRange later = range("pkg:composer/a", null, "5.0");
		later.setVersionStartExcluding("3.0");
		List<AffectedRange> ranges = List.of(later, range("pkg:composer/a", "1.0", "3.0.0"));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "3.0.0"), place("pkg:composer/a@2.0", ranges));
	}

	// ---- length ----

	@ParameterizedTest
	@ValueSource(strings = {"pypi", "composer", "gem", "generic", "npm"})
	void aVersionTooLongToReadIsNotPlaced(String type) {
		// pypi, composer and gem parse recursively and overflow the stack on a few thousand characters
		String purl = "pkg:" + type + "/a";
		String dotted = "1" + ".1".repeat(20_000);
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE), place(purl + "@" + dotted, List.of(range(purl, null, "2.0.0"))));
		// a bound that long leaves out only its own range
		List<AffectedRange> ranges = List.of(range(purl, null, dotted), range(purl, null, "3.0.0"));
		assertEquals(at(Placement.Position.ENDS_BEFORE, "3.0.0"), place(purl + "@1.0.0", ranges));
		assertEquals(VersRanges.toVersString(purl, List.of(range(purl, null, "3.0.0")), VULN),
				VersRanges.toVersString(purl, ranges, VULN));
	}

	@Test
	void aBoundTooLongToReadIsNotCompared() {
		String dotted = "1" + ".1".repeat(20_000);
		assertEquals(at(Placement.Position.ENDS_BEFORE, "3.0"),
				place("pkg:github/acme/tool@1.0", List.of(range("pkg:github/acme/tool", null, dotted + "b1"),
						range("pkg:github/acme/tool", null, "3.0"))));
	}

	@Test
	void aVersionAtTheLengthLimitIsRead() {
		String longest = "1" + ".1".repeat(127) + "0";
		assertEquals(VersRanges.MAX_VERSION_LENGTH, longest.length());
		assertEquals(at(Placement.Position.ENDS_BEFORE, "2.0"), place("pkg:pypi/a@" + longest, List.of(range("pkg:pypi/a", null, "2.0"))));
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE),
				place("pkg:pypi/a@" + longest + "1", List.of(range("pkg:pypi/a", null, "2.0"))));
		// the epoch qualifier counts
		assertEquals(Placement.of(Placement.Position.UNPLACEABLE),
				place("pkg:rpm/a@" + longest + "?epoch=1", List.of(range("pkg:rpm/a", null, "2.0"))));
	}
}
