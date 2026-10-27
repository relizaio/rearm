/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.dto.CreatePermissionTemplateDto;
import io.reliza.model.dto.PermissionTemplateWebDto;
import io.reliza.model.dto.UpdatePermissionTemplateDto;
import io.reliza.model.dto.UserGroupPermissionDto;

/**
 * A permission template (TPF-87, TPF-112): a user's named, saved permission set in one organization,
 * which the UI applies by value into the device-login approval and key request editors. It grants
 * nothing by itself; what is approved from it is clamped to the approver at approval time. Owner only:
 * no status, no members, no sharing. The organization-wide level is the ORGANIZATION row of the set.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PermissionTemplateData extends RelizaDataParent implements RelizaObject {

	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.USER_FIELD)
	private UUID user; // the owner; never changes
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org; // a template belongs to a single organization; never changes
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions = new Permissions();
	@JsonProperty
	private UUID resourceGroup = CommonVariables.DEFAULT_RESOURCE_GROUP;

	private PermissionTemplateData() {}

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

	public UUID getUser() {
		return user;
	}

	private void setUser(UUID user) {
		this.user = user;
	}

	@Override
	public UUID getOrg() {
		return org;
	}

	private void setOrg(UUID orgParam) {
		this.org = orgParam;
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

	/** The template's permission set in the organization: what the UI applies and the tests read. */
	public Set<UserPermission> getOrgPermissions(UUID orgUuid) {
		return permissions.getOrgPermissionsAsSet(orgUuid);
	}

	private void setPermissions(Permissions permissions) {
		this.permissions = permissions;
	}

	@Override
	public UUID getResourceGroup() {
		return this.resourceGroup;
	}

	private void addPermissions(Collection<UserGroupPermissionDto> rows) {
		if (null == rows) return;
		for (UserGroupPermissionDto row : rows) {
			if (null == row) continue;
			setPermission(row.getScope(), row.getObjectId(), row.getType(), row.getFunctions(), row.getApprovals());
		}
	}

	private static String trimmed(String s) {
		return null == s ? null : s.strip();
	}

	/**
	 * The updated template: a null field of the input keeps the stored value; a non-null permissions
	 * list REPLACES the set (the updateApiKeyGroupData rule). The owner and the organization never change.
	 */
	public static PermissionTemplateData updatePermissionTemplateData(PermissionTemplateData ptd, UpdatePermissionTemplateDto dto) {
		PermissionTemplateData updated = new PermissionTemplateData();
		updated.setUser(ptd.getUser());
		updated.setOrg(ptd.getOrg());
		updated.resourceGroup = ptd.getResourceGroup();
		updated.setName(null != dto.getName() ? trimmed(dto.getName()) : ptd.getName());
		updated.setDescription(null != dto.getDescription() ? trimmed(dto.getDescription()) : ptd.getDescription());
		if (null != dto.getPermissions()) {
			updated.addPermissions(dto.getPermissions());
		} else {
			updated.setPermissions(ptd.getPermissions());
		}
		return updated;
	}

	public static PermissionTemplateData permissionTemplateDataFactory(CreatePermissionTemplateDto createDto, UUID user) {
		PermissionTemplateData ptd = new PermissionTemplateData();
		ptd.setName(trimmed(createDto.getName()));
		ptd.setDescription(trimmed(createDto.getDescription()));
		ptd.setUser(user);
		ptd.setOrg(createDto.getOrg());
		ptd.addPermissions(createDto.getPermissions());
		return ptd;
	}

	public static PermissionTemplateData dataFromRecord(PermissionTemplate pt) {
		if (pt.getSchemaVersion() != 0) { // if schema version is not supported, throw exception
			throw new IllegalStateException("Permission template schema version is " + pt.getSchemaVersion()
			+ ", which is not currently supported");
		}
		Map<String,Object> recordData = pt.getRecordData();
		PermissionTemplateData ptd = Utils.OM.convertValue(recordData, PermissionTemplateData.class);
		ptd.setUuid(pt.getUuid());
		ptd.setCreatedDate(pt.getCreatedDate());
		return ptd;
	}

	public static PermissionTemplateWebDto toWebDto(PermissionTemplateData ptd) {
		return PermissionTemplateWebDto.builder()
				.uuid(ptd.getUuid())
				.name(ptd.getName())
				.description(ptd.getDescription())
				.org(ptd.getOrg())
				.user(ptd.getUser())
				.permissions(ptd.getPermissions())
				.createdDate(ptd.getCreatedDate())
				.lastUpdatedBy(ptd.getLastUpdatedBy())
				.build();
	}
}
