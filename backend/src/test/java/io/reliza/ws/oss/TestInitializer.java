/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws.oss;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.Organization;
import io.reliza.service.UserService;
import io.reliza.service.GetOrganizationService;

@Service
public class TestInitializer {

	@Autowired
    private GetOrganizationService getOrganizationService;
	
	public Organization obtainOrganization() {
		return getOrganizationService.getOrganization(UserService.USER_ORG).get();
	}
	
	
}
