/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApprovalPolicySource;
import io.reliza.model.ComponentData.EventType;
import io.reliza.service.saas.TeaWiringAuthorization;
import io.reliza.model.saas.ApprovalPolicyData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.TeaGrantBasis;
import io.reliza.model.ComponentData.TeaWiringStamp;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.OrganizationData.GlobalApprovalPolicyRule;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.saas.ApprovalPolicyDto;
import io.reliza.model.saas.ApprovalPolicy;
import io.reliza.service.saas.OrgApprovalPolicyService.ApprovalPolicyResolution;
import io.reliza.service.saas.TeaOutputEventService.TeaWiring;

/**
 * TEA-7, round 2, T-1, case 36: a save that changes a component's TEA wiring needs
 * PUBLISH_EXTERNALLY at WRITE on the component, through the {@code updateComponent} mutation,
 * as TEA-5 case 43 asks it of a publish by hand. A writer without it keeps editing everything
 * else. Round 3: the function is asked at WRITE (case 45), and a rename into or out of an
 * organization rule that maps names to a TEA-carrying policy is a wiring change, while a create
 * under such a name is the organization's choice (case 50). Round 4, T-11: a rule or a ref naming
 * another policy's TEA action or TEA-firing rule is wiring before that policy is in effect (case
 * 51), and a name an organization rule maps is wiring under a per-component policy too (case 52).
 */
public class TeaOutputEventPermissionIntegrationTest extends TeaWiringIntegrationTestBase {

	// ---------------------------------------------------------------- 36 (a) to (e)

