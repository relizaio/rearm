/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.service.saas.SaasOrganizationService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Minting returns cleartext: on a Free Form key with no holder only an org admin may mint, on a held one only its
 * holder, and the decision is taken on the key row the mint writes (task t20261013-114908-6322).
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyMinterAuthorizationTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private SaasOrganizationService saasOrganizationService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String MINT = "mutation($k: ID!) { addApiKeySecret(apiKeyUuid: $k) { id } }";
	private static final String REGENERATE = "mutation($k: ID!, $s: Int!) { regenerateApiKeySecret(apiKeyUuid: $k, slot: $s) { id } }";
	private static final String UNHELD = "Only an organization administrator may mint secrets for a Free Form key that has no holder";
	private static final String NOT_HOLDER = "Only the holder of this key may mint its secrets";
	private static final String NOT_OWNER = "Only the owner of a personal key may mint its secrets";
	private static final String ADMIN_ONLY = "Only an organization administrator may mint secrets for this key";
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

	private UserData member(Organization org, PermissionType level) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("K " + tag, "k-" + tag + "@minter.io", true, List.of(org.getUuid()),
				"k-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level, null, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A Free Form key of the org, held by the given member, or unheld for null. */
	private UUID freeformKey(Organization org, UserData holder) throws Exception {
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(), UUID.randomUUID().toString(), "minting", WU);
		if (holder != null) apiKeyService.setApiKeyHolder(ak.getUuid(), holder.getUuid(), WU);
		return ak.getUuid();
	}

	/** Runs the mutation as the given user, in the calling thread. */
	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("minter-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private int secretCount(UUID key) {
		return apiKeyService.getApiKeyDto(key).orElseThrow().getSecrets().size();
	}

	private static void assertRefused(ExecutionResult r, String message) {
		assertEquals(1, r.getErrors().size(), String.valueOf(r.getErrors()));
		assertEquals(message, r.getErrors().get(0).getMessage());
	}

	@Test
	public void anUnheldFreeFormKeyIsMintedByAnAdminOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData writer = member(org, PermissionType.READ_WRITE);
		UserData admin = member(org, PermissionType.ADMIN);
		UUID key = freeformKey(org, null);
		Map<String, Object> vars = Map.of("k", key.toString());

		assertRefused(asPerson(writer, MINT, vars), UNHELD);
		assertEquals(0, secretCount(key), "a refused mint writes nothing");
		ExecutionResult minted = asPerson(admin, MINT, vars);
		assertTrue(minted.getErrors().isEmpty(), String.valueOf(minted.getErrors()));

		assertRefused(asPerson(writer, REGENERATE, Map.of("k", key.toString(), "s", 1)), UNHELD);
		ExecutionResult regenerated = asPerson(admin, REGENERATE, Map.of("k", key.toString(), "s", 1));
		assertTrue(regenerated.getErrors().isEmpty(), String.valueOf(regenerated.getErrors()));
	}

	@Test
	public void aHeldFreeFormKeyIsMintedByItsHolderOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData holder = member(org, PermissionType.READ_WRITE);
		UserData other = member(org, PermissionType.READ_WRITE);
		UUID key = freeformKey(org, holder);
		Map<String, Object> vars = Map.of("k", key.toString());

		ExecutionResult minted = asPerson(holder, MINT, vars);
		assertTrue(minted.getErrors().isEmpty(), String.valueOf(minted.getErrors()));
		assertRefused(asPerson(other, MINT, vars), NOT_HOLDER);
		assertRefused(asPerson(member(org, PermissionType.ADMIN), MINT, vars), NOT_HOLDER);
		assertEquals(1, secretCount(key));
	}

	@Test
	public void personalKeysRequestsAndOrgKeysKeepTheirRules() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData owner = member(org, PermissionType.READ_WRITE);
		UserData writer = member(org, PermissionType.READ_WRITE);
		UserData admin = member(org, PermissionType.ADMIN);
		UUID personal = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(), UUID.randomUUID().toString(), "mine", WU).getUuid();
		assertRefused(asPerson(admin, MINT, Map.of("k", personal.toString())), NOT_OWNER);
		assertTrue(asPerson(owner, MINT, Map.of("k", personal.toString())).getErrors().isEmpty());

		UUID asked = apiKeyService.requestFreeformApiKey(writer.getUuid(), org.getUuid(), "asked", null, null, WU).getUuid();
		assertRefused(asPerson(writer, MINT, Map.of("k", asked.toString())), "This key is a request; it can be minted once an admin approves it");

		UUID orgKey = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.ORGANIZATION_RW, org.getUuid(), UUID.randomUUID().toString(), "org", WU).getUuid();
		assertRefused(asPerson(writer, MINT, Map.of("k", orgKey.toString())), ADMIN_ONLY);
		assertTrue(asPerson(admin, MINT, Map.of("k", orgKey.toString())).getErrors().isEmpty());
	}

	/** A member of another org is refused by the org gate, before the key row is locked and before learning its kind. */
	@Test
	public void anOutsiderIsRefusedBeforeAnythingElse() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization other = saasOrganizationService.createOrganization("x-" + UUID.randomUUID(), WU);
		UserData outsider = member(other, PermissionType.ADMIN);
		UUID key = freeformKey(org, null);
		ExecutionResult r = asPerson(outsider, MINT, Map.of("k", key.toString()));
		assertEquals(1, r.getErrors().size());
		assertFalse(r.getErrors().get(0).getMessage().contains("Free Form"), r.getErrors().get(0).getMessage());
		assertEquals(0, secretCount(key));

		assertRefused(asPerson(member(org, PermissionType.ADMIN), MINT, Map.of("k", UUID.randomUUID().toString())), "API key not found");
	}

	/**
	 * The holder is replaced while their mint waits for the key row: the mint decides on the row as the replacement
	 * left it and refuses, rather than on what it read before and handing a fresh secret to a former holder.
	 */
	@Test
	public void aHolderReplacedWhileTheMintWaitsGetsNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData first = member(org, PermissionType.READ_WRITE);
		UserData next = member(org, PermissionType.READ_WRITE);
		UUID key = freeformKey(org, first);
		assertRefused(mintWhileTheHolderChanges(first, key, next.getUuid()), NOT_HOLDER);
		assertEquals(0, secretCount(key));
	}

	/** As above with the holder cleared: the former holder, a plain writer, meets the admin-only rule. */
	@Test
	public void aHolderClearedWhileTheMintWaitsGetsNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData first = member(org, PermissionType.READ_WRITE);
		UUID key = freeformKey(org, first);
		assertRefused(mintWhileTheHolderChanges(first, key, null), UNHELD);
		assertEquals(0, secretCount(key));
	}

	/** Mints as the given user while another transaction holds the key row and sets its holder, then commits. */
	private ExecutionResult mintWhileTheHolderChanges(UserData minter, UUID key, UUID newHolder) throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService admin = Executors.newSingleThreadExecutor();
		ExecutorService minting = Executors.newSingleThreadExecutor();
		try {
			Future<?> changing = admin.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				try {
					apiKeyService.setApiKeyHolder(key, newHolder, WU);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				} finally {
					locked.countDown();
				}
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertTrue(locked.await(30, TimeUnit.SECONDS));
			assertFalse(changing.isDone(), "the holder change holds the row");
			Future<ExecutionResult> mint = minting.submit(() -> asPerson(minter, MINT, Map.of("k", key.toString())));
			Thread.sleep(500);
			assertFalse(mint.isDone(), "the mint waits for the row");
			release.countDown();
			changing.get(30, TimeUnit.SECONDS);
			return mint.get(30, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			admin.shutdownNow();
			minting.shutdownNow();
		}
	}
}
