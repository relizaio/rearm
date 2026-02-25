/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.tea.Rebom.RebomOptions;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseRebomData extends RelizaDataParent implements RelizaObject {

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	
	public record ReleaseBom (
		UUID rebomId,
		RebomOptions rebomMergeOptions) {}
	
	@JsonProperty
	private List<ReleaseBom> reboms = new ArrayList<>();

	public static ReleaseRebomData releaseRebomFactory (UUID org, UUID release, 
																List<ReleaseBom> reboms) {
		ReleaseRebomData rrD = new ReleaseRebomData();
		rrD.setOrg(org);
		rrD.setRelease(release);
		rrD.setReboms(reboms);
		return rrD;
	}
	

	public static ReleaseRebomData dataFromRecord (ReleaseRebom rr) {
		if (rr.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("VCS repository schema version is " + rr.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = rr.getRecordData();
		ReleaseRebomData rrD = Utils.OM.convertValue(recordData, ReleaseRebomData.class);
		rrD.setUuid(rr.getUuid());
		return rrD;
	}

	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
