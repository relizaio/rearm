/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkTestSupport.iso;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.CanonicalMapFlowControl;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.model.SyntheticDtrackBucket;

/**
 * TPF-137 mutation round: the pending-change half of the candidate rule the scan-status explainer mirrors
 * ({@link SyntheticSbomService#isInFanOutCandidateSet}), on a stamped artifact. A component whose bucket the
 * caller does not hold is skipped, not dereferenced; an enrichment-terminal component's pending change is no
 * reason to fan the canonical out; and the control: the same entry on a live component is.
 */
class SyntheticSbomServiceCandidateRuleEdgesTest {

	private final SyntheticSbomService service = new SyntheticSbomService();
	private final ZonedDateTime STAMPED = minutesAgo(30);
	private final ZonedDateTime CHANGED = minutesAgo(10);

	private ArtifactCanonicalMap stampedRow() {
		ArtifactCanonicalMap own = new ArtifactCanonicalMap();
		own.setFlowControl(new CanonicalMapFlowControl(null, iso(STAMPED), null));
		return own;
	}

	private SbomComponent component(int bucketIndex, boolean terminal) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setCanonicalPurl("pkg:npm/dep-" + sc.getUuid() + "@1");
		sc.setSyntheticBucketIndex(bucketIndex);
		if (terminal) sc.setFlowControl(new SbomComponentFlowControl("2026-10-01T00:00:00Z", "OWN_BOM_UNPULLABLE"));
		return sc;
	}

	private SyntheticDtrackBucket bucketWithPending(int index, SbomComponent pending) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setBucketIndex(index);
		b.setLastUpdatedDate(CHANGED);
		b.setFlowControl(new BucketFlowControl(Map.of(pending.getUuid().toString(), iso(CHANGED)), iso(CHANGED)));
		return b;
	}

	private String answer(List<SbomComponent> components, Map<Integer, SyntheticDtrackBucket> buckets) {
		ArtifactCanonicalMap own = stampedRow();
		return String.valueOf(service.isInFanOutCandidateSet(own, List.of(own), components, buckets));
	}

	@Test
	void aPendingChangeOfALiveComponentMakesTheCanonicalACandidate() {
		SbomComponent live = component(0, false);

		String a = answer(List.of(live), Map.of(0, bucketWithPending(0, live)));

		assertTrue(a.contains("inCandidateSet=true"), a);
	}

	@Test
	void aPendingChangeOfAnEnrichmentTerminalComponentIsNoReason() {
		SbomComponent terminal = component(0, true);

		String a = answer(List.of(terminal), Map.of(0, bucketWithPending(0, terminal)));

		assertTrue(a.contains("inCandidateSet=false"), a);
	}

	@Test
	void aComponentWhoseBucketTheCallerDoesNotHoldIsSkipped() {
		SbomComponent live = component(7, false);
		SbomComponent other = component(0, false);

		String a = answer(List.of(live, other), Map.of(0, bucketWithPending(0, other)));

		assertTrue(a.contains("inCandidateSet=true"), "the held bucket's pending change still counts: " + a);
		String none = answer(List.of(live), Map.of());
		assertTrue(none.contains("inCandidateSet=false"), none);
	}
}
