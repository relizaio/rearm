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

/**
 * One (type, value) credential pair owned by an {@link AgentIdentity}.
 * The flat table is what enforces global
 * {@code UNIQUE(identity_type, identity_value)} — jsonb-array
 * uniqueness across rows isn't expressible without triggers.
 *
 * Current types: {@code REARM_API_KEY}. Future: {@code OIDC},
 * {@code GITHUB_INSTALLATION}, etc. — slot in as new rows under the
 * same parent identity.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "agent_identity_credentials")
public class AgentIdentityCredential implements Serializable {
	private static final long serialVersionUID = 234738;

	public enum IdentityType {
		REARM_API_KEY
	}

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID agentIdentityUuid;

	@Column(nullable = false)
	private String identityType;

	@Column(nullable = false)
	private String identityValue;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }
	public UUID getAgentIdentityUuid() { return agentIdentityUuid; }
	public void setAgentIdentityUuid(UUID agentIdentityUuid) { this.agentIdentityUuid = agentIdentityUuid; }
	public String getIdentityType() { return identityType; }
	public void setIdentityType(String identityType) { this.identityType = identityType; }
	public String getIdentityValue() { return identityValue; }
	public void setIdentityValue(String identityValue) { this.identityValue = identityValue; }
	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }
}
