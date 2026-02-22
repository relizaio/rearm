/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;

import io.reliza.model.ArtifactData;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.RelizaObject;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.IntegrationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class IntegrationDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	IntegrationService integrationService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	GetOrganizationService getOrganizationService;

	@Autowired
	SharedArtifactService sharedArtifactService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "configuredBaseIntegrations")
	public Set<IntegrationType> getConfiguredBaseIntegrations (@InputArgument("org") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);

		var odInt = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roInt = odInt.isPresent() ? odInt.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roInt), CallType.READ);

		return integrationService.listConfiguredBaseIntegrationTypesPerOrg(orgUuid);
	}
	
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateIntegrationInput (UUID org, String identifier, String secret, IntegrationType type, URI uri, String schedule, URI frontendUri) {}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createIntegration")
	public IntegrationData createIntegration(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Map<String, Object> integrationInput = dfe.getArgument("integration");
		CreateIntegrationInput cii = Utils.OM.convertValue(integrationInput, CreateIntegrationInput.class);

		var odCreate = getOrganizationService.getOrganizationData(cii.org());
		RelizaObject roCreate = odCreate.isPresent() ? odCreate.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, cii.org(), List.of(roCreate), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		return IntegrationData.dataFromRecord(integrationService
				.createIntegration(cii.identifier(), cii.org(), cii.type(), cii.uri(), cii.secret(), cii.schedule, cii.frontendUri, wu));
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteBaseIntegration")
	public boolean deleteIntegration(DgsDataFetchingEnvironment dfe,
			@InputArgument("org") UUID org, @InputArgument("type") IntegrationType integrationType) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		var odDel = getOrganizationService.getOrganizationData(org);
		RelizaObject roDel = odDel.isPresent() ? odDel.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, org, List.of(roDel), CallType.ADMIN);
		
		Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
				org, integrationType, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isEmpty()) throw new RuntimeException("No base integration found");
		integrationService.deleteIntegration(oid.get().getUuid());
		return true;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "requestRefreshDependencyTrackMetrics")
	public boolean requestRefreshDependencyTrackMetrics(
			@InputArgument("artifact") UUID art) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> oad = artifactService.getArtifactData(art);
		
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, ro.getOrg(), List.of(ro), CallType.ADMIN);
		
		return integrationService.requestMetricsRefreshOnDependencyTrack(oad.get());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "searchDtrackComponentByPurlAndProjects")
	public UUID searchDtrackComponentByPurlAndProjects(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("purl") String purl,
			@InputArgument("dtrackProjects") Set<UUID> dtrackProjects) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		var odSearch = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSearch = odSearch.isPresent() ? odSearch.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSearch), CallType.ESSENTIAL_READ);
		
		return integrationService.searchDtrackComponentByPurlAndProjects(orgUuid, purl, dtrackProjects);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "refreshDtrackProjects")
	public Boolean refreshDtrackProjects(@InputArgument("orgUuid") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		// Verify user has admin access to the organization
		var odRefresh = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roRefresh = odRefresh.isPresent() ? odRefresh.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roRefresh), CallType.ADMIN);
		
		log.info("User {} initiated DTrack project refresh for organization {}", oud.get().getUuid(), orgUuid);
		
		artifactService.refreshDtrackProjects(orgUuid);
		return true;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "cleanupDtrackProjects")
	public Boolean cleanupDtrackProjects(@InputArgument("orgUuid") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		// Verify user has admin access to the organization
		var odCleanup = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roCleanup = odCleanup.isPresent() ? odCleanup.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roCleanup), CallType.ADMIN);
		
		log.info("User {} initiated DTrack project cleanup for organization {}", oud.get().getUuid(), orgUuid);
		
		integrationService.cleanupArchivedDtrackProjectsAsync(orgUuid);
		return true;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "recleanupDtrackProjects")
	public Boolean recleanupDtrackProjects(@InputArgument("orgUuid") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		// Verify user has admin access to the organization
		var odRecleanup = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roRecleanup = odRecleanup.isPresent() ? odRecleanup.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roRecleanup), CallType.ADMIN);
		
		log.info("User {} initiated DTrack project recleanup for organization {}", oud.get().getUuid(), orgUuid);
		
		integrationService.recleanupDtrackProjectsAsync(orgUuid);
		return true;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "syncDtrackProjects")
	public Boolean syncDtrackProjects(@InputArgument("orgUuid") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		// Verify user has admin access to the organization
		var odSync = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSync = odSync.isPresent() ? odSync.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSync), CallType.ADMIN);
		
		log.info("User {} initiated DTrack project sync for organization {}", oud.get().getUuid(), orgUuid);
		
		artifactService.syncUnsyncedDependencyTrackDataAsync(orgUuid);
		return true;
	}
}
