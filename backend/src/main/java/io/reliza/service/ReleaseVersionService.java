/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.VersionResponse;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.VersionAssignment;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.dto.ComponentJsonDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.VersionAssignmentService.GetNewVersionDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.versioning.VersionApi.ActionEnum;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ReleaseVersionService {
	
	@Autowired
	private VersionAssignmentService versionAssignmentService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private ReleaseService releaseService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private OssReleaseService ossReleaseService;

	@Autowired
    private SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
    private GetSourceCodeEntryService getSourceCodeEntryService;
	
	@Transactional
	public VersionResponse getNewVersionWrapper(GetNewVersionDto getNewVersionDto, WhoUpdated wu) throws Exception{
		VersionResponse vr = null;
		UUID projectId = getNewVersionDto.project();
		Optional<ComponentData> opd = getComponentService.getComponentData(projectId);
		if (opd.isEmpty()) {
			log.warn("Component not found: {}", projectId);
			throw new RelizaException("Component " + projectId + " not found");
		}
		ComponentData pd = opd.get();
		BranchData bd = branchService.getBranchDataFromBranchString(getNewVersionDto.branch(), projectId, wu);
		UUID branchUuid = bd.getUuid();

		// Check if source code entry commit is already attached to a release on this branch
		// If found, return the existing version instead of generating a new one
		SceDto sourceCodeEntry = getNewVersionDto.sourceCodeEntry();
		if (null == sourceCodeEntry && null != getNewVersionDto.commits() && !getNewVersionDto.commits().isEmpty()) {
			sourceCodeEntry = getNewVersionDto.commits().get(0);
		} 
		if (sourceCodeEntry != null && StringUtils.isNotEmpty(sourceCodeEntry.getCommit())) {
			UUID vcsUuid = bd.getVcs();
			if (vcsUuid != null) {
				List<SourceCodeEntry> existingSces = getSourceCodeEntryService.getSourceCodeEntriesByVcsAndCommits(
						vcsUuid, List.of(sourceCodeEntry.getCommit()));
				if (!existingSces.isEmpty()) {
					SourceCodeEntry existingSce = existingSces.get(0);
					List<ReleaseData> releasesBySce = sharedReleaseService.findReleaseDatasBySce(
							existingSce.getUuid(), pd.getOrg());
					Optional<ReleaseData> matchingRelease = releasesBySce.stream()
							.filter(rd -> rd.getBranch().equals(branchUuid))
							.findFirst();
					if (matchingRelease.isPresent()) {
						String existingVersion = matchingRelease.get().getVersion();
						log.info("Found existing release {} for commit {} on branch {}", 
								existingVersion, sourceCodeEntry.getCommit(), getNewVersionDto.branch());
						return new VersionResponse(existingVersion, 
								Utils.dockerTagSafeVersion(existingVersion), null, true);
					}
				}
			}
		}

		// Check for missing version schema before attempting version generation
		if (StringUtils.isEmpty(pd.getVersionSchema()) && StringUtils.isEmpty(bd.getVersionSchema())) {
			log.error("Version schema validation failed - Component: {}, UUID: {}, ComponentVersionSchema: {}, BranchVersionSchema: {}", 
				pd.getName(), pd.getUuid(), pd.getVersionSchema(), bd.getVersionSchema());
			throw new RelizaException(String.format(
				"Component '%s' (%s) is missing version schema configuration.",
				pd.getName(), pd.getUuid()
			));
		}

		versionAssignmentService.checkAndUpdateVersionPinOnBranch(pd, bd, getNewVersionDto.versionSchema(), wu);

		ActionEnum bumpAction = getBumpAction(getNewVersionDto.action(), getNewVersionDto.sourceCodeEntry(), getNewVersionDto.commits(), bd, pd);
		
		Optional<VersionAssignment> ova = versionAssignmentService.getSetNewVersionWrapper(branchUuid, bumpAction, getNewVersionDto.modifier(), getNewVersionDto.modifier(), getNewVersionDto.versionType());
		if(ova.isEmpty()) {
			throw new AccessDeniedException("Failed to retrieve next version");
		}
		
		VersionAssignment va =  ova.get();
		String nextVersion = va.getVersion();
		String dockerTagSafeVersion = Utils.dockerTagSafeVersion(nextVersion);
			
		if(getNewVersionDto.onlyVersion())
			vr = new VersionResponse(nextVersion, dockerTagSafeVersion, "");
		
	
		ComponentJsonDto changelog = releaseService.createReleaseAndGetChangeLog(getNewVersionDto.sourceCodeEntry(), getNewVersionDto.commits(), nextVersion, getNewVersionDto.lifecycle(), bd, wu);

		if (null != changelog) {
			vr = new VersionResponse(nextVersion, dockerTagSafeVersion, changelog.toString());
		}

		return vr;
	}
	
	private ActionEnum getBumpAction(String action, SceDto sourceCodeEntry, List<SceDto> commits, BranchData bd, ComponentData pd) throws Exception{
		ActionEnum bumpAction = null;
		if (StringUtils.isNotEmpty(action)) {
			// first check if action input is present
			bumpAction = ActionEnum.getActionEnum(action);
			if (bumpAction == null) {
				log.warn("'action' field input could not be resolved to a valid action type. Defaulting to ActionEnum.Bump");
			}
		} else if (sourceCodeEntry != null || commits != null) {
			// else check for action from sce or commits
			try {
				// retrieve commits of previously rejected releases to prevent improper bump
				Optional<ReleaseData> latestRelease = ossReleaseService.getReleasePerProductComponent(bd.getOrg(), bd.getComponent(), null, bd.getName(), null);
				List<ReleaseData> currentRelease = sharedReleaseService.listReleaseDataOfBranch(bd.getUuid(), 1, true);
				Set<String> rejectedCommits = new HashSet<>();
				if (latestRelease.isPresent() && !currentRelease.isEmpty()) {
					rejectedCommits = sharedReleaseService.listAllReleasesBetweenReleases(currentRelease.get(0).getUuid(), latestRelease.get().getUuid()).stream()
							.flatMap(release -> getSourceCodeEntryService.getSceDataList(release.getAllCommits(), Collections.singleton(pd.getOrg())).stream()
									.map(sce -> sce.getCommit())
									.collect(Collectors.toList())
									.stream()
							).collect(Collectors.toSet());
				}
				bumpAction = sourceCodeEntryService.getBumpActionFromSourceCodeEntryInput(sourceCodeEntry, commits, rejectedCommits);
			} catch (IllegalArgumentException e) {
				// bad to catch unchecked exceptions??
				log.warn("Exception on resolving bump action from commit message. Defaulting to ActionEnum.Bump",commits);
			}
		} else if (pd.getType() == ComponentType.PRODUCT) {
			log.info("PSDEBUG: in generate new version for product from branch data fetcher for branch id = " + bd.getUuid());
			// default to action if present, otherwise if requesting new version for a product, check components for new releases
			try {
				// This product bump functionality is a placeholder, not currently being used at the moment - 2021-08-11 - Christos
				bumpAction = ossReleaseService.getLargestActionFromComponents(pd.getUuid(), bd.getUuid());
			} catch (RelizaException re) {
				throw new RuntimeException(re.getMessage());
			}
		}		
		return bumpAction;
	}
}
