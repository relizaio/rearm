/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.ApiKeyGroupData;

/**
 * API key group root fields (TPF-91, mutation round): the system state is answered first on the two fields
 * that take an organization, so a sealed instance cannot tell an existing organization from an unknown one,
 * and approval roles the organization does not know are refused on update.
 */
public class ApiKeyGroupSystemStateOrderTest extends ApiKeyGroupTestBase {

	@Test
	public void aSealedSystemAnswersTheSystemStateEvenForAnOrganizationThatDoesNotExist() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		UUID ghost = UUID.randomUUID();
		licenseStatus.setSystemSealed(true);
		try {
			String list = onlyError(dgsQueryExecutor.execute(LIST, Map.of("o", ghost.toString())));
			String create = onlyError(dgsQueryExecutor.execute(CREATE,
					Map.of("g", Map.of("name", "ghost-" + UUID.randomUUID(), "org", ghost.toString()))));
			assertTrue(list.contains("System is sealed"), list);
			assertFalse(list.contains("Not authorized"), "the system state is answered before the organization is looked up: " + list);
			assertTrue(create.contains("System is sealed"), create);
			assertFalse(create.contains("Not authorized"), "the system state is answered before the organization is looked up: " + create);
		} finally {
			licenseStatus.setSystemSealed(false);
		}
	}

	@Test
	public void approvalRolesTheOrganizationDoesNotKnowAreRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = group(org, Set.of());
		signInAs(member(org, PermissionType.ADMIN));
		Map<String, Object> input = new HashMap<>();
		input.put("groupId", g.getUuid().toString());
		input.put("approvals", List.of("NO_SUCH_ROLE_" + UUID.randomUUID()));
		String message = onlyError(gqlUpdate(input));
		assertFalse(message.isBlank(), "an approval role the organization does not know is refused");
	}
}
