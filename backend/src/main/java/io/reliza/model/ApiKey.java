/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.reliza.common.CommonVariables;

@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="api_keys")
public class ApiKey implements Serializable, RelizaEntity, RelizaObject {
	
	public enum ApiTypeEnum {
		APPROVAL, // special blank type to be used only for approvals
		INSTANCE,
		CLUSTER,
		COMPONENT,
		ORGANIZATION,
		ORGANIZATION_RW,
		FREEFORM, // arbitrary-permissioned key for RBAC
		USER, // personal key owned by a user: its permissions are a ceiling, intersected with the owner's current permissions
		FEDERATED, // identity row for one external repository trusted by the org's federated trust rules: no secret, permissions computed from the rules on every call
		EXTERNAL; // a key handed to a party outside the organization: authenticates at the TEA token endpoint only, reads what the organization published on TEA, and nothing else

		/**
		 * Whether an id of this type must carry its key order ({@code __ord__<order>}) to name one key.
		 * Every FREEFORM, USER and FEDERATED row is minted with an order, and the org of a presented id of
		 * these types is resolved from the key row itself, so an id without an order (or with an empty one)
		 * names every key of its object rather than one, and is refused before any lookup.
		 * <p>
		 * The set equals {@code AuthHeaderParse.isRbacKey()} today, but the two answer different questions
		 * (the permission model versus the shape of the id) and stay separate, so a future type joins each
		 * on its own merits.
		 */
		public boolean requiresKeyOrder() {
			return this == FREEFORM || this == USER || this == FEDERATED;
		}
	}
	
	private static final long serialVersionUID = 2347342;
	
	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private int revision=0;
	
	@Column(nullable = false)
	private int schemaVersion=0;
	
	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();
	
	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();
	
	@Column(nullable = false)
	private UUID objectUuid;
	
	@Column(nullable = false)
	private String objectType;
	
	@Column(nullable = true)
	private String apiKey;
	
	@Column(nullable = false)
	private UUID org;
	
	@Column(nullable = true)
	private String keyOrder;

	@Column(nullable = true)
	private UUID createdBy;

	// Denormalised "most recent verified use" timestamp. Every verified credential touches
	// ApiKeyAccessService's in-memory accumulator, and the scheduler flushes it through the guarded
	// native ApiKeyRepository.touchLastAccessDateIfNewer, the only writer: read-only to JPA, so a
	// full-entity save of a key loaded earlier never puts back an older value. Lets the key lists
	// read the latest access without scanning the api_key_access audit table (which grows
	// unboundedly with traffic).
	@Column(nullable = true, insertable = false, updatable = false)
	private ZonedDateTime lastAccessDate;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String,Object> recordData;
	
	@Override
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return CommonVariables.DEFAULT_RESOURCE_GROUP;
	}

	@Override
	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(ZonedDateTime createdDate) {
		this.createdDate = createdDate;
	}

	@Override
	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}

	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) {
		this.lastUpdatedDate = lastUpdatedDate;
	}

	public UUID getObjectUuid() {
		return objectUuid;
	}

	public void setObjectUuid(UUID objectUuid) {
		this.objectUuid = objectUuid;
	}

	public ApiTypeEnum getObjectType() {
		return ApiTypeEnum.valueOf(objectType);
	}

	public void setObjectType(ApiTypeEnum objectType) {
		this.objectType = objectType.toString();
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}
	
	public String getKeyOrder() {
		return keyOrder;
	}

	public void setKeyOrder(String keyOrder) {
		this.keyOrder = keyOrder;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(UUID createdBy) {
		this.createdBy = createdBy;
	}

	public ZonedDateTime getLastAccessDate() {
		return lastAccessDate;
	}

	public void setLastAccessDate(ZonedDateTime lastAccessDate) {
		this.lastAccessDate = lastAccessDate;
	}

	@Override
	public UUID getOrg() {
		return org;
	}
	
	public void setOrg(UUID org) {
		this.org = org;
	}
	
	@Override
	public Map<String, Object> getRecordData() {
		return recordData;
	}

	@Override
	public void setRecordData(Map<String, Object> recordData) {
		this.recordData = recordData;
	}

	@Override
	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}
	
	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}
	
}
