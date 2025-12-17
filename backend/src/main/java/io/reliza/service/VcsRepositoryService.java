/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.BranchRepository;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.VcsRepositoryRepository;

@Service
public class VcsRepositoryService {
	
	@Autowired
    private AuditService auditService;
	
	private final VcsRepositoryRepository repository;
	
	private final ComponentRepository componentRepository;
	
	private final BranchRepository branchRepository;
	
	VcsRepositoryService(VcsRepositoryRepository repository, ComponentRepository componentRepository, BranchRepository branchRepository) {
	    this.repository = repository;
	    this.componentRepository = componentRepository;
	    this.branchRepository = branchRepository;
	}
	
	public Optional<VcsRepository> getVcsRepository (UUID uuid) {
		Optional<VcsRepository> ovr = Optional.empty();
		if (null != uuid) {
			ovr = repository.findById(uuid);
		}
		return ovr;
	}
	
	public Optional<VcsRepositoryData> getVcsRepositoryData (UUID uuid) {
		Optional<VcsRepositoryData> vcsData = Optional.empty();
		Optional<VcsRepository> vr = getVcsRepository(uuid);
		if (vr.isPresent()) {
			vcsData = Optional
							.of(
									VcsRepositoryData
									.dataFromRecord(vr
										.get()
								));
		}
		return vcsData;
	}
	
	@Transactional
	public Optional<VcsRepository> getVcsRepositoryWriteLocked (UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}
	
	/**
	 * Find VCS repository data by URI within an organization (private helper).
	 * 
	 * @param orgUuid Organization UUID
	 * @param uri VCS repository URI
	 * @return Optional of VcsRepository
	 */
	private Optional<VcsRepository> findVcsRepositoryByOrgAndUri(UUID orgUuid, String uri) {
		String normalizedUri = Utils.normalizeVcsUri(uri);
		return repository.findByOrgAndUri(orgUuid.toString(), normalizedUri);
	}
	
	/**
	 * Find VCS repository by organization and URI.
	 * @param orgUuid Organization UUID
	 * @param uri VCS repository URI
	 * @return Optional of VcsRepositoryData
	 */
	public Optional<VcsRepositoryData> getVcsRepositoryDataByUri(UUID orgUuid, String uri) {
		Optional<VcsRepositoryData> vcsData = Optional.empty();
		Optional<VcsRepository> vr = findVcsRepositoryByOrgAndUri(orgUuid, uri);
		if (vr.isPresent()) {
			vcsData = Optional.of(VcsRepositoryData.dataFromRecord(vr.get()));
		}
		return vcsData;
	}
	
	public Optional<VcsRepository> getVcsRepositoryByUri (UUID orgUuid, String uri, VcsType type, boolean createIfMissing, WhoUpdated wu) {
		Optional<VcsRepository> ovr = findVcsRepositoryByOrgAndUri(orgUuid, uri);
		if (ovr.isEmpty() && createIfMissing) {
			String vcsName = Utils.deriveVcsNameFromUri(uri);
			ovr = Optional.of(createVcsRepository(vcsName, orgUuid, uri, type, wu));
		}
		return ovr;
	}
	
	public List<VcsRepository> listVcsReposByOrg(UUID orgUuid) {
		return repository.findVcsReposByOrganization(orgUuid
															.toString());
	}
	
	public List<VcsRepositoryData> listVcsRepoDataByOrg(UUID orgUuid) {
		List<VcsRepository> vcsRepoList = listVcsReposByOrg(orgUuid);
		return vcsRepoList
							.stream()
							.map(VcsRepositoryData::dataFromRecord)
							.collect(Collectors.toList());
	}
	
	public List<VcsRepository> getVcsRepositorys (Iterable<UUID> uuids) {
		return (List<VcsRepository>) repository.findAllById(uuids);
	}
	
	public List<VcsRepositoryData> getVcsRepositoryDataList (Iterable<UUID> uuids) {
		List<VcsRepository> branches = getVcsRepositorys(uuids);
		return branches.stream().map(VcsRepositoryData::dataFromRecord).collect(Collectors.toList());
	}

	public VcsRepository createVcsRepository (String name, UUID organization, 
													String uri, VcsType type, WhoUpdated wu) {
		VcsRepository vr = new VcsRepository();
		uri = Utils.cleanVcsUri(uri);
		VcsRepositoryData vrd = VcsRepositoryData.vcsRepositoryFactory(name, organization, uri, type);
		Map<String,Object> recordData = Utils.dataToRecord(vrd);
		return saveVcsRepository(vr, recordData, wu);
	}
	
	private VcsRepository saveVcsRepository (VcsRepository vr, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// TODO: add validation
		Optional<VcsRepository> ovr = getVcsRepository(vr.getUuid());
		if (ovr.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.VCS_REPOSITORIES, vr);
			vr.setRevision(vr.getRevision() + 1);
			vr.setLastUpdatedDate(ZonedDateTime.now());
		}
		vr.setRecordData(recordData);
		vr = (VcsRepository) WhoUpdated.injectWhoUpdatedData(vr, wu);
		return repository.save(vr);
	}
	
	public VcsRepository updateVcsRepository (VcsRepositoryData vrd, WhoUpdated wu) throws RelizaException {
		return updateVcsRepository(vrd.getUuid(), vrd.getName(), vrd.getUri(), wu);
	}
	
	@Transactional
	public VcsRepository updateVcsRepository (UUID vcsUuid, String name, String uri, WhoUpdated wu) throws RelizaException {
		VcsRepository vcsRepo = null;
		Optional<VcsRepository> vOpt = getVcsRepository(vcsUuid);
		if (vOpt.isPresent()) {
			VcsRepository v = vOpt.get();
			VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(v);
			if (StringUtils.isNotEmpty(name)) vrd.setName(name);
			if (StringUtils.isNotEmpty(uri)) vrd.setUri(uri);
			Map<String,Object> recordData = Utils.dataToRecord(vrd);
			vcsRepo = saveVcsRepository(v, recordData, wu);
		}
		return vcsRepo;
	}

