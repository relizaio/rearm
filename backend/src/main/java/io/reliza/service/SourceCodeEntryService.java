/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.reliza.common.CommonVariables.TableName;

import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.versioning.VersionApi;
import io.reliza.versioning.VersionApi.ActionEnum;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SourceCodeEntryService {
	
	@Autowired
	private AuditService auditService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private VcsRepositoryService vcsRepositoryService;
	
	private final SourceCodeEntryRepository repository;
	
	SourceCodeEntryService(SourceCodeEntryRepository repository) {
	    this.repository = repository;
	}
	
	private Optional<SourceCodeEntry> getSourceCodeEntry (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<SourceCodeEntryData> getSourceCodeEntryData (UUID uuid) {
		Optional<SourceCodeEntryData> rData = Optional.empty();
		Optional<SourceCodeEntry> r = getSourceCodeEntry(uuid);
		if (r.isPresent()) {
			rData = Optional
							.of(
								SourceCodeEntryData
									.dataFromRecord(r
										.get()
								));
		}
		return rData;
	}
	
	private List<SourceCodeEntry> getSceList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		// return (List<Release>) repository.findAllById(uuidList);
		return repository.findScesOfOrgsByIds(uuidList, orgs);
	}
	
	public List<SourceCodeEntryData> getSceDataList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		// check if list includes null uuid - in which case include placeholder sce
		Set<UUID> uuidListToResolve;
		boolean includesNull = false;
		if (uuidList.contains(new UUID(0,0)) || uuidList.contains(null)) {
			uuidListToResolve = uuidList.stream().filter(x -> null != x && !x.equals(new UUID(0,0)))
												.collect(Collectors.toSet());
			includesNull = true;
		} else {
			uuidListToResolve = new LinkedHashSet<>(uuidList);
		}
		List<SourceCodeEntry> sces = getSceList(uuidListToResolve, orgs);
		List<SourceCodeEntryData> sceds = sces
				.stream()
				.map(SourceCodeEntryData::dataFromRecord)
				.collect(Collectors.toList());
		if (includesNull) {
			// construct null sced
			var nullSced = SourceCodeEntryData.obtainNullSceData();
			sceds.add(nullSced);
		}
		
		return sceds;
	}

	public List<SourceCodeEntry> getSourceCodeEntrys (Iterable<UUID> uuids) {
		return (List<SourceCodeEntry>) repository.findAllById(uuids);
	}
	
	public List<SourceCodeEntryData> getSourceCodeEntryDataList (Iterable<UUID> uuids) {
		List<SourceCodeEntry> branches = getSourceCodeEntrys(uuids);
		return branches.stream().map(SourceCodeEntryData::dataFromRecord).collect(Collectors.toList());
	}


	public Optional<SourceCodeEntryData> populateSourceCodeEntryByVcsAndCommit(
		SceDto sceDto,
		boolean createIfMissing,
		WhoUpdated wu) {
			Optional<SourceCodeEntryData> osced = Optional.empty();
			VcsType vcsType = sceDto.getType();
			Optional<BranchData> obd =  branchService.getBranchData(sceDto.getBranch());
			if(obd.isEmpty())
				return null;
			
			BranchData bd = obd.get();
			// check vs branch vcs and return error if doesn't match
			UUID vcsUuidFromBranch = bd.getVcs();
			Optional<VcsRepository> ovr = vcsRepositoryService.getVcsRepository(vcsUuidFromBranch);
			String vcsUri = sceDto.getUri();
			if (StringUtils.isNotEmpty(vcsUri) && ovr.isPresent() && !Utils.uriEquals(vcsUri, VcsRepositoryData.dataFromRecord(ovr.get()).getUri())) {
				throw new AccessDeniedException("Current vcs repository in branch does not match the supplied one, manual fix required");
			} else if (ovr.isEmpty() && StringUtils.isNotEmpty(vcsUri) && null != vcsType) {	// branch does not have vcs repo set
				ovr = vcsRepositoryService.getVcsRepositoryByUri(sceDto.getOrganizationUuid(), vcsUri, vcsType, true, wu);
				// update branch with correct vcs repo
				try {
					BranchDto branchDto = BranchDto.builder()
												.uuid(bd.getUuid())
												.vcs(ovr.get().getUuid())
												.vcsBranch(sceDto.getVcsBranch())
												.build();
					branchService.updateBranch(branchDto, wu);
				} catch (RelizaException re) {
					throw new RuntimeException(re.getMessage());
				} 
			} else if (ovr.isEmpty() && null == bd.getVcs()) {
				// fail if no vcs data is provided and branch does not have vcs linked already
				throw new AccessDeniedException("Branch does not have linked VCS repository and no VCS data provided in the call");
			}

			// construct source code entry itself
			sceDto.setBranch(bd.getUuid());
			sceDto.setVcs(ovr.get().getUuid());
			Optional<SourceCodeEntry> osce =  populateSourceCodeEntryByVcsAndCommitRoutine(sceDto, true, wu);
			if (osce.isPresent()) osced = Optional.of(SourceCodeEntryData.dataFromRecord(osce.get()));
			return osced;
	}
	
	private Optional<SourceCodeEntry> populateSourceCodeEntryByVcsAndCommitRoutine (SceDto sceDto, boolean createIfMissing, WhoUpdated wu) {
		// log.info("getSourceCodeEntryByVcsAndCommit - createIfMissing: {}", createIfMissing);
		Optional<SourceCodeEntry> osce = repository.findByCommitAndVcs(sceDto.getCommit(), sceDto.getVcs().toString());
		if (osce.isEmpty() && createIfMissing) {
			// log.info("osce is empty creating new ...");
			osce = Optional.of(createSourceCodeEntry(sceDto, wu));
		}else{
			var sce = osce.get();
			log.info("Existing sce found, updating ...: {}", osce.get());
			// need to add or update to the existing entry?
			SourceCodeEntryData sced = SourceCodeEntryData.scEntryDataFactory(sceDto);
			Map<String,Object> recordData = Utils.dataToRecord(sced);
			osce = Optional.of(saveSourceCodeEntry(sce, recordData, wu));
		}
		return osce;
	}

	public List<SourceCodeEntry> getSourceCodeEntriesByVcsAndCommits (UUID vcsUuid, List<String> commits) {
		return repository.findByCommitsAndVcs(commits, vcsUuid.toString());
	}
	
	public List<SourceCodeEntry> getSourceCodeEntriesByCommitTag (UUID orgUuid, String commit) {
		return repository.findByCommitOrTag(orgUuid.toString(), commit + "%", commit);
	}
	
	public List<SourceCodeEntry> listSceByComponent (UUID projUuid) {
		return repository.findByComponent(projUuid.toString());
	}
	
	public List<SourceCodeEntry> listSceByOrg (UUID orgUuid) {
		return repository.findByOrg(orgUuid.toString());
	}
	
	@Transactional
	public SourceCodeEntry createSourceCodeEntry (SceDto sceDto, WhoUpdated wu) {
		SourceCodeEntry sce = new SourceCodeEntry();
		VcsRepositoryData vrd = vcsRepositoryService.getVcsRepositoryData(sceDto.getVcs()).get(); //must exist - TODO error handling
		// resolve organization via branch
		Optional<BranchData> bdOpt = branchService.getBranchData(sceDto.getBranch());
		if (bdOpt.isPresent()) {
			UUID projUuid = bdOpt.get().getComponent();
			UUID orgUuid = getComponentService
										.getComponentData(projUuid)
										.get()
										.getOrg();
			if (null == sceDto.getOrganizationUuid())
				sceDto.setOrganizationUuid(orgUuid);
		}
		
		SourceCodeEntryData sced = SourceCodeEntryData.scEntryDataFactory(sceDto);
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		return saveSourceCodeEntry(sce, recordData, wu);
	}

	@Transactional
	public boolean addArtifact(UUID sceUuid, UUID artifactUuid, WhoUpdated wu) throws RelizaException{
		SourceCodeEntry sce = getSourceCodeEntry(sceUuid).get();
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
		List<UUID> artifacts = sced.getArtifacts();
		artifacts.add(artifactUuid);
		sced.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		saveSourceCodeEntry(sce, recordData, wu);
		return true;
	}
	
	@Transactional
	private SourceCodeEntry saveSourceCodeEntry (SourceCodeEntry sce, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// TODO: add validation
		Optional<SourceCodeEntry> osce = getSourceCodeEntry(sce.getUuid());
		if (osce.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.SOURCE_CODE_ENTRIES, sce);
			sce.setRevision(sce.getRevision() + 1);
			sce.setLastUpdatedDate(ZonedDateTime.now());
		}
		sce.setRecordData(recordData);
		sce = (SourceCodeEntry) WhoUpdated.injectWhoUpdatedData(sce, wu);
		return repository.save(sce);
	}
	
