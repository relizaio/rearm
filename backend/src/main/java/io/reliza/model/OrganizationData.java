/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApprovalRole;
import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.common.CommonVariables.SidPurlMode;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationData extends RelizaDataParent implements RelizaObject {
	
	public static final String DEFAULT_FEATURE_SET_LABEL = "Feature Set";
	public static final int MAX_TERMINOLOGY_LENGTH = 50;
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Terminology {
		@JsonProperty
		private String featureSetLabel = DEFAULT_FEATURE_SET_LABEL;
		
		public static Terminology getDefault() {
			return new Terminology();
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Settings {
		/**
		 * Whether justification is mandatory for vulnerability analysis.
		 * Nullable so that settings patches can distinguish "leave unchanged" (null) from
		 * "explicitly set". Treated as false when null.
		 */
		@JsonProperty
		private Boolean justificationMandatory;

		/**
		 * Controls whether non-base branches append a branch-derived suffix
		 * to semver, four-part, and calver versions.
		 * Nullable; null is treated as APPEND at resolution time.
		 * INHERIT is not a valid value for organization-level settings.
		 */
		@JsonProperty
		@JsonAlias("branchPrefixMode")
		private BranchSuffixMode branchSuffixMode;

		/**
		 * VEX compliance framework to enforce on vulnerability analysis create/update.
		 * Nullable; treated as {@link VexComplianceFramework#NONE} (pure CycloneDX baseline,
		 * no extra validation) when null. Layers on top of {@link #justificationMandatory},
		 * which remains independently enforced for {@code NOT_AFFECTED} regardless of framework.
		 */
		@JsonProperty
		private VexComplianceFramework vexComplianceFramework;

		/** Null is treated as {@link SidPurlMode#DISABLED} at resolution time. */
		@JsonProperty
		private SidPurlMode sidPurlMode;

		/**
		 * Decoded authority segments. First is the authority (domain or registered name);
		 * subsequent are publisher/BU/product-line. Required when ENABLED_STRICT; optional
		 * under ENABLED_FLEXIBLE; must be null/empty when DISABLED.
		 */
		@JsonProperty
		private List<String> sidAuthoritySegments;

		/**
		 * How long notification rows (outbox events, deliveries and their
		 * read marks) are kept before the daily retention sweep deletes
		 * them. Nullable for the patch idiom; resolved via
		 * {@link #getNotificationRetentionDaysOrDefault()}. Bounds enforced
		 * at update time: the minimum keeps comfortably clear of the email
		 * digest's maximum parking window (P7D) plus the delivery retry
		 * curve, so the sweep can never delete a row that is still
		 * scheduled to go out.
		 */
		@JsonProperty
		private Integer notificationRetentionDays;

		public static final int NOTIFICATION_RETENTION_DAYS_DEFAULT = 90;
		public static final int NOTIFICATION_RETENTION_DAYS_MIN = 14;
		public static final int NOTIFICATION_RETENTION_DAYS_MAX = 730;

		/**
		 * How long {@code finding_change_events} rows (the re-scan-driven "Finding changes over time"
		 * diff table, board task #38) are kept before the daily retention sweep deletes them, age-based
		 * on {@code change_date}. Resolved via {@link #isFindingChangeRetentionEnabled()} +
		 * {@link #getFindingChangeRetentionDays()}.
		 *
		 * <p>DEFAULT = FULL HISTORY (no purge). Unset (null) -- the default -- OR a non-positive value
		 * means retention is DISABLED: rows are never purged and the over-time / posture-reconstruction
		 * reads never clamp their lower bound. The Finding Changes history (esp. resolved findings, whose
		 * only record is this table) is the org's remediation track record, so completeness is the
		 * default; a POSITIVE value opts an org into a bounded window (both a shorter row lifetime AND a
		 * shorter changelog lookback) -- only for a tenant that explicitly wants to cap this table's disk.
		 *
		 * <p>The physical purge sweep was retired with the v1/v2 tables (V64); this value now solely bounds
		 * the over-time / posture-reconstruction reads -- {@code FindingComparisonService} clamps the query
		 * lower bound to {@code now - findingChangeRetentionDays} when enabled (a windowed org simply does
		 * not surface older v3 rows; they are retained).
		 */
		@JsonProperty
		private Integer findingChangeRetentionDays;

		/**
		 * Watermark set to the completion instant when a FULL-range {@code finding_change_events} backfill
		 * finishes for this org (board task #38). Null = the org has NOT been seeded, so the posture-diff
		 * read path cannot trust reverse-replay (the event log is incomplete) and MUST fall back to the
		 * legacy pairwise diff. Once set, the always-on live emit keeps the log current, so it stays seeded.
		 * Makes the "backfill-before-read-flag" ordering safe-by-construction rather than operator-procedural.
		 */
		@JsonProperty
		private ZonedDateTime findingChangeBackfillCompletedAt;

		/**
		 * The {@code FindingChangeKind} vocabulary version this org's event log was seeded/reseeded at
		 * (see {@code ChangelogRecords.FINDING_CHANGE_EVENT_VOCAB_VERSION}). Null on orgs seeded before
		 * versioning existed -- treated as version 1. The read gate requires the CURRENT version, so a
		 * vocabulary widening automatically de-certifies seeded orgs until a full-range reseed re-diffs
		 * their history with the new kinds.
		 */
		@JsonProperty
		private Integer findingChangeBackfillVocabVersion;

		/** @return true once a full-range finding_change_events backfill has completed for this org. */
		public boolean isFindingChangeBackfillComplete() {
			return findingChangeBackfillCompletedAt != null;
		}

		/** @return the vocabulary version the org was seeded at; 1 for pre-versioning watermarks. */
		public int getFindingChangeBackfillVocabVersionOrDefault() {
			return findingChangeBackfillVocabVersion != null ? findingChangeBackfillVocabVersion : 1;
		}

		/**
		 * Historical watermark from the v2 ({@code finding_change_events_v2} + {@code finding_dim}) backfill
		 * (board task #38 normalization, Stage 2). The v2 fact table was dropped in V64 (v3 decommission), so
		 * this field is no longer read -- it is RETAINED (not removed) purely so existing orgs' settings JSONB
		 * still deserializes. Superseded by {@link #findingChangeV3BackfillCompletedAt}.
		 */
		@JsonProperty
		private ZonedDateTime findingChangeV2BackfillCompletedAt;

		/**
		 * Historical: the {@code FindingDimKey.KEY_VERSION} the org's v2 dimension was backfilled at. Retained
		 * for settings-JSONB back-compat after the V64 v2 drop; no longer read.
		 */
		@JsonProperty
		private Integer findingChangeV2BackfillKeyVersion;

		/**
		 * Watermark set when the org's BRANCH-GRAIN {@code finding_change_events_v3} (events-lite, fact-row
		 * dedup) backfill completes. v3 is the SOLE finding-change store (v1/v2 dropped in V64). Null = the
		 * org's v3 table is not yet fully populated, so historical windows read whatever v3 rows exist so far
		 * (there is no v1/v2 fallback); the boot backfill + daily repair sweep converge it.
		 */
		@JsonProperty
		private ZonedDateTime findingChangeV3BackfillCompletedAt;

		/**
		 * The {@code FindingDimKey.KEY_VERSION} the org's v3 fact was backfilled at (v3 shares the v2
		 * {@code finding_dim}). Null -> version 1. A re-key de-certifies the org so the v3 backfill re-runs
		 * before its reads are trusted -- the same self-heal pattern as v2.
		 */
		@JsonProperty
		private Integer findingChangeV3BackfillKeyVersion;

		/** @return true once the branch-grain v3 backfill has completed for this org. */
		public boolean isFindingChangeV3BackfillComplete() {
			return findingChangeV3BackfillCompletedAt != null;
		}

		/** @return the dimension key version the org's v3 was backfilled at; 1 for pre-versioning marks. */
		public int getFindingChangeV3BackfillKeyVersionOrDefault() {
			return findingChangeV3BackfillKeyVersion != null ? findingChangeV3BackfillKeyVersion : 1;
		}

		public static Settings getDefault() {
			return new Settings();
		}

		/**
		 * @return the configured retention, or 90 days if unset.
		 */
		public int getNotificationRetentionDaysOrDefault() {
			return notificationRetentionDays != null
					? notificationRetentionDays : NOTIFICATION_RETENTION_DAYS_DEFAULT;
		}

		/**
		 * @return true when the org has opted into a BOUNDED finding-change window (a positive
		 *         {@code findingChangeRetentionDays}). False -- the default -- means FULL history: the
		 *         purge is skipped and reads never clamp. Callers must guard their read of the raw
		 *         {@code getFindingChangeRetentionDays()} value with this (it is null / non-positive when
		 *         disabled). Deliberately NOT a {@code getXxx} name: a getter matching the bean property
		 *         would shadow Lombok's raw accessor and make Jackson serialize a coerced value, erasing
		 *         the null-vs-explicitly-set distinction the patch idiom relies on.
		 */
		public boolean isFindingChangeRetentionEnabled() {
			return findingChangeRetentionDays != null && findingChangeRetentionDays > 0;
		}

		/**
		 * @return the configured framework, or {@link VexComplianceFramework#NONE} if unset.
		 */
		public VexComplianceFramework getVexComplianceFrameworkOrDefault() {
			return vexComplianceFramework != null ? vexComplianceFramework : VexComplianceFramework.NONE;
		}

		/**
		 * @return the configured sid PURL mode, or {@link SidPurlMode#DISABLED} if unset.
		 */
		public SidPurlMode getSidPurlModeOrDefault() {
			return sidPurlMode != null ? sidPurlMode : SidPurlMode.DISABLED;
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class IgnoreViolation {
		@JsonProperty
		private List<String> licenseViolationRegexIgnore = new LinkedList<>();
		@JsonProperty
		private List<String> securityViolationRegexIgnore = new LinkedList<>();
		@JsonProperty
		private List<String> operationalViolationRegexIgnore = new LinkedList<>();
	}
	
	/**
	 * Org-wide PR-validation trigger rule. When a VCS repository in this
	 * org has no per-repo {@code outputTriggers} set, the resolver walks
	 * this list in order and the first rule whose {@code uriPattern}
	 * fully matches the repo's URI contributes its {@link #trigger} as
	 * the effective EXTERNAL_VALIDATION trigger. Per-repo triggers
	 * always win when present. The list itself is the priority order --
	 * earlier rules win over later ones, and the loser names are
	 * surfaced in the per-VCS provenance UI so operators can clean up
	 * accidental overlap.
	 *
	 * SAAS-only feature: the resolver and write paths live in
	 * {@code service.saas.*}. The model field stays in this shared
	 * class so CE doesn't fork the schema; CE rows simply carry an
	 * empty list.
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class GlobalPrValidationTriggerRule {
		/** Display name; unique within the org. */
		@JsonProperty
		private String name;
		/**
		 * Java regex matched against {@code VcsRepositoryData.uri} via
		 * {@code Pattern.matcher(uri).matches()} -- fully anchored, same
		 * convention as {@code DependencyPatternService}. Operators
		 * write {@code github.com/myorg/.*} (no leading {@code ^},
		 * no trailing {@code $}) and the engine anchors automatically.
		 */
		@JsonProperty
		private String uriPattern;
		/**
		 * Trigger spec. Same shape as a per-VCS trigger; must be of
		 * type {@code EXTERNAL_VALIDATION} and reference an existing
		 * GITHUB integration with the {@code PR_VALIDATE} capability.
		 * Validation happens in {@code OrgValidationTriggerService}
		 * on write.
		 */
		@JsonProperty
		private ComponentData.ReleaseOutputEvent trigger;
	}

	/**
	 * Org-wide approval-policy assignment rule. When a component in this
	 * org has no per-component {@code approvalPolicy} set (or its
	 * per-component reference points to an archived/missing policy),
	 * the resolver walks this list in order and the first rule whose
	 * {@code namePattern} fully matches the component's name AND whose
	 * {@code componentType} filter accepts the component's
	 * {@code type} contributes its {@link #approvalPolicy} as the
	 * effective approval policy. Per-component references always win
	 * when the referenced policy still exists.
	 *
	 * The filter {@code componentType} value {@code ANY} matches both
	 * {@code COMPONENT} and {@code PRODUCT} (there is no {@code ANY}-
	 * typed component entity itself -- it's only a rule-side filter
	 * value).
	 *
	 * SAAS-only feature: the resolver and write paths live in
	 * {@code service.saas.*}. The model field stays in this shared
	 * class so CE doesn't fork the schema; CE rows simply carry an
	 * empty list.
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class GlobalApprovalPolicyRule {
		/** Display name; unique within the org. */
		@JsonProperty
		private String name;
		/**
		 * Java regex matched against {@code ComponentData.name} via
		 * {@code Pattern.matcher(name).matches()} -- fully anchored,
		 * same convention as {@code DependencyPatternService}.
		 * Operators write {@code frontend-.*} (no leading {@code ^},
		 * no trailing {@code $}) and the engine anchors automatically.
		 */
		@JsonProperty
		private String namePattern;
		/**
		 * Type filter. {@code COMPONENT} / {@code PRODUCT} restrict
		 * the match; {@code ANY} (the default when null) matches both
		 * concrete types.
		 */
		@JsonProperty
		private ComponentData.ComponentType componentType;
		/**
		 * Referenced approval policy UUID. Validation rejects rules
		 * whose target policy doesn't exist or doesn't belong to this
		 * org at write time; the resolver re-checks at read time and
		 * surfaces a stale marker on the per-component UI.
		 */
		@JsonProperty
		private UUID approvalPolicy;
	}

	public static class InvitedObject {
		@JsonProperty(CommonVariables.SECRET_FIELD)
		private String secret;
		@JsonProperty(CommonVariables.TYPE_FIELD)
		private PermissionType type; // read only, read write
		@JsonProperty(CommonVariables.EMAIL_FIELD)
		private String email;
		@JsonProperty(CommonVariables.WHO_INVITED_FIELD)
		private UUID whoInvited;
		@JsonProperty
		private ZonedDateTime challengeExpiry;
		
		public PermissionType getType () {
			return type;
		}
		
		public String getEmail () {
			return email;
		}
		
		public UUID getWhoInvited () {
			return whoInvited;
		}
		
		public ZonedDateTime getChallengeExpiry() {
			return challengeExpiry;
		}
	}


	@JsonProperty
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.INVITEES_FIELD)
	private Set<InvitedObject> invitees = new LinkedHashSet<>();
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status;
	@JsonProperty
	private List<ApprovalRole> approvalRoles = new LinkedList<>();
	@JsonProperty
	private Terminology terminology;
	@JsonProperty
	private IgnoreViolation ignoreViolation;
	@JsonProperty
	private Settings settings;
	/**
	 * Org-wide PR-validation trigger rules -- see
	 * {@link GlobalPrValidationTriggerRule}. Empty/null means "no
	 * org-wide rules"; per-repo triggers behave exactly as before.
	 * The list itself is the priority order (first match wins).
	 */
	@JsonProperty
	private List<GlobalPrValidationTriggerRule> globalPrValidationTriggerRules = new LinkedList<>();
	/**
	 * Org-wide approval-policy assignment rules -- see
	 * {@link GlobalApprovalPolicyRule}. Empty/null means "no org-wide
	 * assignments"; per-component approvalPolicy references behave
	 * exactly as before. List order is the priority order (first match
	 * wins).
	 */
	@JsonProperty
	private List<GlobalApprovalPolicyRule> globalApprovalPolicyRules = new LinkedList<>();


	public void removeInvitee(String email, UUID whoInvited){
		boolean found = false;
		Iterator<InvitedObject> invIter = invitees.iterator();
		while (!found && invIter.hasNext()) {
			InvitedObject curInv = invIter.next();
			if (email.equalsIgnoreCase(curInv.email)) {
				invitees.remove(curInv);
				found = true;
			}
		}
	}

	public void addInvitee (String secret, PermissionType type, String email, UUID whoInvited) {
		// check if this email (key) is already registered - if so, simply update secret and type
		boolean found = false;
		Iterator<InvitedObject> invIter = invitees.iterator();
		while (!found && invIter.hasNext()) {
			InvitedObject curInv = invIter.next();
			if (email.equalsIgnoreCase(curInv.email)) {
				curInv.secret = secret;
				curInv.type = type;
				curInv.whoInvited = whoInvited;
				curInv.challengeExpiry = ZonedDateTime.now().plusHours(48);
				found = true;
			}
		}
		// otherwise, create new invitee
		if (!found) {
			InvitedObject invObj = new InvitedObject();
			invObj.secret = secret;
			invObj.type = type;
			invObj.email = email;
			invObj.whoInvited = whoInvited;
			invObj.challengeExpiry = ZonedDateTime.now().plusHours(48);
			invitees.add(invObj);
		}
	}
	
	/**
	 * Scans existing invitees by secret and if present, returns permission type and email.
	 * And also deletes this invitee object from organization data
	 * @param secret
	 */
	public Optional<InvitedObject> resolveInvitee (String secret) {
		// TODO proper locking
		
		Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		Optional<InvitedObject> resolvedInvObject = Optional.empty();
		// scan invitees by secret
		Iterator<InvitedObject> invIter = invitees.iterator();
		while (resolvedInvObject.isEmpty() && invIter.hasNext()) {
			InvitedObject curInv = invIter.next();
			if (encoder.matches(secret, curInv.secret)) {
				resolvedInvObject = Optional.of(curInv);
				invIter.remove();
			}
		}
		return resolvedInvObject;
	}
	
	@Override
	@JsonIgnore
	public UUID getOrg() {
		return uuid;
	}
	
	public static OrganizationData orgDataFromDbRecord (Organization org) {
		if (org.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Organization schema version is " + org.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = org.getRecordData();
		OrganizationData od = Utils.OM.convertValue(recordData, OrganizationData.class);
		od.setCreatedDate(org.getCreatedDate());
		od.setUuid(org.getUuid());
		return od;
	}
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
	
	/**
	 * Returns terminology with defaults if not set
	 */
	@JsonIgnore
	public Terminology getTerminologyWithDefaults() {
		return terminology != null ? terminology : Terminology.getDefault();
	}
	
	/**
	 * Returns settings with defaults if not set
	 */
	@JsonIgnore
	public Settings getSettingsWithDefaults() {
		return settings != null ? settings : Settings.getDefault();
	}
	
	/**
	 * Pattern for allowed characters in terminology: ASCII letters, numbers, spaces, hyphens, underscores
	 */
	private static final java.util.regex.Pattern ALLOWED_CHARS = 
		java.util.regex.Pattern.compile("[^a-zA-Z0-9\\s\\-_]");
	
	/**
	 * Sanitizes and validates terminology input.
	 * Only allows ASCII letters (a-z, A-Z), numbers, spaces, hyphens, and underscores.
	 * @param input the raw input string
	 * @return sanitized string or null if invalid/empty
	 */
	public static String sanitizeTerminologyInput(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String sanitized = ALLOWED_CHARS.matcher(input.trim()).replaceAll("")
			.replaceAll("\\s+", " "); // normalize multiple spaces to single
		if (sanitized.length() > MAX_TERMINOLOGY_LENGTH) {
			sanitized = sanitized.substring(0, MAX_TERMINOLOGY_LENGTH).trim();
		}
		return sanitized.isBlank() ? null : sanitized;
	}
	
}