/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.BranchData.DependencyPattern;
import io.reliza.model.BranchData.FindingAnalyticsParticipation;
import io.reliza.model.BranchData.PullRequestData;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.VERSION_SCHEMA_FIELD)
	private String versionSchema;
	@JsonProperty(CommonVariables.MARKETING_VERSION_SCHEMA_FIELD)
	private String marketingVersionSchema;
	private UUID vcs;
	private String vcsBranch;
	private String metadata;
	private List<ChildComponent> dependencies;
	private List<DependencyPattern> dependencyPatterns;
	private AutoIntegrateState autoIntegrate;
	private BranchType type;
	private Map<Integer, PullRequestData> pullRequestData;
	@JsonProperty("findingAnalyticsParticipation")
	private FindingAnalyticsParticipation findingAnalyticsParticipation;
}