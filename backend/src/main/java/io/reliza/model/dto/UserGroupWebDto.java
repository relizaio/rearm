/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.model.UserPermission.Permissions;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserGroupWebDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private UserGroupStatus status;
	@JsonProperty(CommonVariables.USERS_FIELD)
	private Set<UUID> users;
	@JsonProperty("connectedSsoGroups")
	private Set<String> connectedSsoGroups;
}
