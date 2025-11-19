/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.tea.TeaIdentifier;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID organization;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private ComponentType type;
	private ComponentKind kind;
	@JsonProperty(CommonVariables.VERSION_SCHEMA_FIELD)
	private String versionSchema;
	@JsonProperty(CommonVariables.MARKETING_VERSION_SCHEMA_FIELD)
	private String marketingVersionSchema;
	@JsonProperty(CommonVariables.VERSION_TYPE_FIELD)
	private VersionTypeEnum versionType;
	private UUID vcs;
	@JsonProperty(CommonVariables.FEATURE_BRANCH_VERSION_FIELD)
	private String featureBranchVersioning;
	@JsonProperty(CommonVariables.IS_REPOSITORY_ENABLED)
	private Integer repositoryEnabled;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status;
	@JsonProperty(CommonVariables.API_KEY_ID_FIELD)
	private String apiKeyId;
	@JsonProperty(CommonVariables.API_KEY_FIELD)
	private String apiKey;
	@JsonProperty
	private UUID resourceGroup;
	@JsonProperty
	private String defaultConfig;
	@JsonProperty
	private UUID parent;
	@JsonProperty
	private UUID approvalPolicy;
	@JsonProperty
	private List<ReleaseInputEvent> releaseInputTriggers;
	@JsonProperty
	private List<ReleaseOutputEvent> outputTriggers;
	@JsonProperty
	private List<TeaIdentifier> identifiers;
	@JsonProperty
	private String repoPath;
}