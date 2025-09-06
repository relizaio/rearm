/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.net.URI;
import java.time.ZonedDateTime;
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
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.RelizaObject;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
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
	SharedArtifactService sharedArtifactService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "configuredBaseIntegrations")
	public Set<IntegrationType> getConfiguredBaseIntegrations (@InputArgument("org") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);

		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);

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

		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), cii.org(), CallType.ADMIN);
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
		
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), org, CallType.ADMIN);
		
		Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
				org, integrationType, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isEmpty()) throw new RuntimeException("No base integration found");
		integrationService.deleteIntegration(oid.get().getUuid());
		return true;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "refetchDependencyTrackMetrics")
	public boolean refetchDependencyTrackMetrics(
			@InputArgument("artifact") UUID art) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<Artifact> oa = sharedArtifactService.getArtifact(art);
		
		RelizaObject ro = oa.isPresent() ? ArtifactData.dataFromRecord(oa.get()) : null;
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		
		return artifactService.fetchDependencyTrackDataForArtifact(oa.get(), ZonedDateTime.now());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "requestRefreshDependencyTrackMetrics")
	public boolean requestRefreshDependencyTrackMetrics(
			@InputArgument("artifact") UUID art) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> oad = artifactService.getArtifactData(art);
		
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		
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
		
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		
		return integrationService.searchDtrackComponentByPurlAndProjects(orgUuid, purl, dtrackProjects);
	}
}
