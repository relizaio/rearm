/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.saas.Instance;
import io.reliza.service.ApiKeyService;
import io.reliza.service.UserService;
import io.reliza.service.saas.InstanceService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-51 (architecture-1 section 4.1): an id of a key type that always carries a key order
 * ({@link ApiTypeEnum#requiresKeyOrder()}: FREEFORM, USER, FEDERATED) presented without its order, or with an empty
 * one, is refused by {@link ApiKeyService#isMatchingApiKey} before any lookup, so an object with several keys of the
 * type no longer raises out of the ambiguous lookup and an object with exactly one no longer accepts the orderless
 * id. Valid ordered ids, the organization types and the unordered types answer as before. Driven at the service with
 * a parse built by {@link AuthHeaderParse#builder()}, as {@link ApiKeyTest}.
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyOrderlessIdTest {

	@Autowired private ApiKeyService apiKeyService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private UserService userService;
	@Autowired private InstanceService instanceService;
	@Autowired private PlatformTransactionManager txManager;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private record Key(ApiKey key, String secret) {
		UUID uuid() {
			return key.getUuid();
		}

		String order() {
			return key.getKeyOrder();
		}
	}

	private Key freeformKey(UUID org) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"TPF-51 orderless id test", WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		return new Key(key, secret);
	}

	private static AuthHeaderParse parse(ApiTypeEnum type, UUID object, String order, String secret) {
		return AuthHeaderParse.builder().type(type).objUuid(object).keyOrder(order).apiKey(secret).remoteIp("198.51.100.51").build();
	}

	private UUID orgWithTwoFreeformKeys(List<Key> keys) throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		keys.add(freeformKey(org));
		keys.add(freeformKey(org));
		return org;
	}

	// row 1: two FREEFORM keys, the orderless id with the first key's real secret (the defect: the lookup raised)
	@Test
	public void anOrderlessFreeformIdOnTwoKeysIsRefusedWithoutAnException() throws Exception {
		List<Key> keys = new ArrayList<>();
		UUID org = orgWithTwoFreeformKeys(keys);
		UUID matched = assertDoesNotThrow(() -> apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.FREEFORM, org, null, keys.get(0).secret())));
		assertNull(matched);
	}

	// row 2: the empty order (FREEFORM__<org>__ord__) is the same refusal
	@Test
	public void anEmptyOrderIsTheSameRefusal() throws Exception {
		List<Key> keys = new ArrayList<>();
		UUID org = orgWithTwoFreeformKeys(keys);
		UUID matched = assertDoesNotThrow(() -> apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.FREEFORM, org, "", keys.get(0).secret())));
		assertNull(matched);
	}

	// row 3: exactly one FREEFORM key, the orderless id with its real secret (was accepted through keyList.get(0))
	@Test
	public void anOrderlessFreeformIdOnOneKeyIsRefused() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Key only = freeformKey(org);
		assertNull(apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.FREEFORM, org, null, only.secret())));
	}

	// row 4: a valid ordered id is unchanged: its own secret matches, a wrong secret does not
	@Test
	public void aValidOrderedFreeformIdIsUnchanged() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Key only = freeformKey(org);
		assertEquals(only.uuid(), apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.FREEFORM, org, only.order(), only.secret())));
		assertNull(apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.FREEFORM, org, only.order(), only.secret() + "x")));
	}

	// row 5: a USER key, without and then with its order
	@Test
	public void anOrderlessUserIdIsRefusedAndTheOrderedOneMatches() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("O " + tag, "o-" + tag + "@tpf51orderless.io", true, List.of(org),
				"o-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, null, null, WU);
		ApiKey key = apiKeyService.createObjectApiKey(u.getUuid(), ApiTypeEnum.USER, org, UUID.randomUUID().toString(),
				"TPF-51 orderless id test", WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		assertNull(apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.USER, u.getUuid(), null, secret)));
		assertEquals(key.getUuid(), apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.USER, u.getUuid(), key.getKeyOrder(), secret)));
	}

	private static FederatedIdentity identity(String repository) {
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("acme");
		fi.setRepository(repository);
		fi.setRepositoryUri("github.com/" + repository);
		return fi;
	}

	// row 6: two FEDERATED rows on one org, the orderless id
	@Test
	public void anOrderlessFederatedIdOnTwoRowsIsRefusedWithoutAnException() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		ApiKey first = apiKeyService.ensureFederatedIdentity(org, identity("acme/app-" + tag + "-1"), WU);
		ApiKey second = apiKeyService.ensureFederatedIdentity(org, identity("acme/app-" + tag + "-2"), WU);
		assertNotEquals(first.getUuid(), second.getUuid(), "two rows");
		UUID matched = assertDoesNotThrow(() -> apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.FEDERATED, org, null, "x")));
		assertNull(matched);
	}

	// row 7: an orderless ORGANIZATION_RW key is unchanged (not an ordered type for this task; architecture-1 5.1)
	@Test
	public void anOrderlessOrganizationRwKeyIsUnchanged() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String secret = apiKeyService.setObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org, null, "TPF-51 orderless id test", false, WU);
		ApiKey key = apiKeyService.getApiKeyByObjUuidTypeOrder(org, ApiTypeEnum.ORGANIZATION_RW, null, org).orElseThrow();
		assertEquals("ORGANIZATION_RW__" + org, ApiKeyService.keyIdOf(key));
		AuthHeaderParse p = AuthHeaderParse.builder().type(ApiTypeEnum.ORGANIZATION_RW).objUuid(org).orgUuid(org)
				.apiKey(secret).remoteIp("198.51.100.51").build();
		assertEquals(key.getUuid(), apiKeyService.isMatchingApiKey(p));
	}

	// row 8: an INSTANCE key (unordered type) is unchanged; COMPONENT stays covered by ApiKeyTest.setApiKeyToProject
	@Test
	public void anUnorderedInstanceKeyIsUnchanged() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// createInstance runs @Modifying updates that need a surrounding transaction
		Instance inst = new TransactionTemplate(txManager).execute(status -> {
			try {
				return instanceService.createInstance("tpf51-" + UUID.randomUUID() + ".example.com", org, null, WU);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		String secret = apiKeyService.setObjectApiKey(inst.getUuid(), ApiTypeEnum.INSTANCE, org, null, "TPF-51 orderless id test", false, WU);
		ApiKey key = apiKeyService.getApiKeyByObjUuidTypeOrder(inst.getUuid(), ApiTypeEnum.INSTANCE, null, org).orElseThrow();
		assertEquals(key.getUuid(), apiKeyService.isMatchingApiKey(parse(ApiTypeEnum.INSTANCE, inst.getUuid(), null, secret)));
	}

	// row 9: resolveOrgForKey refuses the orderless FREEFORM parse and resolves the ordered one
	@Test
	public void resolveOrgForKeyRefusesTheOrderlessParse() throws Exception {
		List<Key> keys = new ArrayList<>();
		UUID org = orgWithTwoFreeformKeys(keys);
		UUID resolved = assertDoesNotThrow(() -> apiKeyService.resolveOrgForKey(parse(ApiTypeEnum.FREEFORM, org, null, keys.get(0).secret())));
		assertNull(resolved);
		assertEquals(org, apiKeyService.resolveOrgForKey(parse(ApiTypeEnum.FREEFORM, org, keys.get(0).order(), keys.get(0).secret())));
	}

	// row 10 (security review F-1): the refusal does not depend on how the org was resolved; the __ORGANIZATION__
	// segment spelling arrives with orgUuid set, skips the ambiguous lookup, and is refused all the same
	@Test
	public void theRefusalHoldsWhenTheOrgComesFromTheId() throws Exception {
		List<Key> keys = new ArrayList<>();
		UUID twoKeyOrg = orgWithTwoFreeformKeys(keys);
		UUID oneKeyOrg = testInitializer.obtainOrganization().getUuid();
		Key only = freeformKey(oneKeyOrg);
		for (Object[] c : List.of(new Object[] {twoKeyOrg, keys.get(0)}, new Object[] {oneKeyOrg, only})) {
			UUID org = (UUID) c[0];
			Key k = (Key) c[1];
			for (String order : Arrays.asList(null, "")) {
				AuthHeaderParse p = AuthHeaderParse.builder().type(ApiTypeEnum.FREEFORM).objUuid(org).orgUuid(org).keyOrder(order)
						.apiKey(k.secret()).remoteIp("198.51.100.51").build();
				assertNull(apiKeyService.isMatchingApiKey(p), "org " + org + " order " + order);
			}
		}
	}

	// row 11: the ordered types, from the key model (architecture-1 decision 1)
	@Test
	public void theOrderedTypesAreFreeformUserAndFederated() {
		assertEquals(EnumSet.of(ApiTypeEnum.FREEFORM, ApiTypeEnum.USER, ApiTypeEnum.FEDERATED),
				Arrays.stream(ApiTypeEnum.values()).filter(ApiTypeEnum::requiresKeyOrder)
						.collect(Collectors.toCollection(() -> EnumSet.noneOf(ApiTypeEnum.class))));
	}
}
