/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws.oss;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.IntegrationService;

@Service
public class TestInitializer {

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private IntegrationService integrationService;

	/**
	 * Creates a FRESH organization per call. The shared test suite assumes
	 * per-call org isolation (branch counts, backfill certification state,
	 * per-org analytics rows are all asserted against a pristine org);
	 * returning the standing CE user org lets state bleed across tests and
	 * breaks those assertions. Mirrors the production create-org flow:
	 * record_data with name, WhoUpdated injection, plus the default-on
	 * CISA_KEV integration every new org gets (V54 per-org KEV refactor).
	 */
	public Organization obtainOrganization() {
		String orgName = "testOrg_" + UUID.randomUUID();
		Map<String, Object> orgData = new HashMap<>();
		orgData.put(CommonVariables.NAME_FIELD, orgName);
		Organization org = new Organization();
		org.setRecordData(orgData);
		org = (Organization) WhoUpdated.injectWhoUpdatedData(org, WhoUpdated.getTestWhoUpdated());
		org = organizationRepository.save(org);
		try {
			integrationService.upsertKevIntegration(org.getUuid(),
					IntegrationType.CISA_KEV, true, null, WhoUpdated.getTestWhoUpdated());
		} catch (Exception e) {
			// Never take org creation down on a KEV-integration hiccup --
			// matches the production create-org behavior.
		}
		return org;
	}

}
