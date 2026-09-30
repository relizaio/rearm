/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.dto.FindingSbomMatch;
import io.reliza.dto.FindingSbomMatch.FindingSbomMissReason;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.service.FindingPurlBridge.ComponentIndex;
import io.reliza.service.FindingPurlBridge.InventoryPresence;
import io.reliza.service.FindingPurlBridge.InventoryState;
import io.reliza.service.FindingPurlBridge.ReleaseInventory;
import io.reliza.service.SbomComponentService.ReleaseComponentPurls;

/**
 * Finding purl to release SBOM component, per miss class. The purls are the
 * spellings seen on the sandbox (2026-09-29): stored canonicals keep Debian
 * {@code +} and epoch {@code :} raw, Dependency-Track re-encodes them
 * {@code %2B} / {@code %3A} (and the other way round for rebom rows of an
 * older era), and a scoped npm namespace arrives as {@code @} or {@code %40}.
 * Byte equality with {@code Utils.canonicalizePurl} matched only 44-59% of
 * live finding rows per org; the bridge matched all of them.
 */
class FindingPurlBridgeTest {

	private static final UUID GLIBC = uuid(1);
	private static final UUID ZLIB = uuid(2);
	private static final UUID IMAGEMAGICK = uuid(3);
	private static final UUID BABEL = uuid(4);
	private static final UUID LOG4J = uuid(5);
	private static final UUID TAR_12 = uuid(6);
	private static final UUID TAR_12_15 = uuid(7);
	private static final UUID CPE_ONLY = uuid(8);

