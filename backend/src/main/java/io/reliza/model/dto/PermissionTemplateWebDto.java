/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.UserPermission.Permissions;
import lombok.Builder;
import lombok.Data;

/** A permission template as the browser lane returns it (GraphQL PermissionTemplate, TPF-87, TPF-112). */
@Data
@Builder
public class PermissionTemplateWebDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty(CommonVariables.USER_FIELD)
	private UUID user;
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions;
	@JsonProperty(CommonVariables.CREATED_DATE_FIELD)
	private ZonedDateTime createdDate;
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID lastUpdatedBy;
}
