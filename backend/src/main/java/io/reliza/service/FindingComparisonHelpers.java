/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import io.reliza.model.dto.ReleaseMetricsDto;

/**
 * Helper records for finding comparison operations
 */
public class FindingComparisonHelpers {
	
	/**
	 * Helper record for vulnerability comparison results
	 */
	public record VulnComparison(
		List<ReleaseMetricsDto.VulnerabilityDto> appeared,
		List<ReleaseMetricsDto.VulnerabilityDto> resolved,
		List<ReleaseMetricsDto.VulnerabilityDto> severityChanged
	) {}
	
	/**
	 * Helper record for violation comparison results
	 */
	public record ViolationComparison(
		List<ReleaseMetricsDto.ViolationDto> appeared,
		List<ReleaseMetricsDto.ViolationDto> resolved
	) {}
	
	/**
	 * Helper record for weakness comparison results
	 */
	public record WeaknessComparison(
		List<ReleaseMetricsDto.WeaknessDto> appeared,
		List<ReleaseMetricsDto.WeaknessDto> resolved,
		List<ReleaseMetricsDto.WeaknessDto> severityChanged
	) {}
}
