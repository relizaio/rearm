/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.SbomComponent;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.ws.App;

/**
 * TPF-114 mutation round: the edges of the fan-out statements that the candidate, requeue and
 * contract tests leave open, each against the real database. The slice statement answers the
 * changed components only, stops at its room and keeps the org on its rows, the stamp check
 * filters by the drain's cutoff (TPF-137, replacing the retired bucket query); the stamp keeps
 * what else the row holds and refuses a requeue that happened at the very instant its pass
 * started; the requeue of a canonical touches that canonical only; the pending count is cut at
 * its cutoff; the V102 index is the partial one the never-stamped query repeats.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutQueryEdgesIntegrationTest extends FanOutCandidateTestBase {

	// ---------------- the stamp ----------------

	@Test
	public void aRequeueAtTheInstantThePassStartedLeavesTheRowPending() {
		UUID org = newOrg();
		Mapped q = mapBom(org, null, null, component(org, uniquePurl("q"), 0));
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org, q.canonical()));
		ZonedDateTime requeuedAt = Instant.parse(flowControlOf(q.artifact()).fanOutRequeuedAt()).atZone(ZoneOffset.UTC);
		String stamp = iso(nowMicros().minusMinutes(2));

		assertEquals(0, artifactCanonicalMapRepository.stampFanOutPass(org, q.canonical(), stamp, requeuedAt),
				"a requeue at or after the pass start is left alone");
		assertNull(fanOutAtOf(q.artifact()));
		assertEquals(0, artifactCanonicalMapRepository.stampFanOutPass(org, q.canonical(), stamp, requeuedAt.minusNanos(1000)));
		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org, q.canonical(), stamp, requeuedAt.plusNanos(1000)),
				"a pass that started after the requeue stamps");
		assertEquals(stamp, fanOutAtOf(q.artifact()));
	}

	@Test
	public void theStampMergesIntoWhatTheRowAlreadyHolds() {
		UUID org = newOrg();
		Mapped q = mapBom(org, null, null, component(org, uniquePurl("q"), 0));
		artifactCanonicalMapRepository.markCanonicalFormVerified(q.canonical(), 7);
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org, q.canonical()));
		String requeued = flowControlOf(q.artifact()).fanOutRequeuedAt();
		String stamp = iso(nowMicros().minusMinutes(2));

		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org, q.canonical(), stamp,
				artifactCanonicalMapRepository.dbClockTimestamp()));

		assertEquals(stamp, fanOutAtOf(q.artifact()));
		assertEquals(7, flowControlOf(q.artifact()).canonicalFormVersion(), "the canonical form version survives the stamp");
		assertEquals(requeued, flowControlOf(q.artifact()).fanOutRequeuedAt(), "so does the record of the requeue");
	}

	@Test
	public void aRowWithNoFlowControlAtAllIsStampedAndRequeued() {
		UUID org = newOrg();
		Mapped q = mapBom(org, null, null, component(org, uniquePurl("q"), 0));
		jdbcTemplate.update("update rearm.artifact_canonical_map set flow_control = null where artifact_uuid = ?", q.artifact());
		ZonedDateTime t = nowMicros().minusMinutes(2);

		assertEquals(1, stampFanOut(org, q.canonical(), t));
		assertEquals(iso(t), fanOutAtOf(q.artifact()));

		jdbcTemplate.update("update rearm.artifact_canonical_map set flow_control = null where artifact_uuid = ?", q.artifact());
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org, q.canonical()));
		assertNotNull(flowControlOf(q.artifact()), "a null flow_control is coalesced, not left null by the requeue");
		assertNotNull(flowControlOf(q.artifact()).fanOutRequeuedAt());
		jdbcTemplate.update("update rearm.artifact_canonical_map set flow_control = null where artifact_uuid = ?", q.artifact());
		SbomComponent held = component(org, uniquePurl("held"), 0);
		holdComponent(org, q.canonical(), held);
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForComponents(org, List.of(held.getUuid())));
		assertNotNull(flowControlOf(q.artifact()));
	}

	// ---------------- the requeues ----------------

	@Test
	public void aRequeueOfACanonicalLeavesTheOtherCanonicalsOfTheOrgStamped() {
		UUID org = newOrg();
		Mapped q1 = mapBom(org, null, null, component(org, uniquePurl("q1"), 0));
		Mapped q2 = mapBom(org, null, null, component(org, uniquePurl("q2"), 0));
		String t1 = iso(nowMicros().minusMinutes(2));
		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org, q1.canonical(), t1, FAR_PAST));
		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org, q2.canonical(), t1, FAR_PAST));

		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org, q1.canonical()));

		assertNull(fanOutAtOf(q1.artifact()));
		assertEquals(t1, fanOutAtOf(q2.artifact()), "the other canonical keeps its stamp");
		assertNull(flowControlOf(q2.artifact()).fanOutRequeuedAt());
	}

	@Test
	public void aRequeueOfComponentsReachesTheCanonicalsHoldingThemAndNoOtherInTheOrg() {
		UUID org = newOrg();
		SbomComponent held = component(org, uniquePurl("held"), 0);
		Mapped q1 = mapBom(org, null, null, held);
		Mapped q2 = mapBom(org, null, null, component(org, uniquePurl("other"), 0));
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		stampFanOut(org, q1.canonical(), t1);
		stampFanOut(org, q2.canonical(), t1);

		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForComponents(org, List.of(held.getUuid())));

		assertNull(fanOutAtOf(q1.artifact()));
		assertEquals(iso(t1), fanOutAtOf(q2.artifact()));
	}

	@Test
	public void aRequeueOfComponentsDoesNotReachAnotherOrgsRowsOfTheSameCanonicalUuid() {
		UUID org1 = newOrg();
		UUID org2 = newOrg();
		UUID k = UUID.randomUUID();
		Artifact a1 = saveArtifactWithLastScanned(org1, null);
		Artifact a2 = saveArtifactWithLastScanned(org2, null);
		mapRow(org1, a1.getUuid(), k, null);
		mapRow(org2, a2.getUuid(), k, null);
		SbomComponent held = component(org1, uniquePurl("org1-held"), 0);
		holdComponent(org1, k, held);
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		stampFanOut(org1, k, t1);
		stampFanOut(org2, k, t1);

		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForComponents(org1, List.of(held.getUuid())),
				"only org1's row");

		assertNull(fanOutAtOf(a1.getUuid()));
		assertEquals(iso(t1), fanOutAtOf(a2.getUuid()), "org2's row of the same canonical uuid keeps its stamp");
		assertNull(flowControlOf(a2.getUuid()).fanOutRequeuedAt());
	}

	// ---------------- the pending count ----------------

	@Test
	public void thePendingCountIsTheUnstampedRowsOlderThanTheCutoff() {
		UUID org = newOrg();
		UUID other = newOrg();
		ZonedDateTime now = nowMicros();
		mapBom(org, null, now.minusHours(3), component(org, uniquePurl("a"), 0));
		mapBom(org, null, now.minusHours(1), component(org, uniquePurl("b"), 0));
		Mapped oldStamped = mapBom(org, null, now.minusHours(3), component(org, uniquePurl("c"), 0));
		stampFanOut(org, oldStamped.canonical(), now.minusHours(2));
		mapBom(other, null, now.minusHours(3), component(other, uniquePurl("d"), 0));

		assertEquals(1, artifactCanonicalMapRepository.countFanOutPendingOlderThan(org, now.minusHours(2)),
				"only the unstamped row older than the cutoff, of this org");
		assertEquals(2, artifactCanonicalMapRepository.countFanOutPendingOlderThan(org, now),
				"both unstamped rows once the cutoff is now");
		assertEquals(0, artifactCanonicalMapRepository.countFanOutPendingOlderThan(org, now.minusHours(4)));
	}

	// ---------------- the slice statement and the stamp check (TPF-137) ----------------

	/** A canonical with a chosen uuid holding the given components, mapped once in the org. */
	private UUID canonicalAt(UUID org, UUID canonical, SbomComponent... comps) {
		for (SbomComponent sc : comps) holdComponent(org, canonical, sc);
		mapRow(org, saveArtifactWithLastScanned(org, null).getUuid(), canonical, null);
		return canonical;
	}

	@Test
	public void theWalkAnswersTheChangedComponentsNotTheWholeBucket() {
		UUID org = newOrg();
		SbomComponent c0 = component(org, uniquePurl("b0"), 0);
		SbomComponent c1 = component(org, uniquePurl("b1"), 0);
		Mapped k0 = mapBom(org, null, null, c0);
		mapBom(org, null, null, c1);

		assertEquals(List.of(k0.canonical()), sweep(org, new UUID[] {c0.getUuid()}, 500),
				"the canonicals of the changed component only, not of every member of its bucket");
		assertTrue(sweep(org, new UUID[] {UUID.randomUUID()}, 500).isEmpty(), "an unknown component answers nothing");
	}

	@Test
	public void aNeverStampedCanonicalIsReturnedByTheStampCheck() {
		UUID org = newOrg();
		Mapped z = mapBom(org, null, null, component(org, uniquePurl("fresh"), 0));

		assertEquals(List.of(z.canonical()), artifactCanonicalMapRepository.findCanonicalsStampedBefore(
				org, List.of(z.canonical()), nowMicros().minusMinutes(2)), "the null arm");
	}

	@Test
	public void aStampedCurrentCanonicalIsNotReturnedByTheStampCheck() {
		UUID org = newOrg();
		Mapped x = mapBom(org, null, null, component(org, uniquePurl("current"), 0));
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		mapBom(org, null, null, component(org, uniquePurl("unrelated-pending"), null));
		stampFanOut(org, x.canonical(), t1);

		assertTrue(artifactCanonicalMapRepository.findCanonicalsStampedBefore(org, List.of(x.canonical()), t1).isEmpty(),
				"stamped at the drain's cutoff: current; the pending canonical elsewhere in the org is no reason to return it");
		assertTrue(artifactCanonicalMapRepository.findCanonicalsStampedBefore(org, List.of(x.canonical()), t1.minusSeconds(1))
				.isEmpty(), "stamped after the cutoff: current");
		assertEquals(List.of(x.canonical()), artifactCanonicalMapRepository.findCanonicalsStampedBefore(
				org, List.of(x.canonical()), t1.plusNanos(1000)), "stamped before the cutoff: needs the drain");
	}

	@Test
	public void theSliceStatementStopsAtTheRoomItIsGiven() {
		UUID org = newOrg();
		SbomComponent c = component(org, uniquePurl("many"), 0);
		for (int i = 0; i < 3; i++) canonicalAt(org, FanOutWalkTestSupport.inSlice(0x42), c);
		UUID[] changed = {c.getUuid()};
		UUID from = FanOutWalkSlice.startOf(0x42);
		UUID to = FanOutWalkSlice.endOf(0x42);

		assertEquals(3, fanOutWalkRepository.findChangedCanonicalsInSlice(org, from, to, changed, 500).size());
		assertEquals(2, fanOutWalkRepository.findChangedCanonicalsInSlice(org, from, to, changed, 2).size(),
				"no more than the room");
		assertEquals(1, fanOutWalkRepository.findChangedCanonicalsInSlice(org, from, to, changed, 1).size());
		assertEquals(3, fanOutWalkRepository.findCanonicalsInSlice(org, from, to, 500).size(), "full mode too");
		assertEquals(2, fanOutWalkRepository.findCanonicalsInSlice(org, from, to, 2).size());
	}

	@Test
	public void aCanonicalHoldingSeveralChangedComponentsIsReturnedOnce() {
		UUID org = newOrg();
		SbomComponent c1 = component(org, uniquePurl("one"), 0);
		SbomComponent c2 = component(org, uniquePurl("two"), 0);
		Mapped k = mapBom(org, null, null, c1, c2);

		assertEquals(List.of(k.canonical()), sweep(org, new UUID[] {c1.getUuid(), c2.getUuid()}, 500));
		assertEquals(List.of(k.canonical()), artifactCanonicalMapRepository.findCanonicalsStampedBefore(
				org, List.of(k.canonical(), k.canonical()), nowMicros()), "the stamp check is DISTINCT too");
	}

	@Test
	public void theSliceStatementAndTheStampCheckFollowOnlyTheOrgsOwnRows() {
		UUID org1 = newOrg();
		UUID org2 = newOrg();
		SbomComponent sc1 = component(org1, uniquePurl("shared"), 0);
		Mapped k1 = mapBom(org1, null, null, sc1);
		// a containment row of ANOTHER org holding org1's component
		UUID k2 = UUID.randomUUID();
		holdComponent(org2, k2, sc1);
		mapRow(org1, saveArtifactWithLastScanned(org1, null).getUuid(), k2, null);
		// a canonical that only org2 maps
		UUID k3 = UUID.randomUUID();
		holdComponent(org1, k3, sc1);
		mapRow(org2, saveArtifactWithLastScanned(org2, null).getUuid(), k3, null);

		List<UUID> found = sweep(org1, new UUID[] {sc1.getUuid()}, 500);
		assertTrue(found.contains(k1.canonical()));
		assertFalse(found.contains(k2), "a containment row of another org is not followed");
		assertTrue(artifactCanonicalMapRepository.findCanonicalsStampedBefore(org1, List.of(k3), nowMicros()).isEmpty(),
				"a map row of another org is not followed");
		assertTrue(sweep(org2, new UUID[] {sc1.getUuid()}, 500).equals(List.of(k2)),
				"org2 sees its own containment row only");
	}

	@Test
	public void aTerminalComponentsPurlIsNotRecorded() {
		UUID org = newOrg();
		String live = uniquePurl("live");
		String terminal = uniquePurl("terminal");
		SbomComponent liveC = component(org, live, 0);
		SbomComponent term = component(org, terminal, 0);
		assertEquals(1, sbomComponentRepository.markEnrichmentTerminal(term.getUuid(), "OWN_BOM_PULLED_UNMATCHED"));

		List<UUID> uuids = new ArrayList<>();
		for (SbomComponentRepository.ComponentUuidByPurl row : sbomComponentRepository
				.findUuidsByOrgAndCanonicalPurlIn(org, List.of(live, terminal, uniquePurl("absent")))) {
			assertEquals(live, row.getCanonicalPurl());
			uuids.add(row.getUuid());
		}
		assertEquals(List.of(liveC.getUuid()), uuids, "the non-terminal uuid only; an absent purl records nothing");
		assertTrue(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(newOrg(), List.of(live)).isEmpty(),
				"another org's purl resolves to nothing");
	}

	// ---------------- V102 ----------------

	@Test
	public void theNeverStampedIndexIsThePartialOneAndTheRetiredPoolIndexIsGone() {
		String def = jdbcTemplate.queryForObject("select indexdef from pg_indexes where schemaname = 'rearm'"
				+ " and indexname = 'artifact_canonical_map_fanout_pending_idx'", String.class);
		assertNotNull(def);
		assertTrue(def.contains("ON rearm.artifact_canonical_map"), def);
		assertTrue(def.contains("(org, created_date DESC)"), def);
		assertTrue(def.contains("WHERE ((flow_control ->> 'fanOutAt'::text) IS NULL)"), def);
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from pg_indexes where schemaname = 'rearm'"
				+ " and indexname = 'artifacts_org_fanout_pool_idx'", Integer.class), "V80's pool index is dropped");
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from pg_indexes where schemaname = 'rearm'"
				+ " and indexname = 'artifacts_org_last_scanned_idx'", Integer.class), "V74's index is kept");
	}
}
