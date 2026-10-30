/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
	/** DENIED keys: the administrator's reason, empty when none; null otherwise and on keys denied before it existed. */
	private String deniedReason;
	private io.reliza.model.ApiKeyData.ApiKeyStatus status;
	private java.util.List<io.reliza.model.ApiKeyData.ApiKeySecret> secrets;
	private UUID holder;
	/** Whether an org admin's kill switch holds the key off. Boxed so a projection that omits it (referenceOf) reads null, not false. */
	private Boolean adminDisabled;
	private io.reliza.model.ApiKeyData.ApiKeyOrigin origin;
	private io.reliza.model.FederatedIdentity federation;
	private Integer sessionMaxMinutes;
	/** The declarative name of the key and the apply that last wrote it (task RD3-11); null for a hand-made key. */
	private String declaredName;
	private io.reliza.model.DeclarativeProvenance declarative;
	private Integer secretExpiresDays;
	/** The id a client presents (task TEA-3): printed by the server, never derived by a client. */
	private String keyId;
	/** EXTERNAL keys: the outside party that holds the key, and the organization's TEA id the key id carries. */
	private String holderName;
	private UUID teaOrg;
	private ZonedDateTime accessDate;
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID lastUpdatedBy;
	@JsonProperty("createdBy")
	private UUID createdBy;
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
							.createdBy(ak.getCreatedBy())
							.createdDate(ak.getCreatedDate())
							// .registryRobotLogin(akData.getRegistryRobotLogin())
							.notes(akData.getNotes())
							.deniedReason(akData.getDeniedReason())
							.status(akData.getStatus())
							.holder(akData.getHolder())
							.adminDisabled(akData.isSwitchedOffByAdmin())
							.origin(akData.getOrigin())
							.federation(akData.getFederation())
							.sessionMaxMinutes(akData.getSessionMaxMinutes())
							.declaredName(akData.getDeclaredName())
							.declarative(akData.getDeclarative())
							.secretExpiresDays(akData.getSecretExpiresDays())
							.keyId(io.reliza.service.ApiKeyService.keyIdOf(ak, akData))
							.holderName(akData.getHolderName())
							.teaOrg(akData.getTeaOrg())
							.accessDate(ak.getLastAccessDate())
							.secrets(akData.effectiveSecrets(ak.getApiKey()).stream()
									.map(x -> new io.reliza.model.ApiKeyData.ApiKeySecret(x.getSlot(), null, x.isActive(), x.getCreatedDate(), x.getLastUsedDate(), x.getExpiresDate()))
									.toList()) // hashes never leave the server
							.build();
	}

	/**
	 * What a non-admin reader of an agent may see of the key bound to it:
	 * uuid, org, type and declared name. Everything else (notes, denied
	 * reason, permissions, keyOrder, holder, secret metadata, access date) stays
	 * null; the full record is {@code apiKeys} (org admin) or
	 * {@code myApiKeys} (own keys).
	 */
	public static ApiKeyDto referenceOf(ApiKey ak) {
		ApiKeyData akData = ApiKeyData.dataFromRecord(ak);
		return ApiKeyDto.builder()
							.uuid(akData.getUuid())
							.org(akData.getOrg())
							.type(ak.getObjectType())
							.declaredName(akData.getDeclaredName())
							.build();
	}
}