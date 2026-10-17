/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.BranchData;
import io.reliza.model.RelizaObject;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactWebDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.CommitRecognitionService;
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
	CommitRecognitionService commitRecognitionService;
	
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
	public SourceCodeEntryData getSourceCodeEntry(@InputArgument("sceUuid") UUID sceUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Optional<SourceCodeEntryData> osced = getSourceCodeEntryService.getSourceCodeEntryData(sceUuid);
		RelizaObject ro = osced.isPresent() ? osced.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.BRANCH, osced.isPresent() ? osced.get().getBranch() : null, List.of(ro), CallType.READ);
		return osced.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createSourceCodeEntry")
	public SourceCodeEntryData createSourceCodeEntry(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> sourceCodeEntryInput = dfe.getArgument("sourceCodeEntry");
		SceDto sceDto = Utils.OM.convertValue(sourceCodeEntryInput, SceDto.class);
		Optional<BranchData> obd = branchService.getBranchData(sceDto.getBranch()); 
		RelizaObject branchRo = obd.isPresent() ? obd.get() : null;
		Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(sceDto.getVcs());
		RelizaObject vcsRo = ovrd.isPresent() ? ovrd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.BRANCH, sceDto.getBranch(), List.of(branchRo, vcsRo), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		SourceCodeEntry sce = sourceCodeEntryService.createSourceCodeEntry(sceDto, wu);
		return SourceCodeEntryData.dataFromRecord(sce);
	}

	@DgsData(parentType = "SourceCodeEntry", field = "artifactDetails")
	public List<ArtifactWebDto> artifactsOfSourceCodeEntryWithDep(DgsDataFetchingEnvironment dfe)  {
		SourceCodeEntryData sced = dfe.getSource();
		List<ArtifactWebDto> artList = new LinkedList<>();
		int missing = 0;
		int foreign = 0;
		if (null != sced.getArtifacts() && !sced.getArtifacts().isEmpty()) {
			// Scoped to the entry's owner (GetSourceCodeEntryService.ownerOrg, the one definition
			// of the entry's org this class uses); an entry with no known owner shows none.
			UUID org = getSourceCodeEntryService.ownerOrg(sced);
			for (SCEArtifact scea : sced.getArtifacts()) {
				// Skip a dangling artifact reference rather than .get()-throwing
				// on the missing row, which surfaced as a SERVICE_ERROR that
				// failed the whole enclosing release query.
				var oad = artifactService.getArtifactData(scea.artifactUuid());
				if (oad.isEmpty()) {
					missing++;
				} else if (null == org || !org.equals(oad.get().getOrg())) {
					// Another org's artifact merged into this entry before the merge
					// checked ownership, or any artifact of an entry with no known
					// owner: not this entry's to show.
					foreign++;
				} else {
					artList.add(ArtifactWebDto.fromData(oad.get(), scea.componentUuid()));
				}
			}
		}
		if (missing > 0) {
			log.warn("Source code entry {} references {} missing artifact(s); omitted from artifactDetails",
					sced.getUuid(), missing);
		}
		if (foreign > 0) {
			log.warn("LEGACY-REF: source code entry {} references {} artifact(s) outside its organization; omitted from artifactDetails",
					sced.getUuid(), foreign);
		}
		return artList;
	}
	
	@DgsData(parentType = "SourceCodeEntry", field = "vcsRepository")
	public Optional<VcsRepositoryData> vcsRepositoryOfSourceCodeEntry (DgsDataFetchingEnvironment dfe) {
		Optional<VcsRepositoryData> ovrd = Optional.empty();
		SourceCodeEntryData sced = dfe.getSource();
		UUID vcsRepo = sced.getVcs();
		if (null != vcsRepo) {
			// Scoped to the entry's owner: a stored reference to another org's repository, or
			// an entry with no known owner, resolves to null.
			ovrd = vcsRepositoryService.getVcsRepositoryData(vcsRepo, getSourceCodeEntryService.ownerOrg(sced));
		}
		return ovrd;
	}

	/**
	 * SourceCodeEntry.releases — releases whose primary sourceCodeEntry
	 * is this commit. Used by the agent-session view to render a
	 * commit→release jump-off; usually 0 or 1 entry per SCE, but the
	 * field is a list to cover rebuilds against the same commit.
	 *
	 * <p>Scoped to the entry's owner ({@link GetSourceCodeEntryService#ownerOrg}: its org, or
	 * its branch's for a legacy entry stored without one; none when neither is known), like
	 * artifactDetails and vcsRepository. Every field that hands out a
	 * SourceCodeEntry is itself org-scoped: Query.sourceCodeEntry authorizes
	 * the entry, and the Release, IntermediateFailedRelease and PullRequest
	 * commit fields read through GetSourceCodeEntryService
	 * .getReferenceableSceDataList, which returns only entries of the
	 * parent's own org or of the shared external-components org. So the
	 * entry's org here is either one the caller reached legitimately or the
	 * external org -- whose releases are public by design (the org-scoped
	 * release lookup admits them for every org) -- and never another
	 * customer's org to pivot into.
	 */
	@DgsData(parentType = "SourceCodeEntry", field = "releases")
	public List<ReleaseData> releasesOfSourceCodeEntry(DgsDataFetchingEnvironment dfe) {
		SourceCodeEntryData sced = dfe.getSource();
		if (sced == null || sced.getUuid() == null) return List.of();
		UUID org = getSourceCodeEntryService.ownerOrg(sced);
		if (null == org) return List.of();
		return sharedReleaseService.findReleaseDatasBySce(sced.getUuid(), org);
	}
}