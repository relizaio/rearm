/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.exceptions.RelizaException;


import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ReleaseService;
import io.reliza.service.UserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class MarketingReleaseDatafetcher {
	
	@Autowired
	UserService userService;
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	BranchService branchService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "marketingRelease")
	public void getMarketingRelease(@InputArgument("marketingReleaseUuid") String marketingReleaseUuidStr) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "marketingReleases")
	public void listMarketingReleasesOfComponent(@InputArgument("componentUuid") String componentUuidStr) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseLifecycles")
	public void listReleaseLifecycles () {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addMarketingReleaseManual")
	public void addRelease(DgsDataFetchingEnvironment dfe) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateMarketingRelease")
	public void updateRelease(DgsDataFetchingEnvironment dfe) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "archiveMarketingRelease")
	public void archiveRelease(@InputArgument("marketingReleaseUuid") String marketingReleaseUuid) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "advanceMarketingReleaseLifecycle")
	public void advanceLifecycle(@InputArgument("marketingReleaseUuid") String marketingReleaseUuid,
			@InputArgument("newLifecycle") String newLifecycleStr) throws RelizaException {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "releaseMarketingRelease")
	public void releaseMarketingRelease (DgsDataFetchingEnvironment dfe,
			@InputArgument("marketingVersion") String marketingVersion) throws RelizaException {
		throw new RuntimeException("Currently not part of ReARM CE");
	}

}
