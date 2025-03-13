/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VcsRepositoryData extends RelizaDataParent implements RelizaObject {
	
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@Setter(AccessLevel.PRIVATE)
	private UUID org;
	@JsonProperty(CommonVariables.URI_FIELD)
	private String uri;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private VcsType type = null; // optional
	
	public static VcsRepositoryData vcsRepositoryFactory (String name, UUID organization, 
																String uri, VcsType type) {
		VcsRepositoryData vcsrD = new VcsRepositoryData();
		vcsrD.setName(name);
		vcsrD.setOrg(organization);
		vcsrD.setUri(uri);
		vcsrD.setType(type);
		return vcsrD;
	}
	
	public static VcsRepositoryData dataFromRecord (VcsRepository vcsr) {
		if (vcsr.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("VCS repository schema version is " + vcsr.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = vcsr.getRecordData();
		VcsRepositoryData vcsrD = Utils.OM.convertValue(recordData, VcsRepositoryData.class);
		vcsrD.setUuid(vcsr.getUuid());
		return vcsrD;
	}
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
