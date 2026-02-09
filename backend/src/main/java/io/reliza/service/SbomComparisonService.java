/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.dto.ArtifactWithAttribution;
import io.reliza.dto.ComponentAttribution;
import io.reliza.dto.SbomChangesWithAttribution;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AcollectionData;
import io.reliza.model.ArtifactData;
import io.reliza.model.BranchData;
import io.reliza.model.ReleaseData;
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
	
	@Autowired
	private BranchService branchService;
	
	
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
		// rebom expects (fromIds=old, toIds=new) to return what was added/removed
		return rebomService.getArtifactChangelog(artifacts1, artifacts2, orgUuid);
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
	
	// ==================== Attribution Logic ====================
	
	/**
	 * Internal class to build artifact attribution
	 */
	private static class ArtifactAttribution {
		String purl;
		String name;
		String version;
		List<ComponentAttribution> addedIn = new ArrayList<>();
		List<ComponentAttribution> removedIn = new ArrayList<>();
		boolean isNetAdded;
		boolean isNetRemoved;
		
		ArtifactAttribution(AcollectionData.DiffComponent component) {
			this.purl = component.purl();
			this.version = component.version();
			
			// Extract name from purl
			try {
				com.github.packageurl.PackageURL packageUrl = new com.github.packageurl.PackageURL(component.purl());
				this.name = packageUrl.getName();
			} catch (com.github.packageurl.MalformedPackageURLException e) {
				log.warn("Failed to parse purl for name extraction: {}", component.purl());
				this.name = "unknown";
			}
		}
	}
	
	/**
	 * Aggregate SBOM changes for a single component with accurate per-release attribution.
	 * Processes acollections sequentially to track exactly which release each artifact was added/removed in.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first within each branch)
	 * @param branchDataMap Map of branch UUID to BranchData
	 * @param componentData Component data for attribution
	 * @return SBOM changes with accurate per-release attribution
	 */
	public SbomChangesWithAttribution aggregateComponentChangelogsWithAttribution(
			java.util.LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			io.reliza.model.ComponentData componentData) {
		
		log.debug("Starting component SBOM attribution for component {}", componentData.getName());
		
		// Track which releases each artifact appeared/disappeared in
		Map<String, ArtifactAttribution> artifactMap = new HashMap<>();
		
		// Process each branch
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			BranchData branchData = branchDataMap.get(branchUuid);
			
			if (branchData == null) {
				log.warn("Branch data not found for UUID {}", branchUuid);
				continue;
			}
			
			// Process each release in this branch
			for (ReleaseData release : branchReleases) {
				List<AcollectionData> acollections = acollectionService.getAcollectionDatasOfRelease(release.getUuid());
				
				if (acollections == null || acollections.isEmpty()) {
					continue;
				}
				
				// Process each acollection for this release
				for (AcollectionData acoll : acollections) {
					if (acoll.getArtifactComparison() == null || 
						acoll.getArtifactComparison().changelog() == null) {
						continue;
					}
					
					AcollectionData.ArtifactChangelog changelog = acoll.getArtifactComparison().changelog();
					
					// Create attribution for this release
					ComponentAttribution attr = new ComponentAttribution(
						componentData.getUuid(),
						componentData.getName(),
						release.getUuid(),
						release.getVersion(),
						branchUuid,
						branchData.getName(),
						null
					);
					
					// Track added artifacts
					if (changelog.added() != null) {
						for (AcollectionData.DiffComponent added : changelog.added()) {
							String key = added.purl() + PURL_VERSION_DELIMITER + added.version();
							ArtifactAttribution artifactAttr = artifactMap.computeIfAbsent(
								key, k -> new ArtifactAttribution(added));
							artifactAttr.addedIn.add(attr);
						}
					}
					
					// Track removed artifacts
					if (changelog.removed() != null) {
						for (AcollectionData.DiffComponent removed : changelog.removed()) {
							String key = removed.purl() + PURL_VERSION_DELIMITER + removed.version();
							ArtifactAttribution artifactAttr = artifactMap.computeIfAbsent(
								key, k -> new ArtifactAttribution(removed));
							artifactAttr.removedIn.add(attr);
						}
					}
				}
			}
		}
		
		// Compute net status for each artifact
		for (ArtifactAttribution attr : artifactMap.values()) {
			attr.isNetAdded = !attr.addedIn.isEmpty() && attr.removedIn.isEmpty();
			attr.isNetRemoved = !attr.removedIn.isEmpty() && attr.addedIn.isEmpty();
		}
		
		// Convert to DTO
		List<ArtifactWithAttribution> artifacts = artifactMap.values().stream()
			.map(attr -> new ArtifactWithAttribution(
				attr.purl,
				attr.name,
				attr.version,
				attr.addedIn,
				attr.removedIn,
				attr.isNetAdded,
				attr.isNetRemoved
			))
			.collect(Collectors.toList());
		
		int totalAdded = (int) artifacts.stream().filter(a -> a.isNetAdded()).count();
		int totalRemoved = (int) artifacts.stream().filter(a -> a.isNetRemoved()).count();
		
		log.debug("Component SBOM attribution complete - {} artifacts, {} net added, {} net removed", 
			artifacts.size(), totalAdded, totalRemoved);
		
		return new SbomChangesWithAttribution(artifacts, totalAdded, totalRemoved);
	}
	
	/**
	 * Aggregate SBOM changes across components with release-level attribution.
	 * 
	 * @param componentAcollections Map of component UUID to acollections
	 * @param componentReleases Map of component UUID to releases (for attribution context)
	 * @param componentNames Map of component UUID to component name
	 * @return SBOM changes with release-level attribution
	 */
	public SbomChangesWithAttribution aggregateChangelogsWithAttribution(
			Map<UUID, List<AcollectionData>> componentAcollections,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames) {
		
		log.debug("Starting SBOM attribution aggregation for {} components", componentAcollections.size());
		
		// Build artifact attribution map
		Map<String, ArtifactAttribution> artifactMap = new HashMap<>();
		
		for (Map.Entry<UUID, List<AcollectionData>> entry : componentAcollections.entrySet()) {
			UUID componentUuid = entry.getKey();
			List<AcollectionData> acollections = entry.getValue();
			List<ReleaseData> releases = componentReleases.get(componentUuid);
			
			if (releases == null || releases.isEmpty()) {
				log.debug("No releases found for component {}, skipping", componentUuid);
				continue;
			}
			
			String componentName = componentNames.getOrDefault(componentUuid, "Unknown");
			
			// Aggregate acollections for this component
			AcollectionData.ArtifactChangelog changelog = aggregateChangelogs(acollections);
			
			// Process added artifacts
			if (changelog.added() != null) {
				for (AcollectionData.DiffComponent artifact : changelog.added()) {
					String purl = artifact.purl() + PURL_VERSION_DELIMITER + artifact.version();
					ArtifactAttribution attr = artifactMap.computeIfAbsent(
						purl, 
						k -> new ArtifactAttribution(artifact)
					);
					
					// Find which release added this artifact (use latest release as approximation)
					// In a full implementation, we'd track this more precisely
					ReleaseData latestRelease = releases.get(0);
					ComponentAttribution addedAttr = new ComponentAttribution(
						componentUuid,
						componentName,
						latestRelease.getUuid(),
						latestRelease.getVersion(),
						latestRelease.getBranch(),
						getBranchName(latestRelease.getBranch()),
						null
					);
					attr.addedIn.add(addedAttr);
				}
			}
			
			// Process removed artifacts
			if (changelog.removed() != null) {
				for (AcollectionData.DiffComponent artifact : changelog.removed()) {
					String purl = artifact.purl() + PURL_VERSION_DELIMITER + artifact.version();
					ArtifactAttribution attr = artifactMap.computeIfAbsent(
						purl,
						k -> new ArtifactAttribution(artifact)
					);
					
					// Find which release removed this artifact (use latest release as approximation)
					ReleaseData latestRelease = releases.get(0);
					ComponentAttribution removedAttr = new ComponentAttribution(
						componentUuid,
						componentName,
						latestRelease.getUuid(),
						latestRelease.getVersion(),
						latestRelease.getBranch(),
						getBranchName(latestRelease.getBranch()),
						null
					);
					attr.removedIn.add(removedAttr);
				}
			}
		}
		
		// Compute org-wide flags
		for (ArtifactAttribution attr : artifactMap.values()) {
			attr.isNetAdded = !attr.addedIn.isEmpty() && attr.removedIn.isEmpty();
			attr.isNetRemoved = !attr.removedIn.isEmpty() && attr.addedIn.isEmpty();
		}
		
		// Convert to DTO
		List<ArtifactWithAttribution> artifacts = artifactMap.values().stream()
			.map(attr -> new ArtifactWithAttribution(
				attr.purl,
				attr.name,
				attr.version,
				attr.addedIn,
				attr.removedIn,
				attr.isNetAdded,
				attr.isNetRemoved
			))
			.collect(Collectors.toList());
		
		int totalAdded = (int) artifacts.stream().filter(a -> a.isNetAdded()).count();
		int totalRemoved = (int) artifacts.stream().filter(a -> a.isNetRemoved()).count();
		
		log.debug("SBOM attribution complete - {} artifacts, {} net added, {} net removed", 
			artifacts.size(), totalAdded, totalRemoved);
		
		return new SbomChangesWithAttribution(artifacts, totalAdded, totalRemoved);
	}
	
	/**
	 * Get branch name from UUID
	 */
	private String getBranchName(UUID branchUuid) {
		if (branchUuid == null) return null;
		return branchService.getBranchData(branchUuid)
			.map(BranchData::getName)
			.orElse(null);
	}
	
}
