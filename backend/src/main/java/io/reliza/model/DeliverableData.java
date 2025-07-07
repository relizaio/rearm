/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/


package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.tea.Link;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.versioning.Version;
import io.reliza.versioning.VersionUtils;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliverableData extends RelizaDataParent implements RelizaObject {
	public enum PackageType {
		MAVEN,
		NPM,
		NUGET,
		GEM,
		PYPI,
		CONTAINER;
		
		private PackageType () {}
	}
	
	public enum CPUArchitecture {
		AMD64,
		I386,
		PPC,
		ARMV7,
		ARMV8,
		IA32,
		MIPS,
		RISCV64,
		S390,
		S390X,
		OTHER
	}
	
	public enum OS {
		WINDOWS,
		MACOS,
		LINUX,
		ANDROID,
		CHROMEOS,
		IOS,
		OTHER
	}
	
	public enum BelongsToOrganization {
		INTERNAL,
		EXTERNAL
	}
	
	@Data
	@Builder
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SoftwareDeliverableMetadata {
		/**
		 * For now we just add this field to add cicd meta in it as a string, but in the future
		 * there should be some data driven approach, potentially with enum
		 * i.e. client can list all there jenkins servers or travis or circleci - whatever they use
		 */
		@JsonProperty(CommonVariables.BUILD_ID_FIELD)
		private String buildId; // build identifier, i.e. jenkins build id
		@JsonProperty(CommonVariables.BUILD_URI_FIELD)
		private String buildUri;
		@JsonProperty(CommonVariables.CICD_META_FIELD)
		private String cicdMeta; // for now unstructured data regarding build process, i.e. jenkins uri
		@JsonProperty(CommonVariables.DIGESTS_FIELD)
		private Set<String> digests;
		/**
		 * Timestamp when deliverable build started
		 */
		@JsonProperty(CommonVariables.DATE_FROM_FIELD)
		private ZonedDateTime dateFrom;
		/**
		 * Timestamp when deliverable build ended
		 */
		@JsonProperty(CommonVariables.DATE_TO_FIELD)
		private ZonedDateTime dateTo;
		@JsonProperty(CommonVariables.DURATION_FIELD)
		private Long duration;
		@JsonProperty(CommonVariables.PACKAGE_TYPE_FIELD)
		private PackageType packageType;
		@JsonProperty
		private List<Link> downloadLinks;
		
		private void setDuration() {
			if (null != dateFrom && null != dateTo) {
				this.duration = dateTo.toEpochSecond() - dateFrom.toEpochSecond();
			}
		}
		
		public static SoftwareDeliverableMetadata clone (SoftwareDeliverableMetadata sdm) {
			SoftwareDeliverableMetadata clonedSdm = SoftwareDeliverableMetadata.builder()
					.buildId(sdm.getBuildId())
					.buildUri(sdm.getBuildUri())
					.cicdMeta(sdm.getCicdMeta())
					.digests(null != sdm.getDigests() ? new LinkedHashSet<>(sdm.getDigests()): new LinkedHashSet<>())
					.dateFrom(sdm.getDateFrom())
					.dateTo(sdm.getDateTo())
					.packageType(sdm.getPackageType())
					.build();
			clonedSdm.setDuration();
			return clonedSdm;
		}
	}
	
	private UUID uuid;
	private String displayIdentifier;
	private List<TeaIdentifier> identifiers = new ArrayList<>();
	private UUID org; // if branch uuid is specified, organization must match that of branch
	@JsonProperty(CommonVariables.BRANCH_FIELD)
	private UUID branch = null; // deliverable should belong to a project's branch for internal deliverables
	private BelongsToOrganization isInternal = null;
	private CdxType type = CdxType.FILE; // default is file
	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes = null; // any unstructured metadata we want about this artifact
	@JsonProperty(CommonVariables.TAGS_FIELD)
	private List<TagRecord> tags = new ArrayList<>();

	/**
	 * may be different from release version, i.e. in Google App Engine deployment
	 */
	@JsonProperty(CommonVariables.VERSION_FIELD)
	private String version;
	@JsonProperty(CommonVariables.PUBLISHER_FIELD)
	private String publisher;
	@JsonProperty(CommonVariables.GROUP_FIELD)
	private String group;
	private List<OS> supportedOs = new ArrayList<>();
	private List<CPUArchitecture> supportedCpuArchitectures = new ArrayList<>();
	private StatusEnum status;
	
	// for software deliverables only
	private SoftwareDeliverableMetadata softwareMetadata;
	
	private List<UUID> artifacts = new ArrayList<>();

	public CdxType getType() {
		return this.type;
	}
	
	public List<TagRecord> getTags() {
		return new ArrayList<>(this.tags);
	}
	public void setTags (List<TagRecord> tags) {
		this.tags = new ArrayList<>(tags);
	}


	
	public static DeliverableData deliverableDataFactory(DeliverableDto deliverableDto) {
		DeliverableData dd = new DeliverableData();
		dd.setDisplayIdentifier(deliverableDto.getDisplayIdentifier());
		if (null != deliverableDto.getIdentifiers()) dd.setIdentifiers(deliverableDto.getIdentifiers());
		// if branch is supplied, treat as internal
		if (null != deliverableDto.getBranch()) {
			dd.setIsInternal(BelongsToOrganization.INTERNAL);
		} else {
			dd.setIsInternal(BelongsToOrganization.EXTERNAL);
		}
		dd.setOrg(deliverableDto.getOrg());
		dd.setBranch(deliverableDto.getBranch());
		if (null != deliverableDto.getSoftwareMetadata())
			dd.setSoftwareMetadata(SoftwareDeliverableMetadata.clone(deliverableDto.getSoftwareMetadata()));
		dd.setType(deliverableDto.getType());
		
		if (null != deliverableDto.getTags()) {
			dd.setTags(new ArrayList<>(deliverableDto.getTags()));
		}
		dd.setNotes(deliverableDto.getNotes());
		dd.setPublisher(deliverableDto.getPublisher());
		dd.setVersion(deliverableDto.getVersion());
		dd.setGroup(deliverableDto.getGroup());
		if (null != deliverableDto.getArtifacts())
			dd.setArtifacts(new ArrayList<>(deliverableDto.getArtifacts()));
		return dd;
	}
	
	public static DeliverableData dataFromRecord (Deliverable d) {
		if (d.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Deliverable repository schema version is " + d.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = d.getRecordData();
		DeliverableData dd = Utils.OM.convertValue(recordData, DeliverableData.class);
		dd.setUuid(d.getUuid());
		dd.setCreatedDate(d.getCreatedDate());
		dd.setUpdatedDate(d.getLastUpdatedDate());
		return dd;
	}
	
	public static class DeliverableVersionComparator implements Comparator<DeliverableData> {
		
		private String versionSchema;
		private String versionPin;
		
		public DeliverableVersionComparator(String versionSchema, String versionPin) {
			this.versionSchema = versionSchema;
			this.versionPin = versionPin;
		}
		
		@Override
		public int compare(DeliverableData o1, DeliverableData o2) {
			// if schema or pin not set, use dates
			if (StringUtils.isEmpty(versionSchema) || StringUtils.isEmpty(versionPin)) {
				return o2.getCreatedDate().compareTo(o1.getCreatedDate());
			} else if (StringUtils.isEmpty(o1.version)) {
				return 1;
			} else if (StringUtils.isEmpty(o2.version)) {
				return -1;
			} else {
				// check conformity first
				boolean o1ConformsToSchemaAndPin = VersionUtils
														.isVersionMatchingSchemaAndPin(versionSchema, versionPin, o1.getVersion());
				boolean o2ConformsToSchemaAndPin = VersionUtils
						.isVersionMatchingSchemaAndPin(versionSchema, versionPin, o2.getVersion());
				if (!o1ConformsToSchemaAndPin && !o2ConformsToSchemaAndPin) {
					// both don't conform, use dates
					return o2.getCreatedDate().compareTo(o1.getCreatedDate());
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

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
