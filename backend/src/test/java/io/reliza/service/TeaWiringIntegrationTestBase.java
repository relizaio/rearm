/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.Utils;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.GlobalInputEventRef;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.OrganizationData.GlobalApprovalPolicyRule;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.saas.ApprovalPolicyDto;
import io.reliza.model.saas.ApprovalPolicyData;
import io.reliza.service.saas.OrgApprovalPolicyService;
import io.reliza.service.saas.OrgApprovalPolicyService.ApprovalPolicyResolution;
import io.reliza.service.saas.TeaOutputEventService.TeaWiring;

/**
 * TEA-7, rounds 2 to 4: what the classes that drive {@code updateComponent} through GraphQL as a
 * user share (cases 36, 45, 49 to 54): the mutation, inputs built from actions, rules and refs,
 * the refusal and save assertions, a component writer, the stored wiring as the save compares it
 * (six-argument form), policies, organization rules and named components.
 */
abstract class TeaWiringIntegrationTestBase extends TeaOutputEventQueueIntegrationTestBase {

	@Autowired OrgApprovalPolicyService orgApprovalPolicyService;

	static final String UPDATE = "mutation($c: UpdateComponentInput!) { updateComponent(component: $c) { uuid name } }";

	UUID component() throws Exception {
		return componentRelease(null).component();
	}

	String nameOf(UUID component) {
		return getComponentService.getComponentData(component).orElseThrow().getName();
	}

	ComponentData storedComponent(UUID component) {
		return getComponentService.getComponentData(component).orElseThrow();
	}

