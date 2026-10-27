/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.App;

/**
 * CE refuses TEA output events at the one write path that can carry them (SCORE-72 design 3.3.3,
 * ADR-11, T-12): CE has no engine that fires TEA_PUBLISH or TEA_HIDE, so a component save whose
 * outputTriggers carry either kind is refused whole with "TEA output events are a ReARM Pro feature",
 * after the RESOURCE WRITE check. CE's own test, under oss/, never mirrored.
 */
@SpringBootTest(classes = {App.class})
public class OssComponentDataFetcherTeaWiringTest {

	private static final String REFUSAL = "TEA output events are a ReARM Pro feature";
	private static final String UPDATE_COMPONENT = "mutation($c: UpdateComponentInput!) { updateComponent(component: $c)"
			+ " { uuid outputTriggers { type name } } }";
	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	// The system seal only (not the licence flag, which CE's LicensingConstants sets): a fresh test
	// database is sealed, and a sealed system refuses every non-admin call before authorization.
	@Autowired private LicenseStatus licenseStatus;
	private boolean wasSealed;

	@BeforeEach
	void unsealed() {
		wasSealed = licenseStatus.isSystemSealed();
		licenseStatus.setSystemSealed(false);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		SecurityContextHolder.clearContext();
	}

	private record World(Organization org, Component component, UserData writer, UserData reader) {}

	private World world() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("score72-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		return new World(org, c, member(org, PermissionType.READ_WRITE), member(org, PermissionType.READ_ONLY));
	}

	private UserData member(Organization org, PermissionType level) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea " + tag, "score72-" + tag + "@tea-wiring.io", true, List.of(org.getUuid()),
				"score72-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level,
				Set.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-wiring-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	/** Saves the component with {@code triggers} as outputTriggers; {@code null} omits the field. */
	private ExecutionResult save(UserData user, World w, List<Map<String, Object>> triggers) {
		Map<String, Object> input = new HashMap<>();
		input.put("uuid", w.component().getUuid().toString());
		input.put("name", stored(w).getName());
		if (null != triggers) input.put("outputTriggers", triggers);
		return asPerson(user, UPDATE_COMPONENT, Map.of("c", input));
	}

	private static Map<String, Object> trigger(String name, EventType type) {
		return Map.of("name", name, "type", type.name());
	}

	private static Map<String, Object> mail(UserData to) {
		return Map.of("name", "mail", "type", EventType.EMAIL_NOTIFICATION.name(),
				"users", List.of(to.getUuid().toString()));
	}

	private ComponentData stored(World w) {
		return getComponentService.getComponentData(w.component().getUuid()).orElseThrow();
	}

	private static List<?> triggersOf(ComponentData cd) {
		return null == cd.getOutputTriggers() ? List.of() : cd.getOutputTriggers();
	}

	private static void assertRefused(ExecutionResult r) {
		assertEquals(1, r.getErrors().size(), String.valueOf(r.getErrors()));
		assertEquals(REFUSAL, r.getErrors().get(0).getMessage());
	}

	@Test
	void aTeaPublishActionIsRefusedAndNothingIsStored() throws Exception {
		World w = world();
		assertRefused(save(w.writer(), w, List.of(trigger("publish", EventType.TEA_PUBLISH))));
		assertEquals(List.of(), triggersOf(stored(w)));
	}

	@Test
	void aTeaHideActionIsRefusedAndNothingIsStored() throws Exception {
		World w = world();
		assertRefused(save(w.writer(), w, List.of(trigger("hide", EventType.TEA_HIDE))));
		assertEquals(List.of(), triggersOf(stored(w)));
	}

	@Test
	void aListMixingAPlainActionAndATeaActionIsRefusedWhole() throws Exception {
		World w = world();
		assertRefused(save(w.writer(), w, List.of(mail(w.writer()), trigger("hide", EventType.TEA_HIDE))));
		assertEquals(List.of(), triggersOf(stored(w)), "no partial save");
	}

	@Test
	@SuppressWarnings("unchecked")
	void aSaveWithoutATeaActionIsUnchanged() throws Exception {
		World w = world();
		ExecutionResult r = save(w.writer(), w, List.of(mail(w.writer())));
		assertTrue(r.getErrors().isEmpty(), String.valueOf(r.getErrors()));
		Map<String, Object> saved = (Map<String, Object>) ((Map<String, Object>) r.getData()).get("updateComponent");
		assertEquals(List.of(Map.of("type", "EMAIL_NOTIFICATION", "name", "mail")), saved.get("outputTriggers"));
		assertEquals(1, triggersOf(stored(w)).size());

		// an omitted outputTriggers leaves the stored actions alone; an empty list clears them
		r = save(w.writer(), w, null);
		assertTrue(r.getErrors().isEmpty(), String.valueOf(r.getErrors()));
		assertEquals(1, triggersOf(stored(w)).size());
		r = save(w.writer(), w, List.of());
		assertTrue(r.getErrors().isEmpty(), String.valueOf(r.getErrors()));
		assertEquals(List.of(), triggersOf(stored(w)));
	}

	@Test
	void aCallerWithoutResourceWriteGetsTheAuthorizationRefusalNotTheTeaMessage() throws Exception {
		World w = world();
		ExecutionResult r = save(w.reader(), w, List.of(trigger("publish", EventType.TEA_PUBLISH)));
		assertEquals(1, r.getErrors().size(), String.valueOf(r.getErrors()));
		assertNotEquals(REFUSAL, r.getErrors().get(0).getMessage());
		assertEquals(List.of(), triggersOf(stored(w)));
	}
}
