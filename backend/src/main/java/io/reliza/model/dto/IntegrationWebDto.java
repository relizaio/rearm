/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.model.IntegrationData.IntegrationType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IntegrationWebDto {
	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private String identifier;
	@JsonProperty
	private Boolean isEnabled;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private IntegrationType type;
	@JsonProperty
	private String note;
}
