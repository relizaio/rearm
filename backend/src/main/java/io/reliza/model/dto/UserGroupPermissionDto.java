/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Collection;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserGroupPermissionDto {
	@JsonProperty(CommonVariables.SCOPE_FIELD)
	private PermissionScope scope;
	@JsonProperty
	private UUID objectId;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private PermissionType type;
	@JsonProperty(CommonVariables.APPROVALS_FIELD)
	private Collection<String> approvals;
}
