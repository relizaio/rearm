/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.BranchData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ArtifactWebDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class SourceCodeEntryDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	GetSourceCodeEntryService getSourceCodeEntryService;

	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	BranchService branchService;
	
	@Autowired
	VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sourceCodeEntry")
	public SourceCodeEntryData getSourceCodeEntry(@InputArgument("sceUuid") String sceUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID sceUuid = UUID.fromString(sceUuidStr);
		
		Optional<SourceCodeEntryData> osced = getSourceCodeEntryService.getSourceCodeEntryData(sceUuid);
		RelizaObject ro = osced.isPresent() ? osced.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return osced.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createSourceCodeEntry")
	public SourceCodeEntryData createSourceCodeEntry(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> sourceCodeEntryInput = dfe.getArgument("sourceCodeEntry");
		SceDto sceDto = Utils.OM.convertValue(sourceCodeEntryInput, SceDto.class);
		Optional<BranchData> obd = branchService.getBranchData(sceDto.getBranch()); 
		RelizaObject branchRo = obd.isPresent() ? obd.get() : null;
		Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(sceDto.getVcs());
		RelizaObject vcsRo = ovrd.isPresent() ? ovrd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObjects(oud.get(), List.of(branchRo, vcsRo), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		SourceCodeEntry sce = sourceCodeEntryService.createSourceCodeEntry(sceDto, wu);
		return SourceCodeEntryData.dataFromRecord(sce);
	}

	@DgsData(parentType = "SourceCodeEntry", field = "artifactDetails")
	public List<ArtifactWebDto> artifactsOfSourceCodeEntryWithDep(DgsDataFetchingEnvironment dfe)  {
		SourceCodeEntryData sced = dfe.getSource();
		List<ArtifactWebDto> artList = new LinkedList<>();
		if (null != sced.getArtifacts()) {
			for (SCEArtifact scea : sced.getArtifacts()) {
				ArtifactWebDto awd = ArtifactWebDto.fromData(artifactService
						.getArtifactData(scea.artifactUuid())
						.get(), scea.componentUuid());
				artList.add(awd);
			}
		}
		return artList;
	}
	
	@DgsData(parentType = "SourceCodeEntry", field = "vcsRepository")
	public Optional<VcsRepositoryData> vcsRepositoryOfSourceCodeEntry (DgsDataFetchingEnvironment dfe) {
		Optional<VcsRepositoryData> ovrd = Optional.empty();
		SourceCodeEntryData sced = dfe.getSource();
		UUID vcsRepo = sced.getVcs();
		if (null != vcsRepo) {
			ovrd = vcsRepositoryService.getVcsRepositoryData(vcsRepo);
		}
		return ovrd;
	}
}