/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.RelizaObject;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class ArtifactDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	BranchService branchService;
	
	@Autowired
	OrganizationService organizationService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifact")
	public ArtifactData getArtifact(@InputArgument("artifactUuid") String artifactUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID artifactUuid = UUID.fromString(artifactUuidStr);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return oad.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifactTypes")
	public Set<String> getArtifactTypes() {
		var valList = Arrays.asList(ArtifactType.values());
		return valList.stream().map(v -> v.toString()).collect(Collectors.toSet());
	}

}