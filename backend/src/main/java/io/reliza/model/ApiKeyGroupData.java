/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.dto.ApiKeyGroupWebDto;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;

/**
 * An API key group (TPF-91, child of TPF-86): a permission set of one organization whose members are
 * FREEFORM API keys of that organization. A member key's effective permissions are its own grants
 * union every ACTIVE group holding it (AuthorizationService.keyPermissions). Member uuids live here,
 * validated on write by ApiKeyGroupService and tolerated when dangling on read (no foreign keys).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyGroupData extends RelizaDataParent implements RelizaObject {

	public static final String API_KEYS_FIELD = "apiKeys";

	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org; // a group belongs to a single organization
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions = new Permissions();
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private ApiKeyGroupStatus status = ApiKeyGroupStatus.ACTIVE;
	@JsonProperty(API_KEYS_FIELD)
	private Set<UUID> apiKeys = new LinkedHashSet<>(); // FREEFORM keys of this organization
	@JsonProperty
	private UUID resourceGroup = CommonVariables.DEFAULT_RESOURCE_GROUP;

	private ApiKeyGroupData() {}

	@Override
	public UUID getUuid() {
		return uuid;
	}

	private void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public String getName() {
		return name;
	}

	private void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	private void setDescription(String description) {
		this.description = description;
	}

	@Override
	public UUID getOrg() {
		return org;
	}

	private void setOrg(UUID orgParam) {
		this.org = orgParam;
	}

	/** A copy of the member key uuids. */
	public Set<UUID> getApiKeys() {
		return new LinkedHashSet<>(apiKeys);
	}

	private void setApiKeys(Collection<UUID> apiKeys) {
		this.apiKeys = null == apiKeys ? new LinkedHashSet<>() : new LinkedHashSet<>(apiKeys);
	}

	public boolean hasApiKey(UUID keyUuid) {
		return this.apiKeys.contains(keyUuid);
	}

	public void addApiKey(UUID keyUuid) {
		this.apiKeys.add(keyUuid);
	}

	public boolean removeApiKey(UUID keyUuid) {
		return this.apiKeys.remove(keyUuid);
	}

	public void setPermission(PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
		permissions.setPermission(this.org, scope, objectUuid, type, approvals);
	}

	public void setPermission(PermissionScope scope, UUID objectUuid, PermissionType type,
			Collection<PermissionFunction> functions, Collection<String> approvals) {
		permissions.setPermission(this.org, scope, objectUuid, type, functions, approvals);
	}

	public boolean revokeAllOrgPermissions() {
		return permissions.revokeAllOrgPermissions(this.org);
	}

	public Optional<UserPermission> getPermission(PermissionScope scope, UUID objectUuid) {
		return permissions.getPermission(this.org, scope, objectUuid);
	}

	private Permissions getPermissions() {
		return permissions;
	}

	public Set<UserPermission> getOrgPermissions(UUID orgUuid) {
		return permissions.getOrgPermissionsAsSet(orgUuid);
	}

	private void setPermissions(Permissions permissions) {
		this.permissions = permissions;
	}

	public ApiKeyGroupStatus getStatus() {
		return status;
	}

	public void setStatus(ApiKeyGroupStatus status) {
		this.status = status;
	}

	@Override
	public UUID getResourceGroup() {
		return this.resourceGroup;
	}

	/**
	 * The updated group: a null field of the input keeps the stored value; a non-null apiKeys
	 * REPLACES the member list; non-null permissions replace the organization's permission set;
	 * approvals apply to the organization-wide permission (the UserGroupData.updateUserGroupData rule).
	 * The organization never changes.
	 */
	public static ApiKeyGroupData updateApiKeyGroupData(ApiKeyGroupData akgd, UpdateApiKeyGroupDto dto) {
		ApiKeyGroupData updated = new ApiKeyGroupData();
		updated.setOrg(akgd.getOrg());
		updated.resourceGroup = akgd.getResourceGroup();
		updated.setName(null != dto.getName() ? dto.getName() : akgd.getName());
		updated.setDescription(null != dto.getDescription() ? dto.getDescription() : akgd.getDescription());
		updated.setApiKeys(null != dto.getApiKeys() ? dto.getApiKeys() : akgd.getApiKeys());

		if (null != dto.getPermissions()) {
			for (UserGroupPermissionDto permission : dto.getPermissions()) {
				if (null == permission) continue;
				updated.setPermission(permission.getScope(), permission.getObjectId(), permission.getType(),
						permission.getFunctions(), permission.getApprovals());
			}
		} else {
			updated.setPermissions(akgd.getPermissions());
		}

		if (null != dto.getApprovals()) {
			Optional<UserPermission> orgWide = updated.getPermission(PermissionScope.ORGANIZATION, updated.getOrg());
			PermissionType pt = orgWide.isPresent() ? orgWide.get().getType() : PermissionType.NONE;
			updated.setPermission(PermissionScope.ORGANIZATION, updated.getOrg(), pt, dto.getApprovals());
		}

		updated.setStatus(null != dto.getStatus() ? dto.getStatus() : akgd.getStatus());
		return updated;
	}

	public static ApiKeyGroupData apiKeyGroupDataFactory(CreateApiKeyGroupDto createDto) {
		ApiKeyGroupData akgd = new ApiKeyGroupData();
		akgd.setName(createDto.getName());
		akgd.setDescription(createDto.getDescription());
		akgd.setOrg(createDto.getOrg());
		return akgd;
	}

	public static ApiKeyGroupData dataFromRecord(ApiKeyGroup akg) {
		if (akg.getSchemaVersion() != 0) { // if schema version is not supported, throw exception
			throw new IllegalStateException("API key group schema version is " + akg.getSchemaVersion()
			+ ", which is not currently supported");
		}
		Map<String,Object> recordData = akg.getRecordData();
		ApiKeyGroupData akgd = Utils.OM.convertValue(recordData, ApiKeyGroupData.class);
		akgd.setUuid(akg.getUuid());
		akgd.setCreatedDate(akg.getCreatedDate());
		return akgd;
	}

	public static ApiKeyGroupWebDto toWebDto(ApiKeyGroupData akgd) {
		return ApiKeyGroupWebDto.builder()
				.uuid(akgd.getUuid())
				.name(akgd.getName())
				.description(akgd.getDescription())
				.org(akgd.getOrg())
				.permissions(akgd.getPermissions())
				.status(akgd.getStatus())
				.apiKeys(akgd.getApiKeys())
				.createdDate(akgd.getCreatedDate())
				.lastUpdatedBy(akgd.getLastUpdatedBy())
				.build();
	}
}
