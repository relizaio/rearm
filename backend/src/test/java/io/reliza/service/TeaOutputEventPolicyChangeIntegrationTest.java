/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.model.ApprovalPolicySource;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.OrganizationData.GlobalApprovalPolicyRule;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.UserData;
import io.reliza.model.saas.ApprovalPolicyData;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.saas.OrgApprovalPolicyService.ApprovalPolicyResolution;

/**
 * TEA-7, round 4, T-11, case 54: the effective policy changes without a component save (the
 * organization's rules, an admin's assignment, an archive), and what then fires is wiring an
 * authorized person wrote: a rule naming a policy's TEA action fires only while that policy is
 * in effect and resolves nowhere otherwise, a ref disabling a hide rule holds until a policy
 * change drops it, and an archived policy's uuids are guarded and resolved by nothing.
 */
public class TeaOutputEventPolicyChangeIntegrationTest extends TeaWiringIntegrationTestBase {

	private static final String PUBLISH_BY_HAND = "mutation($r: ID!) { publishReleaseOnTea(release: $r) { outcome } }";

	private String version() {
		return "1.2." + Math.abs(UUID.randomUUID().hashCode() % 100000);
	}

	@Test
	void aPolicyChangeWithoutAComponentSaveRunsOnlyWiringAnAuthorizedPersonWrote() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent gpubA = teaAction(EventType.TEA_PUBLISH, "gpubA");
		// tpa's own rule waits for e2's approval, so an e1 approval fires only the component's lp
		UUID tpa = policyWith("tea-7-54-tpa", List.of(gpubA), List.of(rule("tpa approved", approved(e2), gpubA)),
				e1, e2);
		ReleaseOutputEvent ghide = teaAction(EventType.TEA_HIDE, "ghide");
		ReleaseInputEvent e2Hide = rule("e2", disapproved(e2), ghide);
		UUID hp = policyWith("tea-7-54-hp", List.of(ghide), List.of(e2Hide), e1, e2);
		World w = componentRelease("pkg:generic/c54@1.0.0");
		UUID c = w.component();
		UserData admin = admin();

		// the admin wires lp (naming tpa's gpubA) and a ref disabling hp's hide rule on a component under no policy
		ReleaseInputEvent lp = rule("lp", approved(e1), gpubA);
		assertSaved(update(admin, input(c, "releaseInputTriggers", List.of(rule(lp)),
				"globalInputEventRefs", List.of(ref(e2Hide.getUuid(), true)))), "the admin's own wiring");

		List<GlobalApprovalPolicyRule> previous = orgRules();
		String byName = "^" + nameOf(c) + "$";
		try {
			// (a) the org rules map c to tpa: an approval runs lp
			setOrgRules(previous, orgRule("tea-7-54-" + c, byName, tpa));
			approve(w.release(), e1);
			assertEquals(TeaActKind.PUBLISH, teaAct(w.release()).teaActKind(), "(a) lp requests a PUBLISH");
			assertEquals("Action 'gpubA' fired by rule 'lp'", teaAct(w.release()).teaActComment());

			// (b) the org rules map c to hp instead: lp resolves nowhere, the admin's ref disables the hide
			setOrgRules(previous, orgRule("tea-7-54-" + c, byName, hp));
			UUID r2 = anotherRelease(w, version());
			data(asUser(admin, PUBLISH_BY_HAND, vars("r", r2.toString())), "publishReleaseOnTea");
			assertEquals(TeaPublicationState.PUBLISHED, head(r2).orElseThrow().getState());
			approve(r2, e1);
			sbomPass(r2);
			assertTrue(triggerRows(r2, gpubA.getUuid()).isEmpty(), "(b) lp fires nothing: no TRIGGER row");
			assertFalse(queued(r2), "(b) no request");
			vote(r2, e2, ApprovalState.DISAPPROVED);
			assertTrue(triggerRows(r2, ghide.getUuid()).isEmpty(), "(b) the ref disables ghide");
			assertFalse(queued(r2));
			assertEquals(TeaPublicationState.PUBLISHED, head(r2).orElseThrow().getState());

			// (c) the admin assigns hp per component: the refs go, lp stays and still resolves nowhere
			assertSaved(update(admin, input(c, "approvalPolicy", hp.toString())), "(c) the admin assigns hp");
			assertTrue(refUuids(storedComponent(c).getGlobalInputEventRefs()).isEmpty(), "(c) refs dropped");
			assertTrue(storedComponent(c).getReleaseInputTriggers().stream().anyMatch(r -> lp.getUuid().equals(r.getUuid())),
					"(c) lp kept");
			ossReleaseService.processRelease(r2);
			assertEquals(TeaActKind.HIDE, teaAct(r2).teaActKind(), "(c) the hide rule now requests a HIDE");
			assertEquals(1, triggerRows(r2, ghide.getUuid()).size());
			assertTrue(triggerRows(r2, gpubA.getUuid()).isEmpty(), "(c) lp still resolves nowhere");

			// (d) tpa archived: its uuids leave the live list, so rule B and rule D stop guarding them
			approvalPolicyService.archiveApprovalPolicy(tpa, WU);
			assertTrue(approvalPolicyService.listApprovalPoliciesOfOrg(org.getUuid()).stream().map(ApprovalPolicyData::getUuid)
					.noneMatch(tpa::equals), "(d) tpa is not live");
			UserData writer = writer(c);
			ReleaseOutputEvent shadow = ReleaseOutputEvent.builder().uuid(gpubA.getUuid()).name("x-mail")
					.type(EventType.EMAIL_NOTIFICATION).users(java.util.Set.of()).build();
			ReleaseInputEvent wr = rule("w-names-gpubA", approved(e1), gpubA);
			assertSaved(update(writer, input(c, "outputTriggers", List.of(action(shadow)),
					"releaseInputTriggers", List.of(rule(lp), rule(wr)))), "(d) the writer's mail under gpubA's uuid");
			UUID r3 = anotherRelease(w, version());
			approve(r3, e1);
			assertFalse(queued(r3), "(d) no PUBLISH request");
			assertTrue(head(r3).isEmpty());
			assertTrue(events(r3, ReleaseUpdateScope.TEA_PUBLICATION).isEmpty(), "(d) no TEA_PUBLICATION event");

			// a save naming the archived policy per component is accepted, and nothing of tpa resolves by it
			assertSaved(update(admin, input(c, "approvalPolicy", tpa.toString())), "(d) the archived policy named");
			ApprovalPolicyResolution resolved = resolution(c);
			assertTrue(resolved.perComponentPolicyArchived());
			assertEquals(ApprovalPolicySource.ORG_RULE, resolved.source(), "step 2 applies, not PER_COMPONENT");
			assertEquals(hp, resolved.policyUuid(), "the org rule of (b), never tpa");
		} finally {
			orgApprovalPolicyService.setRules(org.getUuid(), previous, WU);
		}
	}
}
