/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared fixtures of the EXTERNAL key integration tests (task TEA-3): users with a security context, GraphQL as
 * that user, components of a fresh organization.
 */
abstract class ExternalKeyIntegrationTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	protected static final String CREATE = "mutation($o: ID!, $h: String!, $n: String) {"
			+ " createExternalApiKey(orgUuid: $o, holderName: $h, notes: $n) { uuid type holderName teaOrg keyId keyOrder notes"
			+ " status secrets { slot } } }";
	protected static final String EXTERNAL_KEYS = "query($o: ID!) { externalApiKeys(orgUuid: $o) { uuid type keyId } }";
	protected static final String SET_GRANTS = "mutation($k: ID!, $t: PermissionType, $p: [PermissionInput]) {"
			+ " setPermissionsOnFreeformApiKey(apiKeyUuid: $k, permissionType: $t, permissions: $p) {"
			+ " permissions { permissions { scope object type functions approvals } } } }";
	protected static final String ADD_SECRET = "mutation($k: ID!) { addApiKeySecret(apiKeyUuid: $k) { apiKey id authorizationHeader } }";

	@MockitoSpyBean protected UserService userService;
	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ComponentService componentService;
	@Autowired protected LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

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

	protected UserData user(Organization org, PermissionScope scope, UUID object) throws RelizaException {
		return user(org, scope, object, PermissionType.ADMIN);
	}

	/** An organization-wide READ_WRITE member: passes the gates that need org WRITE, not those that need ADMIN. */
	protected UserData writer(Organization org) throws RelizaException {
		return user(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE);
	}

	/** An organization-wide READ_ONLY member: passes the gates that need org READ only. */
	protected UserData reader(Organization org) throws RelizaException {
		return user(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY);
	}

	protected UserData user(Organization org, PermissionScope scope, UUID object, PermissionType type) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea3 " + tag, "tea3-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tea3-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected UserData admin(Organization org) throws RelizaException {
		return user(org, PermissionScope.ORGANIZATION, org.getUuid());
	}

	protected Component component(Organization org) throws RelizaException {
		return component(org, ComponentType.COMPONENT);
	}

	protected Component component(Organization org, ComponentType type) throws RelizaException {
		return componentService.createComponent("tea3-" + UUID.randomUUID(), org.getUuid(), type, "semver", "Branch.Micro", null, WU);
	}

	protected ExecutionResult exec(UserData user, String query, Object... vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-3-test").build();
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

	protected static void fails(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "expected an error");
	}

	protected static Map<String, Object> grant(String scope, Object object, String type, List<String> functions) {
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("scope", scope);
		g.put("object", object == null ? null : object.toString());
		g.put("type", type);
		g.put("functions", functions);
		g.put("approvals", List.of());
		return g;
	}

	protected Map<String, Object> createKey(UserData admin, Organization org, String holder) {
		return ok(exec(admin, CREATE, "o", org.getUuid().toString(), "h", holder, "n", "tea3 test"), "createExternalApiKey");
	}
}
