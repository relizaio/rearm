/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.FlowControl;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CarryForwardArm;
import io.reliza.model.dto.CarryForwardTally;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Regression test for the "product release stranded in Scan pending" self-heal
 * bug (see V55 migration + the scanIncomplete guard in
 * {@link ReleaseMetricsComputeService#computeReleaseMetricsOnRescan}).
 *
 * <p>The "Scan pending" badge is driven by {@code metrics.firstScanned} being
 * null. A product release re-derives its metrics only while it stays matched by
 * a metrics finder; the catch-all is
 * {@code FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE}, which matches while
 * {@code last_updated_date > to_timestamp(metrics->>'lastScanned')}.
 *
 * <p>The pre-fix bug: computing a product's metrics while a child was still
 * unscanned wrote {@code firstScanned=null} (correct) but also stamped
 * {@code lastScanned=now()}. A metrics write does not touch
 * {@code last_updated_date}, so {@code lastScanned} overtook it and the product
 * fell out of the finder — never to be re-derived when the child finally
 * scanned. This test pins the two halves of the fix:
 * <ol>
 *   <li>an incomplete scan must NOT stamp {@code lastScanned}, so the release
 *       stays finder-eligible (self-heal-able); and</li>
 *   <li>once the child is scanned, the next compute sets the product's
 *       {@code firstScanned} (the release heals) and settles it out.</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseScanSelfHealTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ArtifactService artifactService;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired private TestInitializer testInitializer;

	// Epoch-seconds sentinel for a "scanned" child. Stored the way the metrics
	// JSONB stores firstScanned/lastScanned (epoch-seconds number).
	private static final double CHILD_FIRST_SCANNED_EPOCH = 1_700_000_000.0; // ~2023-11

	@Test
	public void productWithUnscannedChild_staysFinderEligible_thenHealsWhenChildScans() {
		Organization org = testInitializer.obtainOrganization();

		// Child release with no firstScanned == still "Scan pending".
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		// Product release bundling the (as-yet unscanned) child.
		List<Map<String, Object>> parents = parentReleasesOf(child.getUuid());
		Release product = saveScanPendingRelease(org.getUuid(), parents);

		// --- Phase 1: child unscanned. Compute the product's metrics. ---
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(product);

		Release afterPhase1 = releaseRepository.findById(product.getUuid()).orElseThrow();
		// All-or-nothing rollup: product must remain scan-pending while a child is unscanned.
		assertNull(firstScanned(afterPhase1),
				"product.firstScanned must stay null while a child release is unscanned");
		// THE FIX: lastScanned must not be stamped for an incomplete scan. Pre-fix, the
		// compute stamped lastScanned=now() (directly or via touchReleaseLastScanned),
		// which is exactly what evicted the release from the BY_UPDATE finder.
		assertNull(lastScanned(afterPhase1),
				"incomplete scan must not stamp lastScanned (would evict from the self-heal finder)");
		// And the observable consequence: the product is still picked up by the finder,
		// so the every-minute sweep will retry it until the child scans.
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"product must remain eligible for FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE "
				+ "so it can self-heal once the child is scanned");

		// --- Phase 2: child finishes scanning. Re-derive the product. ---
		markScanned(child.getUuid(), CHILD_FIRST_SCANNED_EPOCH);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow());

		Release afterPhase2 = releaseRepository.findById(product.getUuid()).orElseThrow();
		// Healed: with every child scanned, the rollup sets the product's firstScanned.
		assertNotNull(firstScanned(afterPhase2),
				"product.firstScanned must be set once every child release is scanned (self-heal)");
		// Settled: a completed scan records lastScanned, taking the release out of the finder.
		assertNotNull(lastScanned(afterPhase2),
				"completed scan must stamp lastScanned so the release settles out of the finder");
	}

	@Test
	public void rejectedReleaseWithUnscannedBom_settlesOutOfTheQueue() {
		Organization org = testInitializer.obtainOrganization();
		// REJECTED release with a never-scanned BOM: pre-fix these were minted
		// continuously by rejectPendingReleases and squatted at the head of the
		// BY_UPDATE finder forever (non-scannable lifecycle -> the BOM will never
		// be scanned -> pre-fence logic kept them "dirty" indefinitely).
		Release rejected = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		UUID bomUuid = saveUnscannedBomArtifact(org.getUuid());
		attachArtifact(rejected.getUuid(), bomUuid);
		setLifecycle(rejected.getUuid(), "REJECTED");

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(rejected.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(rejected.getUuid()).orElseThrow();
		// Nothing to wait for: non-scannable lifecycle settles (stamps lastScanned)
		// instead of waiting on a scan that will never come.
		assertNotNull(lastScanned(after),
				"non-scannable (REJECTED) release must settle: lastScanned stamped even with an unscanned BOM");
		assertFalse(byUpdateFinderUuids().contains(rejected.getUuid()),
				"settled REJECTED release must leave the BY_UPDATE finder (no more head-squatting)");
	}

	/**
	 * The DRAFT shape, which is the one remaining case where a carry-forward failure would be SILENT
	 * AND PERMANENT.
	 *
	 * <p>DRAFT sits below ASSEMBLED, so {@code isScannableLifecycle} is false and {@code
	 * scanIncomplete} evaluates false even with an unscanned BOM attached. The release therefore
	 * SETTLES -- stamps lastScanned, leaves the finder -- rather than staying dirty. That is correct
	 * for an abandoned draft, but it means there is no fence, no [METRICS-STALLED] line, no retry and
	 * no probe: whatever value it settles at is what a user sees indefinitely.
	 *
	 * <p>So the question is only what it settles AT. Before carry-forward a replaced BOM made it
	 * settle at ZERO. It must now settle at the inherited findings. A regression here produces no
	 * error anywhere -- which is why it gets a test rather than an argument.
	 *
	 * <p>The carried state is produced by the real SEAM ({@code carryFindingsAcrossArtifactSwap}
	 * seeding an empty replacement from a findings-bearing predecessor), NOT hand-built. An earlier
	 * version fabricated an unscanned-BOM-with-findings directly, which pinned only the pre-existing
	 * release merge and stayed green with the entire carry-forward diff reverted. Now reverting the
	 * seeding leaves the replacement empty, the release settles at zero, and this goes red.
	 */
	@Test
	public void draftReleaseSettlesAtTheInheritedFindingsRatherThanZero() {
		Organization org = testInitializer.obtainOrganization();
		// A replaced BOM (findings-bearing) and its unscanned replacement (empty) -- the two sides of a
		// rebuild swap.
		UUID predecessor = saveUnscannedBomArtifactWithFindings(org.getUuid(), 6);
		UUID replacement = saveUnscannedBomArtifact(org.getUuid());

		// SEED the replacement through the production seam. This is the line the assertion below pins:
		// with the seeding reverted, the replacement stays empty and the release settles at zero.
		CarryForwardTally tally = artifactService.carryFindingsAcrossArtifactSwap(
				List.of(predecessor), List.of(replacement), WhoUpdated.getAutoWhoUpdated(),
				CarryForwardArm.RELEASE_DIRECT, UUID.randomUUID());
		assertEquals(1, tally.seeded(),
				"sanity: the seam must have carried the predecessor's findings onto the replacement -- "
				+ "if this is 0 the test below would pass vacuously against an empty replacement");

		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifact(r.getUuid(), replacement);
		setLifecycle(r.getUuid(), "DRAFT");

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();

		assertEquals(6, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"a DRAFT release must settle at the INHERITED findings. Settling at zero here is the "
				+ "worst shape in this whole investigation: silent, permanent, and with no probe to "
				+ "surface it, because a sub-ASSEMBLED release never enters the incomplete branch");
		assertNotNull(lastScanned(after),
				"and it still SETTLES -- the lifecycle gate is unchanged, so an abandoned draft does not "
				+ "squat in the finder waiting for a scan that will never come");
		assertFalse(byUpdateFinderUuids().contains(r.getUuid()),
				"which means it leaves the finder pool, exactly as it did before this work");
	}

	@Test
	public void backoffFence_excludesFromFinder_andClearRestores() {
		Organization org = testInitializer.obtainOrganization();
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		Release product = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child.getUuid()));
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"pre-fence sanity: scan-pending product is finder-eligible");

		releaseRepository.recordMetricsComputeIncomplete(product.getUuid(), 3600);

		Release fenced = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertNotNull(fenced.getFlowControl(), "fence must be recorded on flow_control");
		assertNotNull(fenced.getFlowControl().metricsComputeSkipUntil());
		assertEquals(1, fenced.getFlowControl().metricsComputeFailureCount());
		assertFalse(byUpdateFinderUuids().contains(product.getUuid()),
				"fenced release must not consume a finder slot while backing off");

		releaseRepository.clearMetricsComputeBackoff(product.getUuid());
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"cleared fence must re-admit the release to the finder");
	}

	@Test
	public void childCompletion_liftsParentFence_soParentHealsNextTick() {
		Organization org = testInitializer.obtainOrganization();
		// Child: ASSEMBLED, no BOMs, no children -> its compute anchors
		// firstScanned to createdDate (the no-BOM anchor), i.e. a genuine
		// null -> set transition through the rescan path.
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		Release product = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child.getUuid()));
		// Parent is deep in backoff, e.g. its child took hours to scan.
		releaseRepository.recordMetricsComputeIncomplete(product.getUuid(), 3600);
		assertFalse(byUpdateFinderUuids().contains(product.getUuid()));

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(child.getUuid()).orElseThrow());

		Release childAfter = releaseRepository.findById(child.getUuid()).orElseThrow();
		assertNotNull(firstScanned(childAfter), "sanity: child's firstScanned must land via the no-BOM anchor");
		Release productAfter = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertTrue(productAfter.getFlowControl() == null
						|| productAfter.getFlowControl().metricsComputeSkipUntil() == null,
				"child's firstScanned landing must lift the parent's fence (push, not poll)");
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"parent must be finder-eligible again so it heals on the next tick, not after its backoff");
	}

	// ---- ALL_ARTIFACTS_GONE hold (option C) ----

	@Test
	public void emptyGatherWithPriorFindings_HOLDS_ratherThanCollapsingToZero() {
		Organization org = testInitializer.obtainOrganization();
		// A previously-scanned ASSEMBLED release carrying 6 findings whose artifacts have since been
		// dereferenced -- it now gathers NOTHING (owners=r0/s0/d0), the ALL_ARTIFACTS_GONE shape.
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 6);

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(6, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"a release that gathered NOTHING but previously had findings must HOLD them, not collapse "
				+ "to a confident zero. Without the hold it settles at zero PERMANENTLY (scanIncomplete is "
				+ "false when nothing is gathered) and emits a phantom RESOLVED cycle -- the residual "
				+ "carry-forward cannot fix, because there is no replacement artifact to seed.");
		assertNotNull(after.getFlowControl(), "and it is fenced for retry, not settled");
		assertEquals(1, after.getFlowControl().metricsComputeFailureCount(),
				"the hold records an incomplete compute so the release keeps retrying -- it self-heals when "
				+ "an artifact re-attaches, or surfaces in [METRICS-STALLED] if it never does");
	}

	@Test
	public void heldRelease_selfHeals_whenAScannedArtifactReattaches() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 6);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());
		assertEquals(6, ((List<?>) releaseRepository.findById(r.getUuid()).orElseThrow()
				.getMetrics().get("vulnerabilityDetails")).size(), "sanity: held at 6");

		// A BOM re-attaches, already scanned, carrying 3 findings.
		attachArtifact(r.getUuid(), saveScannedBomArtifactWithFindings(org.getUuid(), 3));
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(3, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"the hold is transient, not a freeze: once an artifact re-attaches the release re-derives "
				+ "its real findings");
		assertNotNull(lastScanned(after), "and with a complete scan it settles again");
	}

	@Test
	public void gatheredArtifactScannedClean_collapsesAsRemediation_notHeld() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 4);
		// A SCANNED BOM with ZERO findings is attached -- the CVEs were genuinely fixed.
		attachArtifact(r.getUuid(), saveScannedBomArtifactWithFindings(org.getUuid(), 0));

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(0, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"a GATHERED artifact that scanned clean is remediation, not a dereference. The hold keys on "
				+ "an EMPTY gather (gathered=0), so a release that still holds a scanned BOM must collapse "
				+ "to zero, never hold -- otherwise a genuine fix would be reported vulnerable forever");
		assertNotNull(lastScanned(after), "and settle");
	}

	@Test
	public void productReleaseWithEmptyOwnGather_isNotHeld_stillAggregatesChildren() {
		Organization org = testInitializer.obtainOrganization();
		// A scanned child carrying 3 findings; a product that bundles it, has NO artifacts of its own,
		// and whose own metrics are stale at 5.
		Release child = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 3);
		Release product = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED",
				parentReleasesOf(child.getUuid()), 5);

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertEquals(3, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"a PRODUCT release derives its findings from its children, not its own artifacts, so an "
				+ "empty OWN gather is normal and must NOT trigger the hold -- it must keep re-deriving its "
				+ "rollup (the !hasChildRels guard). Holding it here would freeze the product at stale "
				+ "metrics and ignore every later child rescan.");
	}

	@Test
	public void draftReleaseWithEmptyGather_settlesAsToday_notHeld() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveReleaseWithFindings(org.getUuid(), "DRAFT", null, 6);

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(0, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"the hold is scoped to scannable (ASSEMBLED+) lifecycles, matching the scanIncomplete gate. "
				+ "A DRAFT with an empty gather settles as it does today; widening the hold to DRAFT would "
				+ "recreate the unbounded fenced-draft population design section 5 rejected.");
	}

	// ---- release-level hold, the CI-rebuild SWAP case (gathered>=1, sole BOM unscanned) ----

	@Test
	public void soleUnscannedBomSwap_HOLDS_withoutAnyPairing() {
		Organization org = testInitializer.obtainOrganization();
		// A previously-scanned release carrying 6 findings whose sole BOM was just swapped for a
		// brand-new UNSCANNED one (the CI rebuild). gathered=1, hasAnyBom=true, anyBomUnscanned=true --
		// the exact customer shape whose carry-forward pairing declined.
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 6);
		attachArtifact(r.getUuid(), saveUnscannedBomArtifact(org.getUuid()));

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(6, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"the sole gathered BOM is unscanned and contributes nothing, so the release must HOLD its "
				+ "last-known 6 -- NOT collapse to zero -- with NO artifact pairing involved. This is the "
				+ "gap #433's displayIdentifier pairing left when the CI's deliverable name is unstable.");
		assertNotNull(after.getFlowControl(), "and it is fenced for retry");
		assertEquals(1, after.getFlowControl().metricsComputeFailureCount(),
				"an incomplete compute is recorded so it self-heals when the swap's scan lands");
	}

	@Test
	public void swapHold_selfHeals_whenTheSwappedBomScans() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 6);
		UUID bom = saveUnscannedBomArtifact(org.getUuid());
		attachArtifact(r.getUuid(), bom);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());
		assertEquals(6, ((List<?>) releaseRepository.findById(r.getUuid()).orElseThrow()
				.getMetrics().get("vulnerabilityDetails")).size(), "sanity: held at 6");

		// The swap's own scan lands -- the SAME BOM is now scanned, carrying 4.
		markBomScanned(bom, 4);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(4, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"once the swapped BOM scans, the hold releases and the release re-derives its real findings");
		assertNotNull(lastScanned(after), "and settles");
	}

	@Test
	public void partialRebuild_withANewFinding_isNotHeld_publishesTheAddition() {
		Organization org = testInitializer.obtainOrganization();
		// THE union-safety test -- the exact shape that sank the earlier in-compute guards. The release
		// had 6. A rebuild leaves TWO BOMs gathered: one already scanned carrying a NEW finding (2), and
		// one still pending. Holding the prior 6 would SWALLOW the scanned BOM's addition. It must not.
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 6);
		UUID scannedWithNew = saveScannedBomArtifactWithFindings(org.getUuid(), 2);
		UUID pending = saveUnscannedBomArtifact(org.getUuid());
		attachArtifacts(r.getUuid(), List.of(scannedWithNew, pending));

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(r.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(r.getUuid()).orElseThrow();
		assertEquals(2, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"a gathered artifact DOES carry findings (artsFindings>0), so the hold must NOT fire -- the "
				+ "scanned BOM's finding is published immediately, additions never wait. Holding at 6 here "
				+ "is the exact 'union, not skip' violation that killed the earlier release-level designs; "
				+ "restoring the pending BOM's own prior findings is #433's granular job, not this net's.");
	}

	@Test
	public void backoffSchedule_gracethenEscalateToCap() {
		assertEquals(0, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(null),
				"first attempt is within the grace window");
		assertEquals(0, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(4)),
				"grace window: first 5 attempts are unfenced (per-minute retries)");
		assertEquals(60, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(5)));
		assertEquals(120, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(6)));
		assertEquals(3600, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(11)),
				"cap: escalation tops out at one hour");
		assertEquals(3600, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(500)),
				"cap holds for arbitrarily large failure counts");
	}

	private static FlowControl fcWithFailures(int failureCount) {
		return new FlowControl(null, null, null, null, null, null, null, null, null, failureCount);
	}

	// ---- helpers (local, mirroring ReleaseMetricsFinderQueryTest's fixture style) ----

	private Set<UUID> byUpdateFinderUuids() {
		return releaseRepository.findReleasesForMetricsComputeByUpdate(1000)
				.stream().map(Release::getUuid).collect(Collectors.toSet());
	}

	private static Object firstScanned(Release r) {
		return r.getMetrics() == null ? null : r.getMetrics().get("firstScanned");
	}

	private static Object lastScanned(Release r) {
		return r.getMetrics() == null ? null : r.getMetrics().get("lastScanned");
	}

	private static List<Map<String, Object>> parentReleasesOf(UUID childUuid) {
		Map<String, Object> entry = new HashMap<>();
		entry.put("release", childUuid.toString());
		entry.put("deliverables", new ArrayList<>());
		List<Map<String, Object>> parents = new ArrayList<>();
		parents.add(entry);
		return parents;
	}

	/**
	 * Persist an ASSEMBLED release with no firstScanned (== "Scan pending") and an
	 * empty metrics map. {@code parentReleases} makes it a product/aggregate release.
	 */

	private Release saveScanPendingRelease(UUID orgUuid, List<Map<String, Object>> parentReleases) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", "ASSEMBLED");
		recordData.put("artifacts", new ArrayList<String>());
		recordData.put("parentReleases", parentReleases);
		r.setRecordData(recordData);
		r.setMetrics(new HashMap<>());
		return releaseRepository.save(r);
	}

	private void markScanned(UUID releaseUuid, double firstScannedEpoch) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> metrics = r.getMetrics() != null ? r.getMetrics() : new HashMap<>();
		metrics.put("firstScanned", firstScannedEpoch);
		metrics.put("lastScanned", firstScannedEpoch);
		r.setMetrics(metrics);
		releaseRepository.save(r);
	}

	/**
	 * An unscanned BOM that nonetheless CARRIES findings -- the carry-forward state a replacement
	 * artifact sits in between the upload and its own scan: findings present, firstScanned null.
	 *
	 * <p>Built from the real DTOs and serialised, rather than hand-rolled JSON. A hand-built map
	 * omitted fields the alias organizer requires and blew up inside computeMetricsFromFacts, which
	 * is a fixture bug masquerading as a product bug -- let the model define its own shape.
	 */
	private UUID saveUnscannedBomArtifactWithFindings(UUID orgUuid, int findings) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);

		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			vulns.add(new VulnerabilityDto("pkg:maven/org.example/lib" + i + "@1.0.0", "CVE-" + i,
					VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
					null, null, ZonedDateTime.now(), null, null, null, null, null, false));
		}
		dti.setVulnerabilityDetails(vulns);
		dti.computeMetricsFromFacts();
		// NO firstScanned, and clear the lastScanned computeMetricsFromFacts just invented -- an
		// unscanned artifact must carry neither stamp.
		dti.setFirstScanned(null);
		dti.setLastScanned(null);
		a.setMetrics(Utils.OM.convertValue(dti, LinkedHashMap.class));
		return artifactRepository.save(a).getUuid();
	}

	/**
	 * A release whose metrics ALREADY carry {@code findings} and a PAST scan stamp -- a
	 * previously-scanned release, so a fresh compute's "now is after lastScanned" guard fires. No
	 * artifacts are attached, so it gathers NOTHING: the dereferenced ALL_ARTIFACTS_GONE shape.
	 * {@code parentReleases} makes it a product/aggregate.
	 */
	private Release saveReleaseWithFindings(UUID orgUuid, String lifecycle,
			List<Map<String, Object>> parentReleases, int findings) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now().minusHours(2));
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", lifecycle);
		recordData.put("artifacts", new ArrayList<String>());
		if (parentReleases != null) {
			recordData.put("parentReleases", parentReleases);
		}
		r.setRecordData(recordData);
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(buildVulns(findings, "REL"));
		ZonedDateTime scanned = ZonedDateTime.now().minusHours(1);
		m.setFirstScanned(scanned);
		m.setLastScanned(scanned);
		r.setMetrics(Utils.OM.convertValue(m, LinkedHashMap.class));
		return releaseRepository.save(r);
	}

	/** A SCANNED BOM artifact (firstScanned set) carrying {@code findings} -- gathering it makes the
	 *  release scan-complete, so it settles rather than staying pending. */
	private UUID saveScannedBomArtifactWithFindings(UUID orgUuid, int findings) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now().minusHours(3));
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		dti.setVulnerabilityDetails(buildVulns(findings, "HEAL"));
		dti.computeMetricsFromFacts();
		ZonedDateTime scanned = ZonedDateTime.now().minusHours(3);
		dti.setFirstScanned(scanned);
		dti.setLastScanned(scanned);
		a.setMetrics(Utils.OM.convertValue(dti, LinkedHashMap.class));
		return artifactRepository.save(a).getUuid();
	}

	private static LinkedList<VulnerabilityDto> buildVulns(int findings, String prefix) {
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			vulns.add(new VulnerabilityDto("pkg:maven/org.example/" + prefix.toLowerCase() + i + "@1.0.0",
					prefix + "-" + i, VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
					null, null, ZonedDateTime.now().minusHours(3), null, null, null, null, null, false));
		}
		return vulns;
	}

	/** BOM artifact with no metrics at all -- i.e. never scanned. */
	private UUID saveUnscannedBomArtifact(UUID orgUuid) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		return artifactRepository.save(a).getUuid();
	}

	private void attachArtifact(UUID releaseUuid, UUID artifactUuid) {
		attachArtifacts(releaseUuid, List.of(artifactUuid));
	}

	private void attachArtifacts(UUID releaseUuid, List<UUID> artifactUuids) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> recordData = r.getRecordData();
		List<String> artifacts = new ArrayList<>();
		artifactUuids.forEach(a -> artifacts.add(a.toString()));
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		releaseRepository.save(r);
	}

	/** Turn a previously-unscanned BOM into a scanned one carrying {@code findings} -- the swap's own
	 *  scan landing, which releases the hold. */
	private void markBomScanned(UUID bomUuid, int findings) {
		Artifact a = artifactRepository.findById(bomUuid).orElseThrow();
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		dti.setVulnerabilityDetails(buildVulns(findings, "SCAN"));
		dti.computeMetricsFromFacts();
		ZonedDateTime scanned = ZonedDateTime.now();
		dti.setFirstScanned(scanned);
		dti.setLastScanned(scanned);
		a.setMetrics(Utils.OM.convertValue(dti, LinkedHashMap.class));
		artifactRepository.save(a);
	}

	private void setLifecycle(UUID releaseUuid, String lifecycle) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> recordData = r.getRecordData();
		recordData.put("lifecycle", lifecycle);
		r.setRecordData(recordData);
		releaseRepository.save(r);
	}
}
