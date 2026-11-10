/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Input of updateApiKeyGroup (TPF-91): a null field keeps the stored value; apiKeys replaces the
 * member list (keys of the group's organization, of the type its kind admits); permissions replace
 * the group's set. A group's kind is set at create and never changes, so it has no field here.
 */
@Data
@Builder(toBuilder = true)
public class UpdateApiKeyGroupDto {
	@JsonProperty("groupId")
	private UUID groupId;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty("apiKeys")
	private Set<UUID> apiKeys;
	@JsonProperty("permissions")
	private Collection<UserGroupPermissionDto> permissions;
	@JsonProperty(CommonVariables.APPROVALS_FIELD)
	private Collection<String> approvals;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private ApiKeyGroupStatus status;
}
