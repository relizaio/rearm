/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyData extends RelizaDataParent implements RelizaObject {
	
	private UUID uuid;
	private UUID org;
	@JsonProperty(CommonVariables.VERSION_FIELD)
	private Integer version = 0; // we won't store actual key hash here, 
								 // but we'll store version increment for key rotations for audit
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions = new Permissions();

	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes;
	
	public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
		permissions.setPermission(orgUuid, scope, objectUuid, type, approvals);
	}
	
	public boolean revokePermission (UUID orgUuid, PermissionScope scope, UUID objectUuid) {
		return permissions.revokePermission(orgUuid, scope, objectUuid);
	}
	
	public boolean revokeAllOrgPermissions (UUID orgUuid) {
		return permissions.revokeAllOrgPermissions(orgUuid);
	}
	
	public Permissions getPermissions (UUID orgId) {
		return this.permissions.cloneOrgPermissions(orgId);
	}
	
	public Optional<UserPermission> getPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid) {
		return permissions.getPermission(orgUuid, scope, objectUuid);
	}
	
	public String getNotes() {
		return notes;
	}
	
	public void setNotes(String notes) {
		this.notes = notes;
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return CommonVariables.DEFAULT_RESOURCE_GROUP;
	}

	public static ApiKeyData apiKeyDataFactory (UUID orgUuid) {
		ApiKeyData akd = new ApiKeyData();
		akd.setOrg(orgUuid);
		return akd;
	}
	
	public static ApiKeyData dataFromRecord (ApiKey ak) {
		if (ak.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Api Key repository schema version is " + ak.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = ak.getRecordData();
		ApiKeyData akd = Utils.OM.convertValue(recordData, ApiKeyData.class);
		// akd may be empty, if the case, initialize
		if (null == akd) {
			akd = apiKeyDataFactory(ak.getOrg());
		}
		akd.setUuid(ak.getUuid());
		akd.setOrg(ak.getOrg());
		return akd;
	}
}
