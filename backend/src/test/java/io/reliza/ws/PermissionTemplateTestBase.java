/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.AgentBoardData;
import io.reliza.model.CliSession;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.PermissionTemplate;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.CliSessionRepository;
import io.reliza.service.AgentBoardService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuditService;
import io.reliza.service.CliSessionService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.PermissionTemplateService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The shared rig of the permission template tests (TPF-87, TPF-112 architecture round 4 section 5): the
 * CliSessionLifetimeTest rig (the person signed in is stubbed, everything else is the real server), the
 * section 5 world (alice READ_WRITE with LIFECYCLE_UPDATE, bob READ_ONLY, a component and a board of the
 * organization), the browser-lane documents and an approval from a template's rows.
 */
@SpringBootTest(classes = {App.class})
abstract class PermissionTemplateTestBase {

	@MockitoSpyBean UserService userService;
	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired TestInitializer testInitializer;
	@Autowired ApiKeyService apiKeyService;
	@Autowired CliSessionService cliSessionService;
	@Autowired CliSessionRepository cliSessionRepository;
	@Autowired ComponentService componentService;
	@Autowired AgentBoardService agentBoardService;
	@Autowired PermissionTemplateService permissionTemplateService;
	@Autowired AuditService auditService;
	@Autowired LicenseStatus licenseStatus;

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	static final String SELECTION = "{ uuid name description org user lastUpdatedBy createdDate"
			+ " permissions { permissions { org scope object type functions approvals } } }";
	static final String LIST = "query($o: ID) { myPermissionTemplates(org: $o) " + SELECTION + " }";
	static final String CREATE = "mutation($t: CreatePermissionTemplateInput!) { createPermissionTemplate(template: $t) " + SELECTION + " }";
	static final String UPDATE = "mutation($t: UpdatePermissionTemplateInput!) { updatePermissionTemplate(template: $t) " + SELECTION + " }";
	static final String DELETE = "mutation($u: ID!) { deletePermissionTemplate(uuid: $u) }";
	static final String APPROVE_NEW = "mutation($c: String!, $o: ID, $m: Int, $t: PermissionType, $p: [PermissionInput]) "
			+ "{ approveCliLogin(userCode: $c, createKeyOrgUuid: $o, maxMinutes: $m, permissionType: $t, permissions: $p) { uuid status } }";

	boolean wasSealed;
	boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	// ---------- the rig ----------

	record World(Organization org, UserData alice, UserData bob, Component c, AgentBoardData b) {}

