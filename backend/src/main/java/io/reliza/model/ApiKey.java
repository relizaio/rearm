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
		USER;
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