	private static final Map<UUID, String> CANONICALS = new LinkedHashMap<>();
	static {
		CANONICALS.put(GLIBC, "pkg:deb/debian/glibc@2.36-9+deb12u14?distro=debian-12");
		CANONICALS.put(ZLIB, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12");
		// an older-era row: %2B next to a raw epoch colon
		CANONICALS.put(IMAGEMAGICK, "pkg:deb/debian/imagemagick@8:6.9.11.60%2Bdfsg-1.6%2Bdeb12u13?distro=debian-12.15");
		CANONICALS.put(BABEL, "pkg:npm/%40babel/traverse@7.22.0");
		CANONICALS.put(LOG4J, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1");
		// the same package on two Debian point releases in one release
		CANONICALS.put(TAR_12, "pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-12");
		CANONICALS.put(TAR_12_15, "pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-12.15");
		CANONICALS.put(CPE_ONLY, "cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*");
	}

	private final ReleaseInventory settled = new ReleaseInventory(InventoryState.SETTLED, ComponentIndex.of(CANONICALS), Map.of());

	private SbomComponentService sbomComponentService;
	private FindingPurlBridge bridge;

	@BeforeEach
	void setUp() {
		sbomComponentService = mock(SbomComponentService.class);
		bridge = new FindingPurlBridge();
		ReflectionTestUtils.setField(bridge, "sbomComponentService", sbomComponentService);
	}

	private static UUID uuid(int n) {
		return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
	}

	private static VulnerabilityDto finding(String purl, String vulnId) {
		return new VulnerabilityDto(purl, vulnId, VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
				null, null, null, null, null, null, null, null, false);
	}

	private void assertMatches(UUID expected, String findingPurl) {
		FindingSbomMatch m = settled.match(findingPurl);
		assertEquals(expected, m.sbomComponentUuid(), findingPurl);
		assertEquals(CANONICALS.get(expected), m.canonicalPurl());
		assertNull(m.missReason());
	}

	@Test
	void debianPlusAndEpochMatchWhateverTheEncoding() {
		assertMatches(GLIBC, "pkg:deb/debian/glibc@2.36-9+deb12u14?distro=debian-12");
		assertMatches(GLIBC, "pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12");
		assertMatches(ZLIB, "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12");
		assertMatches(ZLIB, "pkg:deb/debian/zlib@1%3A1.2.13.dfsg-1?distro=debian-12");
		assertMatches(IMAGEMAGICK, "pkg:deb/debian/imagemagick@8:6.9.11.60+dfsg-1.6+deb12u13?distro=debian-12.15");
		assertMatches(IMAGEMAGICK, "pkg:deb/debian/imagemagick@8%3A6.9.11.60%2Bdfsg-1.6%2Bdeb12u13?distro=debian-12.15");
	}

	@Test
	void scopedNpmMatchesEitherNamespaceSpelling() {
		assertMatches(BABEL, "pkg:npm/%40babel/traverse@7.22.0");
		// what IntegrationService makes of Dependency-Track's %40
		assertMatches(BABEL, "pkg:npm/@babel/traverse@7.22.0");
	}

	@Test
	void qualifiersOutsideTheIdentityAreIgnored() {
		assertMatches(TAR_12, "pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?arch=amd64&distro=debian-12");
		assertMatches(LOG4J, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar");
		assertMatches(LOG4J, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1#some/subpath");
	}

	@Test
	void theDistroIsPartOfTheIdentity() {
		assertMatches(TAR_12, "pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-12");
		assertMatches(TAR_12_15, "pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1?distro=debian-12.15");
		assertEquals(FindingSbomMissReason.NOT_IN_INVENTORY,
				settled.match("pkg:deb/debian/glibc@2.36-9+deb12u14?distro=debian-13").missReason());
		// purlIdentity would have matched it: it drops the distro
		assertEquals(FindingSbomMissReason.NOT_IN_INVENTORY,
				settled.match("pkg:deb/debian/glibc@2.36-9+deb12u14").missReason());
	}

	@Test
	void anotherVersionIsNotInTheInventory() {
		// the carried-forward shape: the release's BOM now has 2.14.1
		FindingSbomMatch m = settled.match("pkg:maven/org.apache.logging.log4j/log4j-core@2.24.3");
		assertEquals(FindingSbomMatch.missed(FindingSbomMissReason.NOT_IN_INVENTORY), m);
	}

	@Test
	void findingsWithoutAUsablePurlSayWhy() {
		assertEquals(FindingSbomMissReason.NO_PURL, settled.match(null).missReason());
		assertEquals(FindingSbomMissReason.NO_PURL, settled.match("").missReason());
		// never matched to the cpe-canonical component: the finding carries no cpe to match on
		assertEquals(FindingSbomMissReason.NO_PURL,
				settled.match("cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*").missReason());
		assertEquals(FindingSbomMissReason.UNPARSEABLE_PURL, settled.match("pkg:npm").missReason());
		assertEquals(FindingSbomMissReason.UNPARSEABLE_PURL, settled.match("pkg:npm/%zz@1").missReason());
	}

	@Test
	void aMissSaysHowFarToTrustTheInventory() {
		String absent = "pkg:npm/left-pad@1.3.0";
		assertEquals(FindingSbomMissReason.NOT_IN_INVENTORY, settled.match(absent).missReason());
		assertEquals(FindingSbomMissReason.INVENTORY_PENDING, new ReleaseInventory(InventoryState.INVENTORY_PENDING,
				ComponentIndex.of(CANONICALS), Map.of()).match(absent).missReason());
		assertEquals(FindingSbomMissReason.NO_INVENTORY, ReleaseInventory.NONE.match(absent).missReason());
		// a pending inventory still answers for what it holds
		assertEquals(GLIBC, new ReleaseInventory(InventoryState.INVENTORY_PENDING, ComponentIndex.of(CANONICALS), Map.of())
				.match("pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12").sbomComponentUuid());
	}

	@Test
	void anEncodingVariantDuplicateAnswersWithTheLowestUuidEverywhere() {
		UUID raw = uuid(20);
		UUID encoded = uuid(10);
		Map<UUID, String> dup = new LinkedHashMap<>();
		dup.put(raw, "pkg:deb/debian/perl@5.36.0-7+deb12u3?distro=debian-12");
		dup.put(encoded, "pkg:deb/debian/perl@5.36.0-7%2Bdeb12u3?distro=debian-12");
		ComponentIndex index = ComponentIndex.of(dup);
		String purl = "pkg:deb/debian/perl@5.36.0-7+deb12u3?distro=debian-12";

		assertEquals(encoded, index.componentOf(purl));
		assertEquals(encoded, new ReleaseInventory(InventoryState.SETTLED, index, Map.of()).match(purl).sbomComponentUuid());
		// grouped under the same one only, so no view counts the finding twice
		VulnerabilityDto f = finding(purl, "CVE-2023-47038");
		assertEquals(Map.of(encoded, List.of(f)), FindingPurlBridge.findingsByComponent(index, List.of(f)));
	}

	@Test
	void aComponentRecordedWithoutQualifiersAnswersForTheQualifiedFinding() {
		UUID bare = uuid(30);
		ComponentIndex index = ComponentIndex.of(Map.of(bare, "pkg:apk/alpine/musl@1.2.5-r0"));
		ReleaseInventory inventory = new ReleaseInventory(InventoryState.SETTLED, index, Map.of());

		assertEquals(bare, inventory.match("pkg:apk/alpine/musl@1.2.5-r0?arch=x86_64&distro=3.20.3").sbomComponentUuid());
		assertEquals(bare, inventory.match("pkg:apk/alpine/musl@1.2.5-r0").sbomComponentUuid());
		assertEquals(FindingSbomMissReason.NOT_IN_INVENTORY,
				inventory.match("pkg:apk/alpine/musl@1.2.4-r0?distro=3.20.3").missReason());
		// the qualified component does not answer for a finding that names no distribution
		assertEquals(FindingSbomMissReason.NOT_IN_INVENTORY,
				settled.match("pkg:deb/debian/tar@1.34+dfsg-1.2+deb12u1").missReason());
	}

	@Test
	void findingsGroupByComponentInStoredOrderAndUnmatchedOnesAreLeftOut() {
		VulnerabilityDto a = finding("pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12", "CVE-2025-4802");
		VulnerabilityDto b = finding("pkg:npm/@babel/traverse@7.22.0", "GHSA-67hx-6x53-jw92");
		VulnerabilityDto c = finding("pkg:deb/debian/glibc@2.36-9+deb12u14?distro=debian-12", "CVE-2025-0395");
		VulnerabilityDto carried = finding("pkg:maven/org.apache.logging.log4j/log4j-core@2.24.3", "CVE-2025-68161");
		VulnerabilityDto noPurl = finding(null, "CVE-2026-0001");

		Map<UUID, List<VulnerabilityDto>> grouped = FindingPurlBridge.findingsByComponent(
				ComponentIndex.of(CANONICALS), new ArrayList<>(List.of(a, b, carried, c, noPurl)));

		assertEquals(List.of(a, c), grouped.get(GLIBC));
		assertEquals(List.of(b), grouped.get(BABEL));
		assertEquals(Set.of(GLIBC, BABEL), grouped.keySet());
		assertTrue(FindingPurlBridge.findingsByComponent(ComponentIndex.of(CANONICALS), null).isEmpty());
	}

	@Test
	void theInventoryIsTheReleasesComponents() {
		UUID release = UUID.randomUUID();
		when(sbomComponentService.resolveReleaseComponentPurls(release)).thenReturn(new ReleaseComponentPurls(
				UUID.randomUUID(), Map.of(GLIBC, CANONICALS.get(GLIBC), BABEL, CANONICALS.get(BABEL)), Map.of(), false));

		ReleaseInventory inventory = bridge.inventoryOf(release);

		assertEquals(InventoryState.SETTLED, inventory.state());
		assertEquals(GLIBC, inventory.match("pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12").sbomComponentUuid());
		assertEquals(FindingSbomMissReason.NOT_IN_INVENTORY, inventory.match(CANONICALS.get(ZLIB)).missReason());
	}

	@Test
	void presentInReleaseAnswersWithTheInventoryStateInTheOrderAsked() {
		UUID release = UUID.randomUUID();
		when(sbomComponentService.resolveReleaseComponentPurls(release)).thenReturn(new ReleaseComponentPurls(
				UUID.randomUUID(), Map.of(ZLIB, CANONICALS.get(ZLIB), BABEL, CANONICALS.get(BABEL)), Map.of(), false));
		String zlib = "pkg:deb/debian/zlib@1%3A1.2.13.dfsg-1?distro=debian-12";
		String gone = "pkg:deb/debian/zlib@1:1.2.13.dfsg-1+deb12u1?distro=debian-12";
		String babel = "pkg:npm/@babel/traverse@7.22.0";

		InventoryPresence p = bridge.presentInRelease(release, List.of(zlib, gone, babel));

		assertEquals(InventoryState.SETTLED, p.state());
		assertEquals(List.of(zlib, babel), new ArrayList<>(p.present()));
	}

	@Test
	void aPendingReconcileOrAnEmptyInventoryIsNotSettled() {
		UUID pending = UUID.randomUUID();
		UUID empty = UUID.randomUUID();
		when(sbomComponentService.resolveReleaseComponentPurls(pending)).thenReturn(new ReleaseComponentPurls(
				UUID.randomUUID(), Map.of(ZLIB, CANONICALS.get(ZLIB)), Map.of(), true));
		when(sbomComponentService.resolveReleaseComponentPurls(empty)).thenReturn(new ReleaseComponentPurls(
				UUID.randomUUID(), Map.of(), Map.of(), false));

		assertEquals(InventoryState.INVENTORY_PENDING, bridge.presentInRelease(pending, List.of()).state());
		assertEquals(InventoryState.NO_INVENTORY, bridge.presentInRelease(empty, List.of("pkg:npm/a@1")).state());
		assertEquals(Set.of(), bridge.presentInRelease(empty, List.of("pkg:npm/a@1")).present());
		assertEquals(InventoryState.NO_INVENTORY, bridge.inventoryOf(null).state());
	}
}
