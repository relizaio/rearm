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
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaExposureState;

/**
 * Task TEA-14, decision 3 and the functional reviewer's F-1 (architecture rows 46 to 48): a board's
 * document publish joins the board's real perspectives through the gated changePerspectives. Into a
 * PUBLIC perspective a created component is joined with the SYSTEM standing (TEA-9 round 2's creation
 * acceptance) and a WARN line traces the widening; an archived legacy component whose join would widen
 * refuses the publish, and nothing is recorded.
 *
 * <p>Task TEA-26 (architecture round 3, 5.1 rows 1 to 9): a board from before the map no longer adopts
 * a legacy component into a PUBLIC perspective when that makes it resolve PUBLIC; the publish is refused
 * with the board's text and nothing is written, an organization admin adding the component to the
 * perspective unblocks it, every adoption that widens nothing adopts as before, and the adopt path's
 * join meets the gate under the lock with the SCOPE_ADMIN standing. The pre-check's what-if over the
 * stored set and the refusals of a component with stored perspectives are in
 * AgentDocumentComponentsAdoptPreCheckIntegrationTest; the fixtures are in the shared base.
 */
public class AgentDocumentComponentsTeaJoinIntegrationTest extends AgentDocumentComponentsTeaJoinTestBase {

	// row 46
	@Test
	public void aFirstPublishIntoABoardWhosePerspectiveIsPublicCreatesAndJoinsTheComponent() throws RelizaException {
		Rig r = rig();
		UUID p = publicPerspective(r);

		UUID created = publishArchitecture(r).getComponent();

		assertEquals(Set.of(p), memberships(created));
		assertEquals(TeaVisibility.PUBLIC, visibility(created));
		assertEquals(1, widenings(created).size(), logs.events.toString());
	}

