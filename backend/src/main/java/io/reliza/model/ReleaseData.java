/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.common.EnvironmentType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import io.reliza.common.ValidationResult;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.versioning.Version;
import io.reliza.versioning.VersionUtils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Release is essentially a collection of release entries
 * all concrete details about items in the release are found inside release entries
 * Release itself contains mainly meta data: what is project or product for which release is built,
 * what's overall version, who is responsible
 * @author pavel
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseData extends RelizaDataParent implements RelizaObject, GenericReleaseData {
	
	public enum ReleaseUpdateScope {
		RELEASE_CREATED,
		LIFECYCLE,
		SOURCE_CODE_ENTRY,
		PARENT_RELEASE,
		ARTIFACT,
		INBOUND_DELIVERY,
		OUTBOUND_DELIVERY,
		VARIANT,
		VERSION,
		NOTES,
		TAGS,
		MARKETING_VERSION,
		TRIGGER,
		INPUT_TRIGGER,
		APPROVED_ENVIRONMENT
	}
	
	public enum ReleaseUpdateAction {
		ADDED,
		REMOVED,
		CHANGED
	}
	
	public record ReleaseUpdateEvent (ReleaseUpdateScope rus, ReleaseUpdateAction rua, String oldValue,
			String newValue, UUID objectId, String message, ZonedDateTime date, WhoUpdated wu) {
		// 7-arg ctor for back-compat with the many existing callsites
		// that don't carry a reason string. Delegates to the canonical
		// 8-arg form with message = null.
		public ReleaseUpdateEvent (ReleaseUpdateScope rus, ReleaseUpdateAction rua, String oldValue,
				String newValue, UUID objectId, ZonedDateTime date, WhoUpdated wu) {
			this(rus, rua, oldValue, newValue, objectId, null, date, wu);
		}
	}
	
	public record ReleaseLifecycleEvent (ReleaseLifecycle oldLifecycle, ReleaseLifecycle newLifecycle, ZonedDateTime date, WhoUpdated wu) {}
	
	public record ReleaseApprovalEvent (UUID approvalEntry, String approvalRoleId, ApprovalState state, ZonedDateTime date, WhoUpdated wu, String comment) {}

	public record ReleaseApprovalInput (UUID approvalEntry, String approvalRoleId, ApprovalState state, String comment) {}

	public record ReleaseApprovalProgrammaticInputEntry (String approvalEntry, String approvalRoleId, ApprovalState state, String comment) {}

	public record ReleaseApprovalProgrammaticInput (List<ReleaseApprovalProgrammaticInputEntry> approvals,
			UUID release, UUID component, String version) {}

	public static ReleaseApprovalEvent approvalEventFromInput (ReleaseApprovalInput rai, WhoUpdated wu) {
		return new ReleaseApprovalEvent(rai.approvalEntry(), rai.approvalRoleId(), rai.state(), ZonedDateTime.now(), wu, rai.comment());
	}
	
	public enum ReleaseStatus {
		ACTIVE,
		ARCHIVED
	}
	
	public enum ReleaseLifecycle {
		// IMPORTANT: Order matters here - PS - 2026-03-06
		CANCELLED,
		REJECTED,
	    PENDING,
	    DRAFT,
	    ASSEMBLED,
		READY_TO_SHIP,
	    GENERAL_AVAILABILITY, // CLE - released
		END_OF_MARKETING, // cle - endOfMarketing
		END_OF_DISTRIBUTION, // cle - endOfDistribution
	    END_OF_SUPPORT, // cle - endOfSupport
		END_OF_LIFE // cle - endOfLife
		;

		public static boolean isAssemblyAllowed(ReleaseLifecycle rl) {
			boolean isAllowed = false;
			if (rl == PENDING || rl == DRAFT) isAllowed = true;
			return isAllowed;
		}

		/**
		 * Lifecycles for which finding-change events are NOT emitted, because the release's metrics
		 * are not a statement about shipped software.
		 *
		 * <p>CANCELLED / REJECTED never assembled, so their metrics may be half-finished.
		 *
		 * <p><b>PENDING is deliberately NOT in this set</b>, though an earlier revision of this change
		 * added it on the theory that a rebuild's churn through PENDING is build noise. That theory was
		 * wrong twice over. First, the churn it aimed at is the collapse-to-zero that findings
		 * carry-forward now removes AT SOURCE, so suppressing here is redundant. Second, and worse,
		 * PENDING is where a brand-new release's FIRST scan usually lands -- CI creates the release as
		 * PENDING and Dependency-Track returns minutes later, still PENDING -- so suppressing drops the
		 * genuine first-APPEARED for the release's whole finding set. Nothing re-emits when it settles:
		 * the lifecycle flip recomputes, but the metrics are unchanged, so {@code saveReleaseMetrics}
		 * never fires and no emit is scheduled. The sweep then labels the hole EMIT_SKIPPED_LIFECYCLE,
		 * which reads as benign, so the missing events would not even show up in the cause histogram.
		 * Suppressing a transient state must never be paid for in lost real events.
		 *
		 * <p><b>This lives here because the rule has FOUR consumers</b> -- the live emitter, the
		 * repair sweep's predecessor selection, its repair-cause classification, and its
		 * repair-detail guard -- and it has already drifted once, when one site omitted the
		 * lifecycle arm and a benign skip was reported to the operator as a lost write. Add a
		 * lifecycle here, not at the call sites, and keep the operator-facing text in
		 * {@code FindingChangeEventBackfillService} in step with it.
		 *
		 * <p>Deliberately NOT the same set as {@code isScannableLifecycle} (>= ASSEMBLED), which
		 * additionally excludes DRAFT and PENDING. Both keep emitting: DRAFT is a deliberate user state
		 * that can hold real scanned BOMs indefinitely, and PENDING carries first scans (above).
		 */
		public static boolean isFindingChangeEmitSuppressed(ReleaseLifecycle rl) {
			return rl == CANCELLED || rl == REJECTED;
		}

		/**
		 * True once a release has reached {@link #END_OF_SUPPORT} or later (currently END_OF_SUPPORT,
		 * END_OF_LIFE, and any future terminal state added after them). Such releases are retired and
		 * are hidden from finding-change DISPLAY surfaces: the findings-over-time CHART
		 * ({@code AnalyticsMetricsService}) today, and the changelog posture endpoints in a follow-up
		 * (keyed on current lifecycle, so a retired branch tip drops from posture rather than showing a
		 * phantom "resolved"). Exclusion is display-side ONLY: finding-change emit, the v3 backfill, and
		 * the dedup predecessor-inheritance path are all left untouched, so a retired release keeps its
		 * event history (and a child branch keeps what it legitimately inherited) -- retirement hides a
		 * release, it does not erase it or disturb the dedup engine. Ordinal comparison is deliberate --
		 * the enum order is load-bearing (see the note above). END_OF_MARKETING / END_OF_DISTRIBUTION
		 * are NOT retired (still supported) and keep showing.
		 */
		public static boolean isSupportEnded(ReleaseLifecycle rl) {
			return rl != null && rl.ordinal() >= END_OF_SUPPORT.ordinal();
		}
	}

	public enum UpdateReleaseStrength {
		DRAFT_ONLY,
		DRAFT_PENDING,
		FULL
	}
	
	
	@Getter(AccessLevel.PUBLIC)
	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;
	
	@JsonProperty(CommonVariables.VERSION_FIELD)
	private String version;
	
	@JsonProperty(CommonVariables.MARKETING_VERSION_FIELD)
	private String marketingVersion;
	
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private ReleaseStatus status;
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org = null; // for releases

	@JsonProperty(CommonVariables.COMPONENT_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private UUID component = null; // component parent - data denormalization, branch is still prevalent
	
	@JsonProperty(CommonVariables.BRANCH_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private UUID branch = null; // project parent

	/**
	 * Re-parent this release under another component/branch. Exists SOLELY for
	 * the duplicate-component repair (ComponentDuplicateRepairService), which
	 * folds a duplicate's releases under the surviving leader: component and
	 * branch are identity-adjacent and deliberately keep private setters, so
	 * ordinary mutation paths cannot move a release between components.
	 */
	public void repointToComponentBranch(UUID componentUuid, UUID branchUuid) {
		this.component = componentUuid;
		this.branch = branchUuid;
	}

	@JsonProperty(CommonVariables.PARENT_RELEASES_FIELD)
	private List<ParentRelease> parentReleases = new LinkedList<>();
	
	@JsonProperty(CommonVariables.SOURCE_CODE_ENTRY_FIELD)
	private UUID sourceCodeEntry = null; // this is like export source code entry, for now we allow only one 
	// own source code entry per releases - hopefully that would be enough
	
	@JsonProperty(CommonVariables.ARTIFACTS_FIELD)
	private List<UUID> artifacts = new LinkedList<>();
	
	@JsonProperty
	private List<UUID> inboundDeliverables = new LinkedList<>();
	
	public List<UUID> getInboundDeliverables () {
		return new LinkedList<>(this.inboundDeliverables);
	}
	
	/**
	 * Unified identifier list (TEA-exportable PURL/CPE/TEI plus ReARM-internal
	 * UDI/UDI-DI/UDI-PI/SERIAL/LOT). On a UDI-bearing release the UDI-DI is
	 * minted per version/model; the software-version PI element also lives
	 * here. Per-shipment PI (lot/serial/expiry) lives on ShippedProduct.
	 */
	@JsonProperty
	private List<RearmIdentifier> identifiers = new LinkedList<>();

	public List<RearmIdentifier> getIdentifiers () {
		return new LinkedList<>(this.identifiers);
	}

	/** GUDID submission workflow state for a UDI-bearing release's DI. */
	public enum GudidStatus {
		NOT_SUBMITTED,
		SUBMITTED,
		PUBLISHED;
	}

	/**
	 * Version-specific GUDID descriptors for a UDI-bearing release, keyed by the
	 * release's UDI-DI. Overrides the stable defaults inherited from
	 * {@code ComponentData.MedicalProfile.gudidDefaults}. The AccessGUDID link is
	 * computed from the DI, never stored.
	 */
	@lombok.Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class GudidRecord {
		private String brandName;
		private String versionModel;
		private String gmdnCode;
		private Boolean sterile;
		private Boolean singleUse;
		private Boolean mriSafety;
	}

	/** Version-specific GUDID record (UDI-bearing release only). */
	@JsonProperty
	private GudidRecord gudidRecord;

	/** GUDID submission workflow state. */
	@JsonProperty
	private GudidStatus gudidStatus;

	/** End-of-support date (§524B cyber-device support window). */
	@JsonProperty
	private java.time.LocalDate eos;

	/** End-of-life date (§524B cyber-device support window). */
	@JsonProperty
	private java.time.LocalDate eol;

	/**
	 * Component name captured at first sid emission. Immutable thereafter — a later
	 * component rename does not retroactively edit historical releases. Null when sid
	 * was never emitted for this release.
	 */
	@JsonProperty
	private String sidComponentName;

	/**
	 * Preferred BOM root identifier: sid PURL > any other PURL > release UUID.
	 * Always non-null. Used by VDR / OBOM / aggregated SBOM / DTrack at consumption time.
	 */
	@JsonIgnore
	public String getPreferredBomIdentifier() {
		return SidPurlUtils.pickPreferredPurl(this.identifiers)
				.map(RearmIdentifier::getIdValue)
				.orElseGet(() -> this.uuid != null ? this.uuid.toString() : null);
	}

	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes = null;
	
	/**	
	 * Optional test endpoint for this release, in the future can consider array of endpoints, also endpoints for services
	 * https://github.com/CycloneDX/specification/issues/22
	 */
	@JsonProperty(CommonVariables.ENDPOINT_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private URI endpoint;
	
	/** 
	 * list of commits associated with release
	 * i.e. obtained via git diff between last build and this one
	 * except for the commit associated with primary sourceCodeEntry
	 * for list of all commits refer getAllCommits()
	*/
	@JsonProperty(CommonVariables.COMMITS_FIELD)
	private List<UUID> commits;

	@JsonProperty(CommonVariables.TICKETS_FIELD)
	private Set<UUID> tickets = new LinkedHashSet<>();
	
	@JsonProperty(CommonVariables.TAGS_FIELD)
	private List<TagRecord> tags = new LinkedList<>();
	
	private ReleaseLifecycle lifecycle = ReleaseLifecycle.PENDING;
	
	@JsonProperty
	private Set<EnvironmentType> approvedEnvironments = new LinkedHashSet<>();
	
	@JsonIgnore
	private List<ReleaseApprovalEvent> approvalEvents = new LinkedList<>();

	@JsonIgnore
	private List<ReleaseUpdateEvent> updateEvents = new LinkedList<>();

	/**
	 * An explicit "please approve this release" request. Unlike
	 * {@link #approvalEvents} (dedicated column), these live in plain
	 * record_data — low cardinality, read together with the release.
	 * {@code resolvedAt} is stamped once every requested entry reaches a
	 * terminal state (satisfied or disapproved); null = still open.
	 */
	public record ReleaseApprovalRequest (UUID uuid, UUID requestedBy, List<UUID> approvalEntries,
			ZonedDateTime requestedAt, ZonedDateTime resolvedAt) {
		public ReleaseApprovalRequest {
			// Compact ctor so a JSONB read of an older/partial shape
			// normalizes the list — secondary ctors don't run on
			// Jackson record deserialization.
			if (null == approvalEntries) approvalEntries = List.of();
		}
	}

	private List<ReleaseApprovalRequest> approvalRequests = new LinkedList<>();

	public void addApprovalEvent (ReleaseApprovalEvent rae) {
		this.approvalEvents.add(rae);
	}

	public void addUpdateEvent (ReleaseUpdateEvent rue) {
		this.updateEvents.add(rue);
	}

	public void addApprovalRequest (ReleaseApprovalRequest rar) {
		if (null == this.approvalRequests) this.approvalRequests = new LinkedList<>();
		this.approvalRequests.add(rar);
	}

	@JsonIgnore
	@JsonProperty(CommonVariables.REBOM_UUID_FIELD)
	private UUID rebomUuid;

	public record ReleaseBom (
		UUID rebomId,
		RebomOptions rebomMergeOptions) {}
	
	private List<ReleaseBom> reboms = new ArrayList<>();
	
	@JsonIgnore
	private ReleaseMetricsDto metrics = new ReleaseMetricsDto();

	@JsonIgnore
	private Boolean sbomReconcilePending = Boolean.FALSE;

	@JsonIgnore
	public Set<UUID> getCoreParentReleases () {
		return getParentReleases().stream().map(x -> x.getRelease()).collect(Collectors.toSet());
	}
		
	public static ReleaseData releaseDataFactory(ReleaseDto releaseDto) {
		ReleaseData rd = new ReleaseData();
		rd.setVersion(releaseDto.getVersion());
		rd.setBranch(releaseDto.getBranch());
		rd.setComponent(releaseDto.getComponent());
		rd.setOrg(releaseDto.getOrg());
		rd.setCommits(releaseDto.getCommits());
		rd.setTickets(releaseDto.getTickets());
		var status = releaseDto.getStatus();
		if (null == status) status = ReleaseStatus.ACTIVE;
		rd.setStatus(status);
		var lifecycle = releaseDto.getLifecycle();
		if (null == lifecycle) lifecycle = ReleaseLifecycle.DRAFT;
		rd.setLifecycle(lifecycle);
		if (null != releaseDto.getParentReleases()) {
			rd.setParentReleases(releaseDto.getParentReleases());
		}
		rd.setSourceCodeEntry(releaseDto.getSourceCodeEntry());
		if (null != releaseDto.getArtifacts()) {
			rd.setArtifacts(releaseDto.getArtifacts());
		}
		if (null != releaseDto.getEndpoint()) {
			rd.setEndpoint(releaseDto.getEndpoint());
		}
		if (null != releaseDto.getTags()) {
			rd.setTags(releaseDto.getTags());
		}
		if(null != releaseDto.getInboundDeliverables()){
			rd.setInboundDeliverables(releaseDto.getInboundDeliverables());
		}
		if (null != releaseDto.getIdentifiers()) {
			rd.setIdentifiers(releaseDto.getIdentifiers());
		}
		if (null != releaseDto.getGudidRecord()) {
			rd.setGudidRecord(releaseDto.getGudidRecord());
		}
		if (null != releaseDto.getGudidStatus()) {
			rd.setGudidStatus(releaseDto.getGudidStatus());
		}
		if (null != releaseDto.getEos()) {
			rd.setEos(releaseDto.getEos());
		}
		if (null != releaseDto.getEol()) {
			rd.setEol(releaseDto.getEol());
		}
		if (null != releaseDto.getSidComponentName()) {
			rd.setSidComponentName(releaseDto.getSidComponentName());
		}
		return rd;
	}
	
	public static ValidationResult validateReleaseData (ReleaseData rd) {
		ValidationResult vr = new ValidationResult();
		
		/** tags **/
		int maxTagCount = 0;
		Map<String, Integer> tagKeyCountMap = new HashMap<>();
		if (!rd.tags.isEmpty()) {
			Iterator<TagRecord> tagIter = rd.tags.iterator();
			while (maxTagCount < 2 && tagIter.hasNext()) {
				TagRecord tr = tagIter.next();
				Integer curCount = tagKeyCountMap.get(tr.key());
				if (null == curCount) curCount = 0;
				++curCount;
				if (curCount > maxTagCount) maxTagCount = curCount;
				tagKeyCountMap.put(tr.key(), curCount);
			}
		}
		if (maxTagCount > 1) {
			vr.setErrors(List.of("Release cannot have more than one tag with the same key"));
		}
		return vr;
	}
	
	public static ReleaseData dataFromRecord (Release r) {
		if (r.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Release schema version is " + r.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = r.getRecordData();
		ReleaseData rd = Utils.OM.convertValue(recordData, ReleaseData.class);
		rd.setUuid(r.getUuid());
		rd.setCreatedDate(r.getCreatedDate());
		if (r.getMetrics() != null) {
			rd.setMetrics(Utils.OM.convertValue(r.getMetrics(), ReleaseMetricsDto.class));
		}
		if (r.getApprovalEvents() != null) {
			rd.setApprovalEvents(r.getApprovalEvents().stream()
				.map(m -> Utils.OM.convertValue(m, ReleaseApprovalEvent.class))
				.collect(java.util.stream.Collectors.toCollection(LinkedList::new)));
		}
		if (r.getUpdateEvents() != null) {
			rd.setUpdateEvents(r.getUpdateEvents().stream()
				.map(m -> Utils.OM.convertValue(m, ReleaseUpdateEvent.class))
				.collect(java.util.stream.Collectors.toCollection(LinkedList::new)));
		}
		FlowControl fc = r.getFlowControl();
		rd.setSbomReconcilePending(fc != null && fc.sbomReconcileRequestedAt() != null);
		return rd;
	}

	/**
	 * Totals-only counterpart to {@link #dataFromRecord(Release)} that builds a
	 * ReleaseData from a {@link ReleaseLite}. The metrics come from the generated
	 * metrics_totals column, so the per-finding detail arrays
	 * (vulnerabilityDetails / violationDetails / weaknessDetails) are never
	 * loaded; approvalEvents and updateEvents are intentionally not populated.
	 * Use only where the caller does not need finding-level detail or events.
	 */
	public static ReleaseData fromLite (ReleaseLite r) {
		if (r.getSchemaVersion() != 0) {
			throw new IllegalStateException("Release schema version is " + r.getSchemaVersion() + ", which is not currently supported");
		}
		ReleaseData rd = Utils.OM.convertValue(r.getRecordData(), ReleaseData.class);
		rd.setUuid(r.getUuid());
		rd.setCreatedDate(r.getCreatedDate());
		if (r.getMetricsTotals() != null) {
			rd.setMetrics(Utils.OM.convertValue(r.getMetricsTotals(), ReleaseMetricsDto.class));
		}
		FlowControl fc = r.getFlowControl();
		rd.setSbomReconcilePending(fc != null && fc.sbomReconcileRequestedAt() != null);
		return rd;
	}

	public static class ReleaseDateComparator implements Comparator<ReleaseData> {

		@Override
		public int compare(ReleaseData o1, ReleaseData o2) {
			return o2.getCreatedDate().compareTo(o1.getCreatedDate());
		}
		
	}
	
	public static class ReleaseVersionComparator implements Comparator<GenericReleaseData> {
		
		private String versionSchema;
		private String versionPin;
		
		public ReleaseVersionComparator(String versionSchema, String versionPin) {
			this.versionSchema = versionSchema;
			this.versionPin = versionPin;
		}
		
		@Override
		public int compare(GenericReleaseData o1, GenericReleaseData o2) {
			// if schema or pin not set, use dates
			if (StringUtils.isEmpty(versionSchema) || StringUtils.isEmpty(versionPin)) {
				return o2.getCreatedDate().compareTo(o1.getCreatedDate());
			} else {
				// check conformity first
				boolean o1ConformsToSchemaAndPin = VersionUtils
														.isVersionMatchingSchemaAndPin(versionSchema, versionPin, o1.getVersion());
				boolean o2ConformsToSchemaAndPin = VersionUtils
						.isVersionMatchingSchemaAndPin(versionSchema, versionPin, o2.getVersion());
				if (!o1ConformsToSchemaAndPin && !o2ConformsToSchemaAndPin) {
					// both don't conform
					// if pin is major, use alphanumeric comparison
					if ("major".equalsIgnoreCase(versionPin)) {
						int res = o2.getVersion().compareTo(o1.getVersion());
						return res;
					} else {
						// if nothing worked, use dates
						return o2.getCreatedDate().compareTo(o1.getCreatedDate());
					}
				} else if (o1ConformsToSchemaAndPin && !o2ConformsToSchemaAndPin) {
					return -1;
				} else if (!o1ConformsToSchemaAndPin) { // o2 conforms
					return 1;
				} else {
					// both conform
					Version v1 = Version.getVersion(o1.getVersion(), versionSchema);
					Version v2 = Version.getVersion(o2.getVersion(), versionSchema);
					return v1.compareTo(v2);
				}
			}
		}		
	}

	/**
	 * This method is to be used in changelogs and printing scenarios. In usual and computational scenarios getVersion() should be used instead.
	 * @return
	 */
	@JsonIgnore
	public String getDecoratedVersionString(String zoneIdStr){
		String versionString = version;
		String stringDate = "";
		stringDate = Utils.zonedDateTimeToString(getCreatedDate(), zoneIdStr);
		
		if(StringUtils.isNotEmpty(stringDate)){
			versionString = versionString + " (" + stringDate + ")";
		}
		return versionString;
	}

	@JsonIgnore
	public Set<UUID> getAllCommits(){
		Set<UUID> allCommits = new LinkedHashSet<UUID>();
		allCommits.add(getSourceCodeEntry());
		List<UUID> commits =  getCommits();
		if(commits != null && commits.size() > 0)
			allCommits.addAll(commits);
		return allCommits;
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
	
	public static record ReleaseDataExtended (ReleaseData releaseData,
			String namespace, String productName, Map<String, String> properties) {}
}
