/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="api_key_access")
public class ApiKeyAccess implements Serializable {
	private static final long serialVersionUID = 234734;
	
	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private ZonedDateTime accessDate = ZonedDateTime.now();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = true)
	private String notes;

	@Column(nullable = false)
	private String ipAddress;

	@Column(nullable = true)
	private String apiKeyId;

	@Column(nullable = false)
	private UUID apiKeyUuid;

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public ZonedDateTime getAccessDate() {
		return accessDate;
	}

	public void setAccessDate(ZonedDateTime accessDate) {
		this.accessDate = accessDate;
	}	
	
	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public void setIpAddress(String ipAddress) {
		this.ipAddress = ipAddress;
	}

	public String getApiKeyId() {
		return apiKeyId;
	}

	public void setApiKeyId(String apiKeyId) {
		this.apiKeyId = apiKeyId;
	}

	public UUID getApiKeyUuid() {
		return apiKeyUuid;
	}

	public void setApiKeyUuid(UUID apiKeyUuid) {
		this.apiKeyUuid = apiKeyUuid;
	}
}
