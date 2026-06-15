/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

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
import io.reliza.versioning.VersionType;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentData extends RelizaDataParent implements RelizaObject {
	
	public record ComponentAuthentication (String login, String password, RearmCDAuthType type) {}

	/**
	 * A freeform stakeholder contact for someone who is not a registered
	 * ReARM user (e.g. an external owner reachable only by email/Slack handle).
	 * Both fields are operator-supplied free text and are HTML-sanitized via
	 * {@link #sanitizeContacts} before they are persisted, since they are
	 * rendered back into the component/product team UI.
	 */
	public record FreeformContact (String name, String contact) {}

	/**
	 * Returns a sanitized copy of {@code contacts} with each field run through
	 * jsoup {@code Safelist.basic()} (the same safelist the rest of the
	 * component-update path uses for operator-supplied text). Null input
	 * yields null; null individual fields are preserved as null.
	 */
	public static List<FreeformContact> sanitizeContacts (List<FreeformContact> contacts) {
		if (null == contacts) return null;
		return contacts.stream()
				.map(c -> new FreeformContact(
						StringUtils.isEmpty(c.name()) ? c.name() : Jsoup.clean(c.name(), Safelist.basic()),
						StringUtils.isEmpty(c.contact()) ? c.contact() : Jsoup.clean(c.contact(), Safelist.basic())))
				.collect(Collectors.toList());
	}
	
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

	/**
	 * Physical-ness axis, orthogonal to {@link ComponentKind} and
	 * {@link DeviceClass}. Drives manual/declarative releases (hardware has no
	 * CI build), quantity relevance, and physical-attribute applicability.
	 * Defaults to SOFTWARE for back-compat with legacy rows.
	 */
	public enum ComponentNature {
		SOFTWARE,
		HARDWARE;
	}

	/**
	 * Regulatory profile axis. Orthogonal to nature on purpose: SaMD
	 * (software-only medical device) is SOFTWARE + MEDICAL_TRACKED and needs
	 * UDI/GUDID just like an implant. Lives on the component (a component is or
	 * isn't a medical device — that doesn't change version to version);
	 * DI/GUDID specifics are per-release. Defaults to NONE.
	 */
	public enum DeviceClass {
		NONE,
		MEDICAL_UNTRACKED,
		MEDICAL_TRACKED;
	}

	/** UDI issuing agency — assigned at labeler level, stable across versions. */
	public enum IssuingAgency {
		GS1,
		HIBCC,
		ICCBBA;
	}

	/**
	 * Regulatory metadata for a medical-device component. Present iff
	 * {@code deviceClass != NONE}. Stable, component-level fields that releases
	 * inherit; version-specific GUDID descriptors live on the release.
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class MedicalProfile {
		private IssuingAgency issuingAgency;
		/**
		 * Marks this component as the UDI-DI carrier. The UDI-bearing release is
		 * hardware if a hardware component is present in the feature set, else
		 * the SaMD software component. Exactly one per medical feature set
		 * (V1: not enforced; user instruction).
		 */
		private boolean udiBearing;
		/** Stable GUDID descriptors releases inherit and may override. */
		private GudidDefaults gudidDefaults;
	}

	/**
	 * Stable, rarely-changing GUDID descriptors carried at component level and
	 * inherited by releases (which override version-specific ones).
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class GudidDefaults {
		private String brandName;
		private String companyName;
		private String gmdnCode;
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
		// Output events fired when celExpression evaluates true.
		private Set<UUID> outputEvents;
		// Output events fired when celExpression evaluates false. Optional;
		// null/empty preserves the legacy single-branch behavior (CEL false
		// fires nothing). When set, lets a rule express "if X then A else B"
		// without a second mutually-exclusive rule. Both branches are
		// skipped together when the rule is disabled or the component opts
		// out via GlobalInputEventRef.
		private Set<UUID> outputEventsOnFalse;
		private String celExpression;
		// Rule-level enable/disable. Defaults true so pre-existing rules
		// (no field in stored JSON) keep their old behavior on deserialize.
		// When false, the rule is skipped at evaluation time for every
		// component using the policy — overrides any per-component
		// GlobalInputEventRef state. Applies identically to component-
		// local rules under Component.releaseInputTriggers.
		private boolean enabled = true;
		// Optional rule-level precondition CEL. When non-empty, evaluated
		// BEFORE celExpression — if it returns false (or throws), the
		// rule is skipped entirely (neither true nor false branch fires)
		// and the PR snapshot renders PENDING. Separates "is the rule
		// ready to fire at all?" from "did the condition match?".
		// Necessary for rules with outputEventsOnFalse populated because
		// the false branch would otherwise fire on releases with missing
		// data (metrics default to 0; an unscanned release would silently
		// match "no critical vulns" and trigger the else-branch). Empty
		// preserves single-stage evaluation.
		private String preconditionCelExpression;
	}
	
	@Data
	@Builder
	@lombok.NoArgsConstructor
	@lombok.AllArgsConstructor
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

	/** Physical-ness axis. Defaults to SOFTWARE for legacy rows. */
	@JsonProperty
	private ComponentNature nature = ComponentNature.SOFTWARE;
	/** Regulatory axis. Defaults to NONE for legacy rows. */
	@JsonProperty
	private DeviceClass deviceClass = DeviceClass.NONE;
	/** Present iff deviceClass != NONE. */
	@JsonProperty
	private MedicalProfile medicalProfile;
	/**
	 * Used to specify default helm values file
	 */
	@JsonProperty
	private String defaultConfig;
	
	/**
	 * Manually-assigned leads for this component/product (registered-user
	 * UUIDs). The wider Team and Approvers are derived live from permissions
	 * (not stored here); only the hand-picked leads + freeform {@link #contacts}
	 * are persisted on the component. Empty (never null) by default.
	 */
	@JsonProperty
	private Set<UUID> leads = new LinkedHashSet<>();

	/** Freeform stakeholder contacts for non-registered users; sanitized on write. */
	@JsonProperty
	private List<FreeformContact> contacts = new LinkedList<>();

	@JsonProperty
	private VisibilitySetting visibilitySetting = VisibilitySetting.ORG_INTERNAL;
	
	@JsonProperty
	private List<ReleaseInputEvent> releaseInputTriggers;
	
	@JsonProperty
	private List<ReleaseOutputEvent> outputTriggers;
	
	@JsonProperty
	private List<GlobalInputEventRef> globalInputEventRefs;
	
	/**
	 * Unified identifier list: TEA-exportable types (PURL/CPE/TEI) plus
	 * ReARM-internal ones (UDI/UDI-DI/UDI-PI/SERIAL/LOT).
	 */
	@JsonProperty
	private List<RearmIdentifier> identifiers = new LinkedList<>();
	
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

	/**
	 * Cached value of the synthetic {@code effectiveLifecycle} GraphQL field, populated
	 * up-front by the components-list datafetcher via a single batched SQL query (see
	 * {@code ComponentService.effectiveLifecyclesForComponents}). Read by the per-Component
	 * GraphQL sub-field resolver to skip the legacy per-row release lookup that produced
	 * the N+1 (84 components × ~40ms = ~3.5s).
	 *
	 * <p>{@code @JsonIgnore} so neither {@code recordData} JSONB persistence nor the
	 * GraphQL projection (which goes through the resolver, not this field directly)
	 * picks it up. The companion {@link #effectiveLifecycleCached} flag distinguishes
	 * "not cached yet" from "cached, but the component has no releases" — both of which
	 * surface as a null lifecycle.
	 */
	@com.fasterxml.jackson.annotation.JsonIgnore
	private ReleaseLifecycle cachedEffectiveLifecycle;

	@com.fasterxml.jackson.annotation.JsonIgnore
	private boolean effectiveLifecycleCached;

	public List<RearmIdentifier> getIdentifiers () {
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
		if (null != cpd.getNature()) cd.setNature(cpd.getNature());
		if (null != cpd.getDeviceClass()) cd.setDeviceClass(cpd.getDeviceClass());
		if (null != cpd.getMedicalProfile()) cd.setMedicalProfile(cpd.getMedicalProfile());
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
