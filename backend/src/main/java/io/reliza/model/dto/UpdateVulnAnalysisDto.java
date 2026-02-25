/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import lombok.Data;

@Data
public class UpdateVulnAnalysisDto {
	
	@JsonProperty("analysisUuid")
	private UUID analysisUuid;
	
	@JsonProperty("state")
	private AnalysisState state;
	
	@JsonProperty("justification")
	private AnalysisJustification justification;
	
	@JsonProperty("details")
	private String details;
	
	@JsonProperty("findingAliases")
	private List<String> findingAliases;
	
	@JsonProperty("severity")
	private VulnerabilitySeverity severity;
}
