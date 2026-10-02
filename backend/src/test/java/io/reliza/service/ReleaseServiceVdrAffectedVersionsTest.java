/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.model.vulnerability.Vulnerability.Version.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.common.Utils;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.service.IntegrationService.DtrackAffectedComponentRaw;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * The VDR's {@code affects[].versions[]} for a finding's package, from the
 * live Dependency-Track 4 and 5 responses captured for F1, bound and
 * normalized the way the drain stores them.
 */
class ReleaseServiceVdrAffectedVersionsTest {

	private static final String DT4_SHAPES = "/dtrack/affected-components-live-shapes.json";
	private static final String DT5_SHAPES = "/dtrack/affected-components-live-shapes-dt5.json";

	private static List<AffectedRange> rangesOf(String resource, String key) throws Exception {
		JsonNode vuln;
		try (InputStream in = ReleaseServiceVdrAffectedVersionsTest.class.getResourceAsStream(resource)) {
			vuln = Utils.OM.readTree(in).get(key);
		}
		List<DtrackAffectedComponentRaw> raw = Utils.OM.convertValue(vuln.get("affectedComponents"),
				new TypeReference<List<DtrackAffectedComponentRaw>>() {});
		return AffectedRange.normalize(IntegrationService.toAffectedRanges(raw, vuln.get("source").asString()));
	}

	/** Each entry as "status range-or-version", in order. */
	private static List<String> versions(String resource, String key, String findingPurl) throws Exception {
		return ReleaseService.affectedVersions(findingPurl, rangesOf(resource, key), key).stream()
				.map(v -> v.getStatus().name() + " " + (null != v.getRange() ? v.getRange() : v.getVersion()))
				.toList();
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void anNpmFindingGetsItsPackagesRangeAndTheFixingVersion(String shapes) throws Exception {
		assertEquals(List.of("AFFECTED vers:npm/<4.7.7", "UNAFFECTED 4.7.7"),
				versions(shapes, "GITHUB/GHSA-f2jv-r9rf-7988", "pkg:npm/handlebars@4.0.5"),
				"only the npm identity: the webjars mirrors are other packages");
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void aDebianPackageWithNoFixYetIsAffectedInEveryVersion(String shapes) throws Exception {
		assertEquals(List.of("AFFECTED vers:deb/*"), versions(shapes, "OSV/DEBIAN-CVE-2015-3276",
				"pkg:deb/debian/openldap@2.5.13%2Bdfsg-5?arch=amd64&distro=debian-12"),
				"identity-only row: no fixed version to name as unaffected");
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void aDebianFindingGetsItsOwnReleasesRangeOnly(String shapes) throws Exception {
		String key = "OSV/DEBIAN-CVE-2023-45853";
		assertEquals(List.of("AFFECTED vers:deb/<1:1.3.dfsg-2", "UNAFFECTED 1:1.3.dfsg-2"),
				versions(shapes, key, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64&distro=debian-13"));
		assertEquals(List.of("AFFECTED vers:deb/*"),
				versions(shapes, key, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64&distro=debian-12"));
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void aDebianFindingNamingNoReleaseGetsEveryReleasesRangeButNoFix(String shapes) throws Exception {
		// trixie and forky give the same line, written once; the releases disagree on the fix
		assertEquals(List.of("AFFECTED vers:deb/*", "AFFECTED vers:deb/<1:1.3.dfsg-2"),
				versions(shapes, "OSV/DEBIAN-CVE-2023-45853", "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?arch=amd64"));
	}

	@ParameterizedTest
	@ValueSource(strings = {DT4_SHAPES, DT5_SHAPES})
	void aFindingOutsideTheRangeStillCarriesTheRangeWithoutAFix(String shapes) throws Exception {
		List<String> got = versions(shapes, "OSV/PYSEC-2018-5", "pkg:pypi/django@1.8.19");
		assertEquals(1, got.size());
		assertTrue(got.get(0).startsWith("AFFECTED vers:pypi/"), got.get(0));
	}

	@Test
	void noRangeForThePackageLeavesTheAffectBare() throws Exception {
		assertEquals(List.of(), ReleaseService.affectedVersions("pkg:npm/handlebars@4.0.5", null, "GHSA-x"));
		assertEquals(List.of(), ReleaseService.affectedVersions("pkg:npm/handlebars@4.0.5", List.of(), "GHSA-x"));
		assertEquals(List.of(), versions(DT5_SHAPES, "OSV/DEBIAN-CVE-2015-3276",
				"pkg:deb/debian/libldap-2.5-0@2.5.13%2Bdfsg-5?arch=amd64&distro=debian-12"),
				"a binary package the advisory does not name");
		assertEquals(List.of(), ReleaseService.affectedVersions(null,
				rangesOf(DT5_SHAPES, "GITHUB/GHSA-f2jv-r9rf-7988"), "GHSA-f2jv-r9rf-7988"));
	}

	@Test
	void theStatusesAreTheLibrarysOwn() throws Exception {
		List<Vulnerability.Version> v = ReleaseService.affectedVersions("pkg:npm/handlebars@4.0.5",
				rangesOf(DT5_SHAPES, "GITHUB/GHSA-f2jv-r9rf-7988"), "GHSA-f2jv-r9rf-7988");
		assertEquals(Status.AFFECTED, v.get(0).getStatus());
		assertEquals(Status.UNAFFECTED, v.get(1).getStatus());
	}
}
