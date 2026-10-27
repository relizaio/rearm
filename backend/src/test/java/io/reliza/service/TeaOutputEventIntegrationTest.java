/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.saas.ApprovalPolicyDto;
import io.reliza.service.ReleaseTriggerHook.TriggerPass;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;

/**
 * TEA-7, section 4.3 cases 19 to 23, 26, 27 and 32, against the test database: an approval or a
 * lifecycle change fires a TEA action, the pass only records the act, and the worker run from
 * the test thread publishes or hides. Cases 24, 25, 28 and 30 (retries, refusals, the cascade, a
 * crashed worker) are TeaOutputEventRetryIntegrationTest's; the real hop is
 * TeaOutputEventHopIntegrationTest's.
 */
public class TeaOutputEventIntegrationTest extends TeaOutputEventQueueIntegrationTestBase {

	private List<String> teaEvents(UUID release) {
		List<String> out = new ArrayList<>();
		for (ReleaseUpdateEvent e : events(release, ReleaseUpdateScope.TEA_PUBLICATION)) {
			out.add(e.rua() + " " + e.oldValue() + " -> " + e.newValue());
		}
		return out;
	}

	private ReleaseUpdateEvent lastTeaEvent(UUID release) {
		List<ReleaseUpdateEvent> e = events(release, ReleaseUpdateScope.TEA_PUBLICATION);
		return e.get(e.size() - 1);
	}

	// ---------------------------------------------------------------- 19

