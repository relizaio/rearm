/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.AcollectionService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class AcollectionDataFetcher {
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private AcollectionService acollectionService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private ArtifactService artifactService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getAcollectionsOfRelease")
	public List<AcollectionData> getAcollectionsOfRelease(@InputArgument("releaseUuid") UUID releaseUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE, releaseUuid, List.of(ro), CallType.READ);
		return acollectionService.getAcollectionDatasOfRelease(releaseUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "VersionedArtifact", field = "artifactDetails")
	public ArtifactData getArtifactDetails(DgsDataFetchingEnvironment dfe) {
		VersionedArtifact va = dfe.getSource();
		Integer version = va.version() != null ? va.version().intValue() : null;
		Optional<ArtifactData> artifactOpt = artifactService.getArtifactDataByVersion(va.artifactUuid(), version);
		if (artifactOpt.isPresent()) {
			return artifactOpt.get();
		} else {
			log.warn("Artifact not found for UUID: {}, version: {}", va.artifactUuid(), version);
			return null;
		}
	}
}
