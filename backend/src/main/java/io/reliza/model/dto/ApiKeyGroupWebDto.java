/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.UserPermission.Permissions;
import lombok.Builder;
import lombok.Data;

/** An API key group as the browser lane returns it (TPF-91); members by uuid, never a secret. */
@Data
@Builder
public class ApiKeyGroupWebDto {
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
	private ApiKeyGroupStatus status;
	@JsonProperty("apiKeys")
	private Set<UUID> apiKeys;
	@JsonProperty(CommonVariables.CREATED_DATE_FIELD)
	private ZonedDateTime createdDate;
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID lastUpdatedBy;

	/** A member key by reference (GraphQL ApiKeyRef): never a secret, never a hash. */
	public record ApiKeyRef(UUID uuid, String keyOrder, ApiTypeEnum type, ApiKeyStatus status,
			String notes, String declaredName) {}
}
