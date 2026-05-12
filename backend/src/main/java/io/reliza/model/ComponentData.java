/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.common.CommonVariables.SidPurlOverride;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.VisibilitySetting;
import io.reliza.common.Utils;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.DeliverableData.BelongsToOrganization;
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
	
	public record ComponentAuthentication (String login, String password, RearmCDAuthType type) {}
	
	public enum RearmCDAuthType {
		NOCREDS,
		CREDS,
		ECR;
	}
	
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
		EMAIL_NOTIFICATION,
		VDR_SNAPSHOT_ARTIFACT,
		ADD_APPROVED_ENVIRONMENT,
		// EXTERNAL_VALIDATION fires a check-run-style verdict to an
		// external SCM. Currently routed to GITHUB integrations with the
		// PR_VALIDATE capability (POSTs to /repos/{owner}/{repo}/check-runs);
		// the dispatch is integration-type-aware so additional SCMs can
		// slot in later.
		// Output event fields used:
		//   integration  → IntegrationData UUID (GITHUB type + PR_VALIDATE capability)
		//   eventType    → conclusion (SUCCESS / FAILURE / NEUTRAL / SKIPPED / CANCELLED)
		//   schedule     → installation ID
		//   vcs          → VCS repository UUID (resolves to owner/repo)
		//   clientPayload / celClientPayload → JSON {title, summary, text}
		EXTERNAL_VALIDATION,
		// VALIDATE_PR is an internal (non-integration) signal: a release
		// that finished validating asks the PR aggregator to (re-)compute
		// PR-level state for any open PR whose head commit equals this
		// release's source code entry. Dispatch to the SCM happens from
		// the aggregator, not from this trigger directly — the integration
		// field is unused.
		VALIDATE_PR,
		// INVALIDATE_PR is the failure-side companion to VALIDATE_PR —
		// fired on disapproval / rejection input events. Records a
		// release_validation_event with state FAILURE on every open PR
		// whose commits include this release's SCE. Same dispatch path
		// as VALIDATE_PR, no integration field used.
		INVALIDATE_PR,
		// PR_COMMENT posts a per-release comment to every open PR whose
		// commits[] includes this release's SCE. Independent from the
		// PR-level check-run aggregation: one comment per (release, PR,
		// fire) — never edited in place. Routed through the same
		// GITHUB integration the EXTERNAL_VALIDATION trigger uses
		// (App credentials with PR_VALIDATE capability), but POSTs to
		// issues/comments rather than check-runs.
		// Output event fields used:
		//   integration  → IntegrationData UUID (GITHUB type + PR_VALIDATE capability)
		//   clientPayload / celClientPayload → markdown string appended
		//     to the auto-generated body (additive, not replacement —
		//     contrast with EXTERNAL_VALIDATION)
		PR_COMMENT;
	}
	
	public enum EventScope {
		LOCAL,
		GLOBAL;
	}
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class GlobalInputEventRef {
		private UUID uuid;
		// Opt-out flag (semantics inverted from earlier opt-in design): every
		// rule on a component's effective approval policy fires by default;
		// presence of a ref with disabled=true suppresses just that rule.
		// Refs may also carry per-rule output overrides via the two fields
		// below — those still apply only when disabled is false.
		private boolean disabled;
		private boolean overrideOutputEventsLocally;
		private Set<UUID> outputEventsOverride;
	}
	
	/**
	 * Component-level lifecycle / metadata change scope. Drives both the
	 * audit history on a component and the CLE event derivation: NAME
	 * changes surface as componentRenamed events at the TEA layer.
	 */
	public enum ComponentUpdateScope {
		NAME,
		LIFECYCLE,
		// reserved for follow-up work (CLE supersededBy event, etc.)
		SUPERSEDED_BY
	}

	public enum ComponentUpdateAction {
		ADDED,
		REMOVED,
		CHANGED
	}

	/**
	 * Mirrors {@code ReleaseData.ReleaseUpdateEvent} but scoped to changes
	 * that apply to the component as a whole (renames being the primary
	 * driver in v1). Stored as part of {@code recordData} jsonb on the
	 * component row, so adding new entries needs no schema migration.
	 */
	public record ComponentUpdateEvent(
			ComponentUpdateScope cus,
			ComponentUpdateAction cua,
			String oldValue,
			String newValue,
			ZonedDateTime date,
			WhoUpdated wu) {}

	public enum ConditionType {
		APPROVAL_ENTRY,
		LIFECYCLE,
		BRANCH_TYPE,
		METRICS,
		FIRST_SCANNED
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
			Integer metricsValue,
			Boolean firstScannedPresent
			) {}
	
	public static record ConditionGroup (MatchOperator matchOperator, List<Condition> conditions, List<ConditionGroup> conditionGroups) {}
	
	public static record InputCondition (String approvalEntry, ApprovalState approvalState) {}
	
	public static record InputConditionGroup (MatchOperator matchOperator, List<InputCondition> conditions) {}
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ReleaseInputEvent {
		private UUID uuid;
		private String name;
		private Set<UUID> outputEvents;
		private String celExpression;
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
		private String celClientPayload; // CEL string expression; overrides clientPayload (INTEGRATION_TRIGGER) or notificationMessage (EMAIL_NOTIFICATION)
		private String eventType;
		private Boolean includeSuppressed;
		// VDR_SNAPSHOT_ARTIFACT: explicit snapshot context (replaces old conditionGroup-based detection)
		private UUID snapshotApprovalEntry;      // if set → APPROVAL-type snapshot for this approval entry
		private ReleaseLifecycle snapshotLifecycle; // if set → LIFECYCLE-type snapshot
		// ADD_APPROVED_ENVIRONMENT: environment string to add to release.approvedEnvironments when fired
		private String approvedEnvironment;
		// EXTERNAL_VALIDATION: override the check-run name reported to the SCM.
		// Defaults to `rearm/<componentName>` when null/empty (legacy behaviour).
		// Use case: GitHub branch protection's "Required status checks" list
		// matches by exact string — wildcards are not supported — so an
		// org-wide policy that wants a single required-check entry covering
		// many components has to opt every component's output event into the
		// same unified name (e.g. `rearm/policy`).
		private String checkName;
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
	private List<GlobalInputEventRef> globalInputEventRefs;
	
	@JsonProperty
	private List<TeaIdentifier> identifiers = new LinkedList<>();
	
	/**
	 * Repository path for monorepo component disambiguation
	 * null = legacy component (no specific path)
	 */
	@JsonProperty
	private String repoPath;
	
	@JsonProperty
	private Set<UUID> perspectives;
	
	@JsonProperty
	private ComponentAuthentication authentication;

	/**
	 * Audit + CLE-source history for component-as-a-whole changes. Renames
	 * land here as NAME/CHANGED events; the CLE emitter walks this list
	 * to surface componentRenamed events at the TEA layer.
	 */
	@JsonProperty
	private List<ComponentUpdateEvent> updateEvents = new LinkedList<>();

	public void addUpdateEvent(ComponentUpdateEvent event) {
		if (this.updateEvents == null) this.updateEvents = new LinkedList<>();
		this.updateEvents.add(event);
	}

	/**
	 * Component-level override for branch suffix mode. Nullable.
	 * null or INHERIT means "inherit from organization setting".
	 */
	@JsonProperty
	@JsonAlias("branchPrefixMode")
	private BranchSuffixMode branchSuffixMode;

	/** Component-level sid override. Null/INHERIT defers to perspective or org. */
	@JsonProperty
	private SidPurlOverride sidPurlOverride;

	/** Component-level authority segments. Take effect only when sid is enabled at this or a higher level. */
	@JsonProperty
	private List<String> sidAuthoritySegments;

	/**
	 * Internal/external classification. EXTERNAL components never receive a
	 * platform-stamped sid PURL — that would falsify a vendor claim on third-party software.
	 * Nullable for back-compat with legacy rows; use {@link #getIsInternalOrDefault()} to read.
	 */
	@JsonProperty
	private BelongsToOrganization isInternal;

	public List<TeaIdentifier> getIdentifiers () {
		return new LinkedList<>(this.identifiers);
	}

	/** Treats null as INTERNAL for back-compat with legacy rows. */
	public BelongsToOrganization getIsInternalOrDefault() {
		return isInternal != null ? isInternal : BelongsToOrganization.INTERNAL;
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
		if (null != cpd.getBranchSuffixMode()) cd.setBranchSuffixMode(cpd.getBranchSuffixMode());
		if (null != cpd.getSidPurlOverride()) {
			// INHERIT clears the override (defers to higher level) — store as null for cleaner record
			cd.setSidPurlOverride(cpd.getSidPurlOverride() == SidPurlOverride.INHERIT
					? null : cpd.getSidPurlOverride());
		}
		if (null != cpd.getSidAuthoritySegments()) cd.setSidAuthoritySegments(cpd.getSidAuthoritySegments());
		// isInternal defaults to INTERNAL on create when the caller doesn't supply it,
		// so today's behavior is preserved for clients that don't know about the field.
		cd.setIsInternal(cpd.getIsInternal() != null ? cpd.getIsInternal() : BelongsToOrganization.INTERNAL);
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
