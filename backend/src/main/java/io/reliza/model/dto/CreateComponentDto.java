/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.ComponentData.DefaultBranchName;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateComponentDto {
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID organization;
	private UUID resourceGroup;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private ComponentType type;
	private ComponentKind kind;
	
	private DefaultBranchName defaultBranch;
	
	@JsonProperty(CommonVariables.VERSION_SCHEMA_FIELD)
	private String versionSchema;
	@JsonProperty(CommonVariables.MARKETING_VERSION_SCHEMA_FIELD)
	private String marketingVersionSchema;
	@JsonProperty(CommonVariables.VERSION_TYPE_FIELD)
	private VersionTypeEnum versionType;
	@JsonProperty(CommonVariables.FEATURE_BRANCH_VERSION_FIELD)
	private String featureBranchVersioning;	
	private UUID vcs;
	private UUID parent;
	private UUID approvalPolicy;
	
	private List<TeaIdentifier> identifiers;
	
	private CreateVcsRepositoryDto vcsRepository; // to set repository
	
	private Boolean includeApi; // to create api key right away
	
	private String repoPath; // Repository path for monorepo component disambiguation
}