	UserData member(Organization org, PermissionType level) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("T " + tag, "t-" + tag + "@templates.io", true, List.of(org.getUuid()),
				"t-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level, null, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	World world() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData alice = member(org, PermissionType.READ_WRITE);
		userService.setUserPermission(alice.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_WRITE, Set.of(PermissionFunction.LIFECYCLE_UPDATE), null, WU);
		alice = userService.getUserData(alice.getUuid()).orElseThrow();
		UserData bob = member(org, PermissionType.READ_ONLY);
		Component c = componentService.createComponent("pt_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Component target = componentService.createComponent("pt_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData b = agentBoardService.createBoard(org.getUuid(), "pt-" + UUID.randomUUID(), "permission templates",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		return new World(org, alice, bob, c, b);
	}

	ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("templates-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	static Map<String, Object> row(PermissionScope scope, UUID object, PermissionType type, List<String> functions, List<String> approvals) {
		Map<String, Object> m = new HashMap<>();
		m.put("scope", scope.name());
		m.put("objectId", object.toString());
		m.put("type", type.name());
		if (null != functions) m.put("functions", functions);
		if (null != approvals) m.put("approvals", approvals);
		return m;
	}

	static Map<String, Object> orgRow(Organization org, PermissionType type, String... functions) {
		return row(PermissionScope.ORGANIZATION, org.getUuid(), type, List.of(functions), null);
	}

	static Map<String, Object> componentRow(UUID component, PermissionType type) {
		return row(PermissionScope.COMPONENT, component, type, null, null);
	}

	static Map<String, Object> createInput(Organization org, String name, String description, List<Map<String, Object>> rows) {
		Map<String, Object> m = new HashMap<>();
		m.put("org", org.getUuid().toString());
		m.put("name", name);
		if (null != description) m.put("description", description);
		if (null != rows) m.put("permissions", rows);
		return m;
	}

	static Map<String, Object> updateInput(UUID uuid, String name, String description, List<Map<String, Object>> rows) {
		Map<String, Object> m = new HashMap<>();
		m.put("uuid", uuid.toString());
		if (null != name) m.put("name", name);
		if (null != description) m.put("description", description);
		if (null != rows) m.put("permissions", rows);
		return m;
	}

	static Map<String, Object> ok(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		@SuppressWarnings("unchecked")
		Map<String, Object> out = (Map<String, Object>) data.get(field);
		assertNotNull(out, field);
		return out;
	}

	static String onlyError(ExecutionResult r) {
		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		return r.getErrors().get(0).getMessage();
	}

	UUID create(UserData who, Map<String, Object> input) {
		return UUID.fromString((String) ok(asPerson(who, CREATE, Map.of("t", input)), "createPermissionTemplate").get("uuid"));
	}

	ExecutionResult update(UserData who, Map<String, Object> input) {
		return asPerson(who, UPDATE, Map.of("t", input));
	}

	ExecutionResult delete(UserData who, UUID uuid) {
		return asPerson(who, DELETE, Map.of("u", uuid.toString()));
	}

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> list(UserData who, Organization org) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("o", null == org ? null : org.getUuid().toString());
		ExecutionResult r = asPerson(who, LIST, vars);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("myPermissionTemplates");
	}

	/** A permission set as sorted SCOPE:object:TYPE:[functions] lines, for exact comparison. */
	static Set<String> render(Set<UserPermission> set) {
		return set.stream().map(p -> p.getScope() + ":" + p.getObject() + ":" + p.getType() + ":" + new TreeSet<>(
				p.getFunctions().stream().map(Enum::name).toList())).collect(Collectors.toCollection(TreeSet::new));
	}

	static String line(PermissionScope scope, UUID object, PermissionType type, PermissionFunction... fns) {
		return scope + ":" + object + ":" + type + ":" + new TreeSet<>(java.util.Arrays.stream(fns).map(Enum::name).toList());
	}

	Set<String> stored(UUID template, Organization org) {
		return render(permissionTemplateService.getPermissionTemplateData(template).orElseThrow().getOrgPermissions(org.getUuid()));
	}

	PermissionTemplate rowOf(UUID template) {
		return permissionTemplateService.getPermissionTemplate(template).orElseThrow();
	}

	/** The template's rows as the UI sends them to approveCliLogin (PermissionInput maps). */
	List<Map<String, Object>> asPermissionInputs(UUID template, Organization org) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (UserPermission p : permissionTemplateService.getPermissionTemplateData(template).orElseThrow().getOrgPermissions(org.getUuid())) {
			Map<String, Object> m = new HashMap<>();
			m.put("org", org.getUuid().toString());
			m.put("scope", p.getScope().name());
			m.put("object", p.getObject().toString());
			m.put("type", p.getType().name());
			m.put("functions", p.getFunctions().stream().map(Enum::name).toList());
			m.put("approvals", new ArrayList<>(p.getApprovals()));
			out.add(m);
		}
		return out;
	}

	/** A pending login approved by {@code who} with a new key; the key's permission set in the organization. */
	Set<String> approveFromTemplate(UserData who, Organization org, PermissionType permissionType, UUID template) {
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("templates-host", null, null, null, null));
		assertNotNull(cliSessionService.pending(d.userCode()).orElse(null));
		Map<String, Object> vars = new HashMap<>();
		vars.put("c", d.userCode());
		vars.put("o", org.getUuid().toString());
		vars.put("t", permissionType.name());
		vars.put("p", asPermissionInputs(template, org));
		Map<String, Object> approved = ok(asPerson(who, APPROVE_NEW, vars), "approveCliLogin");
		CliSession s = cliSessionRepository.findById(UUID.fromString((String) approved.get("uuid"))).orElseThrow();
		keyOfLastApproval = s.getApiKey();
		return keyPermissions(keyOfLastApproval, org);
	}

	UUID keyOfLastApproval;

	Set<String> keyPermissions(UUID key, Organization org) {
		return render(apiKeyService.getApiKeyData(key).orElseThrow().getPermissions(org.getUuid()).getOrgPermissionsAsSet(org.getUuid()));
	}

	Map<String, Object> t1(World w) {
		return createInput(w.org(), "T1-" + UUID.randomUUID(), "first", List.of(
				orgRow(w.org(), PermissionType.READ_WRITE, "LIFECYCLE_UPDATE"),
				componentRow(w.c().getUuid(), PermissionType.READ_ONLY)));
	}

}
