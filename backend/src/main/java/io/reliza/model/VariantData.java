/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
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

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.ValidationResult;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.dto.VariantDto;
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
public class VariantData extends RelizaDataParent implements RelizaObject, GenericReleaseData {
	
	public enum VariantType {
		BASE,
		CUSTOM
	}
	
	@JsonProperty
	@Getter(AccessLevel.PUBLIC)
	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;
	
	@JsonProperty
	private String identifier; // i.e. GT for a car - to group similar variants for various releases
	
	@JsonProperty
	private String identifierPrefix = " ";
	
	@JsonProperty
	private Integer order = 0; // 0,1,2... - starts with 0,  increments only within the same identifier group
	
	@JsonProperty(CommonVariables.VERSION_FIELD)
	private String version;
	
	@JsonProperty(CommonVariables.MARKETING_VERSION_FIELD)
	private String marketingVersion;
	
	@JsonProperty
	private String sku; // optional, i.e. Salesforce SKU
	
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private ReleaseStatus status;
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	@JsonProperty
	@Setter(AccessLevel.PRIVATE)
	private UUID release;
	
	@JsonProperty
	@Setter(AccessLevel.PRIVATE)
	private VariantType type;

	@JsonProperty
	private Set<UUID> outboundDeliverables = new LinkedHashSet<>();

	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes = null;
	
	/**	
	 * Optional test endpoint for this release, in the future can consider array of endpoints, also endpoints for services
	 * https://github.com/CycloneDX/specification/issues/22
	 */
	@JsonProperty(CommonVariables.ENDPOINT_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private URI endpoint;
	
	@JsonProperty(CommonVariables.TAGS_FIELD)
	private List<TagRecord> tags = new LinkedList<>();

	@JsonIgnore
	@JsonProperty(CommonVariables.REBOM_UUID_FIELD)
	private UUID rebomUuid;

	
	private List<ReleaseUpdateEvent> updateEvents = new LinkedList<>();

	public static VariantData variantDataFactory(VariantDto variantDto) {
		VariantData vd = new VariantData();
		vd.setVersion(variantDto.getVersion());
		vd.setMarketingVersion(variantDto.getMarketingVersion());
		vd.setOrg(variantDto.getOrg());
		vd.setRelease(variantDto.getRelease());
		vd.setIdentifier(variantDto.getIdentifier());
		vd.setType(variantDto.getType());
		var status = variantDto.getStatus();
		if (null == status) status = ReleaseStatus.ACTIVE;
		vd.setStatus(status);
		vd.setEndpoint(variantDto.getEndpoint());
		vd.setNotes(variantDto.getNotes());
		if (null != variantDto.getOrder()) vd.setOrder(variantDto.getOrder());
		if (null != variantDto.getOutboundDeliverables()) vd.setOutboundDeliverables(variantDto.getOutboundDeliverables());
		if (null != variantDto.getTags()) vd.setTags(variantDto.getTags());
		return vd;
	}
	
	public static ValidationResult validateVariantData (VariantData vd) {
		ValidationResult vr = new ValidationResult();
		
		/** tags **/
		int maxTagCount = 0;
		Map<String, Integer> tagKeyCountMap = new HashMap<>();
		if (!vd.tags.isEmpty()) {
			Iterator<TagRecord> tagIter = vd.tags.iterator();
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
			vr.setErrors(List.of("Variant cannot have more than one tag with the same key"));
		}
		return vr;
	}
	
	public static VariantData dataFromRecord (Variant v) {
		if (v.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Variant schema version is " + v.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = v.getRecordData();
		VariantData vd = Utils.OM.convertValue(recordData, VariantData.class);
		vd.setUuid(v.getUuid());
		vd.setCreatedDate(v.getCreatedDate());
		return vd;
	}
	
	public static class VariantDateComparator implements Comparator<VariantData> {

		@Override
		public int compare(VariantData o1, VariantData o2) {
			return o2.getCreatedDate().compareTo(o1.getCreatedDate());
		}
		
	}
	
	public static class VariantVersionComparator implements Comparator<VariantData> {
		
		private String versionSchema;
		private String versionPin;
		
		public VariantVersionComparator(String versionSchema, String versionPin) {
			this.versionSchema = versionSchema;
			this.versionPin = versionPin;
		}
		
		@Override
		public int compare(VariantData v1, VariantData v2) {
			// if schema or pin not set, use dates
			if (StringUtils.isEmpty(versionSchema) || StringUtils.isEmpty(versionPin)) {
				return v2.getCreatedDate().compareTo(v1.getCreatedDate());
			} else {
				// check conformity first
				boolean v1ConformsToSchemaAndPin = VersionUtils
														.isVersionMatchingSchemaAndPin(versionSchema, versionPin, v1.getVersion());
				boolean v2ConformsToSchemaAndPin = VersionUtils
						.isVersionMatchingSchemaAndPin(versionSchema, versionPin, v2.getVersion());
				if (!v1ConformsToSchemaAndPin && !v2ConformsToSchemaAndPin) {
					// both don't conform
					// if pin is major, use alphanumeric comparison
					if ("major".equalsIgnoreCase(versionPin)) {
						int res = v2.getVersion().compareTo(v1.getVersion());
						return res;
					} else {
						// if nothing worked, use dates
						return v2.getCreatedDate().compareTo(v1.getCreatedDate());
					}
				} else if (v1ConformsToSchemaAndPin && !v2ConformsToSchemaAndPin) {
					return -1;
				} else if (!v1ConformsToSchemaAndPin) { // v2 conforms
					return 1;
				} else {
					// both conform
					Version v1Ver = Version.getVersion(v1.getVersion(), versionSchema);
					Version v2Ver = Version.getVersion(v2.getVersion(), versionSchema);
					return v1Ver.compareTo(v2Ver);
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
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
	
	public void addUpdateEvent (ReleaseUpdateEvent rue) {
		this.updateEvents.add(rue);
	}

	
}
