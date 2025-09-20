/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.UserGroupStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateUserGroupDto {
	@JsonProperty("groupId")
	private UUID groupId;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.USERS_FIELD)
	private Set<UUID> users;
	@JsonProperty("permissions")
	private Collection<UserGroupPermissionDto> permissions;
	@JsonProperty(CommonVariables.APPROVALS_FIELD)
	private Collection<String> approvals;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private UserGroupStatus status;
	@JsonProperty("connectedSsoGroups")
	private Set<String> connectedSsoGroups;
}