	@Test
	void wiringATeaActNeedsPublishExternallyOnTheComponent() throws Exception {
		UUID component = component();
		UserData writer = writer(component);

		// (a) a TEA_PUBLISH action of the writer's own
		ReleaseOutputEvent own = teaAction(EventType.TEA_PUBLISH, "writer publish");
		assertRefused(update(writer, input(component, "outputTriggers", List.of(action(own)))), "(a)");
		assertTrue(wiring(component).actions().isEmpty(), "(a) no TEA action stored");

		// (b) an ordinary action and a rule naming it
		ReleaseOutputEvent mail = teaAction(EventType.EMAIL_NOTIFICATION, "mail");
		ReleaseInputEvent mailRule = rule("mail rule", "release.lifecycle == \"ASSEMBLED\"", mail);
		assertSaved(update(writer, input(component, "outputTriggers", List.of(action(mail)),
				"releaseInputTriggers", List.of(rule(mailRule)))), "(b)");

		// (c) the admin wires p, h and r; the writer re-sends them unchanged with an unrelated change
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		ReleaseInputEvent r = rule("r", "release.lifecycle == \"ASSEMBLED\"", p, h);
		UserData admin = admin();
		assertSaved(update(admin, input(component, "outputTriggers", List.of(action(mail), action(p), action(h)),
				"releaseInputTriggers", List.of(rule(mailRule), rule(r)))), "(c) admin");
		TeaWiring wired = wiring(component);
		assertEquals(2, wired.actions().size());
		// round 5, case 59: the admin's save stamped the two TEA actions and r, ORG_ADMIN, by the admin
		Map<String, TeaWiringStamp> adminStamps = stamps(component);
		assertEquals(Set.of("p", "h", "r"), adminStamps.keySet(), "the TEA pieces only");
		assertTrue(adminStamps.values().stream().allMatch(t -> TeaGrantBasis.ORG_ADMIN == t.basis()
				&& admin.getUuid().equals(t.by())));
		ReleaseOutputEvent other = teaAction(EventType.EMAIL_NOTIFICATION, "other");
		Map<String, Object> renamed = input(component, "outputTriggers",
				List.of(action(mail), action(p), action(h), action(other)),
				"releaseInputTriggers", List.of(rule(mailRule), rule(r)));
		renamed.put("name", nameOf(component) + "-renamed");
		assertSaved(update(writer, renamed), "(c) writer, wiring unchanged");
		assertEquals(wired, wiring(component));
		assertEquals(adminStamps, stamps(component), "case 59: the writer's re-send keeps the admin's stamps");

		// (d) each change of the wiring is refused, and the stored wiring stays
		List<Map<String, Object>> actions = List.of(action(mail), action(p), action(h), action(other));
		ReleaseInputEvent recel = copyOf(r);
		recel.setCelExpression("release.lifecycle == \"READY_TO_SHIP\"");
		ReleaseInputEvent off = copyOf(r);
		off.setEnabled(false);
		ReleaseOutputEvent hRenamed = ReleaseOutputEvent.builder().uuid(h.getUuid()).name("h2").type(EventType.TEA_HIDE)
				.build();
		List<Map<String, Object>> changes = List.of(
				input(component, "releaseInputTriggers", List.of(rule(mailRule), rule(recel))),
				input(component, "releaseInputTriggers", List.of(rule(mailRule), rule(off))),
				input(component, "outputTriggers", List.of(action(mail), action(h), action(other))),
				input(component, "outputTriggers", List.of(action(mail), action(p), action(hRenamed), action(other))));
		for (int i = 0; i < changes.size(); i++) {
			assertRefused(update(writer, changes.get(i)), "(d) change " + i);
			assertEquals(wired, wiring(component), "(d) change " + i + " left the wiring");
		}
		assertEquals(actions.size(), storedComponent(component).getOutputTriggers().size());

		// (e) the function at COMPONENT scope, then at ORGANIZATION scope
		UserData publisher = writer(component, PermissionFunction.PUBLISH_EXTERNALLY);
		assertSaved(update(publisher, changes.get(0)), "(e) COMPONENT scope");
		assertEquals(Set.of(recel), wiring(component).rules());
		assertEquals(TeaGrantBasis.PUBLISH_EXTERNALLY, stamps(component).get("r").basis(), "case 59: the holder's stamp");
		assertEquals(publisher.getUuid(), stamps(component).get("r").by());
		UserData orgPublisher = member(PermissionType.READ_WRITE);
		userService.setUserPermission(orgPublisher.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.PUBLISH_EXTERNALLY), null, WU);
		orgPublisher = userService.getUserData(orgPublisher.getUuid()).orElseThrow();
		assertSaved(update(orgPublisher, changes.get(3)), "(e) ORGANIZATION scope");
		assertTrue(wiring(component).actions().stream().anyMatch(a -> "h2".equals(a.name())));

