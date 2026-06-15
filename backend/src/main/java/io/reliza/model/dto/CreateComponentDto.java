/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.common.CommonVariables.SidPurlOverride;
import io.reliza.model.ComponentData.DefaultBranchName;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentNature;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.DeviceClass;
import io.reliza.model.ComponentData.MedicalProfile;
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
	
	private List<RearmIdentifier> identifiers;

	/** Distribution module: physical-ness, regulatory class, and device profiles. */
	private ComponentNature nature;
	private DeviceClass deviceClass;
	private MedicalProfile medicalProfile;

	private CreateVcsRepositoryDto vcsRepository; // to set repository
	
	private Boolean includeApi; // to create api key right away
	
	private String repoPath; // Repository path for monorepo component disambiguation

	/**
	 * Optional branch suffix mode override for this component.
	 * Null or INHERIT = inherit from organization setting.
	 */
	private BranchSuffixMode branchSuffixMode;

	/** Null/INHERIT defers to higher level. */
	private SidPurlOverride sidPurlOverride;

	/** Authority segments. May be set without enabling — only take effect when an upper level enables. */
	private List<String> sidAuthoritySegments;

	/** INTERNAL/EXTERNAL classification. Server defaults null to INTERNAL on create. */
	private BelongsToOrganization isInternal;
}