//	public void moveVcsToNewOrg(UUID vcsRepository, UUID orgUuid, WhoUpdated wu) {
//		VcsRepository vcsr = getVcsRepository(vcsRepository).get();
//		VcsRepositoryData vcsrd = VcsRepositoryData.dataFromRecord(vcsr);
//		vcsrd.setOrg(orgUuid);
//		saveVcsRepository(vcsr, Utils.dataToRecord(vcsrd), wu);
//	}


	public void saveAll(List<VcsRepository> vcsRepositories){
		repository.saveAll(vcsRepositories);
	}
	
	/**
	 * Archive a VCS repository (soft delete).
	 * Only allowed if no active components or branches are attached to this VCS repository.
	 * 
	 * @param vcsUuid UUID of the VCS repository to archive
	 * @param wu WhoUpdated information
	 * @return true if archived successfully
	 * @throws RelizaException if components or branches are still attached or repository not found
	 */
	public Boolean archiveVcsRepository(UUID vcsUuid, WhoUpdated wu) throws RelizaException {
		Boolean archived = false;
		Optional<VcsRepository> ovr = getVcsRepository(vcsUuid);
		if (ovr.isPresent()) {
			// Check if any active components are attached to this VCS repository
			List<Component> attachedComponents = componentRepository.findComponentsByVcs(vcsUuid.toString());
			if (!attachedComponents.isEmpty()) {
				throw new RelizaException("Cannot archive VCS repository: " + attachedComponents.size() + 
					" active component(s) are still attached. Please remove or reassign components first.");
			}
			
			// Check if any active branches are attached to this VCS repository
			List<Branch> attachedBranches = branchRepository.findBranchesByVcs(vcsUuid.toString());
			if (!attachedBranches.isEmpty()) {
				throw new RelizaException("Cannot archive VCS repository: " + attachedBranches.size() + 
					" active branch(es) are still attached. Please remove or reassign branches first.");
			}
			
			VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(ovr.get());
			vrd.setStatus(StatusEnum.ARCHIVED);
			Map<String, Object> recordData = Utils.dataToRecord(vrd);
			saveVcsRepository(ovr.get(), recordData, wu);
			archived = true;
		}
		return archived;
	}
	
	/**
	 * Find VCS repository by URI including archived ones (for restore logic).
	 * 
	 * @param orgUuid Organization UUID
	 * @param uri VCS repository URI
	 * @return Optional of VcsRepository
	 */
	private Optional<VcsRepository> findVcsRepositoryByOrgAndUriIncludingArchived(UUID orgUuid, String uri) {
		String normalizedUri = Utils.normalizeVcsUri(uri);
		return repository.findByOrgAndUriIncludingArchived(orgUuid.toString(), normalizedUri);
	}
	
	/**
	 * Restore an archived VCS repository.
	 * 
	 * @param vcsUuid UUID of the VCS repository to restore
	 * @param wu WhoUpdated information
	 * @return restored VcsRepository
	 * @throws RelizaException if repository not found
	 */
	public VcsRepository restoreVcsRepository(UUID vcsUuid, WhoUpdated wu) throws RelizaException {
		Optional<VcsRepository> ovr = getVcsRepository(vcsUuid);
		if (ovr.isEmpty()) {
			throw new RelizaException("VCS repository not found");
		}
		VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(ovr.get());
		vrd.setStatus(StatusEnum.ACTIVE);
		Map<String, Object> recordData = Utils.dataToRecord(vrd);
		return saveVcsRepository(ovr.get(), recordData, wu);
	}
	
	/**
	 * Check if a VCS repository with the given URI exists (including archived).
	 * If it exists and is archived, restore it. Otherwise throw an error for duplicates.
	 * 
	 * @param name VCS repository name
	 * @param organization Organization UUID
	 * @param uri VCS repository URI
	 * @param type VCS type
	 * @param wu WhoUpdated information
	 * @return VcsRepository (either restored or newly created)
	 * @throws RelizaException if an active VCS repository with the same URI already exists
	 */
	public VcsRepository createOrRestoreVcsRepository(String name, UUID organization, 
			String uri, VcsType type, WhoUpdated wu) throws RelizaException {
		uri = Utils.cleanVcsUri(uri);
		// Check if an archived VCS repo with this URI already exists
		Optional<VcsRepository> existingArchived = findVcsRepositoryByOrgAndUriIncludingArchived(organization, uri);
		if (existingArchived.isPresent()) {
			VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(existingArchived.get());
			if (vrd.getStatus() == StatusEnum.ARCHIVED) {
				// Restore the archived repository
				vrd.setStatus(StatusEnum.ACTIVE);
				if (StringUtils.isNotEmpty(name)) vrd.setName(name);
				if (null != type) vrd.setType(type);
				Map<String, Object> recordData = Utils.dataToRecord(vrd);
				return saveVcsRepository(existingArchived.get(), recordData, wu);
			} else {
				// Already exists and is active - throw error
				throw new RelizaException("A VCS repository with this URI already exists in this organization.");
			}
		}
		// Create new VCS repository
		return createVcsRepository(name, organization, uri, type, wu);
	}
	
}
