/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AcollectionData;
import io.reliza.model.ArtifactData;
import io.reliza.model.BranchData;
import io.reliza.model.ReleaseData;
import io.reliza.common.CommonVariables.StatusEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for comparing SBOMs (Software Bill of Materials)
 * between releases or date ranges.
 * 
 * This service follows the Single Responsibility Principle by focusing solely on
 * SBOM comparison and aggregation logic.
 */
@Slf4j
@Service
public class SbomComparisonService {
	
	private static final String PURL_VERSION_DELIMITER = "@";
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private AcollectionService acollectionService;
	
	@Autowired
	private RebomService rebomService;
	
	@Autowired
	private ArtifactService artifactService;
	
	@Autowired
	private BranchService branchService;
	
	/**
	 * Aggregates SBOM changes across multiple releases by collecting
	 * ArtifactChangelog data from each release's Acollection
	 * 
	 * @param release1Uuid Starting release UUID
	 * @param release2Uuid Ending release UUID
	 * @param orgUuid Organization UUID
	 * @return Aggregated ArtifactChangelog with net changes
	 */
	public AcollectionData.ArtifactChangelog aggregateSbomChanges(
			UUID release1Uuid, 
			UUID release2Uuid, 
			UUID orgUuid) throws RelizaException {
		
		// Get the latest Acollection for both releases
		AcollectionData acollection2 = acollectionService.getLatestCollectionDataOfRelease(release2Uuid);
		
		if (acollection2 == null) {
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		// OPTIMIZATION 1: Direct match - persisted changelog compares exactly the releases we need
		UUID persistedComparedUuid = acollection2.getArtifactComparison() != null ? 
				acollection2.getArtifactComparison().comparedReleaseUuid() : null;
		
		if (acollection2.getArtifactComparison() != null && 
			acollection2.getArtifactComparison().changelog() != null &&
			release1Uuid.equals(persistedComparedUuid)) {
			
			log.info("✓ Using persisted artifactComparison.changelog for consecutive releases {} -> {}", 
					release1Uuid, release2Uuid);
			return acollection2.getArtifactComparison().changelog();
		}
		
		// OPTIMIZATION 2: Aggregate per-release changelogs for non-consecutive releases
		// This is MUCH faster than calling rebomService because we use already-persisted data
		log.info("Aggregating per-release SBOM changelogs for releases {} -> {} (persisted compares against {})", 
				release1Uuid, release2Uuid, persistedComparedUuid);
		
		try {
			// Get all releases between release1 and release2 (inclusive of release2, exclusive of release1)
			List<ReleaseData> releases = sharedReleaseService.listAllReleasesBetweenReleases(release1Uuid, release2Uuid);
			
			if (releases.isEmpty()) {
				log.warn("No releases found between {} and {}", release1Uuid, release2Uuid);
				return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
			}
			
			// Remove the last element (release1) as listAllReleasesBetweenReleases includes it
			if (releases.size() > 1) {
				releases.remove(releases.size() - 1);
			}
			
			// Aggregate changelogs from all releases
			return aggregatePerReleaseChangelogs(releases);
			
		} catch (Exception e) {
			log.error("Failed to aggregate per-release changelogs, falling back to rebomService: {}", e.getMessage());
			// FALLBACK: Use rebomService only if aggregation fails
			return fallbackToRebomService(release1Uuid, release2Uuid, orgUuid);
		}
	}
	
	/**
	 * Aggregates SBOM changes from a list of acollections.
	 * Pure function - no side effects, no data extraction.
	 * This is the core aggregation logic that can be used with any acollection source.
	 * 
	 * @param acollections List of acollections to aggregate
	 * @return ArtifactChangelog with net changes
	 */
	public AcollectionData.ArtifactChangelog aggregateChangelogs(List<AcollectionData> acollections) {
		
		if (acollections == null || acollections.isEmpty()) {
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		Map<String, AcollectionData.DiffComponent> netAdded = new HashMap<>();
		Map<String, AcollectionData.DiffComponent> netRemoved = new HashMap<>();
		
		for (AcollectionData acollection : acollections) {
			if (acollection == null || acollection.getArtifactComparison() == null || 
				acollection.getArtifactComparison().changelog() == null) {
				continue;
			}
			
			AcollectionData.ArtifactChangelog changelog = acollection.getArtifactComparison().changelog();
			
			// Process added components
			if (changelog.added() != null) {
				for (AcollectionData.DiffComponent comp : changelog.added()) {
					String key = comp.purl() + PURL_VERSION_DELIMITER + comp.version();
					if (netRemoved.containsKey(key)) {
						netRemoved.remove(key);
					} else {
						netAdded.put(key, comp);
					}
				}
			}
			
			// Process removed components
			if (changelog.removed() != null) {
				for (AcollectionData.DiffComponent comp : changelog.removed()) {
					String key = comp.purl() + PURL_VERSION_DELIMITER + comp.version();
					if (netAdded.containsKey(key)) {
						netAdded.remove(key);
					} else {
						netRemoved.put(key, comp);
					}
				}
			}
		}
		
		return new AcollectionData.ArtifactChangelog(
			new HashSet<>(netAdded.values()),
			new HashSet<>(netRemoved.values())
		);
	}
	
	/**
	 * Aggregates SBOM changes from per-release artifactComparison changelogs.
	 * This is much faster than calling rebomService because it uses already-persisted data.
	 * Now delegates to the public aggregateChangelogs method.
	 */
	private AcollectionData.ArtifactChangelog aggregatePerReleaseChangelogs(List<ReleaseData> releases) {
		// Extract acollections from releases
		List<AcollectionData> acollections = releases.stream()
			.map(release -> acollectionService.getLatestCollectionDataOfRelease(release.getUuid()))
			.filter(ac -> ac != null)
			.toList();
		
		// Delegate to pure aggregation method
		return aggregateChangelogs(acollections);
	}
	
	/**
	 * Fallback to rebomService when aggregation fails.
	 * This is the expensive path that makes GraphQL queries.
	 */
	private AcollectionData.ArtifactChangelog fallbackToRebomService(
			UUID release1Uuid, UUID release2Uuid, UUID orgUuid) throws RelizaException {
		
		log.info("Using rebomService fallback for releases {} -> {}", release1Uuid, release2Uuid);
		
		AcollectionData acollection1 = acollectionService.getLatestCollectionDataOfRelease(release1Uuid);
		AcollectionData acollection2 = acollectionService.getLatestCollectionDataOfRelease(release2Uuid);
		
		if (acollection1 == null || acollection2 == null) {
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		// Early bailout: check if artifacts exist
		if (acollection1.getArtifacts() == null || acollection1.getArtifacts().isEmpty() ||
			acollection2.getArtifacts() == null || acollection2.getArtifacts().isEmpty()) {
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		// Extract BOM artifact UUIDs from both collections
		List<UUID> artifacts1 = extractBomIdsFromAcollection(acollection1);
		List<UUID> artifacts2 = extractBomIdsFromAcollection(acollection2);
		
		if (artifacts1.isEmpty() || artifacts2.isEmpty()) {
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		// Use rebomService to compare the SBOMs directly
		return rebomService.getArtifactChangelog(artifacts2, artifacts1, orgUuid);
	}
	
	/**
	 * Extracts internal BOM IDs from an Acollection
	 */
	private List<UUID> extractBomIdsFromAcollection(AcollectionData collection) {
		List<UUID> artIds = collection.getArtifacts().stream()
			.map(AcollectionData.VersionedArtifact::artifactUuid)
			.toList();
		
		List<ArtifactData> artList = artifactService.getArtifactDataList(artIds);
		
		return artList.stream()
			.filter(art -> art.getInternalBom() != null)
			.map(art -> art.getInternalBom().id())
			.distinct()
			.toList();
	}
	
	/**
	 * Compares SBOM changes for a component between two dates by aggregating
	 * across all active branches.
	 * 
	 * @param componentUuid Component UUID
	 * @param dateFrom Start date
	 * @param dateTo End date
	 * @return ArtifactChangelog with changes between the two dates
	 */
	public AcollectionData.ArtifactChangelog compareSbomChangesByDate(
			UUID componentUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo) {
		
		try {
			// Get all active branches
			List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, StatusEnum.ACTIVE);
			
			// Collect acollections from all releases within the date range
			List<AcollectionData> acollections = new ArrayList<>();
			
			for (BranchData branch : branches) {
				List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranch(branch.getUuid(), true);
				
				for (ReleaseData release : branchReleases) {
					if (release.getCreatedDate() != null) {
						ZonedDateTime releaseDate = release.getCreatedDate();
						if (!releaseDate.isBefore(dateFrom) && releaseDate.isBefore(dateTo)) {
							AcollectionData acollection = acollectionService.getLatestCollectionDataOfRelease(release.getUuid());
							if (acollection != null) {
								acollections.add(acollection);
							}
						}
					}
				}
			}
			
			// Delegate to pure aggregation method
			return aggregateChangelogs(acollections);
			
		} catch (Exception e) {
			log.error("Failed to compare SBOM changes by date for component {}: {}", 
					componentUuid, e.getMessage(), e);
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
	}
}
