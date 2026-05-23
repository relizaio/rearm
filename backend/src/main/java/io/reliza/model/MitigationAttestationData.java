/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MitigationAttestationData extends RelizaDataParent implements RelizaObject {

	@JsonProperty("uuid") private UUID uuid;
	@JsonProperty("org") private UUID org;
	@JsonProperty("proposal") private UUID proposal;
	@JsonProperty("claimType") private ClaimType claimType;
	@JsonProperty("claimText") private String claimText;

	@JsonProperty("scope") private AnalysisScope scope;
	@JsonProperty("scopeUuid") private UUID scopeUuid;

	@JsonProperty("assignedTo") private UUID assignedTo;
	@JsonProperty("assignedAt") private ZonedDateTime assignedAt;
	@JsonProperty("deadline") private ZonedDateTime deadline;

	@JsonProperty("status") private AttestationStatus status = AttestationStatus.PENDING;
	@JsonProperty("evidence") private String evidence;
	@JsonProperty("attestedAt") private ZonedDateTime attestedAt;
	@JsonProperty("attestedBy") private UUID attestedBy;
	@JsonProperty("statusReason") private String statusReason;

	public static MitigationAttestationData createMitigationAttestationData(
			UUID org, UUID proposal, ClaimType claimType, String claimText,
			UUID assignedTo, AnalysisScope scope, UUID scopeUuid, ZonedDateTime deadline) {
		MitigationAttestationData d = new MitigationAttestationData();
		d.uuid = UUID.randomUUID();
		d.org = org;
		d.proposal = proposal;
		d.claimType = claimType;
		d.claimText = claimText;
		d.assignedTo = assignedTo;
		d.assignedAt = ZonedDateTime.now();
		d.scope = scope;
		d.scopeUuid = scopeUuid;
		d.deadline = deadline;
		return d;
	}

	public static MitigationAttestationData dataFromRecord(MitigationAttestation e) {
		MitigationAttestationData d = Utils.OM.convertValue(e.getRecordData(), new TypeReference<MitigationAttestationData>() {});
		d.setUuid(e.getUuid());
		return d;
	}

	public static MitigationAttestationData dataFromMap(Map<String, Object> map, UUID uuid) {
		MitigationAttestationData d = Utils.OM.convertValue(map, new TypeReference<MitigationAttestationData>() {});
		d.setUuid(uuid);
		return d;
	}

	public Map<String, Object> toRecordData() {
		return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
	}

	@Override
	public UUID getResourceGroup() {
		return null;
	}
}
