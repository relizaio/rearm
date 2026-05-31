/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
		
	/**
	 * Cheap-on-no-change wrapper for the reconcile drain's snapshot safety net.
	 * {@link #resolveReleaseCollection} does a per-artifact rebom
	 * {@code resolveBomMetas} round-trip before its equality short-circuit; on
	 * the drain that work is almost always wasted, since every artifact-set
	 * mutation already resolves the snapshot synchronously via
	 * {@code saveRelease(AcollectionMode.RESOLVE)}. This pre-checks the gathered
	 * artifact UUID set against the latest acollection's and skips the full
	 * resolve when they match — no rebom calls in the common case.
	 *
	 * <p>Caveat: matches on the UUID set only, so an in-place {@code bomVersion}
	 * bump on an already-attached artifact (same UUID) isn't picked up here.
	 * That only affects the acollection's internal version counter (not the
	 * artifact list or changelog), and the artifact-mutation paths that change
	 * a BOM go through add/replace — which change the UUID set — so this is safe
	 * for the drain's redundant catch-all.
	 */
	public AcollectionData resolveReleaseCollectionIfArtifactsChanged (UUID releaseUuid, WhoUpdated wu) {
		// Light read: only the gathered artifact UUID set (record-data fields) is
		// needed for the cheap change-check; no metrics detail / events.
		ReleaseData rd = sharedReleaseService.getReleaseDataLight(releaseUuid).orElse(null);
		if (rd == null) return null;
		AcollectionData latest = getLatestCollectionDataOfRelease(releaseUuid);
		if (latest != null && latest.getArtifacts() != null) {
			Set<UUID> current = artifactGatherService.gatherReleaseArtifacts(rd);
			Set<UUID> snapshot = latest.getArtifacts().stream()
					.map(VersionedArtifact::artifactUuid)
					.collect(java.util.stream.Collectors.toSet());
			if (current.equals(snapshot)) {
				return latest;
			}
		}
		return resolveReleaseCollection(releaseUuid, wu);
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

	/**
	 * Cache a reconcile-computed SBOM-components changelog onto the release's
	 * latest acollection ({@code artifactComparison}), the value the UI and TEA
	 * read. The changelog itself is now produced by the reconcile set-diff in
	 * {@link io.reliza.service.SbomComponentService}; this just persists it.
	 *
	 * <p>No-op if the release has no acollection yet (a mutation-path
	 * {@code resolveReleaseCollection} will create one and a later reconcile
	 * re-caches) or if the cached value is already identical — so unchanged
	 * diffs don't churn the row.
	 */
	public void cacheReleaseChangelog(UUID releaseUuid, UUID comparedReleaseUuid, ArtifactChangelog changelog) {
		AcollectionData latest = getLatestCollectionDataOfRelease(releaseUuid);
		if (latest == null) {
			log.debug("SBOM_CHANGELOG: no acollection yet for release {}, skipping changelog cache", releaseUuid);
			return;
		}
		ArtifactComparison existing = latest.getArtifactComparison();
		if (existing != null && changelog.equals(existing.changelog())
				&& java.util.Objects.equals(comparedReleaseUuid, existing.comparedReleaseUuid())) {
			return;
		}
		persistArtifactChangelogForCollection(changelog, comparedReleaseUuid, latest.getUuid());
	}

	// Persists the SBOM components changelog onto the acollection (consumed by
	// the UI / TEA). The BOM-diff *notification* is no longer fired here — it
	// fires exactly once per release off the reconcile drain
	// (SbomComponentService.postReconcileBomDiff), gated on lifecycle >=
	// ASSEMBLED, so re-computations don't double-notify.
	@Transactional
	private void persistArtifactChangelogForCollection(ArtifactChangelog artifactChangelog, UUID comparedReleaseUuid,
			UUID acollection){
		Acollection ac = getAcollectionWriteLocked(acollection).get();
		AcollectionData acd = AcollectionData.dataFromRecord(ac);
		AcollectionData.ArtifactComparison artifactComparison = new AcollectionData.ArtifactComparison(artifactChangelog, comparedReleaseUuid);
		acd.setArtifactComparison(artifactComparison);
		Map<String,Object> recordData = Utils.dataToRecord(acd);

		ac.setRecordData(recordData);
		repository.save(ac);
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