	static Map<String, Object> action(ReleaseOutputEvent oe) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uuid", oe.getUuid().toString());
		m.put("name", oe.getName());
		m.put("type", oe.getType().name());
		return m;
	}

	static Map<String, Object> rule(ReleaseInputEvent r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uuid", r.getUuid().toString());
		m.put("name", r.getName());
		m.put("celExpression", r.getCelExpression());
		m.put("enabled", r.isEnabled());
		m.put("outputEvents", r.getOutputEvents().stream().map(UUID::toString).toList());
		return m;
	}

	/** An update of {@code component} carrying its name, plus the given keys. */
	Map<String, Object> input(UUID component, Object... kv) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("uuid", component.toString());
		c.put("name", nameOf(component));
		for (int i = 0; i < kv.length; i += 2) c.put((String) kv[i], kv[i + 1]);
		return c;
	}

	ExecutionResult update(UserData user, Map<String, Object> input) {
		return asUser(user, UPDATE, vars("c", input));
	}

	static void assertRefused(ExecutionResult r, String what) {
		assertFalse(r.getErrors().isEmpty(), what + " was saved");
		String message = r.getErrors().get(0).getMessage();
		assertTrue(message.contains(TeaWiring.NEEDS_PUBLISH_EXTERNALLY), what + ": " + message);
	}

	static void assertSaved(ExecutionResult r, String what) {
		assertTrue(r.getErrors().isEmpty(), what + ": " + r.getErrors());
	}

	/** A member with READ_WRITE on the component only, and the given functions there. */
	UserData writer(UUID component, PermissionFunction... functions) throws Exception {
		UserData u = member(PermissionType.READ_ONLY);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.COMPONENT, component,
				PermissionType.READ_WRITE, List.of(functions), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** The stored wiring, built as OssComponentDataFetcher.authorizeAndStampTeaWiring builds it (round 4, six arguments). */
	TeaWiring wiring(UUID component) {
		ComponentData cd = storedComponent(component);
		ComponentData byRules = Utils.OM.convertValue(Utils.dataToRecord(cd), ComponentData.class);
		byRules.setUuid(cd.getUuid());
		byRules.setApprovalPolicy(null);
		return TeaWiring.of(cd.getOutputTriggers(), cd.getReleaseInputTriggers(), cd.getGlobalInputEventRefs(),
				orgApprovalPolicyService.resolvePolicyDataByComponent(cd).orElse(null),
				orgApprovalPolicyService.resolvePolicyDataByComponent(byRules).orElse(null),
				approvalPolicyService.listApprovalPoliciesOfOrg(org.getUuid()));
	}

	/** A mail action with an empty recipient list, so it fires (and records its TRIGGER row) in a test. */
	static ReleaseOutputEvent mail(String name) {
		return ReleaseOutputEvent.builder().uuid(UUID.randomUUID()).name(name)
				.type(io.reliza.model.ComponentData.EventType.EMAIL_NOTIFICATION).users(Set.of()).build();
	}

	static Map<String, Object> ref(UUID globalRule, boolean disabled, UUID... override) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uuid", globalRule.toString());
		m.put("disabled", disabled);
		if (override.length > 0) {
			m.put("overrideOutputEventsLocally", true);
			m.put("outputEventsOverride", java.util.Arrays.stream(override).map(UUID::toString).toList());
		}
		return m;
	}

	static Set<UUID> refUuids(List<GlobalInputEventRef> refs) {
		return null == refs ? Set.of() : Set.copyOf(refs.stream().map(GlobalInputEventRef::getUuid).toList());
	}

	/** A policy of the fixture's organization over the entries, with these global actions and rules. */
	UUID policyWith(String name, List<ReleaseOutputEvent> actions, List<ReleaseInputEvent> rules, UUID... entries)
			throws Exception {
		UUID policy = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName(name + "-" + UUID.randomUUID()).approvalEntries(List.of(entries)).build(), WU).getUuid();
		approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(actions), WU);
		approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(rules), WU);
		return policy;
	}

	ApprovalPolicyData policyData(UUID policy) {
		return approvalPolicyService.getApprovalPolicyData(policy).orElseThrow();
	}

	static GlobalApprovalPolicyRule orgRule(String name, String pattern, UUID policy) {
		GlobalApprovalPolicyRule r = new GlobalApprovalPolicyRule();
		r.setName(name);
		r.setNamePattern(pattern);
		r.setApprovalPolicy(policy);
		return r;
	}

	List<GlobalApprovalPolicyRule> orgRules() {
		List<GlobalApprovalPolicyRule> rules = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow()
				.getGlobalApprovalPolicyRules();
		return new ArrayList<>(null == rules ? List.of() : rules);
	}

	/** Sets the organization's rules to {@code base} plus {@code added}, as an admin's org settings save does. */
	void setOrgRules(List<GlobalApprovalPolicyRule> base, GlobalApprovalPolicyRule... added) throws Exception {
		List<GlobalApprovalPolicyRule> rules = new ArrayList<>(base);
		rules.addAll(List.of(added));
		orgApprovalPolicyService.setRules(org.getUuid(), rules, WU);
	}

	UUID componentNamed(String name) throws Exception {
		return componentService.createComponent(CreateComponentDto.builder().organization(org.getUuid()).name(name)
				.type(ComponentType.COMPONENT).versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU)
				.getUuid();
	}

	Map<String, Object> renameTo(UUID component, String name) {
		Map<String, Object> c = input(component);
		c.put("name", name);
		return c;
	}

	ApprovalPolicyResolution resolution(UUID component) {
		return orgApprovalPolicyService.resolveForComponent(storedComponent(component),
				getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow());
	}

	/** Another ASSEMBLED release of the world's component at {@code version}, carrying one SBOM. */
	UUID anotherRelease(World w, String version) throws Exception {
		UUID release = draftRelease(w, version);
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		return release;
	}

	/** anotherRelease up to its promotion: a DRAFT release carrying one SBOM, no pass that fires anything yet. */
	UUID draftRelease(World w, String version) throws Exception {
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(w.component())
				.branch(w.branch()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version).build(), WU).getUuid();
		releasesByVersion.put(version, release);
		UUID sbom = seedBom("tea-7-r4-" + UUID.randomUUID() + ".cdx.json");
		releaseService.addArtifact(sbom, release, WU);
		mergedPurls.put(release, new ArrayList<>(List.of("pkg:generic/another@" + version)));
		return release;
	}

	/** An SBOM upload on the release: a NORMAL pass. */
	void sbomPass(UUID release) throws Exception {
		UUID sbom = seedBom("tea-7-r4-" + UUID.randomUUID() + ".cdx.json");
		mergedPurls.computeIfAbsent(release, k -> new ArrayList<>()).add("pkg:generic/pass@" + UUID.randomUUID());
		releaseService.addArtifact(sbom, release, WU);
	}

	// the policy editor's two whole-list mutations, as an admin sends them (round 4 case 53, round 5 cases 55 to 58)
	static final String SET_OUTPUTS = "mutation($p: ID!, $e: [ReleaseOutputEventInput!]!) { "
			+ "setGlobalOutputEvents(approvalPolicyUuid: $p, events: $e) { uuid } }";
	static final String SET_INPUTS = "mutation($p: ID!, $e: [ReleaseInputEventInput!]!) { "
			+ "setGlobalInputEvents(approvalPolicyUuid: $p, events: $e) { uuid } }";

	ExecutionResult setOutputs(UserData admin, UUID policy, ReleaseOutputEvent... events) {
		return asUser(admin, SET_OUTPUTS, vars("p", policy.toString(), "e", List.of(events).stream()
				.map(TeaWiringIntegrationTestBase::action).toList()));
	}

	ExecutionResult setInputs(UserData admin, UUID policy, ReleaseInputEvent... rules) {
		return asUser(admin, SET_INPUTS, vars("p", policy.toString(), "e", List.of(rules).stream()
				.map(TeaWiringIntegrationTestBase::rule).toList()));
	}

	static ReleaseInputEvent copyOf(ReleaseInputEvent r) {
		ReleaseInputEvent c = rule(r.getName(), r.getCelExpression());
		c.setUuid(r.getUuid());
		c.setOutputEvents(r.getOutputEvents());
		c.setEnabled(r.isEnabled());
		return c;
	}
}
