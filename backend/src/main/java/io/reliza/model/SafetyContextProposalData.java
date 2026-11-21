/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.core.type.TypeReference;

/**
 * A staged safety-context level awaiting review (or decided by the trust gate on import).
 * {@link #lineageKey} groups proposals from the same server-controlled source (org, release,
 * binding, effective issuer, uploader): a re-upload supersedes a PENDING proposal of its own
 * lineage only, so one uploader cannot clear another's review queue.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SafetyContextProposalData extends SafetyContextData {

	@JsonProperty("lineageKey") private String lineageKey;
	@JsonProperty("status") private ProposalStatus status = ProposalStatus.PENDING;
	@JsonProperty("actedBy") private UUID actedBy;
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	@JsonProperty("actedAt") private Instant actedAt;
	@JsonProperty("acceptComment") private String acceptComment;
	@JsonProperty("rejectReason") private String rejectReason;
	@JsonProperty("acceptedRecordUuid") private UUID acceptedRecordUuid;

	public static SafetyContextProposalData dataFromRecord(SafetyContextProposal entity) {
		if (entity.getSchemaVersion() != 0) {
			throw new IllegalStateException("Safety context proposal schema version is "
				+ entity.getSchemaVersion() + ", which is not currently supported");
		}
		SafetyContextProposalData data = Utils.OM.convertValue(entity.getRecordData(), SafetyContextProposalData.class);
		data.setUuid(entity.getUuid());
		return data;
	}

	public Map<String, Object> toRecordData() {
		return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
	}
}
