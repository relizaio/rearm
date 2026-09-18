/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.dto.ChangelogRecords.ReleaseVulnerabilityInfo;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * KEV membership on the surfaces that are NOT the release aggregate.
 *
 * <p>Metrics compute stamps {@code knownExploited} onto the release aggregate
 * only. Two consequences shipped as bugs: an artifact reported {@code false}
 * for a finding its own release reported as KEV (BUG 10), and the changelog /
 * attribution surfaces served a hardcoded {@code false} from the GraphQL layer
 * (BUG 11) -- fully wired in the UI, permanently blank.
 *
 * <p>The two fixes are deliberately different, because the two surfaces are:
 * <ul>
 *   <li>changelog / attribution CARRY the flag. Their assemblers already hold a
 *       stamped source, so the fix is plumbing, not a lookup -- no org context
 *       and no KEV query. That is why these tests assert pass-through rather
 *       than probing.</li>
 *   <li>artifacts STAMP at read time, because nothing refreshes a stored
 *       artifact stamp when CISA adds a CVE.</li>
 * </ul>
 */
class KevReadSurfaceStampingTest {

	private KevAssertionService kevAssertionService;
	private ReleaseMetricsComputeService service;
	private final UUID org = UUID.randomUUID();

	@BeforeEach
	void wire() {
		kevAssertionService = mock(KevAssertionService.class);
		service = new ReleaseMetricsComputeService();
		ReflectionTestUtils.setField(service, "kevAssertionService", kevAssertionService);
	}

	private VulnerabilityDto vuln(String cve, Boolean kev) {
		return new VulnerabilityDto("pkg:maven/g/a@1", cve, VulnerabilitySeverity.CRITICAL,
				Set.of(), Set.of(), Set.of(), null, null, null, null, Set.of(), Set.of(),
				null, null, kev);
	}

	// ---------- BUG 11: changelog / attribution carry the flag ----------

	@Test
	void changelogProjectionCarriesKnownExploitedFromTheStampedFinding() {
		// ReleaseVulnerabilityInfo.from is the single projection shared by the
		// release-pair diff and the re-scan over-time path. It used to drop the
		// flag, and the GraphQL layer then hardcoded false for the whole type.
		ReleaseVulnerabilityInfo info = ReleaseVulnerabilityInfo.from(vuln("CVE-2025-31125", true));
		assertEquals(Boolean.TRUE, info.knownExploited(),
				"a KEV finding must stay KEV once projected for the changelog");
		assertEquals(Boolean.FALSE, ReleaseVulnerabilityInfo.from(vuln("CVE-2020-1", false)).knownExploited());
	}

	@Test
	void changelogProjectionNeedsNoOrgContextOrKevLookup() {
		// The point of carrying rather than probing: this projection is reachable
		// from paths that have no org in scope, and must not acquire one.
		ReleaseVulnerabilityInfo.from(vuln("CVE-2025-31125", true));
		verify(kevAssertionService, never()).filterKnownExploited(any(), any());
	}

	@Test
	void anUnknownFlagProjectsAsNullNotFalse() {
		// null means "the producer did not know" -- distinct from a probe that
		// ran and found the CVE absent from the catalog. Collapsing the two
		// would make a missing stamp indistinguishable from a real negative.
		assertNull(ReleaseVulnerabilityInfo.from(vuln("CVE-2020-1", null)).knownExploited());
	}

	// ---------- BUG 10: artifact metrics stamped at read time ----------

	private ReleaseMetricsDto artifactMetricsCarrying(String cve) {
		DependencyTrackIntegration m = new DependencyTrackIntegration();
		m.setVulnerabilityDetails(List.of(vuln(cve, false)));
		return m;
	}

	@Test
	void artifactMetricsAreStampedFromTheOrgKevCatalogOnRead() {
		when(kevAssertionService.filterKnownExploited(eq(org), any()))
				.thenReturn(Set.of("CVE-2025-31125"));
		ReleaseMetricsDto stamped = service.knownExploitedStampedCopy(org,
				artifactMetricsCarrying("CVE-2025-31125"));
		assertEquals(Boolean.TRUE, stamped.getVulnerabilityDetails().get(0).knownExploited(),
				"the artifact must report KEV for a finding its release reports as KEV");
	}

