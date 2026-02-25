/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceGroupData extends RelizaDataParent implements RelizaObject {

	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return uuid;
	}
	
	public static ResourceGroupData defaultResourceGroupData (UUID org) {
		ResourceGroupData defaultAd = new ResourceGroupData();
		defaultAd.setUuid(CommonVariables.DEFAULT_RESOURCE_GROUP);
		defaultAd.setOrg(org);
		defaultAd.setName(CommonVariables.DEFAULT_RESOURCE_GROUP_NAME);
		return defaultAd;
	}
	
	public static ResourceGroupData dataFromRecord (ResourceGroup a) {
		if (a.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Resource group schema version is " + a.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData =a.getRecordData();
		ResourceGroupData ad = Utils.OM.convertValue(recordData, ResourceGroupData.class);
		if(ad.getUuid() == null)
			ad.setUuid(a.getUuid());
		return ad;
	}
	
}