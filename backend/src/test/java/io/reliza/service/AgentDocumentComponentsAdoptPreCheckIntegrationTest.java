/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaProfileService.TeaEffectiveVisibility;
import io.reliza.service.TeaProfileService.TeaExposureChange;
import io.reliza.service.TeaProfileService.TeaPublicPath;
import io.reliza.service.TeaPublicationService.TeaExposureState;

/**
 * Task TEA-26, the adopt path's pre-check on a legacy component that already has perspectives
 * (architecture round 3, 3.3, 3.4 and decision 3; test run 1, T-1 and T-2): the what-if is the stored
 * set plus the board's real perspectives, so a stored membership that makes the join a CONFLICT adopts;
 * and every adoption the pre-check refuses, whatever the component's stored perspectives and whether it
 * resolved PRIVATE or to a CONFLICT before, is refused with the board's text and its remedy, not with
 * the gate's text from under the lock; its remedy names only the board's perspectives the component is
 * not yet in (test run 2, T-4).
 */
public class AgentDocumentComponentsAdoptPreCheckIntegrationTest extends AgentDocumentComponentsTeaJoinTestBase {

	// TEA-26 row 10 (T-1): stored + real is a CONFLICT, so the adoption goes ahead
	@Test
	public void aStoredMembershipThatMakesTheJoinAConflictAdopts() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		UUID q = privatePerspective(a.r());
		componentService.setPerspectives(a.legacy(), List.of(q), TeaCaller.ORG_ADMIN, WU);
		assertEquals(TeaVisibility.PRIVATE, visibility(a.legacy()), "PRIVATE through q");
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()));
		// the board's set alone would widen: only the stored set makes the what-if a CONFLICT
		assertEquals(TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC,
				teaProfileService.assessMembership(component(a.legacy()), Set.of(a.p())).path());

		ReleaseData first = publishArchitecture(a.r());

		assertEquals(a.legacy(), first.getComponent(), "adopted");
		assertEquals(a.legacy(), board(a.r()).getDocumentComponents().get(ARCH), "and recorded");
		assertEquals(Set.of(a.p(), q), memberships(a.legacy()));
		assertNull(visibility(a.legacy()), "a CONFLICT");
		assertEquals(TeaExposureState.CONCEALED, exposure(a.pub()));
		assertTrue(widenings(a.legacy()).isEmpty(), logs.events.toString());
		assertEquals(1, adoptedLines(a.legacy()).size(), logs.events.toString());
	}

	// TEA-26 row 11 (T-2): a component with stored perspectives is refused with the board's text
	@Test
	public void aLegacyComponentWithStoredPerspectivesIsRefusedWithTheBoardsText() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		// a perspective with no TEA row: the component resolves PRIVATE through the organization row
		UUID r0 = perspectiveService.createPerspective(a.r().org().getUuid(), "tea26-norow-" + UUID.randomUUID(), WU)
				.getUuid();
		componentService.setPerspectives(a.legacy(), List.of(r0), TeaCaller.ORG_ADMIN, WU);
		assertEquals(TeaVisibility.PRIVATE, visibility(a.legacy()));
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()));

		RelizaException refused = assertThrows(RelizaException.class, () -> publishArchitecture(a.r()));

		assertAdoptRefusal(a, refused);
		assertNotAdopted(a, Set.of(r0), 1);
	}

	// TEA-26 row 12 (T-2): a CONFLICT before is not PUBLIC; the join that makes it PUBLIC is refused with the board's text
	@Test
	public void aLegacyComponentResolvingToAConflictIsRefusedWithTheBoardsText() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		UUID q1 = offBoardPerspective(a.r(), TeaVisibility.PRIVATE);
		UUID q2 = offBoardPerspective(a.r(), TeaVisibility.PUBLIC);
		// an org admin's FOLLOW of p, saved while the component was a member of p, which it then left
		componentService.setPerspectives(a.legacy(), List.of(q1, q2, a.p()), TeaCaller.ORG_ADMIN, WU);
		teaProfileService.saveProfile(a.r().org().getUuid(), TeaProfileScope.COMPONENT, a.legacy(),
				TeaProfileServiceTestBase.full().toBuilder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE)
						.followedPerspective(a.p()).publishing(TeaPublishing.ENABLED).profiles(null).build(),
				TeaCaller.ORG_ADMIN, WU);
		componentService.setPerspectives(a.legacy(), List.of(q1, q2), TeaCaller.ORG_ADMIN, WU);
		assertNull(visibility(a.legacy()), "a CONFLICT before: the FOLLOW falls through to q1 and q2");
		TeaExposureState exposedBefore = exposure(a.pub());
		assertTrue(TeaExposureState.PUBLIC != exposedBefore, String.valueOf(exposedBefore));
		TeaExposureChange x = teaProfileService.assessMembership(component(a.legacy()), Set.of(q1, q2, a.p()));
		assertEquals(TeaEffectiveVisibility.UNRESOLVED, x.before());
		assertEquals(TeaEffectiveVisibility.PUBLIC, x.after(), "the join makes the FOLLOW resolve PUBLIC");

		RelizaException refused = assertThrows(RelizaException.class, () -> publishArchitecture(a.r()));

		assertAdoptRefusal(a, refused);
		assertNotAdopted(a, Set.of(q1, q2), 1, null, exposedBefore);
	}

	// TEA-26 row 13 (T-4): the remedy names only the board's perspectives the component is not yet in
	@Test
	public void theRemedyNamesOnlyTheBoardsPerspectivesTheComponentIsNotYetIn() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		// a real perspective with no TEA row, on the board ahead of p: the board's set is {r0, p}
		UUID r0 = perspectiveService.createPerspective(org.getUuid(), "tea26-norow-" + UUID.randomUUID(), WU).getUuid();
		Adopting a = adopting(org, TeaVisibility.PUBLIC, List.of(r0));
		assertEquals(List.of(r0, a.p()), boardPerspectiveService.realPerspectives(board(a.r())));
		// the legacy component is already in r0: PRIVATE through the organization row
		componentService.setPerspectives(a.legacy(), List.of(r0), TeaCaller.ORG_ADMIN, WU);
		assertEquals(TeaVisibility.PRIVATE, visibility(a.legacy()));
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()));

		RelizaException refused = assertThrows(RelizaException.class, () -> publishArchitecture(a.r()));

		assertAdoptRefusal(a, refused, List.of(a.p()));
		String remedy = refused.getMessage().substring(refused.getMessage().indexOf("have one add the component to "));
		assertFalse(remedy.contains(perspectiveName(r0)), "r0 already holds the component: " + remedy);
		assertNotAdopted(a, Set.of(r0), 1);
	}
}
