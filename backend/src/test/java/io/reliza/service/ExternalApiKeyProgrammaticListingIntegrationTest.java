/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.saas.SaasOrganizationService;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;

/**
 * Task TEA-3, run 3, T-7: the programmatic key listing ({@code apiKeysProgrammatic}) never shows an EXTERNAL key.
 * Design 3.2 lists EXTERNAL keys to an org ADMIN in the user API only, and section 1 keeps programmatic management
 * of them out of scope, so neither a writer's nor an admin's CONFIGURATION_WRITE key sees one; the other keys of
 * the organization stay listed.
 */
@SpringBootTest(classes = {App.class})
public class ExternalApiKeyProgrammaticListingIntegrationTest extends ExternalKeyIntegrationTestBase {

	private static final String LIST = "query($k: [String!]) { apiKeysProgrammatic(keys: $k) { uuid keyId name type status notes"
			+ " secretSlots { slot active } } }";

	@Autowired private SaasOrganizationService saasOrganizationService;
	@Autowired private ApiKeyService apiKeyService;

	/** A FREEFORM key with this organization-wide level and functions, as a CI caller holds. */
	private ApiKey programmaticKey(Organization org, PermissionType type, PermissionFunction... functions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "tea3 lister", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), type, Set.of(functions), null)), WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	private List<Map<String, Object>> listAs(ApiKey key, List<String> names) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.13"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		Map<String, Object> vars = new HashMap<>();
		vars.put("k", names);
		ExecutionResult r = dgsQueryExecutor.execute(LIST, vars, Map.of(), headers, null, new ServletWebRequest(request));
		return ok(r, "apiKeysProgrammatic");
	}

	@Test
	public void theProgrammaticKeyListingNeverShowsAnExternalKey() throws Exception {
		Organization org = saasOrganizationService.createOrganization("tea3-list-" + UUID.randomUUID(), WU);
		UserData admin = admin(org);
		Map<String, Object> external = createKey(admin, org, "Acme Supplier");
		String externalUuid = (String) external.get("uuid");
		ok(exec(admin, ADD_SECRET, "k", externalUuid), "addApiKeySecret");
		ok(exec(admin, "mutation($k: ID!, $n: String) { setNotesOnApiKey(apiKeyUuid: $k, notes: $n) { uuid } }",
				"k", externalUuid, "n", "agreement 42 with Acme"), "setNotesOnApiKey");
		List<Map<String, Object>> externalRows = ok(exec(admin, EXTERNAL_KEYS, "o", org.getUuid().toString()), "externalApiKeys");
		assertEquals(List.of(externalUuid), externalRows.stream().map(e -> e.get("uuid")).toList(), "the fixture holds the key");

		ApiKey writer = programmaticKey(org, PermissionType.READ_WRITE, PermissionFunction.RESOURCE, PermissionFunction.CONFIGURATION_WRITE);
		ApiKey adminKey = programmaticKey(org, PermissionType.ADMIN, PermissionFunction.CONFIGURATION_WRITE);
		for (ApiKey caller : List.of(writer, adminKey)) {
			List<Map<String, Object>> rows = listAs(caller, null);
			String seen = rows.toString();
			assertTrue(rows.stream().noneMatch(row -> externalUuid.equals(row.get("uuid"))), "no EXTERNAL row by uuid: " + seen);
			assertTrue(rows.stream().noneMatch(row -> "EXTERNAL".equals(row.get("type"))), "no EXTERNAL row by type: " + seen);
			assertFalse(seen.contains("EXTERNAL__"), "no printed external id: " + seen);
			assertFalse(seen.contains("agreement 42"), "no external key notes: " + seen);
			assertEquals(Set.of(writer.getUuid().toString(), adminKey.getUuid().toString()),
					Set.copyOf(rows.stream().map(row -> (String) row.get("uuid")).toList()),
					"the organization's other keys stay listed: " + seen);
			assertTrue(listAs(caller, List.of((String) external.get("keyId"))).isEmpty(), "nor by its printed id");
		}
	}
}