	@Test
	void stampingDoesNotMutateTheCallersMetrics() {
		// The argument belongs to the artifact entity. A read that mutated it
		// would leak a stamp into whatever else holds that instance.
		when(kevAssertionService.filterKnownExploited(eq(org), any()))
				.thenReturn(Set.of("CVE-2025-31125"));
		ReleaseMetricsDto original = artifactMetricsCarrying("CVE-2025-31125");
		ReleaseMetricsDto stamped = service.knownExploitedStampedCopy(org, original);
		assertEquals(Boolean.FALSE, original.getVulnerabilityDetails().get(0).knownExploited(),
				"the caller's copy must be untouched");
		assertEquals(Boolean.TRUE, stamped.getVulnerabilityDetails().get(0).knownExploited());
	}

	@Test
	void stampingPreservesTheRuntimeSubclass() {
		// Artifact.metrics is DependencyTrackMetrics in the schema and
		// DependencyTrackIntegration in Java. clone() goes through
		// Object.clone(), so the subclass survives -- returning a plain
		// ReleaseMetricsDto would drop the subclass-only fields on the wire.
		when(kevAssertionService.filterKnownExploited(eq(org), any())).thenReturn(Set.of());
		ReleaseMetricsDto stamped = service.knownExploitedStampedCopy(org,
				artifactMetricsCarrying("CVE-2020-1"));
		assertTrue(stamped instanceof DependencyTrackIntegration,
				"the copy must still be a DependencyTrackIntegration");
	}

	@Test
	void anArtifactWithNoCveShapedFindingsCostsNoKevQuery() {
		// Read-time stamping is only affordable because it short-circuits.
		DependencyTrackIntegration empty = new DependencyTrackIntegration();
		empty.setVulnerabilityDetails(List.of());
		assertNotNull(service.knownExploitedStampedCopy(org, empty));
		verify(kevAssertionService, never()).filterKnownExploited(any(), any());
	}

	@Test
	void nullMetricsStayNull() {
		// Artifacts legitimately have no metrics; the resolver must not invent any.
		assertNull(service.knownExploitedStampedCopy(org, null));
	}

	@Test
	void aNullOrgResolvesToNoKevRatherThanThrowing() {
		ReleaseMetricsDto stamped = service.knownExploitedStampedCopy(null,
				artifactMetricsCarrying("CVE-2025-31125"));
		assertEquals(Boolean.FALSE, stamped.getVulnerabilityDetails().get(0).knownExploited());
		verify(kevAssertionService, never()).filterKnownExploited(any(), any());
	}

	@Test
	void stampingLeavesStoredTotalsAndScanTimestampsAlone() {
		// The read copy must NOT run computeMetricsFromFacts(). That is a
		// write-path operation: it rewrites the tallies from whatever detail
		// lists are loaded, and defaults a null lastScanned to now -- which would
		// invent a scan timestamp for a never-scanned artifact, changing on every
		// request. Regression guard for exactly that.
		when(kevAssertionService.filterKnownExploited(eq(org), any()))
				.thenReturn(Set.of("CVE-2025-31125"));
		DependencyTrackIntegration m = new DependencyTrackIntegration();
		m.setVulnerabilityDetails(List.of(vuln("CVE-2025-31125", false)));
		m.setCritical(99);
		assertNull(m.getLastScanned(), "precondition: this artifact was never scanned");

		ReleaseMetricsDto stamped = service.knownExploitedStampedCopy(org, m);

		assertEquals(Boolean.TRUE, stamped.getVulnerabilityDetails().get(0).knownExploited());
		assertNull(stamped.getLastScanned(),
				"a read must not invent a lastScanned for an unscanned artifact");
		assertEquals(99, stamped.getCritical(),
				"stored tallies must survive a read-time stamp untouched");
	}

	// ---------- BUG 11: the attribution half ----------

	@Test
	void attributionRecordCarriesAndPreservesKnownExploited() {
		// withScanArrival re-creates the record positionally across 20 arguments;
		// a flag appended at the end is exactly the kind that gets dropped there.
		VulnerabilityWithAttribution v = new VulnerabilityWithAttribution(
				"key", "CVE-2025-31125", "CRITICAL", "pkg:maven/g/a@1", Set.of(),
				List.of(), List.of(), List.of(), 0, 0, 0, false, false, true,
				null, null, null, null, null, Boolean.TRUE);
		assertEquals(Boolean.TRUE, v.knownExploited());
		assertEquals(Boolean.TRUE, v.withScanArrival(null, null, null).knownExploited(),
				"the scan-arrival decoration must preserve every other field verbatim");
	}
}
