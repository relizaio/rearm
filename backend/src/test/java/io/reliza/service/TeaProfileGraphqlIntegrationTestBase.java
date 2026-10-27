/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.ws.App;
import io.reliza.ws.RelizaConfigProps;
import io.reliza.ws.oss.TestInitializer;

/**
 * The shared fixture of the TEA profile GraphQL integration tests (tasks TEA-2 and TEA-9): users
 * with a scope ADMIN, components, the built-in defaults as an input, and the query helpers.
 */
@SpringBootTest(classes = {App.class})
public abstract class TeaProfileGraphqlIntegrationTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	protected static final String SAVE = "mutation($o: ID!, $s: TeaProfileScope!, $ob: ID, $p: TeaProfileInput!) {"
			+ " saveTeaProfile(org: $o, scope: $s, object: $ob, profile: $p) { uuid scope object mode followedPerspective"
			+ " publishing visibility profiles minimumLifecycle revision } }";
	protected static final String PROFILE = "query($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " teaProfile(org: $o, scope: $s, object: $ob) { uuid scope publishing } }";
	protected static final String VIEW = "query($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " teaProfileEditorView(org: $o, scope: $s, object: $ob) { stored { uuid profiles visibility }"
			+ " effective { status source sourceObject profile { uuid profiles visibility }"
			+ " conflictingPerspectives { uuid name type } }"
			+ " parent { status source conflictingPerspectives { name } } publishedReleases supportInjection } }";
	protected static final String LIST = "query($o: ID!) { teaProfilesOfOrg(org: $o) { uuid scope object } }";
	protected static final String DELETE = "mutation($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " deleteTeaProfile(org: $o, scope: $s, object: $ob) }";
	protected static final String DISCOVERY = "query($o: ID!) { teaOrgDiscovery(org: $o) { org teaUuid apiBase wellKnownDocument } }";

	@MockitoSpyBean protected UserService userService;
	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ComponentService componentService;
	@Autowired protected OrganizationService organizationService;
	@Autowired protected TeaProfileRepository teaProfileRepository;
	@Autowired protected OrganizationRepository organizationRepository;
	@Autowired protected ComponentRepository componentRepository;
	@Autowired protected RelizaConfigProps relizaConfigProps;
	@Autowired protected LicenseStatus licenseStatus;

	protected boolean wasSealed;
	protected boolean wasLicensed;

	@BeforeEach
	void operational() {
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

	// ---- fixtures ----

	protected UserData user(Organization org, PermissionScope scope, UUID object) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea " + tag, "tea2-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tea2-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected UserData admin(Organization org) throws RelizaException {
		return user(org, PermissionScope.ORGANIZATION, org.getUuid());
	}

	protected Component component(Organization org) throws RelizaException {
		return componentService.createComponent("tea2-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
	}

	/** The built-in defaults as an input, with the named changes. */
	protected static Map<String, Object> profile(Object... changes) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("publishing", "DISABLED");
		p.put("visibility", "PRIVATE");
		p.put("rawArtifacts", "NONE");
		p.put("productComponents", "PUBLISH_WITH_PRODUCT");
		p.put("minimumLifecycle", "ASSEMBLED");
		p.put("tei", "DISABLED");
		p.put("profiles", List.of("default"));
		for (int i = 0; i < changes.length; i += 2) p.put((String) changes[i], changes[i + 1]);
		return p;
	}

	protected ExecutionResult exec(UserData user, String query, Object... vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-2-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		Map<String, Object> v = new LinkedHashMap<>();
		for (int i = 0; i < vars.length; i += 2) v.put((String) vars[i], vars[i + 1]);
		return dgsQueryExecutor.execute(query, v);
	}

	@SuppressWarnings("unchecked")
	protected <T> T ok(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	protected static void failsWith(ExecutionResult r, String fragment) {
		assertFalse(r.getErrors().isEmpty(), "expected an error containing " + fragment);
		assertTrue(r.getErrors().toString().contains(fragment), r.getErrors().toString());
	}

	protected ExecutionResult save(UserData u, Organization org, String scope, UUID object, Map<String, Object> p) {
		return exec(u, SAVE, "o", org.getUuid().toString(), "s", scope, "ob", null == object ? null : object.toString(),
				"p", p);
	}

	protected Map<String, Object> view(UserData u, Organization org, String scope, UUID object) {
		return ok(exec(u, VIEW, "o", org.getUuid().toString(), "s", scope, "ob",
				null == object ? null : object.toString()), "teaProfileEditorView");
	}

	@SuppressWarnings("unchecked")
	protected static Object at(Map<String, Object> m, String path) {
		Object cur = m;
		for (String k : path.split("\\.")) cur = null == cur ? null : ((Map<String, Object>) cur).get(k);
		return cur;
	}

	protected String orgTeaUuid(UserData u, Organization org) {
		List<Map<String, Object>> orgs = ok(exec(u, "{ organizations { uuid teaUuid } }"), "organizations");
		return (String) orgs.stream().filter(o -> org.getUuid().toString().equals(o.get("uuid"))).findFirst()
				.orElseThrow().get("teaUuid");
	}

	protected UUID perspective(UserData admin, Organization org, String name) {
		String create = "mutation($o: ID!, $n: String!) { createPerspective(org: $o, name: $n) { uuid } }";
		return UUID.fromString((String) ((Map<?, ?>) ok(exec(admin, create, "o", org.getUuid().toString(), "n",
				name + "-" + UUID.randomUUID()), "createPerspective")).get("uuid"));
	}

	protected void perspectivesOf(UserData admin, Component c, UUID... perspectives) {
		ok(exec(admin, "mutation($c: ID!, $p: [ID!]!) { setPerspectivesOnComponent(componentUuid: $c, perspectiveUuids: $p)"
				+ " { uuid } }", "c", c.getUuid().toString(), "p", java.util.Arrays.stream(perspectives).map(UUID::toString)
				.toList()), "setPerspectivesOnComponent");
	}

	protected ExecutionResult delete(UserData u, Organization org, String scope, UUID object) {
		return exec(u, DELETE, "o", org.getUuid().toString(), "s", scope, "ob", null == object ? null : object.toString());
	}
}
