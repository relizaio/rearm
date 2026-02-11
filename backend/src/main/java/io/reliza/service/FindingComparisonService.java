/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.reliza.dto.ComponentAttribution;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.OrgLevelContext;
import io.reliza.dto.ViolationWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.dto.WeaknessWithAttribution;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.dto.FindingChangesRecord;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for comparing findings (vulnerabilities, violations, weaknesses)
 * between releases or date ranges.
 * 
 * This service follows the Single Responsibility Principle by focusing solely on
 * finding comparison logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingComparisonService {
	
	private final BranchService branchService;
	private final SharedReleaseService sharedReleaseService;
	
	private static final String FINDING_KEY_DELIMITER = "|";
	
	/**
	 * Returns branch name from cache, or fetches and caches it.
	 */
	private String cachedBranchName(UUID branchUuid, Map<UUID, String> cache) {
		return cache.computeIfAbsent(branchUuid, branchService::getBranchName);
	}
	
	// Reusable null-safe key extractors for each finding type
	private static final Function<VulnerabilityDto, String> VULN_KEY =
		vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + (vuln.purl() != null ? vuln.purl() : "");
	private static final Function<ViolationDto, String> VIOLATION_KEY =
		violation -> violation.type() + FINDING_KEY_DELIMITER + (violation.purl() != null ? violation.purl() : "");
	private static final Function<WeaknessDto, String> WEAKNESS_KEY =
		weakness -> (weakness.cweId() != null ? weakness.cweId() : weakness.ruleId()) + FINDING_KEY_DELIMITER + (weakness.location() != null ? weakness.location() : "");
	
	/**
	 * Shared comparator that reads metrics directly from ReleaseData objects.
	 * Used by both component-level and org-level finding comparison methods.
	 */
	private final BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> DIRECT_METRICS_COMPARATOR =
		(older, newer) -> {
			ReleaseMetricsDto olderMetrics = older.getMetrics();
			ReleaseMetricsDto newerMetrics = newer.getMetrics();
			return (olderMetrics != null && newerMetrics != null) ? compareMetrics(olderMetrics, newerMetrics) : null;
		};
	
	/**
	 * Generic comparison result holder
	 */
	private record ComparisonResult<T>(
		List<T> appeared,
		List<T> resolved
	) {}
	
	/**
	 * Generic comparison method for finding lists.
	 * Eliminates code duplication across vulnerability, violation, and weakness comparisons.
	 */
	private <T> ComparisonResult<T> compareFindings(
			List<T> list1,
			List<T> list2,
			Function<T, String> keyExtractor) {
		
		List<T> appeared = new ArrayList<>();
		List<T> resolved = new ArrayList<>();
		
		// Build maps by key
		Map<String, T> map1 = new HashMap<>();
		Map<String, T> map2 = new HashMap<>();
		
		if (list1 != null) {
			for (T item : list1) {
				map1.put(keyExtractor.apply(item), item);
			}
		}
		
		if (list2 != null) {
			for (T item : list2) {
				map2.put(keyExtractor.apply(item), item);
			}
		}
		
		// Find appeared (in list2 but not in list1)
		for (Map.Entry<String, T> entry : map2.entrySet()) {
			if (!map1.containsKey(entry.getKey())) {
				appeared.add(entry.getValue());
			}
		}
		
		// Find resolved (in list1 but not in list2)
		for (Map.Entry<String, T> entry : map1.entrySet()) {
			if (!map2.containsKey(entry.getKey())) {
				resolved.add(entry.getValue());
			}
		}
		
		return new ComparisonResult<>(appeared, resolved);
	}
	
	

	private static class FindingAttribution<T> {
		T finding;
		List<ComponentAttribution> appearedIn = new ArrayList<>();
		List<ComponentAttribution> resolvedIn = new ArrayList<>();
		List<ComponentAttribution> presentIn = new ArrayList<>();
	}
	
	/**
	 * Holder for the three parallel attribution maps, reducing parameter noise.
	 */
	private static class AttributionMaps {
		final Map<String, FindingAttribution<ReleaseMetricsDto.VulnerabilityDto>> vulns = new HashMap<>();
		final Map<String, FindingAttribution<ReleaseMetricsDto.ViolationDto>> violations = new HashMap<>();
		final Map<String, FindingAttribution<ReleaseMetricsDto.WeaknessDto>> weaknesses = new HashMap<>();
	}
	
	/**
	 * Compares findings between two metrics objects.
	 * Pure function - no side effects, no data extraction.
	 * This is the core comparison logic that can be used with any metrics source.
	 * 
	 * @param metrics1 Starting metrics
	 * @param metrics2 Ending metrics
	 * @return FindingChangesRecord with appeared, resolved, and severity changed findings
	 */
	public FindingChangesRecord compareMetrics(
			ReleaseMetricsDto metrics1,
			ReleaseMetricsDto metrics2) {
		
		if (metrics1 == null || metrics2 == null) {
			log.warn("FINDINGS_COMPARISON: One or both metrics are null - metrics1={}, metrics2={}", 
				metrics1 != null ? "present" : "null", 
				metrics2 != null ? "present" : "null");
			return FindingChangesRecord.EMPTY;
		}
		
		// Compare vulnerabilities
		var vulnChanges = compareFindings(
			metrics1.getVulnerabilityDetails(),
			metrics2.getVulnerabilityDetails(),
			VULN_KEY
		);
		
		// Compare violations
		var violationChanges = compareFindings(
			metrics1.getViolationDetails(),
			metrics2.getViolationDetails(),
			VIOLATION_KEY
		);
		
		// Compare weaknesses
		var weaknessChanges = compareFindings(
			metrics1.getWeaknessDetails(),
			metrics2.getWeaknessDetails(),
			WEAKNESS_KEY
		);
		
		return new FindingChangesRecord(
			vulnChanges.appeared,
			vulnChanges.resolved,
			violationChanges.appeared,
			violationChanges.resolved,
			weaknessChanges.appeared,
			weaknessChanges.resolved
		);
	}


	
	/**
	 * Generic helper to track findings (appeared or resolved) for any finding type.
	 * The listSelector picks which attribution list to add to (e.g. appearedIn or resolvedIn).
	 */
	private <T> void trackFindings(
			List<T> findings,
			Map<String, FindingAttribution<T>> findingMap,
			ComponentAttribution attr,
			Set<String> handledFindings,
			Function<T, String> keyExtractor,
			Function<FindingAttribution<T>, List<ComponentAttribution>> listSelector) {
		
		if (findings == null) return;
		
		for (T finding : findings) {
			String key = keyExtractor.apply(finding);
			FindingAttribution<T> fa = findingMap.computeIfAbsent(key, k -> new FindingAttribution<>());
			if (fa.finding == null) fa.finding = finding;
			listSelector.apply(fa).add(attr);
			if (handledFindings != null) {
				handledFindings.add(key);
			}
		}
	}
	
	/**
	 * Generic helper to track present findings for any finding type
	 */
	private <T> void trackPresentFindings(
			List<T> presentFindings,
			Map<String, FindingAttribution<T>> findingMap,
			ComponentAttribution releaseAttr,
			Function<T, String> keyExtractor) {
		
		if (presentFindings == null) return;
		
		for (T finding : presentFindings) {
			String key = keyExtractor.apply(finding);
			FindingAttribution<T> fa = findingMap.get(key);
			if (fa != null) {
				boolean alreadyPresent = fa.presentIn.stream()
					.anyMatch(a -> a.releaseUuid().equals(releaseAttr.releaseUuid()));
				if (!alreadyPresent) {
					fa.presentIn.add(releaseAttr);
				}
			}
		}
	}
	
	/**
	 * Tracks appeared and resolved findings for all three finding types from a FindingChangesRecord.
	 * Eliminates the repeated 6-call pattern used in both component-level and org-level methods.
	 */
	private void trackAllFindings(
			FindingChangesRecord changes,
			AttributionMaps maps,
			ComponentAttribution attr,
			Set<String> handledFindings) {
		
		trackFindings(changes.appearedVulnerabilities(), maps.vulns, attr, handledFindings, VULN_KEY, fa -> fa.appearedIn);
		trackFindings(changes.resolvedVulnerabilities(), maps.vulns, attr, handledFindings, VULN_KEY, fa -> fa.resolvedIn);
		trackFindings(changes.appearedViolations(), maps.violations, attr, handledFindings, VIOLATION_KEY, fa -> fa.appearedIn);
		trackFindings(changes.resolvedViolations(), maps.violations, attr, handledFindings, VIOLATION_KEY, fa -> fa.resolvedIn);
		trackFindings(changes.appearedWeaknesses(), maps.weaknesses, attr, handledFindings, WEAKNESS_KEY, fa -> fa.appearedIn);
		trackFindings(changes.resolvedWeaknesses(), maps.weaknesses, attr, handledFindings, WEAKNESS_KEY, fa -> fa.resolvedIn);
	}
	
	/**
	 * Tracks present findings for all three finding types from a metrics object.
	 */
	private void trackAllPresentFindings(
			ReleaseMetricsDto metrics,
			AttributionMaps maps,
			ComponentAttribution releaseAttr) {
		
		trackPresentFindings(metrics.getVulnerabilityDetails(), maps.vulns, releaseAttr, VULN_KEY);
		trackPresentFindings(metrics.getViolationDetails(), maps.violations, releaseAttr, VIOLATION_KEY);
		trackPresentFindings(metrics.getWeaknessDetails(), maps.weaknesses, releaseAttr, WEAKNESS_KEY);
	}
	
	/**
	 * Tracks inherited findings for all three finding types from a metrics object.
	 */
	private void trackAllInheritedFindings(
			ReleaseMetricsDto metrics,
			AttributionMaps maps,
			Set<String> handledFindings) {
		
		trackInheritedFindings(metrics.getVulnerabilityDetails(), maps.vulns, handledFindings, VULN_KEY);
		trackInheritedFindings(metrics.getViolationDetails(), maps.violations, handledFindings, VIOLATION_KEY);
		trackInheritedFindings(metrics.getWeaknessDetails(), maps.weaknesses, handledFindings, WEAKNESS_KEY);
	}
	
	/**
	 * Generic helper to track inherited findings for any finding type
	 */
	private <T> void trackInheritedFindings(
			List<T> findings,
			Map<String, FindingAttribution<T>> findingMap,
			Set<String> handledFindings,
			Function<T, String> keyExtractor) {
		
		if (findings == null) return;
		
		for (T finding : findings) {
			String key = keyExtractor.apply(finding);
			if (!findingMap.containsKey(key) && !handledFindings.contains(key)) {
				FindingAttribution<T> fa = new FindingAttribution<>();
				fa.finding = finding;
				findingMap.put(key, fa);
			}
		}
	}
	
	/**
	 * Compare finding metrics across branches with accurate per-release attribution.
	 * Performs sequential comparisons within each branch to track exactly which release
	 * each vulnerability, violation, or weakness appeared/resolved in.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first within each branch)
	 * @param componentData Component data for attribution
	 * @return Finding changes with accurate per-release attribution
	 */
	public FindingChangesWithAttribution compareMetricsWithAttributionAcrossBranches(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			ComponentData componentData,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache
		) {
		
		log.debug("Starting finding attribution comparison for component {}", componentData.getName());
		
		AttributionMaps maps = new AttributionMaps();
		
		// Track findings handled by fork point comparison to avoid treating them as inherited
		Set<String> forkPointHandledFindings = new HashSet<>();
		
		// PHASES 0-1: Fork point comparisons + pairwise consecutive comparisons
		processForkPointAndPairwise(releasesByBranch,
			componentData.getUuid(), componentData.getName(),
			DIRECT_METRICS_COMPARATOR, maps, forkPointHandledFindings, branchNameCache, forkPointCache, componentData);
		
		// PHASE 2: Track truly inherited findings from first (oldest) release in each branch
		// These are findings that existed before the fork point AND were not handled by fork point comparison
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			List<ReleaseData> branchReleases = entry.getValue();
			
			if (branchReleases.isEmpty()) continue;
			
			// Get the first (oldest) release in this branch
			ReleaseData firstRelease = branchReleases.get(branchReleases.size() - 1);
			ReleaseMetricsDto firstMetrics = firstRelease.getMetrics();
			
			if (firstMetrics == null) continue;
			
			// Track truly inherited findings using generic helper
			trackAllInheritedFindings(firstMetrics, maps, forkPointHandledFindings);
		}
		
		// Track current state by querying ALL releases in each branch
		// This populates presentIn with findings that exist in each release
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			
			if (branchReleases.isEmpty()) continue;
			
			// Iterate through ALL releases in this branch
			for (ReleaseData release : branchReleases) {
				ReleaseMetricsDto releaseMetrics = release.getMetrics();
				
				if (releaseMetrics == null) continue;
				
				ComponentAttribution releaseAttr = new ComponentAttribution(
					componentData.getUuid(),
					componentData.getName(),
					release.getUuid(),
					release.getVersion(),
					branchUuid,
					cachedBranchName(branchUuid, branchNameCache)
				);
				
				// Track present findings using generic helper
				trackAllPresentFindings(releaseMetrics, maps, releaseAttr);
			}
		}
		
		// Determine which releases are the latest in each branch
		Map<UUID, UUID> latestReleasePerBranch = new HashMap<>();
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			if (!branchReleases.isEmpty()) {
				// Index 0 = newest (latest)
				latestReleasePerBranch.put(branchUuid, branchReleases.get(0).getUuid());
			}
		}
		
		// Build attributed findings using component-level flag computation
		return buildAttributedFindings(maps,
			(key, fa) -> computeComponentFindingFlags(fa, latestReleasePerBranch));
	}
	
	/**
	 * Shared core logic: processes fork point comparisons and pairwise consecutive comparisons
	 * for a single component's branches. Used by both component-level and org-level methods.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first)
	 * @param componentUuid Component UUID for attribution
	 * @param componentName Component name for attribution
	 * @param comparator Function that compares two releases and returns finding changes, or null to skip
	 * @param maps Attribution maps holder to populate
	 * @param forkPointHandledFindings Set to track findings handled by fork point (populated by this method)
	 */
	private void processForkPointAndPairwise(
			Map<UUID, List<ReleaseData>> releasesByBranch,
			UUID componentUuid,
			String componentName,
			BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> comparator,
			AttributionMaps maps,
			Set<String> forkPointHandledFindings,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache) {
		processForkPointAndPairwise(releasesByBranch, componentUuid, componentName,
			comparator, maps, forkPointHandledFindings, branchNameCache, forkPointCache, null);
	}

	private void processForkPointAndPairwise(
			Map<UUID, List<ReleaseData>> releasesByBranch,
			UUID componentUuid,
			String componentName,
			BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> comparator,
			AttributionMaps maps,
			Set<String> forkPointHandledFindings,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache,
			ComponentData componentData) {
		
		// baseBranchCache: caches base branch UUID per component to avoid repeated findBranchByName calls
		Map<UUID, Optional<UUID>> baseBranchCache = new HashMap<>();
		
		// PHASE 0: Fork point comparisons for each branch's oldest release
		for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
			UUID branchUuid = branchEntry.getKey();
			List<ReleaseData> branchReleases = branchEntry.getValue();
			if (branchReleases.isEmpty()) continue;
			
			ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
			
			// Use fork point cache to avoid redundant DB lookups
			String cacheKey = branchUuid + ":" + oldestRelease.getUuid();
			ReleaseData forkPointRelease = null;
			
			if (forkPointCache.containsKey(cacheKey)) {
				forkPointRelease = forkPointCache.get(cacheKey);
			} else {
				UUID forkPointReleaseId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
					branchUuid, oldestRelease.getUuid(), oldestRelease, componentData, baseBranchCache);
				
				if (forkPointReleaseId != null) {
					var forkPointReleaseOpt = sharedReleaseService.getReleaseData(forkPointReleaseId);
					forkPointRelease = forkPointReleaseOpt.orElse(null);
				}
				forkPointCache.put(cacheKey, forkPointRelease);
			}
			
			if (forkPointRelease == null) {
				log.debug("No fork point found for branch {} release {}", 
					cachedBranchName(branchUuid, branchNameCache), oldestRelease.getVersion());
				continue;
			}
			
			// Only compare if fork point is on a DIFFERENT branch
			if (forkPointRelease.getBranch().equals(branchUuid)) {
				log.debug("Fork point is on same branch for {}/{}, skipping", 
					componentName, cachedBranchName(branchUuid, branchNameCache));
				continue;
			}
			
			FindingChangesRecord forkPointChanges = comparator.apply(forkPointRelease, oldestRelease);
			if (forkPointChanges == null) continue;
			
			log.debug("Fork point comparison for {}: {} appeared vulns, {} resolved vulns",
				componentName,
				forkPointChanges.appearedVulnerabilities() != null ? forkPointChanges.appearedVulnerabilities().size() : 0,
				forkPointChanges.resolvedVulnerabilities() != null ? forkPointChanges.resolvedVulnerabilities().size() : 0);
			
			ComponentAttribution attr = new ComponentAttribution(
				componentUuid, componentName,
				oldestRelease.getUuid(), oldestRelease.getVersion(),
				branchUuid, cachedBranchName(branchUuid, branchNameCache)
			);
			
			trackAllFindings(forkPointChanges, maps, attr, forkPointHandledFindings);
		}
		
		// PHASE 1: Pairwise consecutive comparisons on each branch
		processPairwiseComparisons(releasesByBranch, componentUuid, componentName,
			comparator, maps, branchNameCache);
	}
	
	/**
	 * Processes pairwise consecutive comparisons within each branch.
	 * Compares older→newer release pairs and tracks appeared/resolved findings.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first)
	 * @param componentUuid Component UUID for attribution
	 * @param componentName Component name for attribution
	 * @param pairComparator Function that compares two releases (older, newer) and returns finding changes, or null to skip
	 * @param maps Attribution maps holder to populate
	 */
	private void processPairwiseComparisons(
			Map<UUID, List<ReleaseData>> releasesByBranch,
			UUID componentUuid,
			String componentName,
			BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> pairComparator,
			AttributionMaps maps,
			Map<UUID, String> branchNameCache) {
		
		for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
			List<ReleaseData> branchReleases = branchEntry.getValue();
			if (branchReleases.size() < 2) continue;
			
			// Iterate from oldest to newest (pairwise)
			for (int i = branchReleases.size() - 1; i > 0; i--) {
				ReleaseData olderRelease = branchReleases.get(i);
				ReleaseData newerRelease = branchReleases.get(i - 1);
				
				FindingChangesRecord changes = pairComparator.apply(olderRelease, newerRelease);
				if (changes == null) continue;
				
				ComponentAttribution pairAttr = new ComponentAttribution(
					componentUuid, componentName,
					newerRelease.getUuid(), newerRelease.getVersion(),
					newerRelease.getBranch(), cachedBranchName(newerRelease.getBranch(), branchNameCache)
				);
				
				trackAllFindings(changes, maps, pairAttr, null);
			}
		}
	}
	
	/**
	 * Builds the final FindingChangesWithAttribution from the three attributed finding lists.
	 * Counts net appeared/resolved totals.
	 */
	private FindingChangesWithAttribution buildFindingChangesResult(
			List<VulnerabilityWithAttribution> vulnerabilities,
			List<ViolationWithAttribution> violations,
			List<WeaknessWithAttribution> weaknesses) {
		
		int totalAppeared = (int) (vulnerabilities.stream().filter(v -> v.isNetAppeared()).count()
			+ violations.stream().filter(v -> v.isNetAppeared()).count()
			+ weaknesses.stream().filter(w -> w.isNetAppeared()).count());
		
		int totalResolved = (int) (vulnerabilities.stream().filter(v -> v.isNetResolved()).count()
			+ violations.stream().filter(v -> v.isNetResolved()).count()
			+ weaknesses.stream().filter(w -> w.isNetResolved()).count());
		
		return new FindingChangesWithAttribution(
			vulnerabilities, violations, weaknesses,
			totalAppeared, totalResolved);
	}
	
	/**
	 * Builds attributed finding lists from the three attribution maps using a flag-computing strategy.
	 * Shared by both component-level and org-level methods — the only difference is how flags are computed.
	 *
	 * @param maps Attribution maps holder
	 * @param flagComputer Function that takes (key, FindingAttribution) and returns FindingFlags
	 */
	private FindingChangesWithAttribution buildAttributedFindings(
			AttributionMaps maps,
			BiFunction<String, FindingAttribution<?>, FindingFlags> flagComputer) {
		
		List<VulnerabilityWithAttribution> vulnerabilities = maps.vulns.entrySet().stream()
			.map(e -> {
				FindingFlags flags = flagComputer.apply(e.getKey(), e.getValue());
				var fa = e.getValue();
				return new VulnerabilityWithAttribution(
					fa.finding.vulnId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : "UNKNOWN",
					fa.finding.purl(),
					fa.finding.aliases(),
					fa.resolvedIn, fa.appearedIn, fa.presentIn,
					flags.isNetResolved(), flags.isNetAppeared(), flags.isStillPresent(),
					flags.orgContext()
				);
			})
			.collect(Collectors.toList());
		
		List<ViolationWithAttribution> violations = maps.violations.entrySet().stream()
			.map(e -> {
				FindingFlags flags = flagComputer.apply(e.getKey(), e.getValue());
				var fa = e.getValue();
				return new ViolationWithAttribution(
					fa.finding.type() != null ? fa.finding.type().name() : "UNKNOWN",
					fa.finding.purl(),
					fa.resolvedIn, fa.appearedIn, fa.presentIn,
					flags.isNetResolved(), flags.isNetAppeared(), flags.isStillPresent(),
					flags.orgContext()
				);
			})
			.collect(Collectors.toList());
		
		List<WeaknessWithAttribution> weaknesses = maps.weaknesses.entrySet().stream()
			.map(e -> {
				FindingFlags flags = flagComputer.apply(e.getKey(), e.getValue());
				var fa = e.getValue();
				return new WeaknessWithAttribution(
					fa.finding.cweId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : "UNKNOWN",
					fa.finding.ruleId(),
					fa.finding.location() != null ? fa.finding.location() : "",
					fa.resolvedIn, fa.appearedIn, fa.presentIn,
					flags.isNetResolved(), flags.isNetAppeared(), flags.isStillPresent(),
					flags.orgContext()
				);
			})
			.collect(Collectors.toList());
		
		return buildFindingChangesResult(vulnerabilities, violations, weaknesses);
	}
	
	/**
	 * Tracks findings that are inherited within a component (present in both first and last metrics of a branch).
	 * Adds the component UUID to the inheritedInComponents map for each inherited finding key.
	 */
	private <T> void trackInheritedInComponents(
			List<T> firstFindings,
			List<T> lastFindings,
			Function<T, String> keyExtractor,
			Map<String, Set<UUID>> inheritedInComponents,
			UUID componentUuid) {
		if (firstFindings == null || lastFindings == null) return;
		
		Set<String> firstKeys = new HashSet<>();
		for (T finding : firstFindings) {
			firstKeys.add(keyExtractor.apply(finding));
		}
		for (T finding : lastFindings) {
			String key = keyExtractor.apply(finding);
			if (firstKeys.contains(key)) {
				inheritedInComponents.computeIfAbsent(key, k -> new HashSet<>()).add(componentUuid);
			}
		}
	}
	
	/**
	 * Computed flags for a finding (used at both component and org level).
	 */
	private record FindingFlags(
		boolean isNetAppeared,
		boolean isStillPresent,
		boolean isNetResolved,
		OrgLevelContext orgContext
	) {}
	
	/**
	 * Computes component-level flags for a single finding.
	 */
	private <T> FindingFlags computeComponentFindingFlags(
			FindingAttribution<T> fa,
			Map<UUID, UUID> latestReleasePerBranch) {
		
		boolean isNetAppeared = !fa.appearedIn.isEmpty() && fa.resolvedIn.isEmpty();
		boolean existsInLatestRelease = fa.presentIn.stream()
			.anyMatch(attr -> latestReleasePerBranch.containsValue(attr.releaseUuid()));
		boolean isStillPresent = existsInLatestRelease && !isNetAppeared;
		boolean isNetResolved = !fa.resolvedIn.isEmpty() && !existsInLatestRelease;
		
		return new FindingFlags(isNetAppeared, isStillPresent, isNetResolved, null);
	}
	
	
	/**
	 * Computes org-level flags and OrgLevelContext for a single finding.
	 * Centralizes the flag computation logic that was previously triplicated for vulns/violations/weaknesses.
	 */
	private <T> FindingFlags computeOrgFindingFlags(
			String key,
			FindingAttribution<T> fa,
			Map<String, Set<UUID>> inheritedInComponents,
			int totalComponents) {
		
		Set<UUID> inherited = inheritedInComponents.getOrDefault(key, Collections.emptySet());
		Set<UUID> appearedComponents = fa.appearedIn.stream()
			.map(ComponentAttribution::componentUuid)
			.collect(Collectors.toSet());
		Set<UUID> resolvedComponents = fa.resolvedIn.stream()
			.map(ComponentAttribution::componentUuid)
			.collect(Collectors.toSet());
		Set<UUID> presentComponents = fa.presentIn.stream()
			.map(ComponentAttribution::componentUuid)
			.collect(Collectors.toSet());
		
		boolean isNetAppeared = !fa.appearedIn.isEmpty() && fa.resolvedIn.isEmpty();
		boolean isStillPresent = !fa.presentIn.isEmpty() && !isNetAppeared;
		boolean isNetResolved = !fa.resolvedIn.isEmpty() && fa.presentIn.isEmpty();
		
		boolean isFullyResolved = resolvedComponents.size() > 0 && presentComponents.isEmpty();
		boolean isPartiallyResolved = !isFullyResolved && resolvedComponents.size() > 0 && presentComponents.size() > 0;
		boolean isInheritedInAllComponents = !isFullyResolved && !isPartiallyResolved && inherited.size() == totalComponents && totalComponents > 1;
		boolean isNewToOrganization = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && inherited.isEmpty();
		boolean wasPreviouslyReported = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && 
			(inherited.size() > 0 || presentComponents.stream().anyMatch(c -> !appearedComponents.contains(c)));
		
		List<String> affectedComponentNames = fa.presentIn.stream()
			.map(ComponentAttribution::componentName)
			.distinct()
			.collect(Collectors.toList());
		
		OrgLevelContext orgContext = new OrgLevelContext(
			isNewToOrganization,
			wasPreviouslyReported,
			isPartiallyResolved,
			isFullyResolved,
			isInheritedInAllComponents,
			presentComponents.size(),
			affectedComponentNames
		);
		
		return new FindingFlags(isNetAppeared, isStillPresent, isNetResolved, orgContext);
	}
	
	/**
	 * Compare metrics across multiple components for organization-level changelog.
	 * Aggregates findings from all components with proper semantics:
	 * - isStillPresent = inherited (existed in first AND last release) in ANY component
	 * - isNetAppeared = appeared in at least one component and NOT inherited in any
	 * - isNetResolved = resolved in at least one component
	 * 
	 * @param componentReleases Map of component UUID to list of releases
	 * @param componentNames Map of component UUID to component name
	 * @return Finding changes with attribution across all components
	 */
	public FindingChangesWithAttribution compareMetricsAcrossComponents(
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache) {
		
		log.debug("ORG-COMPARE: Starting org-level finding comparison across {} components", componentReleases.size());
		
		AttributionMaps maps = new AttributionMaps();
		
		// Track which components have which findings as inherited (first AND last release)
		Map<String, Set<UUID>> inheritedInComponents = new HashMap<>();
		
		// First pass: collect appeared/resolved from each component, per branch
		for (Map.Entry<UUID, List<ReleaseData>> entry : componentReleases.entrySet()) {
			UUID componentUuid = entry.getKey();
			List<ReleaseData> releases = entry.getValue();
			String componentName = componentNames.getOrDefault(componentUuid, "Unknown");
			
			if (releases.isEmpty()) continue;
			
			// Group releases by branch to compare per-branch (not just overall first vs last)
			Map<UUID, List<ReleaseData>> releasesByBranch = releases.stream()
				.collect(Collectors.groupingBy(ReleaseData::getBranch));
			// Sort each branch's releases by creation date (newest first) - required by processForkPointAndPairwise
			releasesByBranch.values().forEach(list -> list.sort(Comparator.comparing(ReleaseData::getCreatedDate).reversed()));
			
			log.debug("ORG-COMPARE: Component {} has {} branches", componentName, releasesByBranch.size());
			
			// PHASES 0-1: Fork point comparisons + pairwise consecutive comparisons (uses cache from component-level)
			Set<String> forkPointHandledFindings = new HashSet<>();
			processForkPointAndPairwise(releasesByBranch, componentUuid, componentName,
				DIRECT_METRICS_COMPARATOR, maps, forkPointHandledFindings, branchNameCache, forkPointCache);
			
			// PHASE 2: Track inherited findings from oldest release in each branch
			// Without this, findings that existed before the date range never enter the maps,
			// causing trackPresentFindings to silently skip them (resulting in 0 "Still Present")
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.isEmpty()) continue;
				ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
				ReleaseMetricsDto oldestMetrics = oldestRelease.getMetrics();
				if (oldestMetrics != null) {
					trackAllInheritedFindings(oldestMetrics, maps, forkPointHandledFindings);
				}
			}
			
			// Track inherited findings per branch: existed in BOTH oldest AND newest release
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.size() < 2) continue;
				ReleaseMetricsDto firstMetrics = branchReleases.get(branchReleases.size() - 1).getMetrics();
				ReleaseMetricsDto lastMetrics = branchReleases.get(0).getMetrics();
				if (firstMetrics != null && lastMetrics != null) {
					trackInheritedInComponents(firstMetrics.getVulnerabilityDetails(),
						lastMetrics.getVulnerabilityDetails(), VULN_KEY, inheritedInComponents, componentUuid);
					trackInheritedInComponents(firstMetrics.getViolationDetails(),
						lastMetrics.getViolationDetails(), VIOLATION_KEY, inheritedInComponents, componentUuid);
					trackInheritedInComponents(firstMetrics.getWeaknessDetails(),
						lastMetrics.getWeaknessDetails(), WEAKNESS_KEY, inheritedInComponents, componentUuid);
				}
			}
			
			// Track present findings from latest release per branch
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.isEmpty()) continue;
				
				ReleaseData latestRelease = branchReleases.get(0);
				ReleaseMetricsDto latestMetrics = latestRelease.getMetrics();
				
				if (latestMetrics == null) continue;
				
				ComponentAttribution latestAttr = new ComponentAttribution(
					componentUuid, componentName,
					latestRelease.getUuid(), latestRelease.getVersion(),
					latestRelease.getBranch(), cachedBranchName(latestRelease.getBranch(), branchNameCache)
				);
				
				trackAllPresentFindings(latestMetrics, maps, latestAttr);
			}
		}
		
		// Build attributed findings using org-level flag computation
		int totalComponents = componentReleases.size();
		return buildAttributedFindings(maps,
			(key, fa) -> computeOrgFindingFlags(key, fa, inheritedInComponents, totalComponents));
	}
	
}
