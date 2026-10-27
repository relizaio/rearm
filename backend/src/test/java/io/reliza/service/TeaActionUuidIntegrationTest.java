/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.UserData;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.saas.ApprovalPolicyDto;
import io.reliza.service.saas.ApprovalPolicyService;
import io.reliza.service.saas.TeaOutputEventService.TeaWiring;

/**
 * TEA-7, round 3, case 49 (T-5): every output uuid a component's rules can name resolves to
 * exactly one action. A component's actions and rules carry distinct uuids, a policy's global
 * actions and rules too, and a component's own action never carries the uuid of a global action
 * of any live policy of its organization. For everyone, admins included: a writer can no longer
 * shadow an admin's TEA_HIDE with a mail action. Round 4, T-11, case 53 (rule C): a global action's
 * TEA kind, and whether a global rule fires one, never change under the uuid, so no stored
 * non-TEA rule or ref becomes TEA wiring by a policy edit.
 */
public class TeaActionUuidIntegrationTest extends TeaWiringIntegrationTestBase {

	private static ReleaseOutputEvent mailWith(UUID uuid, String name) {
		return ReleaseOutputEvent.builder().uuid(uuid).name(name).type(EventType.EMAIL_NOTIFICATION).build();
	}

	private static void assertRefusedWith(ExecutionResult r, String what, String message) {
		assertFalse(r.getErrors().isEmpty(), what + " was saved");
		assertTrue(r.getErrors().get(0).getMessage().contains(message), what + ": " + r.getErrors().get(0).getMessage());
	}

	private List<UUID> storedActionUuids(UUID component) {
		return storedComponent(component).getOutputTriggers().stream().map(ReleaseOutputEvent::getUuid).toList();
	}

