/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

import io.reliza.model.Artifact;
import io.reliza.model.Deliverable;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.Variant;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.repositories.VariantRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Regression tests for the three artifact-driven metrics-finder queries on
 * {@link ReleaseRepository}:
 *
 * <ul>
 *   <li>{@code findReleasesForMetricsComputeByArtifactDirect}</li>
 *   <li>{@code touchReleasesByScannedSceArtifact} (replaced the retired BY_SCE finder)</li>
 *   <li>{@code touchReleasesByScannedDeliverableArtifact} (replaced the retired
 *       BY_OUTBOUND_DELIVERABLES finder)</li>
 * </ul>
 *
 * Pin the per-release comparison: a release whose own {@code metrics.lastScanned} is older
 * than at least one of its (directly-attached, SCE-linked, or outbound-deliverable-linked)
 * artifacts MUST be picked up. Pre-fix versions used a global {@code MAX(release.lastScanned)}
 * cutoff that stranded releases as soon as any other release was recomputed (see SQL
 * comments in {@code VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_ARTIFACT_DIRECT}
 * for the full history).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseMetricsFinderQueryTest {

	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;
	@Autowired private DeliverableRepository deliverableRepository;
	@Autowired private VariantRepository variantRepository;
	@Autowired private TestInitializer testInitializer;

	// Sentinel epochs chosen well above any plausible real release.lastScanned so the
	// regression assertion holds even if the test DB has unrelated rows with high values.
	// 4_000_000_000.0 ≈ year 2096; 5_000_000_000.0 ≈ year 2128.
	private static final double OLD_EPOCH = 4_000_000_000.0;
	private static final double NEW_EPOCH = 5_000_000_000.0;

	/**
	 * The BY_ARTIFACT_DIRECT finder is retired (it expanded every release's
	 * artifact array per tick and began hitting the finders' 10s fail-fast
	 * timeout); the direct touch replaces it. Same fixture as the finder test it
	 * supersedes.
	 */
	@Test
	public void directTouch_advancesReleaseCarryingTheArtifact() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		Release stuckRelease = saveReleaseWithDirectArtifact(org.getUuid(), OLD_EPOCH, freshArt.getUuid());
		Release unrelated = saveReleaseWithLastScanned(org.getUuid(), OLD_EPOCH);

		java.time.Instant before = lastUpdatedOf(stuckRelease.getUuid());
		java.time.Instant unrelatedBefore = lastUpdatedOf(unrelated.getUuid());
		releaseRepository.touchReleasesByScannedArtifactDirect(freshArt.getUuid().toString());

		assertTrue(lastUpdatedOf(stuckRelease.getUuid()).isAfter(before),
				"The direct touch must advance last_updated_date of the release carrying the artifact");
		assertEquals(unrelatedBefore, lastUpdatedOf(unrelated.getUuid()),
				"A release not carrying the artifact must not be touched");
	}

	/** The hourly backlog gauge counts exactly the BY_UPDATE-eligible pool. */
	@Test
	public void backlogGaugeCountsEligibleRelease() {
		Organization org = testInitializer.obtainOrganization();
		long before = releaseRepository.countReleasesEligibleForMetricsCompute();
		// A release whose lastScanned is in the PAST is eligible (its
		// last_updated_date, set at creation, postdates the stamp). The finder
		// fixtures' sentinel epochs are deliberately in 2096+ and would NOT be
		// eligible -- the gauge measures real eligibility, not the sentinels.
		saveReleaseWithLastScanned(org.getUuid(), 1_000.0);
		long after = releaseRepository.countReleasesEligibleForMetricsCompute();
		assertTrue(after >= before + 1,
				"a release with a stale stamp must appear in the backlog gauge (before=" + before + " after=" + after + ")");
	}

	/**
	 * Drain-mode deferral bumps an ELIGIBLE row (the exact rows the touch's no-op
	 * guard refuses) and refuses a SETTLED row (bumping it would newly enqueue it
	 * rather than reorder it) -- the two predicates are deliberate opposites.
	 */
	@Test
	public void deferBumpsEligibleAndRefusesSettled() {
		Organization org = testInitializer.obtainOrganization();
		// lastScanned in the past => eligible
		Release eligible = saveReleaseWithLastScanned(org.getUuid(), 1_000.0);
		// lastScanned sentinel in 2096 => settled (last_updated_date <= stamp)
		Release settled = saveReleaseWithLastScanned(org.getUuid(), NEW_EPOCH);

		java.time.Instant eligibleBefore = lastUpdatedOf(eligible.getUuid());
		java.time.Instant settledBefore = lastUpdatedOf(settled.getUuid());

		assertEquals(1, releaseRepository.deferMetricsRecompute(eligible.getUuid()),
				"deferral must report the eligible row as reordered");
		assertEquals(0, releaseRepository.deferMetricsRecompute(settled.getUuid()),
				"deferral must refuse a settled row -- bumping it would enqueue, not reorder");

		assertTrue(lastUpdatedOf(eligible.getUuid()).isAfter(eligibleBefore),
				"the eligible row's last_updated_date must advance (to the back of the oldest-first queue)");
		assertEquals(settledBefore, lastUpdatedOf(settled.getUuid()),
				"the settled row must be untouched");
	}

	/**
	 * The no-op guard on every touch: a release already eligible for BY_UPDATE
	 * (last_updated_date past its lastScanned) with no compute fence must NOT be
	 * rewritten -- it is queued for compute regardless, and rewriting it per scan
	 * event is the write amplification that bloated releases under grown touch
	 * traffic.
	 */
	@Test
	public void touchSkipsAlreadyEligibleUnfencedRelease() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		// lastScanned=0 => last_updated_date (now) is already past it: eligible.
		Release eligible = saveReleaseWithDirectArtifact(org.getUuid(), 0.0, freshArt.getUuid());

		java.time.Instant before = lastUpdatedOf(eligible.getUuid());
		releaseRepository.touchReleasesByScannedArtifactDirect(freshArt.getUuid().toString());

		assertEquals(before, lastUpdatedOf(eligible.getUuid()),
				"An already-eligible unfenced release must not be rewritten by a touch");
	}

	/**
	 * The BY_SCE finder is retired; the SCE touch replaces it. Same fixture as the
	 * finder test it supersedes, asserting the outcome that actually matters: after
	 * the touch, the release is visible to BY_UPDATE.
	 */
	@Test
	public void sceTouch_makesReleaseVisibleToByUpdate() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		SourceCodeEntry sce = saveSceWithArtifact(org.getUuid(), freshArt.getUuid());
		Release stuckRelease = saveReleaseWithSourceCodeEntry(org.getUuid(), OLD_EPOCH, sce.getUuid());

		java.time.Instant before = lastUpdatedOf(stuckRelease.getUuid());
		releaseRepository.touchReleasesByScannedSceArtifact(freshArt.getUuid().toString());

		assertTrue(lastUpdatedOf(stuckRelease.getUuid()).isAfter(before),
				"The SCE touch must advance last_updated_date, which is what makes the release visible to BY_UPDATE");
	}

	/**
	 * An SCE is canonical per (vcs, commit) and shared by every component built from
	 * that commit, so touching on artifact identity alone would wake every sibling
	 * component's release. Only the release whose component matches the artifact's
	 * componentUuid tag may be touched.
	 */
	@Test
	public void sceTouch_doesNotWakeSiblingComponentSharingTheCommit() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		UUID owningComponent = UUID.randomUUID();
		UUID siblingComponent = UUID.randomUUID();
		SourceCodeEntry sce = saveSceWithComponentTaggedArtifact(org.getUuid(), freshArt.getUuid(), owningComponent);
		Release owning = saveReleaseWithSceAndComponent(org.getUuid(), OLD_EPOCH, sce.getUuid(), owningComponent);
		Release sibling = saveReleaseWithSceAndComponent(org.getUuid(), OLD_EPOCH, sce.getUuid(), siblingComponent);

		java.time.Instant owningBefore = lastUpdatedOf(owning.getUuid());
		java.time.Instant siblingBefore = lastUpdatedOf(sibling.getUuid());
		releaseRepository.touchReleasesByScannedSceArtifact(freshArt.getUuid().toString());

		assertTrue(lastUpdatedOf(owning.getUuid()).isAfter(owningBefore),
				"Owning component's release must be touched");
		assertEquals(siblingBefore, lastUpdatedOf(sibling.getUuid()),
				"A sibling component sharing the commit must NOT be woken by another component's artifact");
	}

	/**
	 * The BY_OUTBOUND_DELIVERABLES finder is retired; the deliverable touch replaces
	 * it. Same fixture, asserting BY_UPDATE visibility after the touch.
	 */
	@Test
	public void deliverableTouch_makesReleaseVisibleToByUpdate() {
		Organization org = testInitializer.obtainOrganization();
		Artifact freshArt = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		Deliverable deliverable = saveDeliverableWithArtifact(org.getUuid(), freshArt.getUuid());
		Release stuckRelease = saveReleaseWithLastScanned(org.getUuid(), OLD_EPOCH);
		saveVariantLinkingReleaseToDeliverable(org.getUuid(), stuckRelease.getUuid(), deliverable.getUuid());

		java.time.Instant before = lastUpdatedOf(stuckRelease.getUuid());
		releaseRepository.touchReleasesByScannedDeliverableArtifact(freshArt.getUuid().toString());

		assertTrue(lastUpdatedOf(stuckRelease.getUuid()).isAfter(before),
				"The deliverable touch must advance last_updated_date, which is what makes the release visible to BY_UPDATE");
	}

	/** A touch for an artifact no release carries must be a no-op, not a fleet-wide wake-up. */
	@Test
	public void touchForUnrelatedArtifactTouchesNothing() {
		Organization org = testInitializer.obtainOrganization();
		Artifact orphan = saveArtifactWithLastScanned(org.getUuid(), NEW_EPOCH);
		Release untouched = saveReleaseWithLastScanned(org.getUuid(), NEW_EPOCH + 10.0);

		java.time.Instant before = lastUpdatedOf(untouched.getUuid());
		releaseRepository.touchReleasesByScannedDeliverableArtifact(orphan.getUuid().toString());
		releaseRepository.touchReleasesByScannedSceArtifact(orphan.getUuid().toString());

		assertEquals(before, lastUpdatedOf(untouched.getUuid()),
				"An artifact attached to nothing must not drag unrelated releases into the finder pool");
	}

	// ---- helpers (intentionally local; cross-cutting test fixtures aren't worth a refactor yet) ----

	/**
	 * {@code last_updated_date} of a release, as the touch moves it.
	 *
	 * <p>The touch is asserted as a BEFORE/AFTER delta rather than by looking the
	 * release up in {@code findReleasesForMetricsComputeByUpdate(n)}, for two
	 * reasons. That finder is {@code ORDER BY last_updated_date ASC LIMIT n}, so a
	 * just-touched row sorts LAST and drops out of the window whenever the test
	 * database holds more than n eligible releases -- which it does in CI, where
	 * every suite shares the schema. And a freshly created fixture already
	 * satisfies the BY_UPDATE predicate on its own, so asserting the predicate
	 * would pass whether or not the touch did anything. The delta is the only
	 * thing that isolates the touch's own effect.
	 */
	private java.time.Instant lastUpdatedOf(UUID releaseUuid) {
		return releaseRepository.findById(releaseUuid).orElseThrow().getLastUpdatedDate().toInstant();
	}

	private static Set<UUID> uuidsOf(List<Release> releases) {
		return releases.stream().map(Release::getUuid).collect(Collectors.toSet());
	}

	private Artifact saveArtifactWithLastScanned(UUID orgUuid, double lastScanned) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		a.setMetrics(metrics);
		return artifactRepository.save(a);
	}

	private Release saveReleaseWithLastScanned(UUID orgUuid, double lastScanned) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("artifacts", new ArrayList<String>());
		r.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		r.setMetrics(metrics);
		return releaseRepository.save(r);
	}

	private Release saveReleaseWithDirectArtifact(UUID orgUuid, double lastScanned, UUID artifactUuid) {
		Release r = saveReleaseWithLastScanned(orgUuid, lastScanned);
		Map<String, Object> recordData = r.getRecordData();
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}

	/** SCE whose artifact entry carries a componentUuid tag, as the real merge path writes it. */
	private SourceCodeEntry saveSceWithComponentTaggedArtifact(UUID orgUuid, UUID artifactUuid, UUID componentUuid) {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		List<Map<String, String>> artifacts = new ArrayList<>();
		Map<String, String> entry = new HashMap<>();
		entry.put("artifactUuid", artifactUuid.toString());
		entry.put("componentUuid", componentUuid.toString());
		artifacts.add(entry);
		recordData.put("artifacts", artifacts);
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce);
	}

	/** Release pinned to both an SCE and a component, so component scoping can be asserted. */
	private Release saveReleaseWithSceAndComponent(UUID orgUuid, double lastScanned, UUID sceUuid, UUID componentUuid) {
		Release r = saveReleaseWithLastScanned(orgUuid, lastScanned);
		Map<String, Object> recordData = r.getRecordData();
		recordData.put("sourceCodeEntry", sceUuid.toString());
		recordData.put("component", componentUuid.toString());
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}

	private Release saveReleaseWithSourceCodeEntry(UUID orgUuid, double lastScanned, UUID sceUuid) {
		Release r = saveReleaseWithLastScanned(orgUuid, lastScanned);
		Map<String, Object> recordData = r.getRecordData();
		recordData.put("sourceCodeEntry", sceUuid.toString());
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}

	private void updateReleaseLastScanned(UUID releaseUuid, double lastScanned) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> metrics = r.getMetrics() != null ? r.getMetrics() : new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		r.setMetrics(metrics);
		releaseRepository.save(r);
	}

	private SourceCodeEntry saveSceWithArtifact(UUID orgUuid, UUID artifactUuid) {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		List<Map<String, String>> artifacts = new ArrayList<>();
		Map<String, String> entry = new HashMap<>();
		entry.put("artifactUuid", artifactUuid.toString());
		artifacts.add(entry);
		recordData.put("artifacts", artifacts);
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce);
	}

	private Deliverable saveDeliverableWithArtifact(UUID orgUuid, UUID artifactUuid) {
		Deliverable d = new Deliverable();
		d.setUuid(UUID.randomUUID());
		d.setCreatedDate(ZonedDateTime.now());
		d.setLastUpdatedDate(ZonedDateTime.now());
		d.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		d.setRecordData(recordData);
		return deliverableRepository.save(d);
	}

	private Variant saveVariantLinkingReleaseToDeliverable(UUID orgUuid, UUID releaseUuid, UUID deliverableUuid) {
		Variant v = new Variant();
		v.setUuid(UUID.randomUUID());
		v.setCreatedDate(ZonedDateTime.now());
		v.setLastUpdatedDate(ZonedDateTime.now());
		v.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("release", releaseUuid.toString());
		List<String> outboundDeliverables = new ArrayList<>();
		outboundDeliverables.add(deliverableUuid.toString());
		recordData.put("outboundDeliverables", outboundDeliverables);
		v.setRecordData(recordData);
		return variantRepository.save(v);
	}
}
