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
import io.reliza.common.Utils;
import io.reliza.common.ValidationResult;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.TeaIdentifier;
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
		TRIGGER
	}
	
	public enum ReleaseUpdateAction {
		ADDED,
		REMOVED,
		CHANGED
	}
	
	public record ReleaseUpdateEvent (ReleaseUpdateScope rus, ReleaseUpdateAction rua, String oldValue, 
			String newValue, UUID objectId, ZonedDateTime date, WhoUpdated wu) {}
	
	public record ReleaseLifecycleEvent (ReleaseLifecycle oldLifecycle, ReleaseLifecycle newLifecycle, ZonedDateTime date, WhoUpdated wu) {}
	
	public record ReleaseApprovalEvent (UUID approvalEntry, String approvalRoleId, ApprovalState state, ZonedDateTime date, WhoUpdated wu) {}
	
	public record ReleaseApprovalInput (UUID approvalEntry, String approvalRoleId, ApprovalState state) {}
	
	public record ReleaseApprovalProgrammaticInputEntry (String approvalEntry, String approvalRoleId, ApprovalState state) {}
	
	public record ReleaseApprovalProgrammaticInput (List<ReleaseApprovalProgrammaticInputEntry> approvals, 
			UUID release, UUID component, String version) {}
	
	public static ReleaseApprovalEvent approvalEventFromInput (ReleaseApprovalInput rai, WhoUpdated wu) {
		return new ReleaseApprovalEvent(rai.approvalEntry(), rai.approvalRoleId(), rai.state(), ZonedDateTime.now(), wu);
	}
	
	public enum ReleaseStatus {
		ACTIVE,
		ARCHIVED
	}
	
	public enum ReleaseLifecycle {
		CANCELLED,
		REJECTED,
	    PENDING,
	    DRAFT,
	    ASSEMBLED,
	    GENERAL_AVAILABILITY,
	    END_OF_SUPPORT;
		
		public static boolean isAssemblyAllowed(ReleaseLifecycle rl) {
			boolean isAllowed = false;
			if (rl == PENDING || rl == DRAFT) isAllowed = true;
			return isAllowed;
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
	
	@JsonProperty
	private List<TeaIdentifier> identifiers = new LinkedList<>();
	
	public List<TeaIdentifier> getIdentifiers () {
		return new LinkedList<>(this.identifiers);
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
	
	private List<ReleaseApprovalEvent> approvalEvents = new LinkedList<>();
	
	private List<ReleaseUpdateEvent> updateEvents = new LinkedList<>();
	
	public void addApprovalEvent (ReleaseApprovalEvent rae) {
		this.approvalEvents.add(rae);
	}
	
	public void addUpdateEvent (ReleaseUpdateEvent rue) {
		this.updateEvents.add(rue);
	}

	@JsonIgnore
	@JsonProperty(CommonVariables.REBOM_UUID_FIELD)
	private UUID rebomUuid;

	public record ReleaseBom (
		UUID rebomId,
		RebomOptions rebomMergeOptions) {}
	
	private List<ReleaseBom> reboms = new ArrayList<>();
	
	@JsonProperty
	private ReleaseMetricsDto metrics = new ReleaseMetricsDto();

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
