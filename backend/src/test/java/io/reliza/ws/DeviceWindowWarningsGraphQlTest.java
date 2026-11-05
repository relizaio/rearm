/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.DeviceClass;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * FDAW-3, B3: {@code DeviceSupportWindow.warnings} is derived on read and comes back IN THE MUTATION
 * RESPONSE of {@code updateComponent}, so the order of the two dates is a warning returned with the save,
 * never a refusal. The device-class refusal stays a refusal.
 */
@SpringBootTest(classes = {App.class})
public class DeviceWindowWarningsGraphQlTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String WINDOW = "medicalProfile { deviceSupportWindow { eos eol warnings { code message } } }";
	private static final String UPDATE = "mutation($c: UpdateComponentInput!) { updateComponent(component: $c) { uuid "
			+ WINDOW + " } }";
	private static final String READ = "query($u: ID!) { component(componentUuid: $u) { uuid " + WINDOW + " } }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;
	private Organization org;
	private UUID component;
	private String name;

	@BeforeEach
	void world() throws Exception {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
		org = testInitializer.obtainOrganization();
		name = "fdaw3-gql-" + UUID.randomUUID().toString().substring(0, 8);
		Component c = componentService.createComponent(name, org.getUuid(), ComponentType.PRODUCT, "semver",
				"Branch.Micro", null, WU);
		component = c.getUuid();
		componentService.updateComponent(ComponentDto.builder().uuid(component).name(name)
				.deviceClass(DeviceClass.MEDICAL_UNTRACKED).build(), WU);
		actAs(admin());
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private UserData admin() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Admin " + tag, "fdaw3-" + tag + "@device.io", true, List.of(org.getUuid()),
				"fdaw3-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private void actAs(UserData user) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("fdaw-3-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	private ExecutionResult update(Map<String, Object> extra) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("uuid", component.toString());
		c.put("name", name);
		c.putAll(extra);
		return dgsQueryExecutor.execute(UPDATE, Map.of("c", c));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> warnings(ExecutionResult r, String root) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> comp = (Map<String, Object>) ((Map<String, Object>) r.getData()).get(root);
		Map<String, Object> mp = (Map<String, Object>) comp.get("medicalProfile");
		Map<String, Object> w = (Map<String, Object>) mp.get("deviceSupportWindow");
		return (List<Map<String, Object>>) w.get("warnings");
	}

	private ExecutionResult window(String eos, String eol) {
		Map<String, Object> w = new LinkedHashMap<>();
		if (null != eos) w.put("eos", eos);
		if (null != eol) w.put("eol", eol);
		return update(Map.of("deviceSupportWindow", w));
	}

	@Test
	void endOfSupportBeforeEndOfLifeIsSavedAndTheMutationResponseCarriesTheWarning() {
		List<Map<String, Object>> ws = warnings(window("2030-12-31", "2033-12-31"), "updateComponent");
		assertEquals(1, ws.size(), ws.toString());
		assertEquals("EOS_BEFORE_EOL", ws.get(0).get("code"));
		String message = (String) ws.get(0).get("message");
		assertTrue(null != message && !message.isBlank(), ws.toString());
		assertTrue(message.contains("2030-12-31") && message.contains("2033-12-31"), message);
		// the read returns the same list: derived on read from what was stored
		assertEquals(ws, warnings(dgsQueryExecutor.execute(READ, Map.of("u", component.toString())), "component"));
	}

	@Test
	void theImdrfOrderIsSavedWithNoWarning() {
		assertEquals(List.of(), warnings(window("2034-06-30", "2033-12-31"), "updateComponent"));
		assertEquals(List.of(), warnings(dgsQueryExecutor.execute(READ, Map.of("u", component.toString())),
				"component"));
	}

	@Test
	void oneDateAloneWarnsNothing() {
		assertEquals(List.of(), warnings(window("2030-12-31", null), "updateComponent"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void aWindowOnANonDeviceIsStillRefused() {
		Map<String, Object> w = new LinkedHashMap<>();
		w.put("eos", "2030-12-31");
		w.put("eol", "2033-12-31");
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("deviceClass", "NONE");
		extra.put("deviceSupportWindow", w);
		ExecutionResult r = update(extra);
		assertFalse(r.getErrors().isEmpty(), "a window on a non-device must stay a refusal");
		// The refusal's text is pinned at the service (DeviceSupportWindowPatchTest
		// .aWindowOnANonDeviceIsRejected): CE's updateComponent resolver rethrows a RelizaException
		// as AccessDeniedException, which reaches the client as "Not authorized". What this pins on
		// both editions is that the refused save changed nothing.
		ExecutionResult read = dgsQueryExecutor.execute(
				"query($u: ID!) { component(componentUuid: $u) { deviceClass " + WINDOW + " } }",
				Map.of("u", component.toString()));
		assertTrue(read.getErrors().isEmpty(), read.getErrors().toString());
		Map<String, Object> comp = (Map<String, Object>) ((Map<String, Object>) read.getData()).get("component");
		assertEquals("MEDICAL_UNTRACKED", comp.get("deviceClass"), "the refused save must not change the class");
		Map<String, Object> mp = (Map<String, Object>) comp.get("medicalProfile");
		assertTrue(null == mp || null == mp.get("deviceSupportWindow"), "the refused save must store no window");
	}
}
