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

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.VcsRepositoryRepository;

@Service
public class VcsRepositoryService {
	
	@Autowired
    private AuditService auditService;
	
	private final VcsRepositoryRepository repository;
	
	VcsRepositoryService(VcsRepositoryRepository repository) {
	    this.repository = repository;
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
		// Strip https:// or http:// prefix if present
		String normalizedUri = uri;
		if (uri.startsWith("https://")) {
			normalizedUri = uri.substring(8);
		} else if (uri.startsWith("http://")) {
			normalizedUri = uri.substring(7);
		}
		
		// Strip username@ prefix if present (e.g., myuser@dev.azure.com)
		int atIndex = normalizedUri.indexOf('@');
		if (atIndex > 0 && atIndex < normalizedUri.indexOf('/')) {
			normalizedUri = normalizedUri.substring(atIndex + 1);
		}
		
		return repository.findByOrgAndUri(orgUuid.toString(), normalizedUri);
	}
	
	/**
	 * Find VCS repository data by URI within an organization.
	 * 
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
			ovr = Optional.of(createVcsRepository(uri, orgUuid, uri, type, wu));
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
	
}