		// (g), first half: the admin makes each change of (d) without the function
		for (int i = 0; i < changes.size(); i++) {
			assertSaved(update(admin, changes.get(i)), "(g) admin change " + i);
		}
	}

	// ---------------------------------------------------------------- 36 (f) and (g)

	@Test
	void aPolicyThatCarriesTeaRulesIsWiringToo() throws Exception {
		UUID entry = approvalEntry();
		ApprovalPolicy teaPolicy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder()
				.org(org.getUuid()).policyName("tea-7-36-" + UUID.randomUUID()).approvalEntries(List.of(entry)).build(),
				WU);
		ReleaseOutputEvent g = teaAction(EventType.TEA_PUBLISH, "g");
		approvalPolicyService.setGlobalOutputEvents(teaPolicy.getUuid(), new ArrayList<>(List.of(g)), WU);
		ReleaseInputEvent gr = rule("gr", approved(entry), g);
		approvalPolicyService.setGlobalInputEvents(teaPolicy.getUuid(), new ArrayList<>(List.of(gr)), WU);
		UUID plainPolicy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder()
				.org(org.getUuid()).policyName("tea-7-36-plain-" + UUID.randomUUID()).approvalEntries(List.of(entry))
				.build(), WU).getUuid();

		UUID onPolicy = component();
		componentService.updateComponent(ComponentDto.builder().uuid(onPolicy).approvalPolicy(teaPolicy.getUuid())
				.build(), WU);
		UUID offPolicy = component();
		UserData writer = writer(onPolicy);
		userService.setUserPermission(writer.getUuid(), org.getUuid(), PermissionScope.COMPONENT, offPolicy,
				PermissionType.READ_WRITE, List.of(), null, WU);
		writer = userService.getUserData(writer.getUuid()).orElseThrow();

		ReleaseInputEvent local = rule("local", approved(entry));
		local.setOutputEvents(Set.of(g.getUuid()));
		Map<String, Object> localRule = input(onPolicy, "releaseInputTriggers", List.of(rule(local)));
		assertRefused(update(writer, localRule), "(f) a local rule naming the global TEA action");

		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("uuid", gr.getUuid().toString());
		ref.put("disabled", true);
		Map<String, Object> disableGr = input(onPolicy, "globalInputEventRefs", List.of(ref));
		assertRefused(update(writer, disableGr), "(f) a ref disabling the global TEA rule");

		Map<String, Object> assignTea = input(offPolicy, "approvalPolicy", teaPolicy.getUuid().toString());
		assertRefused(update(writer, assignTea), "(f) assigning the TEA policy");
		assertEquals(null, storedComponent(offPolicy).getApprovalPolicy());

		Map<String, Object> assignPlain = input(offPolicy, "approvalPolicy", plainPolicy.toString());
		assertSaved(update(writer, assignPlain), "(f) a policy without TEA rules");
		assertEquals(plainPolicy, storedComponent(offPolicy).getApprovalPolicy());

		Map<String, Object> clear = input(onPolicy, "clearApprovalPolicy", true);
		assertRefused(update(writer, clear), "(f) clearing the TEA policy withdraws the component");

		// (g), second half: the admin makes each change of (f) without the function
		UserData admin = admin();
		assertSaved(update(admin, localRule), "(g) local rule");
		assertSaved(update(admin, disableGr), "(g) ref");
		assertSaved(update(admin, input(offPolicy, "approvalPolicy", teaPolicy.getUuid().toString())), "(g) assign");
		assertEquals(teaPolicy.getUuid(), storedComponent(offPolicy).getApprovalPolicy());
	}

	// ---------------------------------------------------------------- 45 (round 3, T-9 point 3)

	@Test
	void publishExternallyIsAskedAtWrite() throws Exception {
		UUID component = component();
		// setUserPermission skips a component grant while the user holds ORGANIZATION READ_WRITE, so
		// the component grant goes first and the organization level is raised after it
		UserData member = member(PermissionType.READ_ONLY);
		userService.setUserPermission(member.getUuid(), org.getUuid(), PermissionScope.COMPONENT, component,
				PermissionType.READ_ONLY, List.of(PermissionFunction.PUBLISH_EXTERNALLY), null, WU);
		userService.setUserPermission(member.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_WRITE, List.of(), null, WU);
		member = userService.getUserData(member.getUuid()).orElseThrow();
		assertEquals(PermissionType.READ_ONLY, member.getPermission(org.getUuid(), PermissionScope.COMPONENT, component)
				.orElseThrow().getType(), "the component grant is READ_ONLY with the function");
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");

		assertRefused(update(member, input(component, "outputTriggers", List.of(action(p)))),
				"PUBLISH_EXTERNALLY at READ only");
		assertTrue(wiring(component).actions().isEmpty());

		userService.setUserPermission(member.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, List.of(), null, WU);
		userService.setUserPermission(member.getUuid(), org.getUuid(), PermissionScope.COMPONENT, component,
				PermissionType.READ_WRITE, List.of(PermissionFunction.PUBLISH_EXTERNALLY), null, WU);
		userService.setUserPermission(member.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_WRITE, List.of(), null, WU);
		member = userService.getUserData(member.getUuid()).orElseThrow();
		assertSaved(update(member, input(component, "outputTriggers", List.of(action(p)))), "PUBLISH_EXTERNALLY at WRITE");
		assertEquals(1, wiring(component).actions().size());
	}

	// ---------------------------------------------------------------- 50 (round 3, T-6)

	static final String CREATE = "mutation($c: CreateComponentInput!) { createComponent(component: $c) { uuid name } }";
	static final String CREATE_PROG = "mutation($c: CreateComponentInput!) { createComponentProgrammatic(component: $c) "
			+ "{ uuid name } }";

	/** A policy carrying an enabled global rule that fires the given global action. */
	private UUID teaPolicy(UUID entry, ReleaseOutputEvent action) throws Exception {
		UUID policy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("tea-7-50-" + UUID.randomUUID()).approvalEntries(List.of(entry)).build(), WU).getUuid();
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(action)), WU);
		approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(List.of(rule("gr-" + action.getName(),
				approved(entry), action))), WU);
		return policy;
	}

	@Test
	void aRenameIntoOrOutOfATeaOrgRuleIsWiringAndACreateIsNot() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String pub = "t7pub-" + tag + "-";
		String hide = "t7hide-" + tag + "-";
		UUID entry = approvalEntry();
		UUID p = teaPolicy(entry, teaAction(EventType.TEA_PUBLISH, "gpub"));
		UUID h = teaPolicy(entry, teaAction(EventType.TEA_HIDE, "ghide"));
		List<GlobalApprovalPolicyRule> previous = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow()
				.getGlobalApprovalPolicyRules();
		List<GlobalApprovalPolicyRule> rules = new ArrayList<>(null == previous ? List.of() : previous);
		rules.add(orgRule("tea-7-50-pub-" + tag, pub + ".*", p));
		rules.add(orgRule("tea-7-50-hide-" + tag, hide + ".*", h));
		orgApprovalPolicyService.setRules(org.getUuid(), rules, WU);
		try {
			UUID x = componentNamed("w-" + tag + "-x");
			UUID y = componentNamed(hide + "y");
			UUID a = componentNamed(pub + "a");
			assertEquals(h, resolution(y).policyUuid());
			assertEquals(p, resolution(a).policyUuid());
			UserData writer = writer(x);
			for (UUID other : List.of(y, a)) {
				userService.setUserPermission(writer.getUuid(), org.getUuid(), PermissionScope.COMPONENT, other,
						PermissionType.READ_WRITE, List.of(), null, WU);
			}
			writer = userService.getUserData(writer.getUuid()).orElseThrow();

			ExecutionResult into = update(writer, renameTo(x, pub + "x"));
			assertRefused(into, "a rename into the publish rule");
			assertTrue(TeaWiring.NEEDS_PUBLISH_EXTERNALLY.contains("a name an organization rule maps to such a policy"));
			assertEquals("w-" + tag + "-x", nameOf(x), "the name is unchanged");
			assertRefused(update(writer, renameTo(y, "plain-" + tag + "-y")), "a rename out of the hide rule");
			assertEquals(hide + "y", nameOf(y));
			assertSaved(update(writer, renameTo(x, "w-" + tag + "-z")), "a rename no TEA rule sees");
			assertSaved(update(writer, renameTo(a, pub + "b")), "a rename within the publish rule");
			assertEquals(p, resolution(a).policyUuid());

			userService.setUserPermission(writer.getUuid(), org.getUuid(), PermissionScope.COMPONENT, x,
					PermissionType.READ_WRITE, List.of(PermissionFunction.PUBLISH_EXTERNALLY), null, WU);
			writer = userService.getUserData(writer.getUuid()).orElseThrow();
			assertSaved(update(writer, renameTo(x, pub + "x")), "with PUBLISH_EXTERNALLY on the component");
			assertEquals(p, resolution(x).policyUuid());
			assertSaved(update(admin(), renameTo(y, "plain-" + tag + "-y")), "the admin");
			assertEquals(null, resolution(y).policyUuid());

			// creates under a matching name: the organization's rule enrols them, for a user and for a key
			UserData orgWriter = member(PermissionType.READ_WRITE);
			Map<String, Object> byUser = data(asUser(orgWriter, CREATE, vars("c", Map.of("org", org.getUuid().toString(),
					"name", pub + "new", "type", "COMPONENT", "versionSchema", "semver", "featureBranchVersioning", "Branch.Micro"))), "createComponent");
			ApprovalPolicyResolution created = resolution(UUID.fromString((String) byUser.get("uuid")));
			assertEquals(ApprovalPolicySource.ORG_RULE, created.source());
			assertEquals(p, created.policyUuid());
			ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
					UUID.randomUUID().toString(), "tea-7-50", WU);
			apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
					PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, Set.of(), null)), WU);
			Map<String, Object> byKey = data(asKey(key, CREATE_PROG, vars("c", Map.of("name", pub + "key",
					"type", "COMPONENT", "versionSchema", "semver", "featureBranchVersioning", "Branch.Micro"))), "createComponentProgrammatic");
			ApprovalPolicyResolution keyed = resolution(UUID.fromString((String) byKey.get("uuid")));
			assertEquals(ApprovalPolicySource.ORG_RULE, keyed.source());
			assertEquals(p, keyed.policyUuid());
		} finally {
			orgApprovalPolicyService.setRules(org.getUuid(), new ArrayList<>(null == previous ? List.of() : previous), WU);
		}
	}

	// ---------------------------------------------------------------- 51 (round 4, T-11)

	@Test
	void wiringThatNamesAnotherPolicysTeaActionOrRuleNeedsPublishExternally() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent gpubA = teaAction(EventType.TEA_PUBLISH, "gpubA");
		ReleaseOutputEvent mA = mail("mA");
		UUID tpa = policyWith("tea-7-51-tpa", List.of(gpubA, mA), List.of(rule("e1", approved(e1), gpubA),
				rule("rm", "release.version == \"never\"", mA)), e1, e2);
		ReleaseOutputEvent ghide = teaAction(EventType.TEA_HIDE, "ghide");
		ReleaseOutputEvent mailH = mail("mail");
		ReleaseInputEvent e2Rule = rule("e2", disapproved(e2), ghide);
		ReleaseInputEvent rhMail = rule("rh-mail", disapproved(e2), mailH);
		UUID hp = policyWith("tea-7-51-hp", List.of(ghide, mailH), List.of(e2Rule, rhMail), e1, e2);
		World w = componentRelease("pkg:generic/w51@1.0.0");
		UUID c = w.component();
		assertEquals(null, resolution(c).policyUuid(), "effective NONE");
		String plantedVersion = "1.1." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		ReleaseInputEvent planted = rule("w-planted", "release.version == \"" + plantedVersion + "\"", gpubA);
		UserData writer = writer(c);

		assertRefused(update(writer, input(c, "releaseInputTriggers", List.of(rule(planted)))), "(a) a rule naming gpubA");
		assertTrue(TeaWiring.NEEDS_PUBLISH_EXTERNALLY.contains("the rules and global-rule refs that fire or disable them "
				+ "on any approval policy of the organization"), "(a) the amended refusal text");
		assertTrue(null == storedComponent(c).getReleaseInputTriggers()
				|| storedComponent(c).getReleaseInputTriggers().isEmpty(), "(a) the stored rules are unchanged");
		assertRefused(update(writer, input(c, "globalInputEventRefs", List.of(ref(e2Rule.getUuid(), true)))),
				"(b) a ref disabling hp's hide rule");
		assertRefused(update(writer, input(c, "globalInputEventRefs", List.of(ref(rhMail.getUuid(), false,
				gpubA.getUuid())))), "(c) a ref overriding rh-mail to gpubA");
		assertTrue(refUuids(storedComponent(c).getGlobalInputEventRefs()).isEmpty(), "(b), (c) no ref stored");
		ReleaseInputEvent namesMail = rule("w-mail", "release.lifecycle == \"ASSEMBLED\"", mA);
		assertSaved(update(writer, input(c, "releaseInputTriggers", List.of(rule(namesMail)))), "(d) a rule naming mA");
		assertSaved(update(writer, input(c, "globalInputEventRefs", List.of(ref(rhMail.getUuid(), true)))),
				"(e) a ref disabling rh-mail");

		List<GlobalApprovalPolicyRule> previous = orgRules();
		String byName = "^" + nameOf(c) + "$";
		try {
			// (f) the admin maps the name to tpa: the org save touches the component not at all
			setOrgRules(previous, orgRule("tea-7-51-" + c, byName, tpa));
			assertEquals(tpa, resolution(c).policyUuid());
			assertEquals(List.of(namesMail.getUuid()), storedComponent(c).getReleaseInputTriggers().stream()
					.map(ReleaseInputEvent::getUuid).toList());
			assertEquals(Set.of(rhMail.getUuid()), refUuids(storedComponent(c).getGlobalInputEventRefs()));
			sbomPass(w.release());
			assertEquals(1, triggerRows(w.release(), mA.getUuid()).size(), "(f) the writer's mail rule fires mA");
			assertTrue(triggerRows(w.release(), gpubA.getUuid()).isEmpty(), "(f) nothing TEA");
			assertFalse(queued(w.release()));

			// (g) the authorized author's wiring, enrolled by the admin's org rules
			UserData publisher = writer(c, PermissionFunction.PUBLISH_EXTERNALLY);
			assertSaved(update(publisher, input(c, "releaseInputTriggers", List.of(rule(namesMail), rule(planted)))),
					"(g) with PUBLISH_EXTERNALLY on the component");
			setOrgRules(previous, orgRule("tea-7-51-" + c, byName, hp));
			setOrgRules(previous, orgRule("tea-7-51-" + c, byName, tpa));
			// case 59: tpa's TEA global and its rule are stamped, its mail action and mail rule are not
			ApprovalPolicyData tpaData = policyData(tpa);
			assertTrue(tpaData.getGlobalOutputEvents().stream().allMatch(a -> (a.getType() == EventType.TEA_PUBLISH)
					== TeaWiringAuthorization.valid(a, tpa)));
			assertTrue(tpaData.getGlobalInputEvents().stream().allMatch(r -> "e1".equals(r.getName())
					== TeaWiringAuthorization.valid(r, tpa)));
			assertEquals(TeaGrantBasis.PUBLISH_EXTERNALLY, stamps(c).get("w-planted").basis());
			UUID r100 = anotherRelease(w, plantedVersion);
			assertEquals(1, triggerRows(r100, gpubA.getUuid()).size(), "(g) gpubA fired by w-planted, no approval");
			assertEquals(TeaActKind.PUBLISH, teaAct(r100).teaActKind());
			assertEquals("Action 'gpubA' fired by rule 'w-planted'", teaAct(r100).teaActComment());

			// (h) the admin assigns hp per component: the refs go, the rules stay
			assertSaved(update(admin(), input(c, "approvalPolicy", hp.toString())), "(h) the admin assigns hp");
			assertTrue(refUuids(storedComponent(c).getGlobalInputEventRefs()).isEmpty(), "(h) refs dropped");
			assertTrue(storedComponent(c).getReleaseInputTriggers().stream()
					.anyMatch(r -> planted.getUuid().equals(r.getUuid())), "(h) w-planted kept");
			vote(w.release(), e2, ApprovalState.DISAPPROVED);
			assertTrue(head(w.release()).isEmpty(), "(h) nothing to hide");
			assertFalse(queued(w.release()));
			assertEquals(1, triggerRows(w.release(), mailH.getUuid()).size(), "(h) the ref is gone: rh-mail fires");
		} finally {
			orgApprovalPolicyService.setRules(org.getUuid(), previous, WU);
		}
	}

	// ---------------------------------------------------------------- 52 (round 4, T-11)

	@Test
	void aNameAnOrgRuleMapsIsWiringEvenUnderAPerComponentPolicy() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String pub = "t7pub-" + tag + "-";
		UUID entry = approvalEntry();
		ReleaseOutputEvent gpub = teaAction(EventType.TEA_PUBLISH, "gpub");
		UUID p = policyWith("tea-7-52-p", List.of(gpub), List.of(rule("gr-pub", approved(entry), gpub)), entry);
		UUID q = policyWith("tea-7-52-q", List.of(), List.of(), entry);
		List<GlobalApprovalPolicyRule> previous = orgRules();
		setOrgRules(previous, orgRule("tea-7-52-" + tag, "^" + pub + ".*", p));
		try {
			UUID x = componentNamed("w-" + tag + "-x");
			UUID y = componentNamed(pub + "y");
			componentService.updateComponent(ComponentDto.builder().uuid(y).approvalPolicy(q).build(), WU);
			UserData writer = writer(x);
			userService.setUserPermission(writer.getUuid(), org.getUuid(), PermissionScope.COMPONENT, y,
					PermissionType.READ_WRITE, List.of(), null, WU);
			writer = userService.getUserData(writer.getUuid()).orElseThrow();
			UserData publisher = writer(x, PermissionFunction.PUBLISH_EXTERNALLY);

			Map<String, Object> intoUnderQ = renameTo(x, pub + "x");
			intoUnderQ.put("approvalPolicy", q.toString());
			assertRefused(update(writer, intoUnderQ), "a rename into the rule in the save that names Q");
			assertEquals("w-" + tag + "-x", nameOf(x), "the name is unchanged");
			assertEquals(null, storedComponent(x).getApprovalPolicy(), "the policy is unchanged");
			assertSaved(update(publisher, intoUnderQ), "the same save with PUBLISH_EXTERNALLY");
			assertEquals(ApprovalPolicySource.PER_COMPONENT, resolution(x).source());
			assertEquals(q, resolution(x).policyUuid());

			assertRefused(update(writer, input(x, "clearApprovalPolicy", true)), "the writer clears Q");
			assertEquals(q, storedComponent(x).getApprovalPolicy());
			assertSaved(update(admin(), input(x, "clearApprovalPolicy", true)), "the admin clears Q");
			assertEquals(ApprovalPolicySource.ORG_RULE, resolution(x).source());
			assertEquals(p, resolution(x).policyUuid());

			assertRefused(update(writer, renameTo(y, "plain-" + tag + "-y")), "a rename out of the rule under Q");
			assertEquals(pub + "y", nameOf(y));
			assertSaved(update(writer, renameTo(y, pub + "z")), "a rename within the rule under Q");
			assertEquals(q, resolution(y).policyUuid());
		} finally {
			orgApprovalPolicyService.setRules(org.getUuid(), previous, WU);
		}
	}

	/** The stored stamps of the component's actions and rules, by name (round 5, case 59). */
	private Map<String, TeaWiringStamp> stamps(UUID component) {
		Map<String, TeaWiringStamp> out = new java.util.HashMap<>();
		ComponentData cd = storedComponent(component);
		if (null != cd.getOutputTriggers()) cd.getOutputTriggers().stream().filter(a -> null != a.getTeaAuth())
				.forEach(a -> out.put(a.getName(), a.getTeaAuth()));
		if (null != cd.getReleaseInputTriggers()) cd.getReleaseInputTriggers().stream().filter(r -> null != r.getTeaAuth())
				.forEach(r -> out.put(r.getName(), r.getTeaAuth()));
		return out;
	}
}
