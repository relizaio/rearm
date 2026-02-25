/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.oss;

import java.util.List;

import java.util.Optional;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.model.IntegrationData;
import io.reliza.model.dto.IntegrationWebDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.IntegrationService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OssIntegrationDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	IntegrationService integrationService;
	
	@Autowired
	UserService userService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "ciIntegrations")
	public List<IntegrationWebDto> getConfiguredBaseIntegrations (@InputArgument("org") UUID orgUuid) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createTriggerIntegration")
	public Optional<IntegrationData> createIntegration(DgsDataFetchingEnvironment dfe) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
}
