/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.dto.FixedIn;
import io.reliza.dto.FixedIn.FixedInVerdict;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.AffectedRangeType;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.service.IntegrationService.DtrackAffectedComponentRaw;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Resolves findings against the live Dependency-Track responses captured for
 * F1, bound and normalized the way the drain stores them, on Dependency-Track
 * 4 and 5 alike.
 */
class FixedInResolverTest {

	private static final String DT4_SHAPES = "/dtrack/affected-components-live-shapes.json";
	private static final String DT5_SHAPES = "/dtrack/affected-components-live-shapes-dt5.json";

	private static List<AffectedRange> rangesOf(String resource, String key) throws Exception {
		JsonNode vuln;
		try (InputStream in = FixedInResolverTest.class.getResourceAsStream(resource)) {
			vuln = Utils.OM.readTree(in).get(key);
		}
		List<DtrackAffectedComponentRaw> raw = Utils.OM.convertValue(vuln.get("affectedComponents"),
				new TypeReference<List<DtrackAffectedComponentRaw>>() {});
		return AffectedRange.normalize(IntegrationService.toAffectedRanges(raw, vuln.get("source").asString()));
	}

	private static FixedIn resolve(String resource, String key, String findingPurl) throws Exception {
		return FixedInResolver.resolve(findingPurl, rangesOf(resource, key), key);
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void handlebarsIsFixedIn477(String shapes) throws Exception {
		FixedIn f = resolve(shapes, "GITHUB/GHSA-f2jv-r9rf-7988", "pkg:npm/handlebars@4.0.5");
		assertEquals(FixedInVerdict.FIXED_IN, f.verdict());
		assertEquals("4.7.7", f.version());
		assertNull(f.versionEndIncluding());
		// Dependency-Track 4 attributes the GHSA row to GitHub and OSV; the DT 5
		// instance mirrors GHSA through OSV and files it under GitHub only
		assertTrue(f.sources().contains(UpstreamSource.GITHUB));
		// the webjars mirrors are other packages, with ranges of their own
		assertEquals("4.7.7",
				resolve(shapes, "GITHUB/GHSA-f2jv-r9rf-7988", "pkg:maven/org.webjars.npm/handlebars@4.1.2").version());
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void djangoIsFixedAtTheEndOfItsOwnSeries(String shapes) throws Exception {
		FixedIn f = resolve(shapes, "OSV/PYSEC-2018-5", "pkg:pypi/django@1.11");
		assertEquals(FixedInVerdict.FIXED_IN, f.verdict());
		assertEquals("1.11.11", f.version());
		assertEquals(List.of(UpstreamSource.OSV), f.sources());
		assertEquals(FixedInVerdict.NOT_IN_ADVISORY_RANGE,
				resolve(shapes, "OSV/PYSEC-2018-5", "pkg:pypi/django@1.8.19").verdict());
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void log4jCoreIsFixedPerSeries(String shapes) throws Exception {
		String key = "GITHUB/GHSA-jfh8-c2jp-5v3q";
		assertEquals("2.15.0", resolve(shapes, key, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1").version());
		assertEquals("2.12.2", resolve(shapes, key, "pkg:maven/org.apache.logging.log4j/log4j-core@2.12.1").version());
		assertEquals("2.3.1", resolve(shapes, key, "pkg:maven/org.apache.logging.log4j/log4j-core@2.0-beta9").version());
		assertEquals(FixedInVerdict.NOT_IN_ADVISORY_RANGE,
				resolve(shapes, key, "pkg:maven/org.apache.logging.log4j/log4j-core@2.12.2").verdict());
		assertEquals(FixedInVerdict.NOT_IN_ADVISORY_RANGE,
				resolve(shapes, key, "pkg:maven/org.apache.logging.log4j/log4j-core@2.15.0").verdict());
		FixedIn guicedee = resolve(shapes, key, "pkg:maven/com.guicedee.services/log4j-core@1.2.1.2-jre17");
		assertEquals(FixedInVerdict.FIXED_AFTER, guicedee.verdict());
		assertEquals("1.2.1.2-jre17", guicedee.versionEndIncluding());
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void anUnfixedDebianPackageHasNoFix(String shapes) throws Exception {
		// The finding's qualifiers (binary arch, distro spelling) never equal the
		// advisory's; the package is what matches.
		assertEquals(FixedInVerdict.NO_FIX_AVAILABLE, resolve(shapes, "OSV/DEBIAN-CVE-2015-3276",
				"pkg:deb/debian/openldap@2.5.13%2Bdfsg-5?arch=amd64&distro=debian-12").verdict());
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void aDebianBinaryPackageIsNotItsSourcePackage(String shapes) throws Exception {
		// Debian advisories name source packages; a binary package's name differs
		assertEquals(FixedIn.of(FixedInVerdict.NO_RANGE_DATA), resolve(shapes, "OSV/DEBIAN-CVE-2015-3276",
				"pkg:deb/debian/libldap-2.5-0@2.5.13%2Bdfsg-5?arch=amd64&distro=debian-12"));
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void aDebianFindingReadsItsOwnReleasesRanges(String shapes) throws Exception {
		// bookworm has no fix; trixie and forky are fixed in 1:1.3.dfsg-2
		String key = "OSV/DEBIAN-CVE-2023-45853";
		assertEquals(FixedInVerdict.NO_FIX_AVAILABLE,
				resolve(shapes, key, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64&distro=debian-12").verdict());
		assertEquals("1:1.3.dfsg-2",
				resolve(shapes, key, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64&distro=debian-13").version());
		// no release named, and the releases disagree
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE),
				resolve(shapes, key, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64"));
		// minizip has bookworm rows only: a trixie package is not sent to bookworm's fix
		assertEquals("1.1-8+deb12u1",
				resolve(shapes, key, "pkg:deb/debian/minizip@1.1-8?arch=amd64&distro=debian-12").version());
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE),
				resolve(shapes, key, "pkg:deb/debian/minizip@1.1-8?arch=amd64&distro=debian-13"));
	}

	private static AffectedRange debianRow(String release, String endExcluding) {
		AffectedRange r = new AffectedRange();
		r.setIdentityType(AffectedIdentityType.PURL);
		r.setIdentity("pkg:deb/debian/openssl?arch=source&distro=" + release);
		r.setRangeType(AffectedRangeType.RANGE);
		r.setVersionEndExcluding(endExcluding);
		r.setSources(List.of(UpstreamSource.OSV));
		return r;
	}

	@Test
	void aBookwormPackageIsNotSentToAnotherReleasesFix() {
		// DEBIAN-CVE-2026-63072 as the Dependency-Track 5 sandbox holds it. Read
		// together the rows end at forky's 3.6.4-1, which bookworm never ships.
		List<AffectedRange> openssl = AffectedRange.normalize(List.of(
				debianRow("bookworm", "3.0.22-1~deb12u1"),
				debianRow("trixie", "3.5.7-1~deb13u2"),
				debianRow("forky", "3.6.4-1")));
		String vuln = "DEBIAN-CVE-2026-63072";
		assertEquals(new FixedIn("3.0.22-1~deb12u1", FixedInVerdict.FIXED_IN, null, List.of(UpstreamSource.OSV),
				List.of(openssl.getFirst().getIdentity())),
				FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=debian-12.15", openssl, vuln));
		assertEquals("3.0.22-1~deb12u1",
				FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=bookworm", openssl, vuln).version());
		assertEquals("3.5.7-1~deb13u2",
				FixedInResolver.resolve("pkg:deb/debian/openssl@3.5.4-1~deb13u1?distro=debian-13", openssl, vuln).version());
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE),
				FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2", openssl, vuln));
		// bullseye is not in the advisory
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE),
				FixedInResolver.resolve("pkg:deb/debian/openssl@1.1.1w-0+deb11u1?distro=debian-11", openssl, vuln));
	}

	@Test
	void theIdentitiesAreTheRowsTheVerdictCameFrom() {
		List<AffectedRange> openssl = AffectedRange.normalize(List.of(
				debianRow("bookworm", "3.0.22-1~deb12u1"),
				debianRow("trixie", "3.5.7-1~deb13u2"),
				debianRow("forky", "3.6.4-1")));
		String bookworm = openssl.stream().filter(r -> r.getIdentity().endsWith("bookworm")).findFirst().orElseThrow().getIdentity();
		// the finding's own release only
		assertEquals(List.of(bookworm),
				FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=debian-12", openssl, "X").identities());
		// every release, when the finding names none and they agree
		assertEquals(3, FixedInResolver.resolve("pkg:deb/debian/openssl@9.9.9-1", openssl, "X").identities().size());
		// nothing to point at without a verdict
		assertEquals(List.of(), FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=debian-11", openssl, "X").identities());
		assertEquals(List.of(), FixedInResolver.resolve("pkg:npm/a@1.0.0", openssl, "X").identities());
	}

	@Test
	void rowsNamingNoReleaseCountForEveryRelease() {
		AffectedRange general = debianRow("x", "3.0.21-1");
		general.setIdentity("pkg:deb/debian/openssl?arch=source");
		List<AffectedRange> ranges = List.of(general, debianRow("bookworm", "3.0.22-1~deb12u1"),
				debianRow("trixie", "3.5.7-1~deb13u2"));
		assertEquals("3.0.22-1~deb12u1",
				FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1?distro=debian-12", ranges, "X").version());
	}

	@Test
	void rowsNamingNoReleaseJoinEachReleaseWhenTheFindingNamesNone() {
		// with the general row, bookworm and trixie both end at 3.0.22-1
		AffectedRange general = debianRow("x", "3.0.21-1");
		general.setIdentity("pkg:deb/debian/openssl?arch=source");
		List<AffectedRange> ranges = List.of(general, debianRow("bookworm", "3.0.22-1"), debianRow("trixie", "3.0.22-1"));
		assertEquals("3.0.22-1", FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1", ranges, "X").version());
	}

	@Test
	void anUnlistedReleaseGetsNoAnswerEvenWithRowsNamingNone() {
		// the general row alone would say 3.0.21-1, but bookworm and trixie say
		// otherwise for their own releases, so a bullseye package gets none
		AffectedRange general = debianRow("x", "3.0.21-1");
		general.setIdentity("pkg:deb/debian/openssl?arch=source");
		List<AffectedRange> ranges = List.of(general, debianRow("bookworm", "3.0.22-1"), debianRow("trixie", "3.0.22-1"));
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE),
				FixedInResolver.resolve("pkg:deb/debian/openssl@1.1.1w-0?distro=debian-11", ranges, "X"));
	}

	@Test
	void aDebEpochQualifierIsPartOfTheVersion() {
		List<AffectedRange> zlib = List.of(debianRow("bookworm", "1:1.2.13.dfsg-1+deb12u1"));
		zlib.forEach(r -> r.setIdentity("pkg:deb/debian/zlib?arch=source&distro=bookworm"));
		assertEquals("1:1.2.13.dfsg-1+deb12u1",
				FixedInResolver.resolve("pkg:deb/debian/zlib@1.2.13.dfsg-1?epoch=1&distro=debian-12", zlib, "X").version());
		// without the qualifier the version reads as epoch 0, below every epoch-1 version
		assertEquals(FixedInVerdict.FIXED_IN,
				FixedInResolver.resolve("pkg:deb/debian/zlib@1.2.14?distro=debian-12", zlib, "X").verdict());
		assertEquals(FixedInVerdict.NOT_IN_ADVISORY_RANGE,
				FixedInResolver.resolve("pkg:deb/debian/zlib@1.2.14?epoch=1&distro=debian-12", zlib, "X").verdict());
	}

	@Test
	void releasesAgreeingIsAnAnswerWithoutARelease() {
		List<AffectedRange> ranges = List.of(debianRow("bookworm", "3.0.22-1"), debianRow("trixie", "3.0.22-1"));
		assertEquals("3.0.22-1", FixedInResolver.resolve("pkg:deb/debian/openssl@3.0.20-1", ranges, "X").version());
	}

	@Test
	void theReleaseOfAPurl() throws Exception {
		assertEquals("bookworm", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=debian-12")));
		assertEquals("bookworm", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=debian-12.15")));
		assertEquals("bookworm", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=12")));
		assertEquals("bookworm", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a?arch=source&distro=Bookworm")));
		assertEquals("bookworm", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=debian-bookworm")));
		assertEquals("sid", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=debian-sid")));
		assertNull(FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=debian-")));
		// a release this code does not know yet stays a number, and matches no codename
		assertEquals("16", FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?distro=debian-16")));
		// release numbers are mapped for Debian only
		assertEquals("ubuntu-22.04", FixedInResolver.releaseOf(new PackageURL("pkg:deb/ubuntu/a@1?distro=ubuntu-22.04")));
		assertNull(FixedInResolver.releaseOf(new PackageURL("pkg:deb/debian/a@1?arch=amd64")));
		assertNull(FixedInResolver.releaseOf(new PackageURL("pkg:npm/a@1")));
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void cpeIdentitiesNeverMatchAPurl(String shapes) throws Exception {
		assertEquals(FixedIn.of(FixedInVerdict.NO_RANGE_DATA), resolve(shapes, "NVD/CVE-2021-44228",
				"pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"));
	}

	/** The verdict and versions, not the sources: those follow each instance's mirror settings. */
	private static String answer(FixedIn f) {
		return f.verdict() + " " + f.version() + " " + f.versionEndIncluding();
	}

	@Test
	void dependencyTrack4And5GiveTheSameVerdicts() throws Exception {
		String[][] probes = {
				{"GITHUB/GHSA-f2jv-r9rf-7988", "pkg:npm/handlebars@4.0.5"},
				{"GITHUB/GHSA-f2jv-r9rf-7988", "pkg:npm/handlebars@4.7.7"},
				{"GITHUB/GHSA-jfh8-c2jp-5v3q", "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"},
				{"GITHUB/GHSA-jfh8-c2jp-5v3q", "pkg:maven/org.xbib.elasticsearch/log4j@6.3.2.1"},
				{"OSV/PYSEC-2018-5", "pkg:pypi/django@2.0.1"},
				{"OSV/DEBIAN-CVE-2015-3276", "pkg:deb/debian/openldap@2.4.47"},
				{"OSV/DEBIAN-CVE-2023-45853", "pkg:deb/debian/zlib@1:1.3.dfsg-3"},
		};
		for (String[] p : probes) {
			assertEquals(answer(resolve(DT4_SHAPES, p[0], p[1])), answer(resolve(DT5_SHAPES, p[0], p[1])), p[0] + " " + p[1]);
		}
	}

	@Test
	void theFixItNamesIsNotAffected() throws Exception {
		String key = "GITHUB/GHSA-jfh8-c2jp-5v3q";
		for (String v : List.of("2.0-beta9", "2.0", "2.3", "2.4", "2.11.9", "2.12.1", "2.13.0", "2.14.1", "2.15.0-rc1")) {
			String purl = "pkg:maven/org.apache.logging.log4j/log4j-core@" + v;
			FixedIn f = resolve(DT5_SHAPES, key, purl);
			assertEquals(FixedInVerdict.FIXED_IN, f.verdict(), v);
			assertNotEquals(v, f.version());
			assertEquals(FixedInVerdict.NOT_IN_ADVISORY_RANGE,
					resolve(DT5_SHAPES, key, "pkg:maven/org.apache.logging.log4j/log4j-core@" + f.version()).verdict(),
					v + " -> " + f.version());
		}
	}

	@Test
	void noRangesOrNoPurlIsNoRangeData() {
		assertEquals(FixedIn.of(FixedInVerdict.NO_RANGE_DATA), FixedInResolver.resolve("pkg:npm/a@1.0.0", null, "X"));
		assertEquals(FixedIn.of(FixedInVerdict.NO_RANGE_DATA), FixedInResolver.resolve("pkg:npm/a@1.0.0", List.of(), "X"));
		AffectedRange r = new AffectedRange();
		r.setIdentity("pkg:npm/a");
		assertEquals(FixedIn.of(FixedInVerdict.NO_RANGE_DATA), FixedInResolver.resolve(null, List.of(r), "X"));
		assertEquals(FixedIn.of(FixedInVerdict.NO_RANGE_DATA), FixedInResolver.resolve("not a purl", List.of(r), "X"));
	}

	@Test
	void aFindingWithoutAParseableVersionIsUncomparable() throws Exception {
		String key = "GITHUB/GHSA-f2jv-r9rf-7988";
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE), resolve(DT5_SHAPES, key, "pkg:npm/handlebars"));
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE), resolve(DT5_SHAPES, key, "pkg:npm/handlebars@latest"));
		// too long to read: pypi's ordering would overflow the stack on it
		assertEquals(FixedIn.of(FixedInVerdict.UNCOMPARABLE),
				resolve(DT5_SHAPES, "OSV/PYSEC-2018-5", "pkg:pypi/django@1" + ".1".repeat(20_000)));
	}
}
