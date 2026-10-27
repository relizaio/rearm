/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.saas.Instance;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.service.saas.InstanceService;
import io.reliza.ws.oss.TestInitializer;

/**
 * An organization administrator's kill switch on a key (INACTIVE and adminDisabled) is undone by an administrator
 * only: not by an org writer through setApiKeyStatus, not by a component or instance writer re-minting the key, and
 * not by a declarative write that read the key before the administrator disabled it (task t20261014-124950-26722).
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyKillSwitchTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyRepository apiKeyRepository;
	@Autowired private ComponentService componentService;
	@Autowired private InstanceService instanceService;
	@Autowired private PlatformTransactionManager txManager;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String STATUS = "mutation($k: ID!, $s: ApiKeyStatus!) { setApiKeyStatus(apiKeyUuid: $k, status: $s) { uuid } }";
	private static final String COMPONENT_MINT = "mutation($c: ID!) { setComponentApiKey(componentUuid: $c) { apiKey } }";
	private static final String ARCHIVE = "mutation($c: ID!) { archiveComponent(componentUuid: $c) }";
	private static final String INSTANCE_MINT = "mutation($i: ID!) { setInstanceApiKey(instanceUuid: $i) { apiKey } }";
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
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		SecurityContextHolder.clearContext();
	}

	private UserData member(Organization org, PermissionType level, PermissionFunction... functions) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("S " + tag, "s-" + tag + "@kill-switch.io", true, List.of(org.getUuid()),
				"s-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level,
				functions.length == 0 ? null : Set.of(functions), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("kill-switch-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private ExecutionResult setStatus(UserData user, UUID key, ApiKeyStatus status) {
		return asPerson(user, STATUS, Map.of("k", key.toString(), "s", status.name()));
	}

	private ApiKeyData data(UUID key) {
		return ApiKeyData.dataFromRecord(apiKeyService.getApiKey(key).orElseThrow());
	}

	private static void assertOk(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), String.valueOf(r.getErrors()));
	}

	private static void assertKillSwitchRefusal(ExecutionResult r) {
		assertEquals(1, r.getErrors().size(), String.valueOf(r.getErrors()));
		assertEquals(ApiKeyService.ADMIN_DISABLED_REFUSAL, r.getErrors().get(0).getMessage());
	}

	private void assertDisabledByAdmin(UUID key) {
		ApiKeyData akd = data(key);
		assertEquals(ApiKeyStatus.INACTIVE, akd.getStatus());
		assertTrue(akd.isAdminDisabled());
		assertTrue(apiKeyService.getApiKeyDto(key).orElseThrow().getAdminDisabled(), "the listing reports the switch");
	}

	private UUID componentKey(Component c, Organization org) {
		return apiKeyService.getApiKeyByObjUuidTypeOrder(c.getUuid(), ApiTypeEnum.COMPONENT, null, org.getUuid()).orElseThrow().getUuid();
	}

	/** Puts the mark on a key that stays ACTIVE, as a re-mint left one before the switch was enforced there. */
	private void leaveStaleMark(UUID key) {
		ApiKey ak = apiKeyService.getApiKey(key).orElseThrow();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		assertEquals(ApiKeyStatus.ACTIVE, akd.getStatus());
		akd.setAdminDisabled(true);
		ak.setRecordData(Utils.dataToRecord(akd));
		apiKeyRepository.save(ak);
	}

	private UUID freeformKey(Organization org) {
		return apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "kill switch", WU).getUuid();
	}

	private boolean matches(Component c, String secret) {
		return null != apiKeyService.isMatchingApiKey(AuthHeaderParse.builder().objUuid(c.getUuid()).type(ApiTypeEnum.COMPONENT)
				.apiKey(secret).build());
	}

	/** Org write access is not administration: a writer neither re-enables an administrator's switch nor sets one. */
	@Test
	public void anOrgWriterIsNotAnAdministratorForTheKillSwitch() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		UserData writer = member(org, PermissionType.READ_WRITE);
		UserData holder = member(org, PermissionType.READ_WRITE);
		UUID key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "kill switch", WU).getUuid();
		apiKeyService.setApiKeyHolder(key, holder.getUuid(), WU);

		assertOk(setStatus(admin, key, ApiKeyStatus.INACTIVE));
		assertDisabledByAdmin(key);
		assertKillSwitchRefusal(setStatus(writer, key, ApiKeyStatus.ACTIVE));
		assertKillSwitchRefusal(setStatus(holder, key, ApiKeyStatus.ACTIVE));
		assertOk(setStatus(writer, key, ApiKeyStatus.INACTIVE));
		assertDisabledByAdmin(key);
		assertKillSwitchRefusal(setStatus(holder, key, ApiKeyStatus.ACTIVE));
		assertOk(setStatus(admin, key, ApiKeyStatus.ACTIVE));
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertFalse(data(key).isAdminDisabled(), "an administrator's re-enable clears the mark");

		assertOk(setStatus(writer, key, ApiKeyStatus.INACTIVE));
		assertFalse(data(key).isAdminDisabled(), "a writer's deactivation is not the administrator's switch");
		assertOk(setStatus(holder, key, ApiKeyStatus.ACTIVE));
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
	}

	/** Re-minting a component key re-enables it: an administrator's switch is undone by an administrator's re-mint only. */
	@Test
	public void aComponentKeyAnAdministratorDisabledIsReMintedByAnAdministratorOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		UserData writer = member(org, PermissionType.READ_WRITE);
		Component c = componentService.createComponent("kill-switch-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
		Map<String, Object> vars = Map.of("c", c.getUuid().toString());

		ExecutionResult first = asPerson(writer, COMPONENT_MINT, vars);
		assertOk(first);
		UUID key = componentKey(c, org);
		assertOk(setStatus(admin, key, ApiKeyStatus.INACTIVE));
		String storedHash = apiKeyService.getApiKey(key).orElseThrow().getApiKey();

		assertKillSwitchRefusal(asPerson(writer, COMPONENT_MINT, vars));
		assertDisabledByAdmin(key);
		assertEquals(storedHash, apiKeyService.getApiKey(key).orElseThrow().getApiKey(), "a refused re-mint writes no secret");

		ExecutionResult reMinted = asPerson(admin, COMPONENT_MINT, vars);
		assertOk(reMinted);
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertFalse(data(key).isAdminDisabled());
		Map<String, Map<String, String>> d = reMinted.getData();
		assertTrue(matches(c, d.get("setComponentApiKey").get("apiKey")));

		assertOk(setStatus(writer, key, ApiKeyStatus.INACTIVE));
		assertOk(asPerson(writer, COMPONENT_MINT, vars));
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus(), "a writer's own deactivation is lifted by a re-mint, as before");
	}

	/**
	 * Archiving a component deletes its key at write level; the tombstone keeps the administrator's switch, so the
	 * writer's re-mint that revives the row is refused like any other, and an administrator's re-mint lifts it.
	 */
	@Test
	public void archivingTheComponentDoesNotLiftTheSwitch() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		UserData writer = member(org, PermissionType.READ_WRITE);
		Component c = componentService.createComponent("kill-switch-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
		Map<String, Object> vars = Map.of("c", c.getUuid().toString());
		assertOk(asPerson(writer, COMPONENT_MINT, vars));
		UUID key = componentKey(c, org);
		assertOk(setStatus(admin, key, ApiKeyStatus.INACTIVE));

		assertOk(asPerson(writer, ARCHIVE, vars));
		assertTrue(apiKeyService.getApiKey(key).isEmpty(), "archiving deleted the key");
		assertKillSwitchRefusal(asPerson(writer, COMPONENT_MINT, vars));
		assertTrue(apiKeyService.getApiKey(key).isEmpty(), "a refused re-mint revives nothing");

		ExecutionResult reMinted = asPerson(admin, COMPONENT_MINT, vars);
		assertOk(reMinted);
		assertEquals(key, componentKey(c, org), "the same row is reused");
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertFalse(data(key).isAdminDisabled());
		Map<String, Map<String, String>> d = reMinted.getData();
		assertTrue(matches(c, d.get("setComponentApiKey").get("apiKey")));
	}

	/** A deleted key nobody switched off is revived by a writer's re-mint, as before. */
	@Test
	public void aDeletedKeyWithoutTheSwitchIsRevivedByAWriter() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("kill-switch-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
		apiKeyService.setObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, false, WU);
		UUID key = componentKey(c, org);
		apiKeyService.setApiKeyStatus(key, ApiKeyStatus.INACTIVE, false, WU);
		apiKeyService.deleteApiKey(key, WU);

		String secret = apiKeyService.setObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, false, WU);
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertTrue(matches(c, secret));
	}

	/** An instance key: an instance writer's re-mint is refused on an administrator's switch, an administrator's lifts it. */
	@Test
	public void anInstanceKeyAnAdministratorDisabledIsReMintedByAnAdministratorOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		UserData writer = member(org, PermissionType.READ_WRITE, PermissionFunction.DEVOPS_READ, PermissionFunction.DEVOPS_WRITE);
		// createInstance runs @Modifying updates that need a surrounding transaction
		Instance inst = new TransactionTemplate(txManager).execute(status -> {
			try {
				return instanceService.createInstance("kill-switch-" + UUID.randomUUID() + ".example.com", org.getUuid(), null, WU);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		Map<String, Object> vars = Map.of("i", inst.getUuid().toString());
		assertOk(asPerson(writer, INSTANCE_MINT, vars));
		UUID key = apiKeyService.getApiKeyByObjUuidTypeOrder(inst.getUuid(), ApiTypeEnum.INSTANCE, null, org.getUuid()).orElseThrow().getUuid();
		assertOk(setStatus(admin, key, ApiKeyStatus.INACTIVE));

		assertKillSwitchRefusal(asPerson(writer, INSTANCE_MINT, vars));
		assertDisabledByAdmin(key);
		assertOk(asPerson(admin, INSTANCE_MINT, vars));
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertFalse(data(key).isAdminDisabled());
	}

	/**
	 * A mark left on an ACTIVE key holds nothing: a writer re-mints the key, deactivates and re-activates it, and a
	 * delete or a declared deactivation does not turn the mark into a switch; the key reads as not switched off.
	 */
	@Test
	public void aMarkLeftOnAnActiveKeyHoldsNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData writer = member(org, PermissionType.READ_WRITE);
		Component c = componentService.createComponent("kill-switch-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
		apiKeyService.setObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, false, WU);
		UUID key = componentKey(c, org);

		leaveStaleMark(key);
		assertFalse(data(key).isSwitchedOffByAdmin());
		ApiKeyDto dto = apiKeyService.getApiKeyDto(key).orElseThrow();
		assertFalse(dto.getAdminDisabled(), "the listing reports the switch, not the leftover mark");
		String secret = apiKeyService.setObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, false, WU);
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertFalse(data(key).isAdminDisabled());
		assertTrue(matches(c, secret));

		leaveStaleMark(key);
		assertOk(setStatus(writer, key, ApiKeyStatus.INACTIVE));
		assertFalse(data(key).isAdminDisabled(), "a writer's deactivation does not turn the mark into a switch");
		assertOk(setStatus(writer, key, ApiKeyStatus.ACTIVE));

		leaveStaleMark(key);
		apiKeyService.deleteApiKey(key, WU);
		apiKeyService.setObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, false, WU);
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus(), "the tombstone did not keep the leftover mark");
		assertFalse(data(key).isAdminDisabled());

		UUID freeform = freeformKey(org);
		leaveStaleMark(freeform);
		apiKeyService.writeDeclaredSettings(freeform, declared(ApiKeyStatus.ACTIVE, false), WU);
		assertFalse(data(freeform).isAdminDisabled(), "a declared ACTIVE on an ACTIVE key goes through and clears the mark");
		leaveStaleMark(freeform);
		apiKeyService.writeDeclaredSettings(freeform, declared(ApiKeyStatus.INACTIVE, false), WU);
		assertEquals(ApiKeyStatus.INACTIVE, data(freeform).getStatus());
		assertFalse(data(freeform).isAdminDisabled(), "a declared deactivation is not the administrator's switch");
	}

	/** A declared deactivation leaves an administrator's switch on. */
	@Test
	public void aDeclaredDeactivationKeepsTheSwitch() throws Exception {
		UUID key = freeformKey(testInitializer.obtainOrganization());
		apiKeyService.setApiKeyStatus(key, ApiKeyStatus.INACTIVE, true, WU);
		apiKeyService.writeDeclaredSettings(key, declared(ApiKeyStatus.INACTIVE, false), WU);
		assertDisabledByAdmin(key);
	}

	/**
	 * The declarative apply checks the switch on the key as it read it, unlocked; an administrator who disables the key
	 * after that read wins, because the write checks again on the locked row.
	 */
	@Test
	public void aDeclarativeWriteDecidedBeforeAnAdministratorDisabledTheKeyIsRefused() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "before", WU).getUuid();
		apiKeyService.setApiKeyStatus(key, ApiKeyStatus.INACTIVE, true, WU);

		RelizaException refused = assertThrows(RelizaException.class,
				() -> apiKeyService.writeDeclaredSettings(key, declared(ApiKeyStatus.ACTIVE, false), WU));
		assertEquals(ApiKeyService.ADMIN_DISABLED_REFUSAL, refused.getMessage());
		assertDisabledByAdmin(key);
		assertEquals("before", data(key).getNotes());
		assertNull(data(key).getDeclaredName(), "a refused write writes nothing");

		apiKeyService.writeDeclaredSettings(key, declared(ApiKeyStatus.ACTIVE, true), WU);
		assertEquals(ApiKeyStatus.ACTIVE, data(key).getStatus());
		assertFalse(data(key).isAdminDisabled());
		assertEquals("after", data(key).getNotes());
	}

	private static ApiKeyService.DeclaredSettings declared(ApiKeyStatus status, boolean byAdmin) {
		return new ApiKeyService.DeclaredSettings("kill-switch-" + UUID.randomUUID(), null, Set.of("status", "notes"), "after",
				status, List.of(), null, null, byAdmin);
	}
}
