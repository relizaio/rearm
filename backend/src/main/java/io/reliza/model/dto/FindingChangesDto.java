/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;

/**
 * DTOs for tracking finding changes (vulnerabilities, violations, weaknesses) between releases
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FindingChangesDto {

	public record FindingChangesRecord(
		// Vulnerabilities - full tracking (appeared, resolved, severity changed)
		List<VulnerabilityDto> appearedVulnerabilities,
		List<VulnerabilityDto> resolvedVulnerabilities,
		List<VulnerabilityDto> severityChangedVulnerabilities,
		
		// Violations - appeared/resolved only (no severity)
		List<ViolationDto> appearedViolations,
		List<ViolationDto> resolvedViolations,
		
		// Weaknesses - full tracking (appeared, resolved, severity changed)
		List<WeaknessDto> appearedWeaknesses,
		List<WeaknessDto> resolvedWeaknesses,
		List<WeaknessDto> severityChangedWeaknesses,
		
		FindingChangesSummary summary
	) {}

	public record FindingChangesSummary(
		// Per-type counts
		int appearedVulnerabilitiesCount,
		int resolvedVulnerabilitiesCount,
		int severityChangedVulnerabilitiesCount,
		
		int appearedViolationsCount,
		int resolvedViolationsCount,
		
		int appearedWeaknessesCount,
		int resolvedWeaknessesCount,
		int severityChangedWeaknessesCount,
		
		// Overall totals
		int totalAppearedCount,
		int totalResolvedCount,
		int netChange
	) {}
}
