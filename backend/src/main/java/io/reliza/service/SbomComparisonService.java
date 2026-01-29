/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

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
	private AcollectionService acollectionService;
	
	@Autowired
	private RebomService rebomService;
	
	@Autowired
	private ArtifactService artifactService;
	
	
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
			log.debug("SBOM_AGG: No acollections provided");
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		log.debug("Starting SBOM aggregation with {} acollections", acollections.size());
		
		Map<String, AcollectionData.DiffComponent> netAdded = new HashMap<>();
		Map<String, AcollectionData.DiffComponent> netRemoved = new HashMap<>();
		
		int processedCount = 0;
		int skippedCount = 0;
		
		for (AcollectionData acollection : acollections) {
			if (acollection == null) {
				log.debug("SBOM_AGG: Skipping null acollection");
				skippedCount++;
				continue;
			}
			
			log.debug("SBOM_AGG: Processing acollection UUID: {}, hasArtifactComparison: {}, hasChangelog: {}", 
				acollection.getUuid(),
				acollection.getArtifactComparison() != null,
				acollection.getArtifactComparison() != null && acollection.getArtifactComparison().changelog() != null);
			
			if (acollection.getArtifactComparison() == null || 
				acollection.getArtifactComparison().changelog() == null) {
				log.debug("SBOM_AGG: Skipping acollection {} - no changelog", acollection.getUuid());
				skippedCount++;
				continue;
			}
			
			AcollectionData.ArtifactChangelog changelog = acollection.getArtifactComparison().changelog();
			int addedSize = changelog.added() != null ? changelog.added().size() : 0;
			int removedSize = changelog.removed() != null ? changelog.removed().size() : 0;
			
			log.debug("Acollection {} - Added: {}, Removed: {}", 
				acollection.getUuid(), addedSize, removedSize);
			
			if (addedSize > 0 && changelog.added() != null) {
				log.debug("Added components: {}", 
					changelog.added().stream()
						.map(c -> c.purl() + "@" + c.version())
						.collect(java.util.stream.Collectors.joining(", ")));
			}
			
			if (removedSize > 0 && changelog.removed() != null) {
				log.debug("Removed components: {}", 
					changelog.removed().stream()
						.map(c -> c.purl() + "@" + c.version())
						.collect(java.util.stream.Collectors.joining(", ")));
			}
			
			processedCount++;
			
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
		
		log.info("SBOM aggregation complete - Net added: {}, Net removed: {} (processed: {}, skipped: {})", 
			netAdded.size(), netRemoved.size(), processedCount, skippedCount);
		
		return new AcollectionData.ArtifactChangelog(
			new HashSet<>(netAdded.values()),
			new HashSet<>(netRemoved.values())
		);
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
	
}