	// TEA-26 row 1 (replaces TEA-14 row 47): the reviewer's case
	@Test
	public void aBoardFromBeforeTheMapRefusesToAdoptALegacyComponentThatWouldResolvePublic() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		Set<UUID> before = memberships(a.legacy());
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()), "private before the publish");

		RelizaException refused = assertThrows(RelizaException.class, () -> publishArchitecture(a.r()));

		assertAdoptRefusal(a, refused);
		assertNotAdopted(a, before, 1);
	}

	// TEA-26 row 2: the order of composition does not matter (followSetChange judges recorded components only)
	@Test
	public void aLegacyComponentComposedBeforeTheBoardsLastSetChangeIsRefusedTheSameWay() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		Rig r = legacy(rig(org, target, "Adopting " + UUID.randomUUID()));
		UUID legacy = legacyComponent(target, org);
		UUID p = publicPerspective(r);
		Adopting a = new Adopting(r, p, legacy, privatePublication(r, legacy));
		Set<UUID> before = memberships(legacy);
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()));

		RelizaException refused = assertThrows(RelizaException.class, () -> publishArchitecture(r));

		assertAdoptRefusal(a, refused);
		assertNotAdopted(a, before, 1);
	}

	// TEA-26 row 3: the admin act through the gated set path unblocks the adoption
	@Test
	public void anOrganizationAdminAddingTheComponentToThePerspectiveUnblocksTheAdoption() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		assertAdoptRefusal(a, assertThrows(RelizaException.class, () -> publishArchitecture(a.r())));

		perspectiveService.addPerspectiveToComponent(a.legacy(), a.p(), TeaCaller.ORG_ADMIN, WU);
		Set<UUID> afterAdmin = memberships(a.legacy());
		assertTrue(afterAdmin.contains(a.p()), afterAdmin.toString());
		assertEquals(TeaExposureState.PUBLIC, exposure(a.pub()), "PUBLIC by the admin's act");

		ReleaseData first = publishArchitecture(a.r());

		assertEquals(a.legacy(), first.getComponent(), "adopted");
		assertEquals(a.legacy(), board(a.r()).getDocumentComponents().get(ARCH), "and recorded");
		assertEquals(afterAdmin, memberships(a.legacy()), "the publish changed no membership");
		assertTrue(widenings(a.legacy()).isEmpty(), logs.events.toString());
		assertEquals(1, adoptedLines(a.legacy()).size(), "the refused publish logged none, the adoption one: " + logs.events);
	}

	// TEA-26 row 4
	@Test
	public void aLegacyComponentJoiningANonPublicPerspectiveAdoptsAsToday() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PRIVATE);

		ReleaseData first = publishArchitecture(a.r());

		assertEquals(a.legacy(), first.getComponent(), "adopted");
		assertEquals(a.legacy(), board(a.r()).getDocumentComponents().get(ARCH), "and recorded");
		assertTrue(memberships(a.legacy()).contains(a.p()), memberships(a.legacy()).toString());
		assertEquals(TeaVisibility.PRIVATE, visibility(a.legacy()));
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()));
		assertTrue(widenings(a.legacy()).isEmpty(), logs.events.toString());
		assertEquals(1, adoptedLines(a.legacy()).size(), "one adopted line, after the join: " + logs.events);
	}

	// TEA-26 row 5: nothing becomes more public than it is
	@Test
	public void aLegacyComponentAlreadyPublicAdoptsAsToday() throws RelizaException {
		// its own OVERRIDE row is PUBLIC
		Adopting a = adopting(TeaVisibility.PUBLIC);
		teaProfileService.saveProfile(a.r().org().getUuid(), TeaProfileScope.COMPONENT, a.legacy(),
				TeaProfileServiceTestBase.full().toBuilder().mode(TeaComponentProfileMode.OVERRIDE)
						.publishing(TeaPublishing.ENABLED).visibility(TeaVisibility.PUBLIC).build(),
				TeaCaller.ORG_ADMIN, WU);
		assertEquals(TeaExposureState.PUBLIC, exposure(a.pub()), "PUBLIC before the publish");

		ReleaseData first = publishArchitecture(a.r());

		assertEquals(a.legacy(), first.getComponent(), "adopted");
		assertEquals(a.legacy(), board(a.r()).getDocumentComponents().get(ARCH), "and recorded");
		assertTrue(memberships(a.legacy()).contains(a.p()), memberships(a.legacy()).toString());
		assertTrue(widenings(a.legacy()).isEmpty(), logs.events.toString());

		// already a member of the PUBLIC perspective: the join's delta is empty
		Adopting member = adopting(TeaVisibility.PUBLIC);
		componentService.setPerspectives(member.legacy(), List.of(member.p()), TeaCaller.ORG_ADMIN, WU);
		assertEquals(TeaExposureState.PUBLIC, exposure(member.pub()));

		ReleaseData adopted = publishArchitecture(member.r());

		assertEquals(member.legacy(), adopted.getComponent(), "adopted");
		assertEquals(member.legacy(), board(member.r()).getDocumentComponents().get(ARCH), "and recorded");
		assertEquals(Set.of(member.p()), memberships(member.legacy()));
		assertTrue(widenings(member.legacy()).isEmpty(), logs.events.toString());
	}

	// TEA-26 row 6: only after == PUBLIC refuses, at the pre-check and at the gate alike
	@Test
	public void aJoinThatMakesAConflictAdoptsAsToday() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		UUID q = publicPerspective(a.r(), TeaVisibility.PRIVATE, List.of(a.p()));

		ReleaseData first = publishArchitecture(a.r());

		assertEquals(a.legacy(), first.getComponent(), "adopted");
		assertEquals(a.legacy(), board(a.r()).getDocumentComponents().get(ARCH), "and recorded");
		assertEquals(Set.of(a.p(), q), memberships(a.legacy()));
		assertNull(visibility(a.legacy()), "a CONFLICT");
		assertEquals(TeaExposureState.CONCEALED, exposure(a.pub()));
		assertTrue(widenings(a.legacy()).isEmpty(), logs.events.toString());
	}

	// row 48 (TEA-14), TEA-26 row 8
	@Test
	public void anArchivedLegacyComponentWhoseJoinWouldWidenRefusesThePublish() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		UUID legacy = legacyComponent(target, org);
		Rig r = legacy(rig(org, target, "Archived " + UUID.randomUUID()));
		publicPerspective(r);
		componentService.archiveComponent(legacy, WU);
		Set<UUID> before = memberships(legacy);

		RelizaException refused = assertThrows(RelizaException.class, () -> publishArchitecture(r));

		assertTrue(refused.getMessage().startsWith("Component " + component(legacy).getName()
				+ " is archived: with perspectives"), refused.getMessage());
		assertTrue(refused.getMessage().endsWith("an archived component cannot widen its TEA exposure"),
				refused.getMessage());
		assertFalse(refused.getMessage().startsWith("adopting component"),
				"the archived rule fires inside the assessment, before the widening test");
		var map = board(r).getDocumentComponents();
		assertNull(null == map ? null : map.get(ARCH), "nothing recorded in the board's map");
		assertTrue(sharedReleaseService.listReleaseDatasOfComponent(legacy, 10, 0).isEmpty(), "no release");
		assertEquals(before, memberships(legacy), "the component's perspectives are unchanged");
		assertTrue(widenings(legacy).isEmpty(), logs.events.toString());
	}

	// TEA-26 row 9 (security F-4): pins the adopt path's standing at the gate under the lock, not the race
	@Test
	public void theAdoptPathJoinIsRefusedUnderTheLockWhenTheWindowWasOpened() throws RelizaException {
		Adopting a = adopting(TeaVisibility.PUBLIC);
		UUID q = privatePerspective(a.r());
		componentService.setPerspectives(a.legacy(), List.of(q), TeaCaller.ORG_ADMIN, WU);
		// the first state: PRIVATE through q, and the pre-check's what-if is a CONFLICT, which would pass
		assertEquals(TeaVisibility.PRIVATE, visibility(a.legacy()));
		assertEquals(TeaExposureState.PRIVATE, exposure(a.pub()));
		assertEquals(TeaProfileService.TeaPublicPath.NONE,
				teaProfileService.assessMembership(component(a.legacy()), Set.of(a.p(), q)).path());

		// the window: a non-admin act that widens nothing on its own
		perspectiveService.setPerspectivesOnComponent(a.legacy(), List.of(), TeaCaller.SCOPE_ADMIN, WU);
		assertTrue(memberships(a.legacy()).isEmpty(), memberships(a.legacy()).toString());

		RelizaException locked = assertThrows(RelizaException.class,
				() -> boardPerspectiveService.joinAdoptedDocumentComponent(board(a.r()), a.legacy(), WU));

		String m = locked.getMessage();
		assertTrue(m.startsWith("Only an organization admin can change the perspectives of component "
				+ component(a.legacy()).getName()), m);
		assertTrue(m.contains("PUBLIC TEA profile of perspective " + perspectiveName(a.p())), m);
		assertNotAdopted(a, Set.of(), 1);

		// the retry reads the changed state at the pre-check and answers the board's text
		assertAdoptRefusal(a, assertThrows(RelizaException.class, () -> publishArchitecture(a.r())));
		assertNotAdopted(a, Set.of(), 1);
	}
}
