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
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
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

	/** Kill switch for the whole key id: INACTIVE refuses every secret and every token issued for the key. */
	/** REVOKED is the delete tombstone: the row stays for the unique index, and every read path filters it out. */
	public enum ApiKeyStatus { ACTIVE, INACTIVE, REVOKED,
		/** a free-form key a write user asked for; visible, never usable, until an admin approves (ACTIVE) or denies (DENIED) */
		REQUESTED, DENIED }

	/**
	 * One of up to two secrets of a key id (AWS-style rotation: add the second, move clients,
	 * retire the first). Only the Argon2 hash is stored. Rows written before rotation existed
	 * carry their single hash in the api_key column; {@link #effectiveSecrets(String)} maps that
	 * to slot 1 until the first write materialises the list.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ApiKeySecret implements Serializable {
		private static final long serialVersionUID = 1L;
		@JsonProperty
		private int slot;
		@JsonProperty
		private String hash;
		@JsonProperty
		private boolean active = true;
		@JsonProperty
		private ZonedDateTime createdDate;
		@JsonProperty
		private ZonedDateTime lastUsedDate;
		/** optional hard stop: after this instant the secret is refused like a retired one, and tokens minted with it end here at the latest */
		@JsonProperty
		private ZonedDateTime expiresDate;

		public ApiKeySecret(int slot, String hash, boolean active, ZonedDateTime createdDate, ZonedDateTime lastUsedDate) {
			this(slot, hash, active, createdDate, lastUsedDate, null);
		}

		public boolean isExpired() {
			return expiresDate != null && !expiresDate.isAfter(ZonedDateTime.now());
		}

		/** active and not expired: the only state in which a secret authenticates */
		public boolean isUsable() {
			return active && !isExpired();
		}
	}

	public static final int MAX_SECRETS = 2;

	@JsonProperty
	private ApiKeyStatus status = ApiKeyStatus.ACTIVE;
	@JsonProperty
	private List<ApiKeySecret> secrets = new LinkedList<>();
	/** FREEFORM keys only: the user who requested the key and the only one who may mint or regenerate its secrets */
	@JsonProperty
	private UUID holder;
	/** where the key came from: created by hand, or by a CLI browser login for that session (deleted with the session) */
	public enum ApiKeyOrigin { MANUAL, CLI_SESSION }
	@JsonProperty
	private ApiKeyOrigin origin = ApiKeyOrigin.MANUAL;
	public ApiKeyOrigin getOrigin() { return origin == null ? ApiKeyOrigin.MANUAL : origin; }

	/** set when an org admin deactivated the key: the owner or holder cannot re-activate it, only an admin can */
	@JsonProperty
	private boolean adminDisabled;

	/** FEDERATED rows only: the external repository this identity stands for, with its pinned ids. */
	@JsonProperty
	private FederatedIdentity federation;

	public ApiKeyStatus getStatus() { return status == null ? ApiKeyStatus.ACTIVE : status; }
	public List<ApiKeySecret> getSecrets() { return secrets == null ? new LinkedList<>() : secrets; }

	/** Secrets to try, legacy column included when the list was never materialised. */
	public List<ApiKeySecret> effectiveSecrets(String legacyColumnHash) {
		List<ApiKeySecret> list = getSecrets();
		if (!list.isEmpty()) return list;
		if (legacyColumnHash == null) return List.of();
		return List.of(new ApiKeySecret(1, legacyColumnHash, true, null, null));
	}

	public Optional<ApiKeySecret> secret(int slot) {
		return getSecrets().stream().filter(x -> x.getSlot() == slot).findFirst();
	}
	
	public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
		permissions.setPermission(orgUuid, scope, objectUuid, type, approvals);
	}

	public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type,
			Collection<PermissionFunction> functions, Collection<String> approvals) {
		permissions.setPermission(orgUuid, scope, objectUuid, type, functions, approvals);
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
