/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.VcsType;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AuthorizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class VcsRepositoryDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	UserService userService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vcsRepository")
	public VcsRepositoryData getVcsRepository(@InputArgument("vcs") String vcsStr) {
		UUID vcsRepositoryUuid = UUID.fromString(vcsStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(vcsRepositoryUuid);
		RelizaObject ro = ovrd.isPresent() ? ovrd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return ovrd.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "listVcsReposOfOrganization")
	public List<VcsRepositoryData> listVcsReposOfOrganization(@InputArgument("orgUuid") String orgUuidStr) {
		UUID orgUuid = UUID.fromString(orgUuidStr);	
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = organizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return vcsRepositoryService.listVcsRepoDataByOrg(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vcsRepositoryTypes")
	public Set<String> getVcsRepositoryTypes() {
		return VcsType.getAvailableTypes();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createVcsRepository")
	public VcsRepositoryData createVcsRepository(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> vcsRepositoryInput = dfe.getArgument("vcsRepository");
		String orgUuidStr = (String) vcsRepositoryInput.get(CommonVariables.ORGANIZATION_FIELD);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = organizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		String name = (String) vcsRepositoryInput.get(CommonVariables.NAME_FIELD);
		String uri = (String) vcsRepositoryInput.get(CommonVariables.URI_FIELD);
		String typeStr = (String) vcsRepositoryInput.get(CommonVariables.TYPE_FIELD);
		VcsType type = null;
		if (StringUtils.isNotEmpty(typeStr)) {
			type = VcsType.resolveStringToType(typeStr);
		}
		VcsRepository vr = vcsRepositoryService.createVcsRepository(name, orgUuid, uri, type, wu);
		return VcsRepositoryData.dataFromRecord(vr);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateVcsRepository")
	public VcsRepositoryData updateBranch(
			@InputArgument("vcsUuid") String vcsUuidStr,
			@InputArgument("name") String name,
			@InputArgument("uri") String uri) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		UUID vcsUuid = UUID.fromString(vcsUuidStr);
		Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(vcsUuid);
		RelizaObject ro = ovrd.isPresent() ? ovrd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			return VcsRepositoryData.dataFromRecord(vcsRepositoryService.updateVcsRepository(vcsUuid, name, uri, wu));
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
	}
}
