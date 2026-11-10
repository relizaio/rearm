/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApiKeyGroupKind;
import lombok.Builder;
import lombok.Data;

/** Input of createApiKeyGroup (TPF-91); a null kind reads INTERNAL. */
@Data
@Builder(toBuilder = true)
public class CreateApiKeyGroupDto {
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty("kind")
	private ApiKeyGroupKind kind;
}