	@Test
	void anApprovalPublishesVersionOneAfterThePass() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);

		approve(w.release(), entry);

		assertTrue(head(w.release()).isEmpty(), "nothing ran inside the pass");
		FlowControl fc = teaAct(w.release());
		assertNotNull(fc.teaActRequestedAt());
		assertEquals(TeaActKind.PUBLISH, fc.teaActKind());
		assertEquals(publish.getUuid(), fc.teaActTrigger());
		assertEquals("publish-on-approval", fc.teaActTriggerName());
		assertEquals("Action 'publish-on-approval' fired by rule 'approved'", fc.teaActComment());
		assertNull(fc.teaActClaimedAt());
		assertEquals(1, triggerRows(w.release(), publish.getUuid()).size());
		verify(teaActDispatcher).asyncRun(w.release());

		teaOutputEventService.runRequested(w.release());

		TeaPublicationData p = head(w.release()).orElseThrow();
		assertEquals(TeaPublicationState.PUBLISHED, p.getState());
		assertEquals(1, p.getLatestVersion());
		assertNull(p.getLastPublishedBy(), "published by the system, not a person");
		assertEquals(1, collectionRepository.listByPublication(p.getUuid()).size());
		assertEquals(List.of("ADDED null -> v1"), teaEvents(w.release()));
		ReleaseUpdateEvent added = lastTeaEvent(w.release());
		assertEquals(fc.teaActComment(), added.message());
		assertEquals(ProgrammaticType.AUTO, added.wu().getCreatedType());
		assertTrue(noTeaActKey(w.release()), "every teaAct key is gone");
		assertTrue(events(w.release(), ReleaseUpdateScope.GUARD).isEmpty());
	}

	// ---------------------------------------------------------------- 20

	@Test
	void aReevaluationDoesNotRepublish() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		approve(w.release(), entry);
		teaOutputEventService.runRequested(w.release());
		assertEquals(1, head(w.release()).orElseThrow().getLatestVersion());

		ossReleaseService.processRelease(w.release(), TriggerPass.REEVALUATION);

		assertFalse(queued(w.release()), "no new request");
		assertEquals(1, triggerRows(w.release(), publish.getUuid()).size(), "no new TRIGGER row");
		assertEquals(List.of("ADDED null -> v1"), teaEvents(w.release()));
	}

	// ---------------------------------------------------------------- 21

	@Test
	void aMatchingPassRequestsAgainAndOnlyChangedContentMakesAVersion() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		approve(w.release(), entry);
		teaOutputEventService.runRequested(w.release());

		ossReleaseService.processRelease(w.release());
		assertTrue(queued(w.release()), "a NORMAL pass whose rule matches asks again");
		teaOutputEventService.runRequested(w.release());
		assertEquals(1, head(w.release()).orElseThrow().getLatestVersion(), "UNCHANGED");
		assertEquals(List.of("ADDED null -> v1"), teaEvents(w.release()), "no new TEA_PUBLICATION event");
		assertEquals(1, triggerRows(w.release(), publish.getUuid()).size(), "the TRIGGER row is recorded once");
		assertTrue(noTeaActKey(w.release()));

		UUID second = seedBom("tea-7-second.cdx.json");
		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		releaseService.addArtifact(second, w.release(), WU);
		assertTrue(queued(w.release()), "the artifact save ran the pass");
		teaOutputEventService.runRequested(w.release());

		assertEquals(2, head(w.release()).orElseThrow().getLatestVersion(), "REPUBLISHED");
		assertEquals(List.of("ADDED null -> v1", "CHANGED v1 -> v2"), teaEvents(w.release()));
		assertEquals("Action 'publish-on-approval' fired by rule 'approved'", lastTeaEvent(w.release()).message());
	}

	// ---------------------------------------------------------------- 22

	@Test
	void aLifecycleRuleHidesOnce() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		ReleaseOutputEvent hide = teaAction(EventType.TEA_HIDE, "hide-on-reject");
		configure(w.component(), rule("rejected", "release.lifecycle == \"REJECTED\"", hide), hide);

		ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.REJECTED, WU);

		assertEquals(TeaActKind.HIDE, teaAct(w.release()).teaActKind());
		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertEquals("REMOVED PUBLISHED v1 -> HIDDEN", teaEvents(w.release()).get(1));
		assertEquals("Action 'hide-on-reject' fired by rule 'rejected'", lastTeaEvent(w.release()).message());
		assertTrue(noTeaActKey(w.release()));

		ossReleaseService.processRelease(w.release());
		assertFalse(queued(w.release()), "a hide fires once");
		assertEquals(1, triggerRows(w.release(), hide.getUuid()).size());

		// 38 (round 2, T-2): a publish rule on the lifecycle that still matches is withheld
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-rejected");
		configure(w.component(), List.of(rule("rejected", "release.lifecycle == \"REJECTED\"", hide),
				rule("publish while rejected", "release.lifecycle == \"REJECTED\"", publish)), hide, publish);
		ossReleaseService.processRelease(w.release());
		assertFalse(queued(w.release()), "no request");
		assertTrue(triggerRows(w.release(), publish.getUuid()).isEmpty());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		List<ReleaseUpdateEvent> note = withheld(w.release());
		assertEquals(1, note.size());
		assertEquals("Action 'publish-rejected': withheld while action 'hide-on-reject' matches on rule 'rejected'",
				note.get(0).message());
	}

	// ---------------------------------------------------------------- 23

	@Test
	void aHideWithNothingToHideStaysArmedUntilThereIs() throws Exception {
		// The rule of 22 cannot be used here: a REJECTED release can never be published afterwards
		// (LIFECYCLE_TOO_LOW). A disapproval leaves the release ASSEMBLED and publishable.
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent hide = teaAction(EventType.TEA_HIDE, "hide-on-disapproval");
		configure(w.component(), rule("disapproved", disapproved(entry), hide), hide);

		vote(w.release(), entry, ApprovalState.DISAPPROVED);

		assertFalse(queued(w.release()), "never published: nothing to hide");
		assertTrue(triggerRows(w.release(), hide.getUuid()).isEmpty(), "still armed");
		assertTrue(events(w.release(), ReleaseUpdateScope.GUARD).isEmpty());
		verify(teaActDispatcher, never()).asyncRun(any());

		publish(w.release(), TeaPublishMode.PUBLISH);
		ossReleaseService.processRelease(w.release());
		assertEquals(TeaActKind.HIDE, teaAct(w.release()).teaActKind());
		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertEquals(1, triggerRows(w.release(), hide.getUuid()).size());

		// 38 (round 2, T-2): a matching publish rule on a further NORMAL pass is withheld
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-assembled");
		configure(w.component(), List.of(rule("disapproved", disapproved(entry), hide),
				rule("assembled", "release.lifecycle == \"ASSEMBLED\"", publish)), hide, publish);
		ossReleaseService.processRelease(w.release());
		assertFalse(queued(w.release()), "vetoed");
		assertTrue(triggerRows(w.release(), publish.getUuid()).isEmpty());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertEquals(1, withheld(w.release()).size());
	}

	// ---------------------------------------------------------------- 26

	@Test
	void aComponentThatDoesNotPublishIsSkippedSilently() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		saveComponentProfile(w.component(), TeaPublishing.DISABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE);
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);

		approve(w.release(), entry);

		assertFalse(queued(w.release()));
		assertTrue(triggerRows(w.release(), publish.getUuid()).isEmpty());
		assertTrue(events(w.release(), ReleaseUpdateScope.GUARD).isEmpty(), "an opt-out is not a refusal to note");

		teaProfileService.deleteProfile(org.getUuid(), TeaProfileScope.COMPONENT, w.component(), TeaCaller.ORG_ADMIN, WU);
		ossReleaseService.processRelease(w.release());
		assertTrue(queued(w.release()));
		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.PUBLISHED, head(w.release()).orElseThrow().getState());
	}

	// ---------------------------------------------------------------- 27

	@Test
	void aGlobalPolicyActionPublishes() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		UUID policy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("tea-7-" + UUID.randomUUID()).approvalEntries(List.of(entry)).build(), WU).getUuid();
		ReleaseOutputEvent global = teaAction(EventType.TEA_PUBLISH, "global-publish");
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(global)), WU);
		ReleaseInputEvent globalRule = rule("approved globally", approved(entry), global);
		approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(List.of(globalRule)), WU);
		componentService.updateComponent(ComponentDto.builder().uuid(w.component()).approvalPolicy(policy).build(), WU);

		approve(w.release(), entry);

		assertEquals(global.getUuid(), teaAct(w.release()).teaActTrigger());
		teaOutputEventService.runRequested(w.release());
		assertEquals(1, head(w.release()).orElseThrow().getLatestVersion());
		assertEquals("Action 'global-publish' fired by rule 'approved globally'", lastTeaEvent(w.release()).message());
	}

	// ---------------------------------------------------------------- 32

	@Test
	void aRuleThatPublishesAndHidesHides() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish");
		ReleaseOutputEvent hide = teaAction(EventType.TEA_HIDE, "hide");
		configure(w.component(), rule("both", approved(entry), publish, hide), publish, hide);

		approve(w.release(), entry);

		assertEquals(TeaActKind.HIDE, teaAct(w.release()).teaActKind());
		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertEquals(1, triggerRows(w.release(), hide.getUuid()).size());
		assertTrue(triggerRows(w.release(), publish.getUuid()).isEmpty(), "the publish was pruned from the pass");
		assertEquals(ReleaseUpdateAction.REMOVED, lastTeaEvent(w.release()).rua());
		assertEquals(1, withheld(w.release()).size(), "round 2: the in-pass prune is noted");

		// 38 (round 2, T-2): a second NORMAL pass with rule 'both' still matching
		ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.READY_TO_SHIP, WU);
		assertFalse(queued(w.release()));
		assertEquals(1, triggerRows(w.release(), hide.getUuid()).size(), "the hide is spent");
		assertTrue(triggerRows(w.release(), publish.getUuid()).isEmpty(), "the publish is withheld");
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertEquals(1, withheld(w.release()).size(), "the note once");
	}

	// ---------------------------------------------------------------- 37 (round 2, T-2)

	@Test
	void aMatchingHideRuleHoldsAcrossPasses() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		ReleaseInputEvent hideRule = rule("security disapproved", disapproved(e2), h);
		configure(w.component(), List.of(rule("qa approved", approved(e1), p), hideRule), p, h);

		approve(w.release(), e1);
		teaOutputEventService.runRequested(w.release());
		assertEquals(1, head(w.release()).orElseThrow().getLatestVersion());

		vote(w.release(), e2, ApprovalState.DISAPPROVED);
		assertEquals(TeaActKind.HIDE, teaAct(w.release()).teaActKind());
		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());

		// the B9 replay: an SBOM upload is a NORMAL pass, and 'p' still matches
		UUID second = seedBom("tea-7-37-second.cdx.json");
		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		releaseService.addArtifact(second, w.release(), WU);
		assertFalse(queued(w.release()), "no request while the hide rule matches");
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertEquals(1, triggerRows(w.release(), p.getUuid()).size(), "no new TRIGGER row for the publish");
		List<ReleaseUpdateEvent> note = withheld(w.release());
		assertEquals(1, note.size());
		assertEquals(p.getUuid(), note.get(0).objectId());
		assertEquals("p", note.get(0).oldValue());
		assertEquals("Action 'p': withheld while action 'h' matches on rule 'security disapproved'", note.get(0).message());
		assertEquals(ProgrammaticType.AUTO, note.get(0).wu().getCreatedType());

		UUID third = seedBom("tea-7-37-third.cdx.json");
		releaseService.addArtifact(third, w.release(), WU);
		assertFalse(queued(w.release()));
		assertEquals(1, withheld(w.release()).size(), "a repeated pass adds no note");

		// the hide rule stops matching (an admin disables it): the publish rule publishes again
		hideRule.setEnabled(false);
		configure(w.component(), List.of(rule("qa approved", approved(e1), p), hideRule), p, h);
		ossReleaseService.processRelease(w.release());
		assertEquals(TeaActKind.PUBLISH, teaAct(w.release()).teaActKind());
		teaOutputEventService.runRequested(w.release());
		TeaPublicationData head = head(w.release()).orElseThrow();
		assertEquals(TeaPublicationState.PUBLISHED, head.getState());
		assertEquals(2, head.getLatestVersion());
		assertEquals("CHANGED HIDDEN -> PUBLISHED v2", teaEvents(w.release()).get(teaEvents(w.release()).size() - 1));
	}

	// ---------------------------------------------------------------- 44 (round 3, T-9 point 2)

	/** A policy of the fixture's organization over both entries, with these global actions and rules. */
	private UUID policyWith(List<ReleaseOutputEvent> actions, List<ReleaseInputEvent> rules, UUID... entries)
			throws Exception {
		UUID policy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("tea-7-44-" + UUID.randomUUID()).approvalEntries(List.of(entries)).build(), WU).getUuid();
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(actions), WU);
		approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(rules), WU);
		return policy;
	}

	private void assertVetoedBySbom(World w, ReleaseOutputEvent publish, String hide, String rule) throws Exception {
		UUID sbom = seedBom("tea-7-44-" + UUID.randomUUID() + ".cdx.json");
		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		releaseService.addArtifact(sbom, w.release(), WU);
		assertFalse(queued(w.release()), "no request while the policy's hide rule matches");
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		List<ReleaseUpdateEvent> note = withheld(w.release());
		assertEquals(1, note.size(), "one withheld note");
		assertEquals(publish.getUuid(), note.get(0).objectId());
		assertEquals("Action '" + publish.getName() + "': withheld while action '" + hide + "' matches on rule '" + rule
				+ "'", note.get(0).message());
	}

	@Test
	void aPolicysHideRuleVetoesAutomaticPublishes() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();

		// the G1 replay: both rules on the policy, none on the component
		World w = componentRelease("pkg:generic/pump@1.0.0");
		ReleaseOutputEvent gp = teaAction(EventType.TEA_PUBLISH, "gp");
		ReleaseOutputEvent gh = teaAction(EventType.TEA_HIDE, "gh");
		UUID both = policyWith(List.of(gp, gh), List.of(rule("global approved", approved(e1), gp),
				rule("global disapproved", disapproved(e2), gh)), e1, e2);
		componentService.updateComponent(ComponentDto.builder().uuid(w.component()).approvalPolicy(both).build(), WU);
		approve(w.release(), e1);
		teaOutputEventService.runRequested(w.release());
		assertEquals(1, head(w.release()).orElseThrow().getLatestVersion());
		vote(w.release(), e2, ApprovalState.DISAPPROVED);
		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		int notesBefore = withheld(w.release()).size();
		assertEquals(1, notesBefore, "the disapproval pass withheld 'gp' and said so");
		assertVetoedBySbom(w, gp, "gh", "global disapproved");

		// the mixed shape: the component's own publish rule, the policy's hide rule
		World m = componentRelease("pkg:generic/mixed@1.0.0");
		ReleaseOutputEvent lp = teaAction(EventType.TEA_PUBLISH, "lp");
		ReleaseOutputEvent gh2 = teaAction(EventType.TEA_HIDE, "gh2");
		UUID hideOnly = policyWith(List.of(gh2), List.of(rule("policy disapproved", disapproved(e2), gh2)), e1, e2);
		componentService.updateComponent(ComponentDto.builder().uuid(m.component()).approvalPolicy(hideOnly).build(), WU);
		configure(m.component(), rule("local approved", approved(e1), lp), lp);
		approve(m.release(), e1);
		teaOutputEventService.runRequested(m.release());
		assertEquals(1, head(m.release()).orElseThrow().getLatestVersion());
		vote(m.release(), e2, ApprovalState.DISAPPROVED);
		teaOutputEventService.runRequested(m.release());
		assertEquals(TeaPublicationState.HIDDEN, head(m.release()).orElseThrow().getState());
		assertVetoedBySbom(m, lp, "gh2", "policy disapproved");
	}
}
