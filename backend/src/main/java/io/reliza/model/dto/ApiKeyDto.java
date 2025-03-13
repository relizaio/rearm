/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.UserPermission.Permissions;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions;
	@JsonProperty(CommonVariables.OBJECT_FIELD)
	private UUID object;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private ApiTypeEnum type;
	private String keyOrder;
	private String registryRobotLogin;
	private String notes;
	private ZonedDateTime accessDate;
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID lastUpdatedBy;
	@JsonProperty(CommonVariables.CREATED_DATE_FIELD)
	@Setter(AccessLevel.PROTECTED) ZonedDateTime createdDate;

	public static ApiKeyDto fromApiKey(ApiKey ak) {
		ApiKeyData akData = ApiKeyData.dataFromRecord(ak);
		return ApiKeyDto.builder()
							.uuid(akData.getUuid())
							.org(akData.getOrg())
							.permissions(akData.getPermissions(akData.getOrg()))
							.object(ak.getObjectUuid())
							.type(ak.getObjectType())
							.keyOrder(ak.getKeyOrder())
							.lastUpdatedBy(akData.getLastUpdatedBy())
							.createdDate(ak.getCreatedDate())
							// .registryRobotLogin(akData.getRegistryRobotLogin())
							.notes(akData.getNotes())
							.build();
	}
}