/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.dto;

import java.util.List;

import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;

/**
 * Record for tracking finding changes (vulnerabilities, violations, weaknesses) between releases.
 */
public record FindingChangesRecord(
	// Vulnerabilities
	List<VulnerabilityDto> appearedVulnerabilities,
	List<VulnerabilityDto> resolvedVulnerabilities,
	
	// Violations
	List<ViolationDto> appearedViolations,
	List<ViolationDto> resolvedViolations,
	
	// Weaknesses
	List<WeaknessDto> appearedWeaknesses,
	List<WeaknessDto> resolvedWeaknesses
) {
	public static final FindingChangesRecord EMPTY = new FindingChangesRecord(
		List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
}
