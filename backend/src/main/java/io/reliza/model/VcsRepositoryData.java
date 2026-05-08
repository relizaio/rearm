/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
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
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status = StatusEnum.ACTIVE;

	/**
	 * VCS-scoped output triggers. Currently constrained to ≤1 entry of
	 * type {@link EventType#EXTERNAL_VALIDATION} — the PR aggregator uses
	 * this entry to dispatch the aggregated PR-level verdict to the SCM
	 * (check-run / merge-request status / etc.). Component-scoped output
	 * triggers stay on {@link ComponentData#getOutputTriggers()} — VCS
	 * triggers fire only at the PR level, after aggregation across all
	 * releases attributed to a PR via SCE matching.
	 */
	@JsonProperty
	private List<ReleaseOutputEvent> outputTriggers = new LinkedList<>();
	
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

	/**
	 * Validate the {@link #outputTriggers} constraint at the data layer:
	 * at most one entry, and if present it must be EXTERNAL_VALIDATION.
	 * Service-layer setters call this before persisting so the invariant
	 * is preserved across all write paths (REST, GraphQL, programmatic).
	 */
	public static void validateOutputTriggers(List<ReleaseOutputEvent> triggers) {
		if (triggers == null || triggers.isEmpty()) return;
		if (triggers.size() > 1) {
			throw new IllegalArgumentException("VCS repository accepts at most one outputTrigger");
		}
		ReleaseOutputEvent t = triggers.get(0);
		if (t.getType() != EventType.EXTERNAL_VALIDATION) {
			throw new IllegalArgumentException("VCS-level outputTrigger must be of type EXTERNAL_VALIDATION; got "
					+ t.getType());
		}
	}
}
