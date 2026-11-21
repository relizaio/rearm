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
 * An accepted safety-context level. At most one ACTIVE record exists per identity; a change
 * supersedes it ({@link #supersededBy}) and records are never deleted. "Accepted" records the
 * import path ({@link #acceptedVia}), not ReARM approval of the issuer's safety decision.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SafetyContextRecordData extends SafetyContextData {

	public enum RecordStatus { ACTIVE, SUPERSEDED }

	public enum AcceptedVia { AUTO_SELF, AUTO_VENDOR, MANUAL }

	@JsonProperty("status") private RecordStatus status = RecordStatus.ACTIVE;
	@JsonProperty("proposalUuid") private UUID proposalUuid;
	@JsonProperty("acceptedVia") private AcceptedVia acceptedVia;
	@JsonProperty("acceptedBy") private UUID acceptedBy;
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	@JsonProperty("acceptedAt") private Instant acceptedAt;
	@JsonProperty("acceptedByUploader") private boolean acceptedByUploader;
	@JsonProperty("acceptComment") private String acceptComment;
	@JsonProperty("supersededBy") private UUID supersededBy;

	public static SafetyContextRecordData dataFromRecord(SafetyContextRecord entity) {
		if (entity.getSchemaVersion() != 0) {
			throw new IllegalStateException("Safety context record schema version is "
				+ entity.getSchemaVersion() + ", which is not currently supported");
		}
		SafetyContextRecordData data = Utils.OM.convertValue(entity.getRecordData(), SafetyContextRecordData.class);
		data.setUuid(entity.getUuid());
		return data;
	}

	public Map<String, Object> toRecordData() {
		return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
	}
}
