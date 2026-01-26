/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import io.reliza.model.dto.FindingChangesDto;
import io.reliza.model.dto.ReleaseMetricsDto;
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
public class FindingComparisonService {
	
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
			return emptyFindingChanges();
		}
		
		return compareFindingMetrics(metrics1, metrics2);
	}
	
	/**
	 * Core comparison logic for finding metrics (internal implementation)
	 */
	private FindingChangesDto.FindingChangesRecord compareFindingMetrics(
			ReleaseMetricsDto metrics1,
			ReleaseMetricsDto metrics2) {
		
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
	
}
