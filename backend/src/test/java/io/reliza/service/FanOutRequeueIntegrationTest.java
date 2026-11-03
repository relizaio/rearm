/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.SbomComponent;
import io.reliza.ws.App;

/**
 * TPF-114 architecture 4.8 (b) and 4.11 (a) and (c), on the test database: every stamp and
 * requeue statement is org-scoped; a gate-held canonical is requeued when its blocker turns
 * enrichment-terminal (F-1); and the stamp's precondition refuses a pass that started before
 * a requeue, pending map rows included (F-4). The database clock is read through the real
 * repository method (F-6), never a stub.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutRequeueIntegrationTest extends FanOutCandidateTestBase {

	/** 4.8 (b): the same canonical uuid in two orgs; stamps and requeues never cross. */
	@Test
	public void stampsAndRequeuesAreOrgScoped() {
		UUID org1 = newOrg();
		UUID org2 = newOrg();
		UUID k = UUID.randomUUID();
		Artifact a1 = saveArtifactWithLastScanned(org1, null);
		Artifact a2 = saveArtifactWithLastScanned(org2, null);
		mapRow(org1, a1.getUuid(), k, null);
		mapRow(org2, a2.getUuid(), k, null);
		String stamp = iso(nowMicros().minusMinutes(5));

		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org1, k, stamp, FAR_PAST));
		assertNull(fanOutAtOf(a2.getUuid()), "org1's stamp must not reach org2's row");

		String purl = uniquePurl("org2-only");
		SbomComponent org2Comp = component(org2, purl, 0);
		holdComponent(org2, k, org2Comp);
		bucketAt(org2, 0, nowMicros().minusMinutes(1), purl);
		assertTrue(sweep(org1, new UUID[] {org2Comp.getUuid()}, 500).isEmpty(),
				"org2's components never surface in org1's slice statement");

		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org2, k, stamp, FAR_PAST));
		assertEquals(0, artifactCanonicalMapRepository.requeueFanOutForComponents(org1, List.of(org2Comp.getUuid())),
				"the org-scoped subquery names no org1 row");
		assertEquals(stamp, fanOutAtOf(a2.getUuid()));
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org1, k),
				"org1's own row only, now pending again");
		assertNull(fanOutAtOf(a1.getUuid()));
		assertEquals(stamp, fanOutAtOf(a2.getUuid()), "org2's row keeps its stamp");
	}

	/** 4.11 (a): the reviewer's recipe, with the negative control. */
	@Test
	public void aGateHeldCanonicalIsRequeuedWhenItsBlockerTurnsTerminal() {
		UUID org = newOrg();
		String aPurl = uniquePurl("a");
		SbomComponent a = component(org, aPurl, 0);
		SbomComponent y = unbucketedComponent(org, uniquePurl("y"));
		SbomComponent z = unbucketedComponent(org, uniquePurl("z"));
		Mapped g = mapBom(org, null, null, a, y);
		Mapped h = mapBom(org, null, null, a, z);
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		bucketAt(org, 0, t1, aPurl);

		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t1), fanOutAtOf(g.artifact()), "a gate skip is stamped");
		assertNull(lastScannedOf(g.artifact()), "and writes nothing to the artifact");
		assertEquals(iso(t1), fanOutAtOf(h.artifact()));

		// What W1 runs: the mark, then the requeue of the canonicals holding the marked row.
		assertEquals(1, sbomComponentRepository.markEnrichmentTerminal(y.getUuid(), "OWN_BOM_PULLED_UNMATCHED"));
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForComponents(org, List.of(y.getUuid())));
		assertNull(fanOutAtOf(g.artifact()));
		assertNotNull(Instant.parse(flowControlOf(g.artifact()).fanOutRequeuedAt()));
		// Negative control: the mark alone, no requeue (round 1's regression).
		assertEquals(1, sbomComponentRepository.markEnrichmentTerminal(z.getUuid(), "OWN_BOM_PULLED_UNMATCHED"));

		syntheticSbomService.fanOutOrg(org);

		assertNotNull(lastScannedOf(g.artifact()), "the requeued canonical is fanned out on the next tick");
		assertEquals(iso(t1), fanOutAtOf(g.artifact()));
		assertNull(lastScannedOf(h.artifact()),
				"without the requeue no bucket moved, so the canonical is never fanned out again");
	}

	/** 4.11 (c): a pass that started before a requeue cannot stamp over it. */
	@Test
	public void aStampFromAPassThatStartedBeforeTheRequeueWritesNothing() {
		UUID org = newOrg();
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		Mapped q = mapBom(org, null, null, component(org, uniquePurl("q"), 0));
		assertEquals(1, stampFanOut(org, q.canonical(), t1));

		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org, q.canonical()));
		assertEquals(0, artifactCanonicalMapRepository.stampFanOutPass(org, q.canonical(), iso(t1),
				ZonedDateTime.now().minusHours(1)));
		assertNull(fanOutAtOf(q.artifact()));
		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org, q.canonical(), iso(t1),
				artifactCanonicalMapRepository.dbClockTimestamp()));
		assertEquals(iso(t1), fanOutAtOf(q.artifact()));
	}

	/** 4.11 (c), the pending-row case (F-4): the requeue marks a row no pass has stamped yet. */
	@Test
	public void aRequeueMarksAPendingRowSoTheRunningPassCannotStampIt() {
		UUID org = newOrg();
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		Mapped p = mapBom(org, null, null, component(org, uniquePurl("p"), 0));
		assertNull(flowControlOf(p.artifact()), "a new map row has flow_control NULL");

		ZonedDateTime passStartedAt = artifactCanonicalMapRepository.dbClockTimestamp();
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForCanonical(org, p.canonical()),
				"the pending row is marked, not skipped");
		assertNotNull(Instant.parse(flowControlOf(p.artifact()).fanOutRequeuedAt()));
		assertNull(fanOutAtOf(p.artifact()));
		assertEquals(0, artifactCanonicalMapRepository.stampFanOutPass(org, p.canonical(), iso(t1), passStartedAt));
		assertNull(fanOutAtOf(p.artifact()));
		assertTrue(neverFannedOut(org).contains(p.canonical()));

		// The component form.
		SbomComponent w = unbucketedComponent(org, uniquePurl("w"));
		Mapped p2 = mapBom(org, null, null, w);
		assertNull(flowControlOf(p2.artifact()));
		ZonedDateTime pass2StartedAt = artifactCanonicalMapRepository.dbClockTimestamp();
		assertEquals(1, artifactCanonicalMapRepository.requeueFanOutForComponents(org, List.of(w.getUuid())));
		assertEquals(0, artifactCanonicalMapRepository.stampFanOutPass(org, p2.canonical(), iso(t1), pass2StartedAt));
		assertEquals(1, artifactCanonicalMapRepository.stampFanOutPass(org, p2.canonical(), iso(t1),
				artifactCanonicalMapRepository.dbClockTimestamp()));
	}
}
