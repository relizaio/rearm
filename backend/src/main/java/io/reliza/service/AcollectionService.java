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
import io.reliza.exceptions.RelizaException;
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

		// Always recalculate prevReleaseId during finalization
		// Don't trust the stored comparedReleaseUuid as it may be incorrect (e.g., from out-of-order finalization)
		prevReleaseId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(branch, releaseId);
		
		
		UUID nextReleaseId = sharedReleaseService.findNextReleasesOfBranchForRelease(branch, releaseId);

		if (prevReleaseId != null) {
			// Validate that prevRelease is actually before current release chronologically
			// This prevents inverted changelogs from being created
			Optional<ReleaseData> prevRd = sharedReleaseService.getReleaseData(prevReleaseId);
			Optional<ReleaseData> currRd = sharedReleaseService.getReleaseData(releaseId);
			
			if (prevRd.isPresent() && currRd.isPresent()) {
				if (prevRd.get().getCreatedDate().isAfter(currRd.get().getCreatedDate())) {
					log.warn("SBOM_CHANGELOG: prevRelease {} (date: {}) is AFTER current release {} (date: {}) - treating as first release", 
						prevReleaseId, prevRd.get().getCreatedDate(), 
						releaseId, currRd.get().getCreatedDate());
					prevReleaseId = null;
				}
			}
			
			if (prevReleaseId != null) {
				// Force recalculate during finalization to handle race condition where initial Acollection had incomplete artifacts
				resolveBomDiff(releaseId, prevReleaseId, org, true);
			}
		} else {
			log.warn("SBOM_CHANGELOG: No previous release found for release {}, cannot calculate diff", releaseId);
		}
		if (nextReleaseId != null) {
			resolveBomDiff(nextReleaseId, releaseId, org, true);
		}
	}


	public void resolveBomDiff(UUID releaseId, UUID prevReleaseId, UUID org){
		resolveBomDiff(releaseId, prevReleaseId, org, false);
	}

	public void resolveBomDiff(UUID releaseId, UUID prevReleaseId, UUID org, boolean forceRecalculate){
		log.debug("SBOM_DIFF_DEBUG: Starting resolveBomDiff for release {} vs prev {}, forceRecalculate={}", releaseId, prevReleaseId, forceRecalculate);
		
		AcollectionData currAcollectionData = getLatestCollectionDataOfRelease(releaseId);
		AcollectionData prevAcollectionData = getLatestCollectionDataOfRelease(prevReleaseId);
		
		log.debug("SBOM_DIFF_DEBUG: Current acollection: uuid={}, artifacts={}", 
			currAcollectionData != null ? currAcollectionData.getUuid() : "null",
			currAcollectionData != null && currAcollectionData.getArtifacts() != null ? currAcollectionData.getArtifacts().size() : "null");
		log.debug("SBOM_DIFF_DEBUG: Previous acollection: uuid={}, artifacts={}", 
			prevAcollectionData != null ? prevAcollectionData.getUuid() : "null",
			prevAcollectionData != null && prevAcollectionData.getArtifacts() != null ? prevAcollectionData.getArtifacts().size() : "null");
		
		if(null != currAcollectionData && null != currAcollectionData.getArtifacts() 
			&& currAcollectionData.getArtifacts().size() > 0 
			&& null != prevAcollectionData 
			&& prevAcollectionData.getArtifacts().size() > 0
			&& ! prevAcollectionData.getArtifacts().equals(currAcollectionData.getArtifacts())
		){
			log.debug("SBOM_DIFF_DEBUG: Conditions met, proceeding with BOM diff calculation");
			
            List<UUID> currArtifacts = getInternalBomIdsFromACollection(currAcollectionData);
			List<UUID> prevArtifacts = getInternalBomIdsFromACollection(prevAcollectionData);
			
			log.debug("SBOM_DIFF_DEBUG: Current internal BOM IDs: {}", currArtifacts);
			log.debug("SBOM_DIFF_DEBUG: Previous internal BOM IDs: {}", prevArtifacts);
			
			if (currArtifacts.isEmpty() && prevArtifacts.isEmpty()) {
				log.warn("SBOM_CHANGELOG: Both current and previous releases have NO internal BOM IDs - cannot calculate changelog");
				return;
			}
			
			log.debug("SBOM_DIFF_DEBUG: Calling rebomService.getArtifactChangelog");
			// Call with prevArtifacts (old/baseline) first, then currArtifacts (new/current)
			ArtifactChangelog artifactChangelog = rebomService.getArtifactChangelog(prevArtifacts, currArtifacts, org);
			log.debug("SBOM_DIFF_DEBUG: Changelog result - added: {}, removed: {}", 
				artifactChangelog != null && artifactChangelog.added() != null ? artifactChangelog.added().size() : "null",
				artifactChangelog != null && artifactChangelog.removed() != null ? artifactChangelog.removed().size() : "null");

			// If forceRecalculate is true (called from finalization), always update even if changelog appears same
			// This handles the race condition where initial Acollection had incomplete artifacts
			if (forceRecalculate || null == currAcollectionData.getArtifactComparison() || null == currAcollectionData.getArtifactComparison().changelog()  || !currAcollectionData.getArtifactComparison().changelog().equals(artifactChangelog)) {
				
				persistArtifactChangelogForCollection(artifactChangelog, prevReleaseId, currAcollectionData.getUuid());

			}else{
				log.debug("SBOM_CHANGELOG: Duplicate trigger for release {}, not persisting changelog", releaseId);
			}
		} else {
			log.warn("SBOM_CHANGELOG: Skipping resolveBomDiff - conditions not met. Current artifacts null/empty: {}, Previous artifacts null/empty: {}, Artifacts equal: {}",
				currAcollectionData.getArtifacts() == null || currAcollectionData.getArtifacts().isEmpty(),
				prevAcollectionData == null || prevAcollectionData.getArtifacts() == null || prevAcollectionData.getArtifacts().isEmpty(),
				prevAcollectionData != null && prevAcollectionData.getArtifacts() != null && currAcollectionData.getArtifacts() != null && prevAcollectionData.getArtifacts().equals(currAcollectionData.getArtifacts()));
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
		List<ArtifactData> artList = artifactService.getArtifactDataList(artIds);
		
		List<UUID> bomIds = artList.stream()
		.filter(art -> null != art.getInternalBom())
		.map(art -> art.getInternalBom().id())
		.distinct()
		.toList();
		
		return bomIds;
	}
	
	/**
	 * Extracts acollections from releases between two releases for SBOM comparison.
	 * Used for changelog generation to compare SBOM changes across a release range.
	 * 
	 * @param uuid1 First release UUID (baseline)
	 * @param uuid2 Second release UUID (comparison target)
	 * @return List of acollections from releases in the range (excluding baseline)
	 * @throws RelizaException if releases cannot be retrieved
	 */
	public List<AcollectionData> getAcollectionsForReleaseRange(
			UUID uuid1, 
			UUID uuid2) throws RelizaException {
		
		List<ReleaseData> releases = sharedReleaseService
			.listAllReleasesBetweenReleases(uuid1, uuid2);
		
		if (releases.isEmpty()) {
			return List.of();
		}
		
		// Include all acollections for sequential aggregation
		// The aggregation logic handles null/empty changelogs (e.g., first release) correctly
		return releases.stream()
			.map(r -> getLatestCollectionDataOfRelease(r.getUuid()))
			.filter(java.util.Objects::nonNull)
			.toList();
	}
	
	/**
	 * Extracts acollections for a component across all branches within a date range.
	 * Used for date-based changelog generation to aggregate SBOM data across branches.
	 * 
	 * @param componentUuid Component UUID
	 * @param dateFrom Start date
	 * @param dateTo End date
	 * @return List of acollections from all releases in the date range across all branches
	 */
	public List<AcollectionData> getAcollectionsForDateRange(
			UUID componentUuid, 
			java.time.ZonedDateTime dateFrom, 
			java.time.ZonedDateTime dateTo) {
		
		List<io.reliza.model.BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, null);
		List<AcollectionData> acollections = new java.util.ArrayList<>();
		
		for (io.reliza.model.BranchData branch : branches) {
			List<ReleaseData> releases = sharedReleaseService
				.listReleaseDataOfBranchBetweenDates(
					branch.getUuid(), dateFrom, dateTo, io.reliza.model.ReleaseData.ReleaseLifecycle.DRAFT);
			
			for (ReleaseData release : releases) {
				AcollectionData ac = getLatestCollectionDataOfRelease(release.getUuid());
				if (ac != null) {
					acollections.add(ac);
				}
			}
		}
		
		return acollections;
	}
}
