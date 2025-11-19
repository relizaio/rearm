/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.VisibilitySetting;
import io.reliza.common.Utils;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.versioning.VersionType;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentData extends RelizaDataParent implements RelizaObject {
	
	public enum ComponentType {
		COMPONENT,
		PRODUCT,
		ANY;
	}
	
	public enum ComponentKind {
		HELM,
		GENERIC;
	}
	
	public enum DefaultBranchName {
		MAIN,
		MASTER;
	}
	
	public enum MatchOperator {
		AND,
		OR;
	}
	
	public enum EventType {
		RELEASE_LIFECYCLE_CHANGE,
		MARKETING_RELEASE_LIFECYCLE_CHANGE,
		INTEGRATION_TRIGGER,
		EMAIL_NOTIFICATION;
	}
	
	public enum ConditionType {
		APPROVAL_ENTRY,
		LIFECYCLE,
		BRANCH_TYPE,
		METRICS
	}
	
	public enum ComparisonSign {
		EQUALS,
		GREATER,
		LOWER,
		GREATER_OR_EQUALS,
		LOWER_OR_EQUALS
	}
	
	public enum MetricsType {
    	CRITICAL_VULNS,
    	HIGH_VULNS,
    	MEDIUM_VULNS,
    	LOW_VULNS,
    	UNASSIGNED_VULNS,
    	SECURITY_VIOLATIONS,
    	OPERATIONAL_VIOLATIONS,
    	LICENSE_VIOLATIONS
	}
	
	public static record Condition (ConditionType type,
			UUID approvalEntry,
			ApprovalState approvalState,
			Set<ReleaseLifecycle> possibleLifecycles,
			Set<BranchType> possibleBranchTypes,
			MetricsType metricsType,
			ComparisonSign comparisonSign,
			Integer metricsValue
			) {}
	
	public static record ConditionGroup (MatchOperator matchOperator, List<Condition> conditions, List<ConditionGroup> conditionGroups) {}
	
	public static record InputCondition (String approvalEntry, ApprovalState approvalState) {}
	
	public static record InputConditionGroup (MatchOperator matchOperator, List<InputCondition> conditions) {}
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ReleaseInputEvent {
		private UUID uuid;
		private String name;
		private ConditionGroup conditionGroup;
		private Set<UUID> outputEvents;
	}
	
	@Data
	@Builder
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ReleaseOutputEvent {
		private UUID uuid;
		private String name;
		private EventType type;
		private ReleaseLifecycle toReleaseLifecycle;
		private UUID integration;
		private Set<UUID> users;
		private String notificationMessage;
		private UUID vcs;
		private String schedule;
		private String clientPayload; // i.e. additional GitHub parameters
		private String eventType;
	}
	
	@JsonProperty
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty
	private UUID org;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private ComponentType type;
	@JsonProperty(CommonVariables.DEFAULT_BRANCH_FIELD)
	private DefaultBranchName defaultBranch;
	@JsonProperty(CommonVariables.VERSION_SCHEMA_FIELD)
	private String versionSchema;
	@JsonProperty(CommonVariables.MARKETING_VERSION_SCHEMA_FIELD)
	private String marketingVersionSchema;
	@JsonProperty(CommonVariables.VERSION_TYPE_FIELD)
	private VersionTypeEnum versionType;
	@JsonProperty
	private UUID vcs;
	@JsonProperty(CommonVariables.FEATURE_BRANCH_VERSION_FIELD)
	private String featureBranchVersioning = VersionType.FEATURE_BRANCH.getSchema();
	@JsonProperty(CommonVariables.IS_REPOSITORY_ENABLED)
	private Integer repositoryEnabled;
	@JsonProperty
	private UUID parent;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status = StatusEnum.ACTIVE;
	@JsonProperty
	private UUID approvalPolicy;
	@JsonProperty
	private ComponentKind kind = ComponentKind.GENERIC;
	@JsonProperty
	private UUID resourceGroup = CommonVariables.DEFAULT_RESOURCE_GROUP;
	
	/**
	 * Used to specify default helm values file
	 */
	@JsonProperty
	private String defaultConfig;
	
	// TODO: add project manager and project team
	@JsonProperty
	private VisibilitySetting visibilitySetting = VisibilitySetting.ORG_INTERNAL;
	
	@JsonProperty
	private List<ReleaseInputEvent> releaseInputTriggers;
	
	@JsonProperty
	private List<ReleaseOutputEvent> outputTriggers;
	
	@JsonProperty
	private List<TeaIdentifier> identifiers = new LinkedList<>();
	
	/**
	 * Repository path for monorepo component disambiguation
	 * null = legacy component (no specific path)
	 */
	@JsonProperty
	private String repoPath;
	
	public List<TeaIdentifier> getIdentifiers () {
		return new LinkedList<>(this.identifiers);
	}
	
	public static ComponentData componentDataFactory(CreateComponentDto cpd) {
		ComponentData cd = new ComponentData();
		cd.setName(cpd.getName());
		cd.setOrg(cpd.getOrganization());
		cd.setType(cpd.getType());
		cd.setVersionType(cpd.getVersionType());
		cd.setMarketingVersionSchema(cpd.getMarketingVersionSchema());
		cd.setDefaultBranch(cpd.getDefaultBranch());
		cd.setVersionSchema(cpd.getVersionSchema());
		cd.setFeatureBranchVersioning(cpd.getFeatureBranchVersioning());
		cd.setVcs(cpd.getVcs());
		cd.setParent(cpd.getParent());
		if (null != cpd.getIdentifiers()) cd.setIdentifiers(cpd.getIdentifiers());
		if (null != cpd.getKind()) cd.setKind(cpd.getKind());
		if (null != cpd.getRepoPath()) cd.setRepoPath(cpd.getRepoPath());
		return cd;
	}
	
	public static ComponentData dataFromRecord (Component c) {
		if (c.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Component schema version is " + c.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = c.getRecordData();
		ComponentData cd = Utils.OM.convertValue(recordData, ComponentData.class);
		cd.setUuid(c.getUuid());
		return cd;
	}
	@Override
	public UUID getResourceGroup() {
		return this.resourceGroup;
	}
}
