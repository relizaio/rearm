/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.Acollection;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.ArtifactComparison;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AcollectionRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AcollectionService {
	
	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	@Autowired
	RebomService rebomService;

	@Autowired
	NotificationService notificationService;

	@Autowired
	GetComponentService getComponentService;

	@Autowired
	BranchService branchService;
	
	@Autowired
	private ArtifactGatherService artifactGatherService;
			
	private final AcollectionRepository repository;
	
	AcollectionService(AcollectionRepository repository) {
		this.repository = repository;
	}
	
	public Optional<Acollection> getAcollection (UUID uuid) {
		return repository.findById(uuid);
	}

	@Transactional
	public Optional<Acollection> getAcollectionWriteLocked (UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}
	
	public Optional<AcollectionData> getAcollectionData (UUID uuid) {
		Optional<AcollectionData> ovd = Optional.empty();
		Optional<Acollection> v = getAcollection(uuid);
		if (v.isPresent()) {
			ovd = Optional
							.of(
									AcollectionData
									.dataFromRecord(v
										.get()
								));
		}
		return ovd;
	}
	
	public List<AcollectionData> getAcollectionDatasOfRelease (UUID releaseUuid) {
		return repository.findAcollectionsByRelease(releaseUuid.toString()).stream().map(AcollectionData::dataFromRecord).toList();
	}
	
	public AcollectionData getLatestCollectionDataOfRelease(UUID releaseUuid) {
		AcollectionData latestAcd = null;
		List<AcollectionData> existingCollections = getAcollectionDatasOfRelease(releaseUuid);
		if (!existingCollections.isEmpty()) {
			var ecIter = existingCollections.iterator();
			while (ecIter.hasNext()) {
				var curCol = ecIter.next();
				if (latestAcd == null || curCol.getVersion() > latestAcd.getVersion()) {
					latestAcd = curCol;
				}
			}
		}
		return latestAcd;
	}
		
	@Transactional
	public AcollectionData resolveReleaseCollection (UUID releaseUuid, WhoUpdated wu) {
		AcollectionData resolvedCollection = null;
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid).get();
		Set<UUID> artIds = artifactGatherService.gatherReleaseArtifacts(rd);
		Set<VersionedArtifact> versionedArtifacts = new HashSet<>();
		artIds.forEach(aid -> {
			var ad = artifactService.getArtifactData(aid).get();
			if (!rd.getOrg().equals(ad.getOrg())) {
				log.error("SECURITY: mismatching org for release = " + releaseUuid + ", art = " + aid);
				throw new IllegalStateException("Wrong release or artifact");
			}
			Long version = Long.valueOf(0);
			if (ad.getStoredIn() == StoredIn.REARM && artifactService.isRebomStoreable(ad)) {
				var bomMetas = rebomService.resolveBomMetas(ad.getInternalBom().id(), rd.getOrg());
				var verOpt = bomMetas.stream().map(x -> Long.valueOf(x.bomVersion())).max(Long::compareTo);
				if (verOpt.isPresent()) {
					version = verOpt.get();
				} else {
					log.warn("Missing bom version on rebom for artid = " + ad.getInternalBom().id());
					version = Long.valueOf(1);
				}
			} else {
				// TODO
				version = Long.valueOf(1);
			}
			VersionedArtifact va = new VersionedArtifact(aid, version, ad.getType());
			versionedArtifacts.add(va);
		});
		
		
		var latestAcolData = getLatestCollectionDataOfRelease(releaseUuid);
		Long curColVersion = Long.valueOf(1);
		if (null != latestAcolData) curColVersion = latestAcolData.getVersion() + 1;
		
		if (null != latestAcolData && versionedArtifacts.equals(latestAcolData.getArtifacts())) {
			resolvedCollection = latestAcolData;
		} else {
			ArtifactComparison artifactComparison = null != latestAcolData ? latestAcolData.getArtifactComparison() : null;
			AcollectionData curColData = AcollectionData.acollectionDataFactory(rd.getOrg(), releaseUuid, curColVersion, versionedArtifacts, artifactComparison);
			curColData.resolveUpdateReason(latestAcolData);
			Map<String,Object> recordData = Utils.dataToRecord(curColData);
			Acollection ac = new Acollection();
			ac.setUuid(curColData.getUuid());
			ac = saveAcollection(ac, recordData, wu);
			resolvedCollection = AcollectionData.dataFromRecord(ac);
		}
		
		return resolvedCollection;
	}
	
	@Transactional
	private Acollection saveAcollection (Acollection ac, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// per schema version 0 we require that schema version 0 has version and identifier
		if (null == recordData || recordData.isEmpty() ||  null == recordData.get(CommonVariables.VERSION_FIELD)) {
			throw new IllegalStateException("Artifact Collection must have record data");
		}
		ac.setRecordData(recordData);
		Optional<Acollection> oac = getAcollectionWriteLocked(ac.getUuid());
		if (oac.isPresent()) {
			throw new IllegalStateException("Artifact Collections are immutable and cannot be modified, new version must be created instead");
		}
		ac = (Acollection) WhoUpdated.injectWhoUpdatedData(ac, wu);
		return repository.save(ac);
	}

	public void releaseBomChangelogRoutine(UUID releaseId, UUID branch, UUID org){

		UUID prevReleaseId = null;

		AcollectionData acd = getLatestCollectionDataOfRelease(releaseId);

		if(null != acd && null != acd.getArtifactComparison() && null != acd.getArtifactComparison().comparedReleaseUuid()) {
			prevReleaseId = acd.getArtifactComparison().comparedReleaseUuid();
		}else{
			prevReleaseId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(branch, releaseId);
		}
		if(prevReleaseId == null){
			// First release on this branch: compare with latest release on base branch
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseId);
			if (ord.isPresent()) {
				ReleaseData rd = ord.get();
				UUID componentId = rd.getComponent();
				Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
				if (ocd.isPresent() && ocd.get().getDefaultBranch() != null) {
					String baseBranchName = ocd.get().getDefaultBranch().name();
					try {
						Optional<Branch> baseBranchOpt = branchService.findBranchByName(componentId, baseBranchName.toLowerCase());
						if (baseBranchOpt.isPresent()) {
							Optional<ReleaseData> baseBranchLatestRd = sharedReleaseService.getLatestNonCancelledOrRejectedReleaseDataOfBranch(baseBranchOpt.get().getUuid());
							if (baseBranchLatestRd.isPresent()) {
								prevReleaseId = baseBranchLatestRd.get().getUuid();
							} else {
								log.debug("No release found on base branch {} for comparison.", baseBranchName);
							}
						} else {
							log.debug("Base branch {} not found for component {}", baseBranchName, componentId);
						}
					} catch (Exception e) {
						log.error("Error finding base branch by name: {}", baseBranchName, e);
					}
				}
			}
		}
		
		UUID nextReleaseId = sharedReleaseService.findNextReleasesOfBranchForRelease(branch, releaseId);
		// log.info("RGDEBUG: prevReleaseId found: {}", prevReleaseId);
		// log.info("RGDEBUG: nextReleaseId found: {}", nextReleaseId);

		if (prevReleaseId != null) {
			resolveBomDiff(releaseId, prevReleaseId, org);
		}
		if (nextReleaseId != null) {
			resolveBomDiff(nextReleaseId, releaseId, org);
		}
	}


	public void resolveBomDiff(UUID releaseId, UUID prevReleaseId, UUID org){
		// log.info("resolveBomDiff for releaseId: {}, prevReleaseId: {} ", releaseId, prevReleaseId);
		AcollectionData currAcollectionData = getLatestCollectionDataOfRelease(releaseId);
		AcollectionData prevAcollectionData = getLatestCollectionDataOfRelease(prevReleaseId);
		
		if(null != currAcollectionData.getArtifacts() 
			&& currAcollectionData.getArtifacts().size() > 0 
			&& null != prevAcollectionData 
			&& prevAcollectionData.getArtifacts().size() > 0
			&& ! prevAcollectionData.getArtifacts().equals(currAcollectionData.getArtifacts())
		){
			// log.info("RGDEBUG: current Release ID: {}", releaseId);
            List<UUID> currArtifacts = getInternalBomIdsFromACollection(currAcollectionData);
			// log.info("RGDEBUG: current Artifacts: {}", currArtifacts);

			// log.info("RGDEBUG: prev Release ID: {}", prevReleaseId);
			List<UUID> prevArtifacts = getInternalBomIdsFromACollection(prevAcollectionData);
			// log.info("RGDEBUG: prev Artifacts: {}", prevArtifacts);
			
			ArtifactChangelog artifactChangelog = rebomService.getArtifactChangelog(currArtifacts, prevArtifacts, org);

			if (null == currAcollectionData.getArtifactComparison() || null == currAcollectionData.getArtifactComparison().changelog()  || !currAcollectionData.getArtifactComparison().changelog().equals(artifactChangelog)) {
				log.debug("artifact changelog is different, persisiting!");
				persistArtifactChangelogForCollection(artifactChangelog, prevReleaseId, currAcollectionData.getUuid());

			}else{
				log.debug("duplicate trigger, not persisting changelog");
			}
		}
	}

	@Transactional
	private void persistArtifactChangelogForCollection(ArtifactChangelog artifactChangelog, UUID comparedReleaseUuid, UUID acollection){
		Acollection ac = getAcollectionWriteLocked(acollection).get();
		AcollectionData acd = AcollectionData.dataFromRecord(ac);
		AcollectionData.ArtifactComparison artifactComparison = new AcollectionData.ArtifactComparison(artifactChangelog, comparedReleaseUuid);
		acd.setArtifactComparison(artifactComparison);
		Map<String,Object> recordData = Utils.dataToRecord(acd);	

		ac.setRecordData(recordData);
		repository.save(ac);

		AcollectionData updatedAcd = AcollectionData.dataFromRecord(ac);
		UUID org = updatedAcd.getOrg();
		UUID releaseUuid = updatedAcd.getRelease();
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isPresent()) {
			notificationService.sendBomDiffAlert(org, ord.get(), artifactChangelog);
		}
	}
	private List<UUID> getInternalBomIdsFromACollection(AcollectionData collection){
		List<UUID> artIds = collection.getArtifacts().stream().map(VersionedArtifact::artifactUuid).toList();
		// log.info("RGDEBUG: artIds: {}", artIds);
		List<ArtifactData> artList = artifactService.getArtifactDataList(artIds);
		return artList.stream()
		.filter(art -> null != art.getInternalBom())
		.map(art -> art.getInternalBom().id())
		.distinct()
		.toList();
	}
}
