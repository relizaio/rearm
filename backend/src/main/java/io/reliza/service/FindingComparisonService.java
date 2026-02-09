/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.reliza.dto.ComponentAttribution;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.OrgLevelContext;
import io.reliza.dto.ViolationWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.dto.WeaknessWithAttribution;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.FindingChangesDto;
import io.reliza.model.dto.ReleaseMetricsDto;
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
	 * Generic comparison result holder
	 */
	private record ComparisonResult<T>(
		List<T> appeared,
		List<T> resolved,
		List<T> severityChanged
	) {}
	
	/**
	 * Functional interface for extracting unique key from a finding
	 */
	@FunctionalInterface
	private interface KeyExtractor<T> {
		String extractKey(T item);
	}
	
	/**
	 * Functional interface for checking severity changes
	 */
	@FunctionalInterface
	private interface SeverityChecker<T> {
		boolean hasSeverityChanged(T item1, T item2);
	}
	
	/**
	 * Generic comparison method for finding lists
	 * Eliminates code duplication across vulnerability, violation, and weakness comparisons
	 */
	private <T> ComparisonResult<T> compareFindings(
			List<T> list1,
			List<T> list2,
			KeyExtractor<T> keyExtractor,
			SeverityChecker<T> severityChecker) {
		
		List<T> appeared = new ArrayList<>();
		List<T> resolved = new ArrayList<>();
		List<T> severityChanged = new ArrayList<>();
		
		// Build maps by key
		Map<String, T> map1 = new HashMap<>();
		Map<String, T> map2 = new HashMap<>();
		
		if (list1 != null) {
			for (T item : list1) {
				map1.put(keyExtractor.extractKey(item), item);
			}
		}
		
		if (list2 != null) {
			for (T item : list2) {
				map2.put(keyExtractor.extractKey(item), item);
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
		
		// Find severity changes (in both but different severity)
		if (severityChecker != null) {
			for (Map.Entry<String, T> entry : map2.entrySet()) {
				String key = entry.getKey();
				if (map1.containsKey(key)) {
					T item1 = map1.get(key);
					T item2 = entry.getValue();
					
					if (severityChecker.hasSeverityChanged(item1, item2)) {
						severityChanged.add(item2);
					}
				}
			}
		}
		
		return new ComparisonResult<>(appeared, resolved, severityChanged);
	}
	
	/**
	 * Compare vulnerabilities between two lists
	 */
	private ComparisonResult<ReleaseMetricsDto.VulnerabilityDto> compareVulnerabilities(
			List<ReleaseMetricsDto.VulnerabilityDto> list1,
			List<ReleaseMetricsDto.VulnerabilityDto> list2) {
		
		return compareFindings(
			list1,
			list2,
			vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + (vuln.purl() != null ? vuln.purl() : ""),
			(vuln1, vuln2) -> vuln1.severity() != vuln2.severity()
		);
	}
	
	/**
	 * Compare violations between two lists
	 */
	private ComparisonResult<ReleaseMetricsDto.ViolationDto> compareViolations(
			List<ReleaseMetricsDto.ViolationDto> list1,
			List<ReleaseMetricsDto.ViolationDto> list2) {
		
		return compareFindings(
			list1,
			list2,
			violation -> violation.type() + FINDING_KEY_DELIMITER + (violation.purl() != null ? violation.purl() : ""),
			null
		);
	}
	
	/**
	 * Compare weaknesses between two lists
	 */
	private ComparisonResult<ReleaseMetricsDto.WeaknessDto> compareWeaknesses(
			List<ReleaseMetricsDto.WeaknessDto> list1,
			List<ReleaseMetricsDto.WeaknessDto> list2) {
		
		return compareFindings(
			list1,
			list2,
			weakness -> {
				String findingId = weakness.cweId() != null ? weakness.cweId() : weakness.ruleId();
				return findingId + FINDING_KEY_DELIMITER + (weakness.location() != null ? weakness.location() : "");
			},
			(weakness1, weakness2) -> weakness1.severity() != weakness2.severity()
		);
	}
	
	/**
	 * Returns an empty FindingChangesRecord
	 */
	public FindingChangesDto.FindingChangesRecord emptyFindingChanges() {
		return new FindingChangesDto.FindingChangesRecord(
			List.of(), List.of(), List.of(),  // vulnerabilities
			List.of(), List.of(),              // violations
			List.of(), List.of(), List.of(),   // weaknesses
			new FindingChangesDto.FindingChangesSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
		);
	}

	public class FindingAttribution<T> {
			T finding;
			List<ComponentAttribution> appearedIn = new ArrayList<>();
			List<ComponentAttribution> resolvedIn = new ArrayList<>();
			List<ComponentAttribution> presentIn = new ArrayList<>();
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
	public FindingChangesDto.FindingChangesRecord compareMetrics(
			ReleaseMetricsDto metrics1,
			ReleaseMetricsDto metrics2) {
		
		if (metrics1 == null || metrics2 == null) {
			log.warn("FINDINGS_COMPARISON: One or both metrics are null - metrics1={}, metrics2={}", 
				metrics1 != null ? "present" : "null", 
				metrics2 != null ? "present" : "null");
			return emptyFindingChanges();
		}
		
		// Compare vulnerabilities
		var vulnChanges = compareVulnerabilities(
			metrics1.getVulnerabilityDetails(),
			metrics2.getVulnerabilityDetails()
		);
		
		// Compare violations
		var violationChanges = compareViolations(
			metrics1.getViolationDetails(),
			metrics2.getViolationDetails()
		);
		
		// Compare weaknesses
		var weaknessChanges = compareWeaknesses(
			metrics1.getWeaknessDetails(),
			metrics2.getWeaknessDetails()
		);
		
		// Build summary
		int totalAppearedCount = vulnChanges.appeared.size() + violationChanges.appeared.size() + weaknessChanges.appeared.size();
		int totalResolvedCount = vulnChanges.resolved.size() + violationChanges.resolved.size() + weaknessChanges.resolved.size();
		int netChange = totalAppearedCount - totalResolvedCount;
		
		FindingChangesDto.FindingChangesSummary summary = new FindingChangesDto.FindingChangesSummary(
			vulnChanges.appeared.size(),           // appearedVulnerabilitiesCount
			vulnChanges.resolved.size(),           // resolvedVulnerabilitiesCount
			vulnChanges.severityChanged.size(),    // severityChangedVulnerabilitiesCount
			violationChanges.appeared.size(),      // appearedViolationsCount
			violationChanges.resolved.size(),      // resolvedViolationsCount
			weaknessChanges.appeared.size(),       // appearedWeaknessesCount
			weaknessChanges.resolved.size(),       // resolvedWeaknessesCount
			weaknessChanges.severityChanged.size(), // severityChangedWeaknessesCount
			totalAppearedCount,                    // totalAppearedCount
			totalResolvedCount,                    // totalResolvedCount
			netChange                              // netChange
		);
		
		return new FindingChangesDto.FindingChangesRecord(
			vulnChanges.appeared,
			vulnChanges.resolved,
			vulnChanges.severityChanged,
			violationChanges.appeared,
			violationChanges.resolved,
			weaknessChanges.appeared,
			weaknessChanges.resolved,
			weaknessChanges.severityChanged,
			summary
		);
	}


	
	/**
	 * Generic helper to track appeared findings for any finding type
	 */
	private <T> void trackAppearedFindings(
			List<T> appearedFindings,
			Map<String, FindingAttribution<T>> findingMap,
			ComponentAttribution attr,
			Set<String> handledFindings,
			java.util.function.Function<T, String> keyExtractor) {
		
		if (appearedFindings == null) return;
		
		for (T finding : appearedFindings) {
			String key = keyExtractor.apply(finding);
			FindingAttribution<T> fa = findingMap.computeIfAbsent(key, k -> new FindingAttribution<>());
			if (fa.finding == null) fa.finding = finding;
			fa.appearedIn.add(attr);
			if (handledFindings != null) {
				handledFindings.add(key);
			}
		}
	}
	
	/**
	 * Generic helper to track resolved findings for any finding type
	 */
	private <T> void trackResolvedFindings(
			List<T> resolvedFindings,
			Map<String, FindingAttribution<T>> findingMap,
			ComponentAttribution attr,
			Set<String> handledFindings,
			java.util.function.Function<T, String> keyExtractor) {
		
		if (resolvedFindings == null) return;
		
		for (T finding : resolvedFindings) {
			String key = keyExtractor.apply(finding);
			FindingAttribution<T> fa = findingMap.computeIfAbsent(key, k -> new FindingAttribution<>());
			if (fa.finding == null) fa.finding = finding;
			fa.resolvedIn.add(attr);
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
			java.util.function.Function<T, String> keyExtractor) {
		
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
	 * Generic helper to track inherited findings for any finding type
	 */
	private <T> void trackInheritedFindings(
			List<T> findings,
			Map<String, FindingAttribution<T>> findingMap,
			Set<String> handledFindings,
			java.util.function.Function<T, String> keyExtractor) {
		
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
	 * @param branchDataMap Map of branch UUID to BranchData
	 * @param componentData Component data for attribution
	 * @param org Organization UUID
	 * @param metricsExtractor Function to extract metrics for a release pair
	 * @return Finding changes with accurate per-release attribution
	 */
	public FindingChangesWithAttribution compareMetricsWithAttributionAcrossBranches(
			java.util.LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			ComponentData componentData,
			UUID org,
			java.util.function.BiFunction<UUID, UUID, io.reliza.service.ChangeLogService.MetricsPair> metricsExtractor) {
		
		log.debug("Starting finding attribution comparison for component {}", componentData.getName());
		
		
		// Track attribution for all three finding types
		Map<String, FindingAttribution<ReleaseMetricsDto.VulnerabilityDto>> vulnMap = new HashMap<>();
		Map<String, FindingAttribution<ReleaseMetricsDto.ViolationDto>> violationMap = new HashMap<>();
		Map<String, FindingAttribution<ReleaseMetricsDto.WeaknessDto>> weaknessMap = new HashMap<>();
		
		// Track findings handled by fork point comparison to avoid treating them as inherited
		Set<String> forkPointHandledFindings = new HashSet<>();
		
		// PHASE 1: Handle branch boundaries (fork point comparisons)
		// For the first release of each branch, compare against its fork point on the base branch
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			BranchData branchData = branchDataMap.get(branchUuid);
			
			if (branchReleases.isEmpty() || branchData == null) continue;
			
			// Get the first (oldest) release in this branch
			ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
			
			// Find the fork point (previous release to compare against)
			UUID forkPointReleaseId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
				branchUuid, oldestRelease.getUuid());
			
			if (forkPointReleaseId == null) {
				log.debug("No fork point found for branch {} release {}", branchData.getName(), oldestRelease.getVersion());
				continue;
			}
			
			// Get fork point release data
			var forkPointReleaseOpt = sharedReleaseService.getReleaseData(forkPointReleaseId);
			if (forkPointReleaseOpt.isEmpty()) {
				log.warn("Fork point release {} not found", forkPointReleaseId);
				continue;
			}
			
			ReleaseData forkPointRelease = forkPointReleaseOpt.get();
			
			// Only compare if fork point is on a DIFFERENT branch
			if (forkPointRelease.getBranch().equals(branchUuid)) {
				log.debug("Fork point is on same branch, skipping");
				continue;
			}
			
			// Get fork point branch data
			BranchData forkPointBranchData = branchDataMap.get(forkPointRelease.getBranch());
			if (forkPointBranchData == null) {
				var forkPointBranchOpt = branchService.getBranchData(forkPointRelease.getBranch());
				if (forkPointBranchOpt.isEmpty()) {
					log.warn("Fork point branch data not found for UUID {}", forkPointRelease.getBranch());
					continue;
				}
				forkPointBranchData = forkPointBranchOpt.get();
			}
			
			log.debug("Comparing branch boundary: {} {} vs {} {} for component {}", 
				forkPointBranchData.getName(), forkPointRelease.getVersion(),
				branchData.getName(), oldestRelease.getVersion(), componentData.getName());
			
			// Extract metrics for fork point comparison
			io.reliza.service.ChangeLogService.MetricsPair forkPointMetrics = 
				metricsExtractor.apply(forkPointReleaseId, oldestRelease.getUuid());
			
			if (forkPointMetrics == null) {
				log.warn("Could not extract metrics for fork point comparison between {} and {}", 
					forkPointReleaseId, oldestRelease.getUuid());
				continue;
			}
			
			// Compare findings between fork point and oldest release
			FindingChangesDto.FindingChangesRecord forkPointChanges = compareMetrics(
				forkPointMetrics.metrics1(), forkPointMetrics.metrics2());
			
			log.debug("Fork point comparison result: {} appeared vulns, {} resolved vulns",
				forkPointChanges.appearedVulnerabilities() != null ? forkPointChanges.appearedVulnerabilities().size() : 0,
				forkPointChanges.resolvedVulnerabilities() != null ? forkPointChanges.resolvedVulnerabilities().size() : 0);
			
			// Create attribution for the oldest release
			ComponentAttribution attr = new ComponentAttribution(
				componentData.getUuid(),
				componentData.getName(),
				oldestRelease.getUuid(),
				oldestRelease.getVersion(),
				branchUuid,
				branchData.getName(),
				forkPointRelease.getVersion()
			);
			
			// Track findings at branch boundary using generic helpers
			trackAppearedFindings(forkPointChanges.appearedVulnerabilities(), vulnMap, attr, forkPointHandledFindings,
				vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
			trackResolvedFindings(forkPointChanges.resolvedVulnerabilities(), vulnMap, attr, forkPointHandledFindings,
				vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
			
			trackAppearedFindings(forkPointChanges.appearedViolations(), violationMap, attr, forkPointHandledFindings,
				violation -> violation.type() + FINDING_KEY_DELIMITER + violation.purl());
			trackResolvedFindings(forkPointChanges.resolvedViolations(), violationMap, attr, forkPointHandledFindings,
				violation -> violation.type() + FINDING_KEY_DELIMITER + violation.purl());
			
			trackAppearedFindings(forkPointChanges.appearedWeaknesses(), weaknessMap, attr, forkPointHandledFindings,
				weakness -> weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location());
			trackResolvedFindings(forkPointChanges.resolvedWeaknesses(), weaknessMap, attr, forkPointHandledFindings,
				weakness -> weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location());
		}
		
		// PHASE 2: Process each branch sequentially (consecutive pairs within branch)
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			BranchData branchData = branchDataMap.get(branchUuid);
			
			if (branchData == null) {
				log.warn("Branch data not found for UUID {}", branchUuid);
				continue;
			}
			
			// Compare consecutive pairs within this branch
			log.debug("Processing branch {} with {} releases for component {}", branchData.getName(), branchReleases.size(), componentData.getName());
			for (int i = 0; i < branchReleases.size() - 1; i++) {
				ReleaseData prevRelease = branchReleases.get(i + 1);  // older
				ReleaseData currentRelease = branchReleases.get(i);   // newer
				
				io.reliza.service.ChangeLogService.MetricsPair metrics = 
					metricsExtractor.apply(prevRelease.getUuid(), currentRelease.getUuid());
				
				if (metrics == null) {
					log.debug("Metrics null for {} -> {} on branch {}", prevRelease.getVersion(), currentRelease.getVersion(), branchData.getName());
					continue;
				}
				
				FindingChangesDto.FindingChangesRecord changes = compareMetrics(
					metrics.metrics1(), metrics.metrics2());
				
				log.debug("Consecutive pair {} -> {}: {} appeared vulns, {} resolved vulns", 
					prevRelease.getVersion(), currentRelease.getVersion(),
					changes.appearedVulnerabilities() != null ? changes.appearedVulnerabilities().size() : 0,
					changes.resolvedVulnerabilities() != null ? changes.resolvedVulnerabilities().size() : 0);
				
				ComponentAttribution attr = new ComponentAttribution(
					componentData.getUuid(),
					componentData.getName(),
					currentRelease.getUuid(),
					currentRelease.getVersion(),
					branchUuid,
					branchData.getName(),
					prevRelease.getVersion()
				);
				
				// Track findings using generic helpers
				trackAppearedFindings(changes.appearedVulnerabilities(), vulnMap, attr, null,
					vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
				trackResolvedFindings(changes.resolvedVulnerabilities(), vulnMap, attr, null,
					vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
				
				trackAppearedFindings(changes.appearedViolations(), violationMap, attr, null,
					violation -> violation.type() + FINDING_KEY_DELIMITER + violation.purl());
				trackResolvedFindings(changes.resolvedViolations(), violationMap, attr, null,
					violation -> violation.type() + FINDING_KEY_DELIMITER + violation.purl());
				
				trackAppearedFindings(changes.appearedWeaknesses(), weaknessMap, attr, null,
					weakness -> weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location());
				trackResolvedFindings(changes.resolvedWeaknesses(), weaknessMap, attr, null,
					weakness -> weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location());
			}
		}
		
		// PHASE 3: Track truly inherited findings from first (oldest) release in each branch
		// These are findings that existed before the fork point AND were not handled by fork point comparison
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			
			if (branchReleases.isEmpty()) continue;
			
			// Get the first (oldest) release in this branch
			ReleaseData firstRelease = branchReleases.get(branchReleases.size() - 1);
			
			// Get metrics for first release
			io.reliza.service.ChangeLogService.MetricsPair firstMetrics = 
				metricsExtractor.apply(firstRelease.getUuid(), firstRelease.getUuid());
			
			if (firstMetrics == null || firstMetrics.metrics2() == null) continue;
			
			// Track truly inherited findings using generic helper
			trackInheritedFindings(firstMetrics.metrics2().getVulnerabilityDetails(), vulnMap, forkPointHandledFindings,
				vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
			trackInheritedFindings(firstMetrics.metrics2().getViolationDetails(), violationMap, forkPointHandledFindings,
				violation -> violation.type() + FINDING_KEY_DELIMITER + violation.purl());
			trackInheritedFindings(firstMetrics.metrics2().getWeaknessDetails(), weaknessMap, forkPointHandledFindings,
				weakness -> weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location());
		}
		
		// Track current state by querying ALL releases in each branch
		// This populates presentIn with findings that exist in each release
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			
			if (branchReleases.isEmpty()) continue;
			
			BranchData branchData = branchDataMap.get(branchUuid);
			if (branchData == null) continue;
			
			// Iterate through ALL releases in this branch
			for (ReleaseData release : branchReleases) {
				// Get metrics for this release
				io.reliza.service.ChangeLogService.MetricsPair releaseMetrics = 
					metricsExtractor.apply(release.getUuid(), release.getUuid());
				
				if (releaseMetrics == null || releaseMetrics.metrics2() == null) continue;
				
				ComponentAttribution releaseAttr = new ComponentAttribution(
					componentData.getUuid(),
					componentData.getName(),
					release.getUuid(),
					release.getVersion(),
					branchUuid,
					branchData.getName(),
					null
				);
				
				// Track present findings using generic helper
				trackPresentFindings(releaseMetrics.metrics2().getVulnerabilityDetails(), vulnMap, releaseAttr,
					vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
				trackPresentFindings(releaseMetrics.metrics2().getViolationDetails(), violationMap, releaseAttr,
					violation -> violation.type() + FINDING_KEY_DELIMITER + violation.purl());
				trackPresentFindings(releaseMetrics.metrics2().getWeaknessDetails(), weaknessMap, releaseAttr,
					weakness -> weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location());
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
		
		// Build attributed findings for all three types
		List<VulnerabilityWithAttribution> vulnerabilities = vulnMap.values().stream()
			.map(fa -> {
				// Calculate flags
				boolean isNetAppeared = !fa.appearedIn.isEmpty() && fa.resolvedIn.isEmpty();
				boolean existsInLatestRelease = fa.presentIn.stream()
					.anyMatch(attr -> latestReleasePerBranch.containsValue(attr.releaseUuid()));
				
				// isStillPresent = exists in ANY latest release and is not purely new
				boolean isStillPresent = existsInLatestRelease && !isNetAppeared;
				
				// isNetResolved = resolved somewhere AND not present in any latest release
				boolean isNetResolved = !fa.resolvedIn.isEmpty() && !existsInLatestRelease;
				
				return new VulnerabilityWithAttribution(
					fa.finding.vulnId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : "UNKNOWN",
					fa.finding.purl(),
					fa.resolvedIn,
					fa.appearedIn,
					fa.presentIn,
					isNetResolved,
					isNetAppeared,
					isStillPresent,
					null  // orgContext not applicable for component-level view
				);
			})
			.collect(Collectors.toList());
		
		List<ViolationWithAttribution> violations = violationMap.values().stream()
			.map(fa -> {
				// Calculate flags
				boolean isNetAppeared = !fa.appearedIn.isEmpty() && fa.resolvedIn.isEmpty();
				boolean existsInLatestRelease = fa.presentIn.stream()
					.anyMatch(attr -> latestReleasePerBranch.containsValue(attr.releaseUuid()));
				
				// isStillPresent = exists in ANY latest release and is not purely new
				boolean isStillPresent = existsInLatestRelease && !isNetAppeared;
				
				// isNetResolved = resolved somewhere AND not present in any latest release
				boolean isNetResolved = !fa.resolvedIn.isEmpty() && !existsInLatestRelease;
				
				return new ViolationWithAttribution(
					fa.finding.type() != null ? fa.finding.type().name() : "UNKNOWN",
					fa.finding.purl(),
					fa.resolvedIn,
					fa.appearedIn,
					fa.presentIn,
					isNetResolved,
					isNetAppeared,
					isStillPresent,
					null  // orgContext not applicable for component-level view
				);
			})
			.collect(Collectors.toList());
		
		List<WeaknessWithAttribution> weaknesses = weaknessMap.values().stream()
			.map(fa -> {
				// Calculate flags
				boolean isNetAppeared = !fa.appearedIn.isEmpty() && fa.resolvedIn.isEmpty();
				boolean existsInLatestRelease = fa.presentIn.stream()
					.anyMatch(attr -> latestReleasePerBranch.containsValue(attr.releaseUuid()));
				
				// isStillPresent = exists in ANY latest release and is not purely new
				boolean isStillPresent = existsInLatestRelease && !isNetAppeared;
				
				// isNetResolved = resolved somewhere AND not present in any latest release
				boolean isNetResolved = !fa.resolvedIn.isEmpty() && !existsInLatestRelease;
				
				return new WeaknessWithAttribution(
					fa.finding.cweId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : "UNKNOWN",
					fa.finding.location() != null ? fa.finding.location() : "",
					fa.resolvedIn,
					fa.appearedIn,
					fa.presentIn,
					isNetResolved,
					isNetAppeared,
					isStillPresent,
					null  // orgContext not applicable for component-level view
				);
			})
			.collect(Collectors.toList());
		
		int totalAppeared = (int) (vulnerabilities.stream().filter(v -> v.isNetAppeared()).count()
			+ violations.stream().filter(v -> v.isNetAppeared()).count()
			+ weaknesses.stream().filter(w -> w.isNetAppeared()).count());
		
		int totalResolved = (int) (vulnerabilities.stream().filter(v -> v.isNetResolved()).count()
			+ violations.stream().filter(v -> v.isNetResolved()).count()
			+ weaknesses.stream().filter(w -> w.isNetResolved()).count());
		
		log.debug("Finding attribution complete - {} vulnerabilities, {} violations, {} weaknesses", 
			vulnerabilities.size(), violations.size(), weaknesses.size());
		
		return new FindingChangesWithAttribution(
			vulnerabilities, violations, weaknesses, 
			totalAppeared, totalResolved);
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
			Map<UUID, String> componentNames) {
		
		log.debug("ORG-COMPARE: Starting org-level finding comparison across {} components", componentReleases.size());
		
		// Use internal attribution structure to track per-component state
		Map<String, FindingAttribution<ReleaseMetricsDto.VulnerabilityDto>> vulnMap = new HashMap<>();
		Map<String, FindingAttribution<ReleaseMetricsDto.ViolationDto>> violationMap = new HashMap<>();
		Map<String, FindingAttribution<ReleaseMetricsDto.WeaknessDto>> weaknessMap = new HashMap<>();
		
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
			
			// PHASE 0: Fork point comparisons for each branch's oldest release
			log.debug("ORG-COMPARE: Component {} has {} branches: {}", componentName, releasesByBranch.size(),
				releasesByBranch.entrySet().stream()
					.map(be -> getBranchName(be.getKey()) + "(" + be.getValue().size() + " releases)")
					.collect(Collectors.joining(", ")));
			for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
				UUID branchUuid = branchEntry.getKey();
				List<ReleaseData> branchReleases = branchEntry.getValue();
				if (branchReleases.isEmpty()) continue;
				
				ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
				String branchName = getBranchName(branchUuid);
				
				UUID forkPointReleaseId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
					branchUuid, oldestRelease.getUuid());
				
				if (forkPointReleaseId == null) {
					log.debug("ORG-COMPARE: PHASE0 {}/{}: No fork point found for oldest release {}", componentName, branchName, oldestRelease.getVersion());
					continue;
				}
				
				var forkPointReleaseOpt = sharedReleaseService.getReleaseData(forkPointReleaseId);
				if (forkPointReleaseOpt.isEmpty()) continue;
				
				ReleaseData forkPointRelease = forkPointReleaseOpt.get();
				
				// Only compare if fork point is on a DIFFERENT branch
				if (forkPointRelease.getBranch().equals(branchUuid)) {
					log.debug("ORG-COMPARE: PHASE0 {}/{}: Fork point {} is on SAME branch, skipping", componentName, branchName, forkPointRelease.getVersion());
					continue;
				}
				
				ReleaseMetricsDto forkMetrics = forkPointRelease.getMetrics();
				ReleaseMetricsDto oldestMetrics = oldestRelease.getMetrics();
				
				if (forkMetrics == null || oldestMetrics == null) {
					log.debug("ORG-COMPARE: PHASE0 {}/{}: Metrics null (fork={}, oldest={})", componentName, branchName, forkMetrics != null, oldestMetrics != null);
					continue;
				}
				
				log.debug("ORG-COMPARE: PHASE0 {}/{}: Comparing fork point {}({}) vs oldest {}({}). Fork vulns={}, Oldest vulns={}",
					componentName, branchName,
					getBranchName(forkPointRelease.getBranch()), forkPointRelease.getVersion(),
					branchName, oldestRelease.getVersion(),
					forkMetrics.getVulnerabilityDetails() != null ? forkMetrics.getVulnerabilityDetails().size() : 0,
					oldestMetrics.getVulnerabilityDetails() != null ? oldestMetrics.getVulnerabilityDetails().size() : 0);
				
				FindingChangesDto.FindingChangesRecord forkChanges = compareMetrics(forkMetrics, oldestMetrics);
				
				log.debug("ORG-COMPARE: PHASE0 {}/{}: Result: {} appeared vulns, {} resolved vulns",
					componentName, branchName,
					forkChanges.appearedVulnerabilities() != null ? forkChanges.appearedVulnerabilities().size() : 0,
					forkChanges.resolvedVulnerabilities() != null ? forkChanges.resolvedVulnerabilities().size() : 0);
				if (forkChanges.appearedVulnerabilities() != null) {
					for (var v : forkChanges.appearedVulnerabilities()) {
						log.debug("ORG-COMPARE: PHASE0 {}/{}: APPEARED vuln {} in {}", componentName, branchName, v.vulnId(), v.purl());
					}
				}
				if (forkChanges.resolvedVulnerabilities() != null) {
					for (var v : forkChanges.resolvedVulnerabilities()) {
						log.debug("ORG-COMPARE: PHASE0 {}/{}: RESOLVED vuln {} in {}", componentName, branchName, v.vulnId(), v.purl());
					}
				}
				
				ComponentAttribution forkAttr = new ComponentAttribution(
					componentUuid, componentName,
					oldestRelease.getUuid(), oldestRelease.getVersion(),
					branchUuid, getBranchName(branchUuid),
					forkPointRelease.getVersion()
				);
				
				// Track appeared/resolved at branch boundary
				if (forkChanges.appearedVulnerabilities() != null) {
					for (ReleaseMetricsDto.VulnerabilityDto vuln : forkChanges.appearedVulnerabilities()) {
						String key = vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl();
						FindingAttribution<ReleaseMetricsDto.VulnerabilityDto> fa =
							vulnMap.computeIfAbsent(key, k -> new FindingAttribution<>());
						if (fa.finding == null) fa.finding = vuln;
						fa.appearedIn.add(forkAttr);
					}
				}
				if (forkChanges.resolvedVulnerabilities() != null) {
					for (ReleaseMetricsDto.VulnerabilityDto vuln : forkChanges.resolvedVulnerabilities()) {
						String key = vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl();
						FindingAttribution<ReleaseMetricsDto.VulnerabilityDto> fa =
							vulnMap.computeIfAbsent(key, k -> new FindingAttribution<>());
						if (fa.finding == null) fa.finding = vuln;
						fa.resolvedIn.add(forkAttr);
					}
				}
				if (forkChanges.appearedViolations() != null) {
					for (ReleaseMetricsDto.ViolationDto v : forkChanges.appearedViolations()) {
						String key = v.type() + FINDING_KEY_DELIMITER + v.purl();
						FindingAttribution<ReleaseMetricsDto.ViolationDto> fa =
							violationMap.computeIfAbsent(key, k -> new FindingAttribution<>());
						if (fa.finding == null) fa.finding = v;
						fa.appearedIn.add(forkAttr);
					}
				}
				if (forkChanges.resolvedViolations() != null) {
					for (ReleaseMetricsDto.ViolationDto v : forkChanges.resolvedViolations()) {
						String key = v.type() + FINDING_KEY_DELIMITER + v.purl();
						FindingAttribution<ReleaseMetricsDto.ViolationDto> fa =
							violationMap.computeIfAbsent(key, k -> new FindingAttribution<>());
						if (fa.finding == null) fa.finding = v;
						fa.resolvedIn.add(forkAttr);
					}
				}
				if (forkChanges.appearedWeaknesses() != null) {
					for (ReleaseMetricsDto.WeaknessDto w : forkChanges.appearedWeaknesses()) {
						String key = w.cweId() + FINDING_KEY_DELIMITER + w.location();
						FindingAttribution<ReleaseMetricsDto.WeaknessDto> fa =
							weaknessMap.computeIfAbsent(key, k -> new FindingAttribution<>());
						if (fa.finding == null) fa.finding = w;
						fa.appearedIn.add(forkAttr);
					}
				}
				if (forkChanges.resolvedWeaknesses() != null) {
					for (ReleaseMetricsDto.WeaknessDto w : forkChanges.resolvedWeaknesses()) {
						String key = w.cweId() + FINDING_KEY_DELIMITER + w.location();
						FindingAttribution<ReleaseMetricsDto.WeaknessDto> fa =
							weaknessMap.computeIfAbsent(key, k -> new FindingAttribution<>());
						if (fa.finding == null) fa.finding = w;
						fa.resolvedIn.add(forkAttr);
					}
				}
			}
			
			// PHASE 1: Pairwise consecutive comparisons on each branch
			// This captures ALL intermediate appeared/resolved findings, not just the net diff
			for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
				List<ReleaseData> branchReleases = branchEntry.getValue();
				String branchName = getBranchName(branchEntry.getKey());
				if (branchReleases.size() < 2) {
					log.debug("ORG-COMPARE: PHASE1 {}/{}: Only {} release(s), skipping per-branch comparison", componentName, branchName, branchReleases.size());
					continue;
				}
				
				// Releases are sorted newest-first; iterate from oldest to newest (pairwise)
				ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
				ReleaseData newestRelease = branchReleases.get(0);
				log.debug("ORG-COMPARE: PHASE1 {}/{}: Pairwise comparison across {} releases ({} -> {})",
					componentName, branchName, branchReleases.size(), oldestRelease.getVersion(), newestRelease.getVersion());
				
				for (int i = branchReleases.size() - 1; i > 0; i--) {
					ReleaseData olderRelease = branchReleases.get(i);
					ReleaseData newerRelease = branchReleases.get(i - 1);
					
					ReleaseMetricsDto olderMetrics = olderRelease.getMetrics();
					ReleaseMetricsDto newerMetrics = newerRelease.getMetrics();
					
					if (olderMetrics == null || newerMetrics == null) continue;
					
					log.debug("ORG-COMPARE: PHASE1 {}/{}: Comparing {} vs {}",
						componentName, branchName, olderRelease.getVersion(), newerRelease.getVersion());
					
					FindingChangesDto.FindingChangesRecord changes = compareMetrics(olderMetrics, newerMetrics);
					
					if (changes.appearedVulnerabilities() != null) {
						for (var v : changes.appearedVulnerabilities()) {
							log.debug("ORG-COMPARE: PHASE1 {}/{}: APPEARED vuln {} in {} (since {})", componentName, branchName, v.vulnId(), v.purl(), newerRelease.getVersion());
						}
					}
					if (changes.resolvedVulnerabilities() != null) {
						for (var v : changes.resolvedVulnerabilities()) {
							log.debug("ORG-COMPARE: PHASE1 {}/{}: RESOLVED vuln {} in {} (since {})", componentName, branchName, v.vulnId(), v.purl(), newerRelease.getVersion());
						}
					}
					
					ComponentAttribution pairAttr = new ComponentAttribution(
						componentUuid, componentName,
						newerRelease.getUuid(), newerRelease.getVersion(),
						newerRelease.getBranch(), getBranchName(newerRelease.getBranch()),
						olderRelease.getVersion()
					);
					
					// Track appeared vulnerabilities
					if (changes.appearedVulnerabilities() != null) {
						for (ReleaseMetricsDto.VulnerabilityDto vuln : changes.appearedVulnerabilities()) {
							String key = vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl();
							FindingAttribution<ReleaseMetricsDto.VulnerabilityDto> fa = 
								vulnMap.computeIfAbsent(key, k -> new FindingAttribution<>());
							if (fa.finding == null) fa.finding = vuln;
							fa.appearedIn.add(pairAttr);
						}
					}
					
					// Track resolved vulnerabilities
					if (changes.resolvedVulnerabilities() != null) {
						for (ReleaseMetricsDto.VulnerabilityDto vuln : changes.resolvedVulnerabilities()) {
							String key = vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl();
							FindingAttribution<ReleaseMetricsDto.VulnerabilityDto> fa = 
								vulnMap.computeIfAbsent(key, k -> new FindingAttribution<>());
							if (fa.finding == null) fa.finding = vuln;
							fa.resolvedIn.add(pairAttr);
						}
					}
					
					// Track appeared violations
					if (changes.appearedViolations() != null) {
						for (ReleaseMetricsDto.ViolationDto violation : changes.appearedViolations()) {
							String key = violation.type() + FINDING_KEY_DELIMITER + violation.purl();
							FindingAttribution<ReleaseMetricsDto.ViolationDto> fa = 
								violationMap.computeIfAbsent(key, k -> new FindingAttribution<>());
							if (fa.finding == null) fa.finding = violation;
							fa.appearedIn.add(pairAttr);
						}
					}
					
					// Track resolved violations
					if (changes.resolvedViolations() != null) {
						for (ReleaseMetricsDto.ViolationDto violation : changes.resolvedViolations()) {
							String key = violation.type() + FINDING_KEY_DELIMITER + violation.purl();
							FindingAttribution<ReleaseMetricsDto.ViolationDto> fa = 
								violationMap.computeIfAbsent(key, k -> new FindingAttribution<>());
							if (fa.finding == null) fa.finding = violation;
							fa.resolvedIn.add(pairAttr);
						}
					}
					
					// Track appeared weaknesses
					if (changes.appearedWeaknesses() != null) {
						for (ReleaseMetricsDto.WeaknessDto weakness : changes.appearedWeaknesses()) {
							String key = weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location();
							FindingAttribution<ReleaseMetricsDto.WeaknessDto> fa = 
								weaknessMap.computeIfAbsent(key, k -> new FindingAttribution<>());
							if (fa.finding == null) fa.finding = weakness;
							fa.appearedIn.add(pairAttr);
						}
					}
					
					// Track resolved weaknesses
					if (changes.resolvedWeaknesses() != null) {
						for (ReleaseMetricsDto.WeaknessDto weakness : changes.resolvedWeaknesses()) {
							String key = weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location();
							FindingAttribution<ReleaseMetricsDto.WeaknessDto> fa = 
								weaknessMap.computeIfAbsent(key, k -> new FindingAttribution<>());
							if (fa.finding == null) fa.finding = weakness;
							fa.resolvedIn.add(pairAttr);
						}
					}
				}
				
				// Track inherited findings: existed in BOTH oldest AND newest release of this branch
				ReleaseMetricsDto firstMetrics = oldestRelease.getMetrics();
				ReleaseMetricsDto lastMetrics = newestRelease.getMetrics();
				
				if (firstMetrics != null && lastMetrics != null) {
					Set<String> firstVulns = new HashSet<>();
					if (firstMetrics.getVulnerabilityDetails() != null) {
						for (ReleaseMetricsDto.VulnerabilityDto vuln : firstMetrics.getVulnerabilityDetails()) {
							firstVulns.add(vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl());
						}
					}
					if (lastMetrics.getVulnerabilityDetails() != null) {
						for (ReleaseMetricsDto.VulnerabilityDto vuln : lastMetrics.getVulnerabilityDetails()) {
							String key = vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl();
							if (firstVulns.contains(key)) {
								inheritedInComponents.computeIfAbsent(key, k -> new HashSet<>()).add(componentUuid);
							}
						}
					}
					
					Set<String> firstViols = new HashSet<>();
					if (firstMetrics.getViolationDetails() != null) {
						for (ReleaseMetricsDto.ViolationDto v : firstMetrics.getViolationDetails()) {
							firstViols.add(v.type() + FINDING_KEY_DELIMITER + v.purl());
						}
					}
					if (lastMetrics.getViolationDetails() != null) {
						for (ReleaseMetricsDto.ViolationDto v : lastMetrics.getViolationDetails()) {
							String key = v.type() + FINDING_KEY_DELIMITER + v.purl();
							if (firstViols.contains(key)) {
								inheritedInComponents.computeIfAbsent(key, k -> new HashSet<>()).add(componentUuid);
							}
						}
					}
					
					Set<String> firstWeaks = new HashSet<>();
					if (firstMetrics.getWeaknessDetails() != null) {
						for (ReleaseMetricsDto.WeaknessDto w : firstMetrics.getWeaknessDetails()) {
							firstWeaks.add(w.cweId() + FINDING_KEY_DELIMITER + w.location());
						}
					}
					if (lastMetrics.getWeaknessDetails() != null) {
						for (ReleaseMetricsDto.WeaknessDto w : lastMetrics.getWeaknessDetails()) {
							String key = w.cweId() + FINDING_KEY_DELIMITER + w.location();
							if (firstWeaks.contains(key)) {
								inheritedInComponents.computeIfAbsent(key, k -> new HashSet<>()).add(componentUuid);
							}
						}
					}
				}
			}
		}
		
		// Second pass: populate presentIn with latest release data per branch
		log.debug("ORG-COMPARE: PASS2 - Tracking present state from latest releases per branch");
		for (Map.Entry<UUID, List<ReleaseData>> entry : componentReleases.entrySet()) {
			UUID componentUuid = entry.getKey();
			List<ReleaseData> releases = entry.getValue();
			String componentName = componentNames.getOrDefault(componentUuid, "Unknown");
			
			if (releases.isEmpty()) continue;
			
			// Group by branch and check the latest release of each branch
			Map<UUID, List<ReleaseData>> releasesByBranch = releases.stream()
				.collect(Collectors.groupingBy(ReleaseData::getBranch));
			
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.isEmpty()) continue;
				
				// Index 0 = newest (latest) release on this branch
				ReleaseData latestRelease = branchReleases.get(0);
				ReleaseMetricsDto latestMetrics = latestRelease.getMetrics();
				
				if (latestMetrics == null) continue;
				
				log.debug("ORG-COMPARE: PASS2 {}/{} latest={}: {} vulns in metrics",
					componentName, getBranchName(latestRelease.getBranch()), latestRelease.getVersion(),
					latestMetrics.getVulnerabilityDetails() != null ? latestMetrics.getVulnerabilityDetails().size() : 0);
				
				ComponentAttribution latestAttr = new ComponentAttribution(
					componentUuid, componentName,
					latestRelease.getUuid(), latestRelease.getVersion(),
					latestRelease.getBranch(), getBranchName(latestRelease.getBranch()),
					null
				);
				
				if (latestMetrics.getVulnerabilityDetails() != null) {
					for (ReleaseMetricsDto.VulnerabilityDto vuln : latestMetrics.getVulnerabilityDetails()) {
						String key = vuln.vulnId() + FINDING_KEY_DELIMITER + vuln.purl();
						FindingAttribution<ReleaseMetricsDto.VulnerabilityDto> fa = vulnMap.get(key);
						if (fa != null) {
							fa.presentIn.add(latestAttr);
							log.debug("ORG-COMPARE: PASS2 {}/{}/{}: vuln {} PRESENT in latest",
								componentName, getBranchName(latestRelease.getBranch()), latestRelease.getVersion(), vuln.vulnId());
						}
					}
				}
				
				if (latestMetrics.getViolationDetails() != null) {
					for (ReleaseMetricsDto.ViolationDto violation : latestMetrics.getViolationDetails()) {
						String key = violation.type() + FINDING_KEY_DELIMITER + violation.purl();
						FindingAttribution<ReleaseMetricsDto.ViolationDto> fa = violationMap.get(key);
						if (fa != null) {
							fa.presentIn.add(latestAttr);
						}
					}
				}
				
				if (latestMetrics.getWeaknessDetails() != null) {
					for (ReleaseMetricsDto.WeaknessDto weakness : latestMetrics.getWeaknessDetails()) {
						String key = weakness.cweId() + FINDING_KEY_DELIMITER + weakness.location();
						FindingAttribution<ReleaseMetricsDto.WeaknessDto> fa = weaknessMap.get(key);
						if (fa != null) {
							fa.presentIn.add(latestAttr);
						}
					}
				}
			}
		}
		
		// Build final attributed findings with correct flags
		List<VulnerabilityWithAttribution> vulnerabilities = vulnMap.entrySet().stream()
			.map(e -> {
				String key = e.getKey();
				FindingAttribution<ReleaseMetricsDto.VulnerabilityDto> fa = e.getValue();
				
				// Build org-level context sets
				Set<UUID> inheritedComponents = inheritedInComponents.getOrDefault(key, Collections.emptySet());
				Set<UUID> appearedComponents = fa.appearedIn.stream()
					.map(ComponentAttribution::componentUuid)
					.collect(Collectors.toSet());
				Set<UUID> resolvedComponents = fa.resolvedIn.stream()
					.map(ComponentAttribution::componentUuid)
					.collect(Collectors.toSet());
				Set<UUID> presentComponents = fa.presentIn.stream()
					.map(ComponentAttribution::componentUuid)
					.collect(Collectors.toSet());
				
				// isNetAppeared = appeared somewhere AND NOT resolved anywhere
				boolean isNetAppeared = !fa.appearedIn.isEmpty() && fa.resolvedIn.isEmpty();
				
				// isStillPresent = present in any latest release AND not purely new
				boolean isStillPresent = !fa.presentIn.isEmpty() && !isNetAppeared;
				
				// isNetResolved = resolved somewhere AND not present in any latest release
				boolean isNetResolved = !fa.resolvedIn.isEmpty() && fa.presentIn.isEmpty();
				
				int totalComponents = componentReleases.size();
				// Categories are mutually exclusive with priority:
				// fullyResolved > partiallyResolved > inheritedInAll > newToOrg
				boolean isFullyResolved = resolvedComponents.size() > 0 && presentComponents.isEmpty();
				boolean isPartiallyResolved = !isFullyResolved && resolvedComponents.size() > 0 && presentComponents.size() > 0;
				boolean isInheritedInAllComponents = !isFullyResolved && !isPartiallyResolved && inheritedComponents.size() == totalComponents && totalComponents > 1;
				boolean isNewToOrganization = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && inheritedComponents.isEmpty();
				boolean wasPreviouslyReported = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && 
					(inheritedComponents.size() > 0 || presentComponents.stream().anyMatch(c -> !appearedComponents.contains(c)));
				
				log.debug("ORG-COMPARE: FLAGS vuln {}: appearedIn=[{}], resolvedIn=[{}], presentIn=[{}], inherited={} -> category: new={}, partialRes={}, fullyRes={}, inherited={}, prevReported={}",
					fa.finding.vulnId(),
					fa.appearedIn.stream().map(a -> a.componentName() + "/" + a.releaseVersion()).collect(Collectors.joining(", ")),
					fa.resolvedIn.stream().map(a -> a.componentName() + "/" + a.releaseVersion()).collect(Collectors.joining(", ")),
					fa.presentIn.stream().map(a -> a.componentName() + "/" + a.branchName() + "/" + a.releaseVersion()).collect(Collectors.joining(", ")),
					inheritedComponents.size(),
					isNewToOrganization, isPartiallyResolved, isFullyResolved, isInheritedInAllComponents, wasPreviouslyReported);
				
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
				
				return new VulnerabilityWithAttribution(
					fa.finding.vulnId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : "UNKNOWN",
					fa.finding.purl(),
					fa.resolvedIn,
					fa.appearedIn,
					fa.presentIn,
					isNetResolved,
					isNetAppeared,
					isStillPresent,
					orgContext
				);
			})
			.collect(Collectors.toList());
		
		List<ViolationWithAttribution> violations = violationMap.entrySet().stream()
			.map(e -> {
				String key = e.getKey();
				FindingAttribution<ReleaseMetricsDto.ViolationDto> fa = e.getValue();
				
				Set<UUID> inheritedComponents = inheritedInComponents.getOrDefault(key, Collections.emptySet());
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
				
				int totalComponents = componentReleases.size();
				// Categories are mutually exclusive with priority:
				// fullyResolved > partiallyResolved > inheritedInAll > newToOrg
				boolean isFullyResolved = resolvedComponents.size() > 0 && presentComponents.isEmpty();
				boolean isPartiallyResolved = !isFullyResolved && resolvedComponents.size() > 0 && presentComponents.size() > 0;
				boolean isInheritedInAllComponents = !isFullyResolved && !isPartiallyResolved && inheritedComponents.size() == totalComponents && totalComponents > 1;
				boolean isNewToOrganization = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && inheritedComponents.isEmpty();
				boolean wasPreviouslyReported = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && 
					(inheritedComponents.size() > 0 || presentComponents.stream().anyMatch(c -> !appearedComponents.contains(c)));
				
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
				
				return new ViolationWithAttribution(
					fa.finding.type() != null ? fa.finding.type().name() : "UNKNOWN",
					fa.finding.purl(),
					fa.resolvedIn,
					fa.appearedIn,
					fa.presentIn,
					isNetResolved,
					isNetAppeared,
					isStillPresent,
					orgContext
				);
			})
			.collect(Collectors.toList());
		
		List<WeaknessWithAttribution> weaknesses = weaknessMap.entrySet().stream()
			.map(e -> {
				String key = e.getKey();
				FindingAttribution<ReleaseMetricsDto.WeaknessDto> fa = e.getValue();
				
				Set<UUID> inheritedComponents = inheritedInComponents.getOrDefault(key, Collections.emptySet());
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
				
				int totalComponents = componentReleases.size();
				// Categories are mutually exclusive with priority:
				// fullyResolved > partiallyResolved > inheritedInAll > newToOrg
				boolean isFullyResolved = resolvedComponents.size() > 0 && presentComponents.isEmpty();
				boolean isPartiallyResolved = !isFullyResolved && resolvedComponents.size() > 0 && presentComponents.size() > 0;
				boolean isInheritedInAllComponents = !isFullyResolved && !isPartiallyResolved && inheritedComponents.size() == totalComponents && totalComponents > 1;
				boolean isNewToOrganization = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && inheritedComponents.isEmpty();
				boolean wasPreviouslyReported = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 && 
					(inheritedComponents.size() > 0 || presentComponents.stream().anyMatch(c -> !appearedComponents.contains(c)));
				
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
				
				return new WeaknessWithAttribution(
					fa.finding.cweId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : "UNKNOWN",
					fa.finding.location() != null ? fa.finding.location() : "",
					fa.resolvedIn,
					fa.appearedIn,
					fa.presentIn,
					isNetResolved,
					isNetAppeared,
					isStillPresent,
					orgContext
				);
			})
			.collect(Collectors.toList());
		
		int totalAppeared = (int) (vulnerabilities.stream().filter(v -> v.isNetAppeared()).count()
			+ violations.stream().filter(v -> v.isNetAppeared()).count()
			+ weaknesses.stream().filter(w -> w.isNetAppeared()).count());
		
		int totalResolved = (int) (vulnerabilities.stream().filter(v -> v.isNetResolved()).count()
			+ violations.stream().filter(v -> v.isNetResolved()).count()
			+ weaknesses.stream().filter(w -> w.isNetResolved()).count());
		
		log.debug("Org-level finding comparison complete - {} vulnerabilities, {} violations, {} weaknesses", 
			vulnerabilities.size(), violations.size(), weaknesses.size());
		
		return new FindingChangesWithAttribution(
			vulnerabilities, violations, weaknesses, 
			totalAppeared, totalResolved);
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