//	public boolean moveScesOfComponentToNewOrg (UUID projectUuid, UUID newOrg, WhoUpdated wu) {
//		boolean moved = false;
//		// locate sces
//		List<SourceCodeEntry> sceList = listSceByComponent(projectUuid);
//		if (!sceList.isEmpty()) {
//			for (SourceCodeEntry sce : sceList) {
//				SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
//				sced.setOrg(newOrg);
//				// save
//				saveSourceCodeEntry(sce, Utils.dataToRecord(sced), wu);
//				moved = true;
//			}
//		}
//		return moved;
//	}
	
	public List<SourceCodeEntryData> listSceDataByComponent(UUID projUuid){
		List<SourceCodeEntry> sceList = listSceByComponent(projUuid);
		return sceList
		.stream()
		.map(SourceCodeEntryData::dataFromRecord)
		.collect(Collectors.toList());
	}
	
	

	
	/**
	 * Mutates commits
	 * @param sceMap
	 * @param commits
	 */
	public void normalizeSceMapAndCommits(Map<String, Object> sceMap, List<Map<String, Object>> commits) {
		
	}

	
	/**
	 * <b>TODO</b> pass sceDto object instead of Map and List?
	 * <p>This function returns the bump action that should be taken based on a commit message.
	 * The commit message comes from either the soureCodeEntry map, or the commits list. All
	 * commit messages present in sceMap or commits list will be parsed, and the largest bump
	 * action will be returned.
	 * 
	 * @param sceMap {@code Map<String, Object>} object representing a SourceCodeEntryInput
	 * @param commits {@code List<Map<String, Object>>} commits list object
	 * @return {@code ActionEnum} the largest action parsed from commit message contents, or null if no valid commit message is present in SCE.
	 */
	public ActionEnum getBumpActionFromSourceCodeEntryInput(SceDto sceMap, List<SceDto> commits, Set<String> rejectedCommits) {
		// make sure all commit messages use System line seperator for newlines
		// this can be removed once versioning library updated to at least commit db5c3387a1a1b31d0f248cac82251ba3f4783638
		if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommitMessage())) {
			sceMap.cleanMessage();
		}
		
		// If SCE specifies a commit but no commitMessage, try and find matching commit in repo
		if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommit()) 
				&& null != sceMap.getVcs() && StringUtils.isEmpty(sceMap.getCommitMessage())) {
			// Convert sceMap to sceDto and transfer to sourceCodeEntry service to check if commit exists in repo already
			Optional<SourceCodeEntryData> osced = Optional.empty();
			osced = populateSourceCodeEntryByVcsAndCommit(sceMap, false, WhoUpdated.getAutoWhoUpdated());
			if (osced.isPresent() && StringUtils.isNotEmpty(osced.get().getCommitMessage())) {
				String commitMessage = osced.get().getCommitMessage();
				sceMap.setCommitMessage(commitMessage);
			} else {
				// if commit message not found, null sceMap so we don't try to parse non-existent commit message field
				sceMap = null;
			}
		}
		
		// Check what input is present. If commits list is null, simply parse from sceMap
		if (sceMap != null && (commits == null || commits.isEmpty()) && StringUtils.isNotEmpty(sceMap.getCommitMessage())) {
			return VersionApi.getActionFromRawCommit(sceMap.getCommitMessage());
		// Otherwise, if commits list is present, parse every commit message and return largest action
		} else if (commits != null && !commits.isEmpty()) {
			// Add commit from SCE to commits list to easily iterate through all commits
			if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommitMessage()) 
					&& !sceMap.getCommit().equalsIgnoreCase(commits.get(0).getCommit())) {
				commits.add(sceMap);
			}
			
			// Find largest action from list
			ActionEnum largestAction = null;
			for (SceDto commit : commits) {
				try {
					if (!rejectedCommits.contains(commit.getCommit())) {
						ActionEnum action = VersionApi.getActionFromRawCommit(commit.getCommitMessage());
						// Check if action is greater than largestAction we have parsed so far
						if (action != null && (largestAction == null || action.compareTo(largestAction) > 0)) {
							largestAction = action;
						}
					}
				} catch (IllegalArgumentException e) {
					// if commit message does not meet spec for some reason, just ignore it
				}
			}
			// return largest action, may be null if we could not find a valid commit message in the commits list
			return largestAction;
		} else {
			// sceMap is null (or does not contain CommitMessage field) and commits list is null, nothing to parse action from, return null
			return null;
		}
	}

	public Set<UUID> getTicketsList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		List<SourceCodeEntryData> sces = getSceDataList(uuidList, orgs);
		return sces
				.stream()
				.map(SourceCodeEntryData::getTicket)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
	}

	public Optional<SourceCodeEntry> findLatestSceWithTicketAndOrg(UUID ticket, UUID org) {
		return repository.findByTicketAndOrg(ticket.toString(), org.toString());
	}


	public void saveAll(List<SourceCodeEntry> sourceCodeEntries){
		repository.saveAll(sourceCodeEntries);
	}
}
