/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.ApiKeyGroupKind;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.service.ExternalApiKeyService;

/**
 * FDAW-37 row 18, the GraphQL half: the group verbs on a CUSTOMER group stay organization ADMIN, both
 * membership verbs refuse a key of the other kind with the server's sentence, and the kind reads back
 * and filters the list.
 */
public class ApiKeyGroupCustomerKindTest extends ApiKeyGroupTestBase {

	private static final String NOT_AUTHORIZED = "Not authorized";
	private static final String CREATE_KIND = "mutation($g: CreateApiKeyGroupInput!) { createApiKeyGroup(apiKeyGroup: $g)"
			+ " { uuid kind } }";
	private static final String LIST_KIND = "query($o: ID!, $k: ApiKeyGroupKind) { apiKeyGroupsOfOrg(org: $o, kind: $k) { uuid kind } }";

	@Autowired ExternalApiKeyService externalApiKeyService;

	private ApiKeyGroupData customerGroup(Organization org) throws RelizaException {
		return apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder().name("akg-" + UUID.randomUUID())
				.org(org.getUuid()).kind(ApiKeyGroupKind.CUSTOMER).build(), WU);
	}

	private ExecutionResult gqlCreateCustomer(Organization org) {
		return dgsQueryExecutor.execute(CREATE_KIND, Map.of("g", Map.of("name", "akg-" + UUID.randomUUID(),
				"org", org.getUuid().toString(), "kind", "CUSTOMER")));
	}

	private ExecutionResult gqlListKind(Organization org, String kind) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("o", org.getUuid().toString());
		vars.put("k", kind);
		return dgsQueryExecutor.execute(LIST_KIND, vars);
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> listed(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, List<Map<String, String>>> data = r.getData();
		return data.get("apiKeyGroupsOfOrg").stream().collect(Collectors.toMap(m -> m.get("uuid"), m -> m.get("kind")));
	}

	@Test
	public void aMemberWhoIsNotAdminIsRefusedEveryVerb() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = customerGroup(org);
		ApiKey ext = externalApiKeyService.create(org.getUuid(), "akg-" + UUID.randomUUID(), null, WU);
		signInAs(member(org, PermissionType.READ_WRITE));

		assertEquals(NOT_AUTHORIZED, onlyError(gqlCreateCustomer(org)));
		Map<String, Object> rename = new HashMap<>();
		rename.put("groupId", g.getUuid().toString());
		rename.put("name", "akg-" + UUID.randomUUID());
		assertEquals(NOT_AUTHORIZED, onlyError(gqlUpdate(rename)));
		assertEquals(NOT_AUTHORIZED, onlyError(gqlListKind(org, "CUSTOMER")));
		assertEquals(NOT_AUTHORIZED, onlyError(gqlDelete(g.getUuid())));
		assertEquals(NOT_AUTHORIZED, onlyError(gqlSetGroups(org.getUuid(), ext.getUuid(), g.getUuid())));
		assertEquals(g.getName(), stored(g).getName());
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(g).getStatus());
		assertEquals(Set.of(), stored(g).getApiKeys());
	}

	@Test
	public void bothMembershipVerbsRefuseAKeyOfTheOtherKind() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		ApiKey ext = externalApiKeyService.create(org.getUuid(), "akg-" + UUID.randomUUID(), null, WU);
		ApiKey ff = freeform(org, "ff");
		ApiKeyGroupData internal = newGroup(org);
		ApiKeyGroupData cust = customerGroup(org);
		signInAs(admin);

		assertEquals("Only FREEFORM keys can join an API key group: " + ext.getUuid(), onlyError(gqlUpdateMembers(internal.getUuid(), ext.getUuid())));
		assertEquals("Only external keys can join a customer group: " + ff.getUuid(), onlyError(gqlUpdateMembers(cust.getUuid(), ff.getUuid())));
		assertEquals("Only FREEFORM keys can join an API key group: " + ext.getUuid(),
				onlyError(gqlSetGroups(org.getUuid(), ext.getUuid(), cust.getUuid(), internal.getUuid())));
		assertEquals(Set.of(), stored(internal).getApiKeys());
		assertEquals(Set.of(), stored(cust).getApiKeys());

		assertTrue(gqlUpdateMembers(cust.getUuid(), ext.getUuid()).getErrors().isEmpty());
		assertEquals(Set.of(ext.getUuid()), stored(cust).getApiKeys());
	}

	@Test
	public void theKindReadsBackAndFiltersTheList() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		ApiKeyGroupData internal = newGroup(org);
		ExecutionResult created = gqlCreateCustomer(org);
		assertTrue(created.getErrors().isEmpty(), created.getErrors().toString());
		Map<String, Map<String, String>> data = created.getData();
		String customer = data.get("createApiKeyGroup").get("uuid");
		assertEquals("CUSTOMER", data.get("createApiKeyGroup").get("kind"));
		String own = internal.getUuid().toString();

		Map<String, String> customers = listed(gqlListKind(org, "CUSTOMER"));
		Map<String, String> internals = listed(gqlListKind(org, "INTERNAL"));
		Map<String, String> both = listed(gqlListKind(org, null));
		assertEquals("CUSTOMER", customers.get(customer));
		assertTrue(!customers.containsKey(own), customers.toString());
		assertTrue(customers.values().stream().allMatch("CUSTOMER"::equals), customers.toString());
		assertEquals("INTERNAL", internals.get(own));
		assertTrue(!internals.containsKey(customer), internals.toString());
		assertTrue(internals.values().stream().allMatch("INTERNAL"::equals), internals.toString());
		assertEquals(customers.size() + internals.size(), both.size());
		assertTrue(both.keySet().containsAll(Arrays.asList(customer, own)), both.toString());
	}
}