	private UUID policyWithGlobal(ReleaseOutputEvent global) throws Exception {
		UUID entry = approvalEntry();
		UUID policy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("tea-7-49-" + UUID.randomUUID()).approvalEntries(List.of(entry)).build(), WU).getUuid();
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(global)), WU);
		approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(List.of(rule("gr-" + global.getName(),
				approved(entry), global))), WU);
		return policy;
	}

	private String policyName(UUID policy) {
		return approvalPolicyService.getApprovalPolicyData(policy).orElseThrow().getPolicyName();
	}

	@Test
	void anActionUuidIsDistinctOnTheComponentAndNeverAGlobals() throws Exception {
		UUID component = component();
		UserData writer = writer(component);
		UserData admin = admin();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		ReleaseInputEvent r = rule("r", "release.lifecycle == \"ASSEMBLED\"", p, h);
		assertSaved(update(admin, input(component, "outputTriggers", List.of(action(p), action(h)),
				"releaseInputTriggers", List.of(rule(r)))), "the admin wires p and h");
		List<UUID> wired = storedActionUuids(component);
		// main's refusal in the fetcher runs before the funnel's, so a caller of the mutation reads main's text
		// (TEA-7 round 8, F-2); the funnel's own text is pinned by ComponentServiceWiringCarryTest
		String repeatedH = "Duplicate action uuid: " + h.getUuid();

		// (a) the writer puts a mail action under h's uuid, ahead of h
		Map<String, Object> shadow = input(component, "outputTriggers",
				List.of(action(mailWith(h.getUuid(), "x-shadow")), action(p), action(h)));
		assertRefusedWith(update(writer, shadow), "(a) writer", repeatedH);
		assertEquals(wired, storedActionUuids(component), "(a) the stored list is unchanged");
		// (b) the admin sends the same
		assertRefusedWith(update(admin, shadow), "(b) admin", repeatedH);
		assertEquals(wired, storedActionUuids(component));

		// (c) the component's own policy carries a global TEA_HIDE 'gh'
		ReleaseOutputEvent gh = teaAction(EventType.TEA_HIDE, "gh");
		UUID assigned = policyWithGlobal(gh);
		componentService.updateComponent(ComponentDto.builder().uuid(component).approvalPolicy(assigned).build(), WU);
		String reusesGh = "Output event 'x-shadow-global' (" + gh.getUuid() + ") reuses the uuid of global output event "
				+ "'gh' of approval policy '" + policyName(assigned) + "'; give it a uuid of its own";
		Map<String, Object> shadowGlobal = input(component, "outputTriggers",
				List.of(action(p), action(h), action(mailWith(gh.getUuid(), "x-shadow-global"))));
		assertRefusedWith(update(writer, shadowGlobal), "(c) writer", reusesGh);
		assertRefusedWith(update(admin, shadowGlobal), "(c) admin", reusesGh);
		assertEquals(wired, storedActionUuids(component));

		// (d) a policy of the organization the component is not on
		ReleaseOutputEvent other = teaAction(EventType.EMAIL_NOTIFICATION, "other");
		UUID unassigned = policyWithGlobal(other);
		assertRefusedWith(update(writer, input(component, "outputTriggers",
				List.of(action(p), action(h), action(mailWith(other.getUuid(), "x-other"))))), "(d) writer",
				"reuses the uuid of global output event 'other' of approval policy '" + policyName(unassigned) + "'");

		// (e) two rules with one uuid
		ReleaseOutputEvent mail = teaAction(EventType.EMAIL_NOTIFICATION, "mail");
		ReleaseInputEvent mailRule = rule("mail rule", "release.lifecycle == \"ASSEMBLED\"", mail);
		ReleaseInputEvent twin = rule("mail rule twin", "release.lifecycle == \"DRAFT\"", mail);
		twin.setUuid(mailRule.getUuid());
		assertRefusedWith(update(writer, input(component, "outputTriggers", List.of(action(p), action(h), action(mail)),
				"releaseInputTriggers", List.of(rule(r), rule(mailRule), rule(twin)))), "(e) writer",
				"Rule uuid " + mailRule.getUuid() + " appears more than once on this component");
		assertEquals(wired, storedActionUuids(component));

		// (f) an ordinary action with a uuid of its own
		assertSaved(update(writer, input(component, "outputTriggers", List.of(action(p), action(h), action(mail)),
				"releaseInputTriggers", List.of(rule(r), rule(mailRule)))), "(f) writer");
		assertEquals(List.of(p.getUuid(), h.getUuid(), mail.getUuid()), storedActionUuids(component));
	}

	@Test
	void aPolicysGlobalActionsAndRulesCarryDistinctUuids() throws Exception {
		UUID entry = approvalEntry();
		ReleaseOutputEvent g = teaAction(EventType.TEA_PUBLISH, "g");
		ReleaseOutputEvent twinAction = mailWith(g.getUuid(), "g twin");
		ReleaseInputEvent gr = rule("gr", approved(entry), g);
		ReleaseInputEvent twinRule = rule("gr twin", approved(entry), g);
		twinRule.setUuid(gr.getUuid());

		UUID policy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("tea-7-49-p-" + UUID.randomUUID()).approvalEntries(List.of(entry)).build(), WU).getUuid();
		RelizaException outputs = assertThrows(RelizaException.class, () -> approvalPolicyService
				.setGlobalOutputEvents(policy, new ArrayList<>(List.of(g, twinAction)), WU));
		assertEquals("Global output event uuid " + g.getUuid() + " appears more than once on this policy",
				outputs.getMessage());
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(g)), WU);
		RelizaException inputs = assertThrows(RelizaException.class, () -> approvalPolicyService
				.setGlobalInputEvents(policy, new ArrayList<>(List.of(gr, twinRule)), WU));
		assertEquals("Global input event uuid " + gr.getUuid() + " appears more than once on this policy",
				inputs.getMessage());
		assertTrue(approvalPolicyService.getApprovalPolicyData(policy).orElseThrow().getGlobalInputEvents() == null
				|| approvalPolicyService.getApprovalPolicyData(policy).orElseThrow().getGlobalInputEvents().isEmpty(),
				"nothing saved");

		assertThrows(RelizaException.class, () -> approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder()
				.org(org.getUuid()).policyName("tea-7-49-c-" + UUID.randomUUID()).approvalEntries(List.of(entry))
				.globalOutputEvents(new ArrayList<>(List.of(g, twinAction))).build(), WU));
		assertThrows(RelizaException.class, () -> approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder()
				.org(org.getUuid()).policyName("tea-7-49-c-" + UUID.randomUUID()).approvalEntries(List.of(entry))
				.globalInputEvents(new ArrayList<>(List.of(gr, twinRule))).build(), WU));
	}

	// ---------------------------------------------------------------- 53 (round 4, T-11, rule C)

	private List<EventType> globalTypes(UUID policy) {
		return policyData(policy).getGlobalOutputEvents().stream().map(ReleaseOutputEvent::getType).toList();
	}

	@Test
	void aGlobalsKindIsFixedUnderItsUuid() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID entry = approvalEntry();
		ReleaseOutputEvent mail = mail("mail");
		ReleaseInputEvent rMail = rule("r-mail", "release.lifecycle == \"ASSEMBLED\"", mail);
		UUID policy = policyWith("tea-7-53", List.of(mail), List.of(rMail), entry);
		World c = componentRelease("pkg:generic/c53@1.0.0");
		componentService.updateComponent(ComponentDto.builder().uuid(c.component()).approvalPolicy(policy).build(), WU);
		UserData writer = writer(c.component());
		UserData admin = admin();
		ReleaseInputEvent lr = rule("lr", "release.lifecycle == \"ASSEMBLED\"", mail);
		assertSaved(update(writer, input(c.component(), "releaseInputTriggers", List.of(rule(lr)),
				"globalInputEventRefs", List.of(ref(rMail.getUuid(), true)))), "the writer's rule on mail and ref on r-mail");
		TeaWiring wired = wiring(c.component());
		assertTrue(wired.rules().isEmpty() && wired.refs().isEmpty(), "not TEA wiring");

		// the admin retypes mail to TEA_PUBLISH under its uuid
		ReleaseOutputEvent mailAsTea = ReleaseOutputEvent.builder().uuid(mail.getUuid()).name("mail")
				.type(EventType.TEA_PUBLISH).build();
		String kindFixed = String.format(ApprovalPolicyService.KIND_FIXED_ACTION, "mail", mail.getUuid());
		RelizaException retype = assertThrows(RelizaException.class, () -> approvalPolicyService
				.setGlobalOutputEvents(policy, new ArrayList<>(List.of(mailAsTea)), WU));
		assertEquals(kindFixed, retype.getMessage());
		assertRefusedWith(setOutputs(admin, policy, mailAsTea), "the retype through GraphQL", kindFixed);
		assertEquals(List.of(EventType.EMAIL_NOTIFICATION), globalTypes(policy), "the stored policy is unchanged");
		assertEquals(wired, wiring(c.component()), "the component's wiring is unchanged");

		// a new TEA_PUBLISH under a new uuid is saved; r-mail may not start firing it
		ReleaseOutputEvent gp = teaAction(EventType.TEA_PUBLISH, "gp");
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(mail, gp)), WU);
		ReleaseInputEvent rMailTea = copyOf(rMail);
		rMailTea.setOutputEvents(Set.of(mail.getUuid(), gp.getUuid()));
		String ruleFixed = String.format(ApprovalPolicyService.KIND_FIXED_RULE, "r-mail", rMail.getUuid());
		RelizaException start = assertThrows(RelizaException.class, () -> approvalPolicyService
				.setGlobalInputEvents(policy, new ArrayList<>(List.of(rMailTea)), WU));
		assertEquals(ruleFixed, start.getMessage());
		assertRefusedWith(setInputs(admin, policy, rMailTea), "r-mail firing gp through GraphQL", ruleFixed);
		assertEquals(Set.of(mail.getUuid()), policyData(policy).getGlobalInputEvents().get(0).getOutputEvents());

		// a new rule r-tea firing gp is saved, and the ref on r-mail does not touch it
		ReleaseInputEvent rTea = rule("r-tea", approved(entry), gp);
		approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(List.of(copyOf(rMail), rTea)), WU);
		approve(c.release(), entry);
		assertEquals(TeaActKind.PUBLISH, teaAct(c.release()).teaActKind(), "r-tea requests a PUBLISH");
		assertEquals("Action 'gp' fired by rule 'r-tea'", teaAct(c.release()).teaActComment());

		// r-tea may not stop firing gp
		ReleaseInputEvent rTeaOff = copyOf(rTea);
		rTeaOff.setOutputEvents(Set.of());
		RelizaException stop = assertThrows(RelizaException.class, () -> approvalPolicyService
				.setGlobalInputEvents(policy, new ArrayList<>(List.of(copyOf(rMail), rTeaOff)), WU));
		assertEquals(String.format(ApprovalPolicyService.KIND_FIXED_RULE, "r-tea", rTea.getUuid()), stop.getMessage());

		// the full lists re-sent unchanged, as the policy page sends them
		assertSaved(setOutputs(admin, policy, mail, gp), "the outputs re-sent");
		assertSaved(setInputs(admin, policy, rMail, rTea), "the rules re-sent");
		assertEquals(List.of(EventType.EMAIL_NOTIFICATION, EventType.TEA_PUBLISH), globalTypes(policy));
	}

	// ---------------------------------------------------------------- 58 (round 5, T-12, part 3)

	@Test
	void aRemovedGlobalsUuidNeverComesBack() throws Exception {
		UUID entry = approvalEntry();
		UserData admin = admin();
		ReleaseOutputEvent mailA = mail("mailA");
		ReleaseOutputEvent gpA = teaAction(EventType.TEA_PUBLISH, "gpA");
		ReleaseInputEvent rA = rule("rA", approved(entry), gpA);
		UUID p = policyWith("tea-7-58-p", List.of(mailA, gpA), List.of(rA), entry);
		ReleaseOutputEvent qa = mail("qa");
		ReleaseOutputEvent qGone = mail("q-gone");
		UUID q = policyWith("tea-7-58-q", List.of(qa, qGone), List.of(), entry);
		approvalPolicyService.setGlobalOutputEvents(q, new ArrayList<>(List.of(qa)), WU);
		ReleaseOutputEvent ra = mail("ra");
		UUID archived = policyWith("tea-7-58-r", List.of(ra), List.of(), entry);
		approvalPolicyService.archiveApprovalPolicy(archived, WU);

		// the editor deletes a rule, then a stale page re-sends it; the same for an action
		assertSaved(setInputs(admin, p), "rA deleted");
		assertRefusedWith(setInputs(admin, p, rA), "rA re-sent", "Global rule 'rA' (" + rA.getUuid()
				+ ") was removed from this policy on ");
		assertSaved(setOutputs(admin, p, mailA), "gpA deleted");
		assertRefusedWith(setOutputs(admin, p, mailA, gpA), "gpA re-sent", "Global output event 'gpA' ("
				+ gpA.getUuid() + ") was removed from this policy on ");
		assertEquals(List.of(mailA.getUuid()), policyData(p).getGlobalOutputEvents().stream()
				.map(ReleaseOutputEvent::getUuid).toList());

		// a new global with a client-minted uuid of its own is saved
		ReleaseOutputEvent fresh = teaAction(EventType.TEA_HIDE, "fresh");
		assertSaved(setOutputs(admin, p, mailA, fresh), "a fresh uuid");

		// another policy's live, archived or retired uuid is refused, naming the policy
		for (Object[] c : new Object[][] { { qa, q }, { ra, archived }, { qGone, q } }) {
			ReleaseOutputEvent other = (ReleaseOutputEvent) c[0];
			ReleaseOutputEvent reuse = ReleaseOutputEvent.builder().uuid(other.getUuid()).name("reuse")
					.type(EventType.EMAIL_NOTIFICATION).build();
			assertRefusedWith(setOutputs(admin, p, mailA, fresh, reuse), "reusing " + other.getName(),
					"is or was the uuid of global action '" + other.getName() + "' of approval policy '"
							+ policyName((UUID) c[1]) + "'");
		}

		// a create keeps a fresh uuid and refuses a retired one
		ReleaseOutputEvent born = teaAction(EventType.TEA_PUBLISH, "born");
		UUID created = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("tea-7-58-c-" + UUID.randomUUID()).approvalEntries(List.of(entry))
				.globalOutputEvents(new ArrayList<>(List.of(born))).build(), WU).getUuid();
		assertEquals(born.getUuid(), policyData(created).getGlobalOutputEvents().get(0).getUuid());
		ReleaseOutputEvent gpAgain = ReleaseOutputEvent.builder().uuid(gpA.getUuid()).name("gpA")
				.type(EventType.TEA_PUBLISH).build();
		RelizaException refused = assertThrows(RelizaException.class, () -> approvalPolicyService.createApprovalPolicy(
				ApprovalPolicyDto.builder().org(org.getUuid()).policyName("tea-7-58-c-" + UUID.randomUUID())
						.approvalEntries(List.of(entry)).globalOutputEvents(new ArrayList<>(List.of(gpAgain))).build(), WU));
		assertTrue(refused.getMessage().contains("is or was the uuid of global action 'gpA' of approval policy '"
				+ policyName(p) + "'"), refused.getMessage());
	}
}
