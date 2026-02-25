/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.VariantData.VariantType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class VariantDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty
	private String version;
	@JsonProperty
	private String marketingVersion;
	@JsonProperty
	private Set<UUID> outboundDeliverables;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private ReleaseStatus status;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes;
	@JsonProperty(CommonVariables.ENDPOINT_FIELD)
	private URI endpoint;
	@JsonProperty
	private String identifier;
	@JsonProperty(CommonVariables.TAGS_FIELD)
	private List<TagRecord> tags;
	@JsonProperty
	private Integer order;
	@JsonProperty
	private VariantType type;
}
