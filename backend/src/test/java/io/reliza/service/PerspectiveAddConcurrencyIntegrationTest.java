package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reliza.exceptions.RelizaException;

/**
 * TPF-40: PerspectiveService.addPerspectiveToComponent applies its one-perspective delta to the
 * stored set under the component row lock, re-read (TPF-38's changePerspectives), so concurrent
 * adds keep each other's perspective and an add of a stored perspective writes nothing. Rows 12 to
 * 14 of the TPF-40 architecture, section 4.2; the resolver's checks are rows 1 to 11 in
 * io.reliza.ws.PerspectiveAddIntegrationTest. The writers call the service because the rig runs
 * them on threads with no security context.
 */
class PerspectiveAddConcurrencyIntegrationTest extends ComponentConcurrentWriteTestBase {

	private void add(UUID perspective) throws RelizaException {
		perspectiveService.addPerspectiveToComponent(c, perspective, TeaProfileService.TeaCaller.ORG_ADMIN, WU);
	}

	@Test
	@Timeout(60)
	void row12_twoAddsOfDifferentPerspectives_bothStay() throws Exception {
		int rev = revision(c);
		int aud = audits(c);
		held(() -> add(p), () -> add(q));
		assertEquals(Set.of(p, q), stored().getPerspectives());
		assertEquals(rev + 2, revision(c));
		assertEquals(aud + 2, audits(c));
	}

	@Test
	@Timeout(60)
	void row13_twoAddsOfTheSamePerspective_writeOnce() throws Exception {
		int rev = revision(c);
		int aud = audits(c);
		held(() -> add(p), () -> add(p));
		assertEquals(Set.of(p), stored().getPerspectives());
		assertEquals(rev + 1, revision(c));
		assertEquals(aud + 1, audits(c));
	}

	@Test
	@Timeout(60)
	void row14_anAddAfterAPreReadKeepsTheCommittedSet() throws Exception {
		int rev = revision(c);
		int aud = audits(c);
		preRead(() -> componentService.setPerspectives(c, Set.of(q), WU), seen -> add(p));
		assertEquals(Set.of(q, p), stored().getPerspectives());
		assertEquals(rev + 2, revision(c));
		assertEquals(aud + 2, audits(c));
	}

	// TEA-14 architecture row 25: an add and a removal through the gated writers, started together on a
	// component holding {P}: neither puts back what the other removed nor drops what the other added
	@Test
	@Timeout(60)
	void row25_aConcurrentAddAndRemoveBothLand() throws Exception {
		componentService.setPerspectives(c, Set.of(p), WU);
		held(() -> add(q),
				() -> componentService.changePerspectives(c, Set.of(), Set.of(p), TeaProfileService.TeaCaller.ORG_ADMIN, WU));
		assertEquals(Set.of(q), stored().getPerspectives());
	}
}
