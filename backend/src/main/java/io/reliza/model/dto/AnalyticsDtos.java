/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

import io.reliza.model.ComponentData.ComponentType;

public class AnalyticsDtos {
	private AnalyticsDtos() {}

	public static record ReleasesPerComponent(UUID componentuuid, String componentname,
			ComponentType componenttype, Long rlzcount) {}
	
	public static record ReleasesPerBranch(UUID componentuuid, String componentname, UUID branchuuid,
			String branchname, ComponentType componenttype, Long rlzcount) {}

	public static record MostVulnerableComponent(UUID componentuuid, String componentname,
			ComponentType componenttype, ReleaseMetricsDto metrics) {}
	
	public static record ActiveComponentsInput(UUID organization, ZonedDateTime cutOffDate,
			ComponentType componentType, Integer maxComponents) {}
	
	public static record VulnViolationsChartDto(ZonedDateTime createdDate, Integer num, String type) {}
	
	
	public record XYData(String x, Long y) {}
	
	public record VegaDateValue(String date, Long num) {}
	
}
