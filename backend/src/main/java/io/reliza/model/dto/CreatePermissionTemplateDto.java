/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Collection;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Builder;
import lombok.Data;

/** Input of createPermissionTemplate (TPF-87, TPF-112); the rows are what the group editors send. */
@Data
@Builder
public class CreatePermissionTemplateDto {
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty("permissions")
	private Collection<UserGroupPermissionDto> permissions;
}
