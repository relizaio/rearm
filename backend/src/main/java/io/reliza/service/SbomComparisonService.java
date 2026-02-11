/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import io.reliza.model.AcollectionData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.DiffComponent;
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
	
	private static String safeVersion(String version) {
		return version != null ? version : "";
	}
	
	/**
	 * Aggregates SBOM changes from a list of acollections.
	 * Pure function - no side effects, no data extraction.
	 * This is the core aggregation logic that can be used with any acollection source.
	 * 
	 * @param acollections List of acollections to aggregate
	 * @return ArtifactChangelog with net changes
	 */
	public ArtifactChangelog aggregateChangelogs(List<AcollectionData> acollections) {
		
		if (acollections == null || acollections.isEmpty()) {
			log.debug("SBOM_AGG: No acollections provided");
			return new AcollectionData.ArtifactChangelog(Set.of(), Set.of());
		}
		
		log.debug("Starting SBOM aggregation with {} acollections", acollections.size());
		
		Map<String, DiffComponent> netAdded = new HashMap<>();
		Map<String, DiffComponent> netRemoved = new HashMap<>();
		
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
			
			ArtifactChangelog changelog = acollection.getArtifactComparison().changelog();
			int addedSize = changelog.added() != null ? changelog.added().size() : 0;
			int removedSize = changelog.removed() != null ? changelog.removed().size() : 0;
			
			log.debug("Acollection {} - Added: {}, Removed: {}", 
				acollection.getUuid(), addedSize, removedSize);
			
			processedCount++;
			
			// Process added components
			if (changelog.added() != null) {
				for (DiffComponent comp : changelog.added()) {
					String key = comp.purl() + PURL_VERSION_DELIMITER + safeVersion(comp.version());
					if (netRemoved.containsKey(key)) {
						netRemoved.remove(key);
					} else {
						netAdded.put(key, comp);
					}
				}
			}
			
			// Process removed components
			if (changelog.removed() != null) {
				for (DiffComponent comp : changelog.removed()) {
					String key = comp.purl() + PURL_VERSION_DELIMITER + safeVersion(comp.version());
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
		
		return new ArtifactChangelog(
			new HashSet<>(netAdded.values()),
			new HashSet<>(netRemoved.values())
		);
	}
	
	
	/**
	 * Processes a set of artifact diffs (added or removed) into the attribution map.
	 * @param diffs The diff components to process
	 * @param artifactMap The attribution map to populate
	 * @param componentAttr The attribution to add to each artifact
	 * @param isAdded true to add to addedIn, false to add to removedIn
	 */
	private void processArtifactDiffs(
			Set<DiffComponent> diffs,
			Map<String, ArtifactAttribution> artifactMap,
			ComponentAttribution componentAttr,
			boolean isAdded) {
		if (diffs == null) return;
		for (DiffComponent artifact : diffs) {
			String key = artifact.purl() + PURL_VERSION_DELIMITER + safeVersion(artifact.version());
			ArtifactAttribution attr = artifactMap.computeIfAbsent(key, k -> new ArtifactAttribution(artifact));
			if (isAdded) {
				attr.addedIn.add(componentAttr);
			} else {
				attr.removedIn.add(componentAttr);
			}
		}
	}
	
	/**
	 * Computes net flags, converts ArtifactAttribution map to DTOs, and builds the final result.
	 * Shared by both component-level and org-level attribution methods.
	 */
	private SbomChangesWithAttribution buildArtifactResult(Map<String, ArtifactAttribution> artifactMap) {
		// Compute net status for each artifact
		for (ArtifactAttribution attr : artifactMap.values()) {
			attr.isNetAdded = !attr.addedIn.isEmpty() && attr.removedIn.isEmpty();
			attr.isNetRemoved = !attr.removedIn.isEmpty() && attr.addedIn.isEmpty();
		}
		
		List<ArtifactWithAttribution> artifacts = artifactMap.values().stream()
			.map(attr -> new ArtifactWithAttribution(
				attr.purl, attr.name, attr.version,
				attr.addedIn, attr.removedIn,
				attr.isNetAdded, attr.isNetRemoved
			))
			.collect(Collectors.toList());
		
		int totalAdded = (int) artifacts.stream().filter(a -> a.isNetAdded()).count();
		int totalRemoved = (int) artifacts.stream().filter(a -> a.isNetRemoved()).count();
		
		return new SbomChangesWithAttribution(artifacts, totalAdded, totalRemoved);
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
		
		ArtifactAttribution(DiffComponent component) {
			this.purl = component.purl();
			this.version = component.version() != null ? component.version() : "";
			
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
	 * @param componentData Component data for attribution
	 * @return SBOM changes with accurate per-release attribution
	 */
	public SbomChangesWithAttribution aggregateComponentChangelogsWithAttribution(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, String> branchNameMap,
			ComponentData componentData,
			Map<UUID, List<AcollectionData>> releaseAcollectionsMap) {
		
		log.debug("Starting component SBOM attribution for component {}", componentData.getName());
		
		// Track which releases each artifact appeared/disappeared in
		Map<String, ArtifactAttribution> artifactMap = new HashMap<>();
		
		// Process each branch
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			
			// Process each release in this branch
			for (ReleaseData release : branchReleases) {
				List<AcollectionData> acollections = releaseAcollectionsMap.getOrDefault(release.getUuid(), List.of());
				
				if (acollections == null || acollections.isEmpty()) {
					continue;
				}
				
				// Process each acollection for this release
				for (AcollectionData acoll : acollections) {
					if (acoll.getArtifactComparison() == null || 
						acoll.getArtifactComparison().changelog() == null) {
						continue;
					}
					
					ArtifactChangelog changelog = acoll.getArtifactComparison().changelog();
					
					// Create attribution for this release
					ComponentAttribution attr = new ComponentAttribution(
						componentData.getUuid(), componentData.getName(),
						release.getUuid(), release.getVersion(),
						branchUuid, branchNameMap.get(branchUuid)
					);
					
					processArtifactDiffs(changelog.added(), artifactMap, attr, true);
					processArtifactDiffs(changelog.removed(), artifactMap, attr, false);
				}
			}
		}
		
		log.debug("Component SBOM attribution complete for component {}", componentData.getName());
		
		return buildArtifactResult(artifactMap);
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
			Map<UUID, String> branchNameMap,
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
			ArtifactChangelog changelog = aggregateChangelogs(acollections);
			
			// Use latest release as approximation for attribution
			ReleaseData latestRelease = releases.get(0);
			ComponentAttribution componentAttr = new ComponentAttribution(
				componentUuid, componentName,
				latestRelease.getUuid(), latestRelease.getVersion(),
				latestRelease.getBranch(), branchNameMap.get(latestRelease.getBranch())
			);
			
			processArtifactDiffs(changelog.added(), artifactMap, componentAttr, true);
			processArtifactDiffs(changelog.removed(), artifactMap, componentAttr, false);
		}
		
		return buildArtifactResult(artifactMap);
	}
	
}
