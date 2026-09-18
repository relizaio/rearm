/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A CLI browser-login request that becomes a session once the user approves it in the SPA.
 * Plain columns, no record_data: nothing here is user-editable and every field is queried.
 * The device code and refresh token are stored as SHA-256 hex digests only.
 */
@Data
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "cli_sessions")
public class CliSession implements Serializable {
	private static final long serialVersionUID = 20260912L;

	public enum Status { PENDING, ACTIVE, DENIED, REVOKED }

	@Id
	private UUID uuid = UUID.randomUUID();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.PENDING;

	@Column(name = "user_code", nullable = false)
	private String userCode;

	@Column(name = "device_code_hash")
	private String deviceCodeHash;

	@Column(name = "refresh_token_hash")
	private String refreshTokenHash;

	/** The refresh token retired by the last rotation, kept for the grace window (see CliSessionCodes.ROTATION_GRACE). */
	@Column(name = "previous_refresh_token_hash")
	private String previousRefreshTokenHash;

	@Column(name = "rotated_date")
	private ZonedDateTime rotatedDate;

	@Column(name = "api_key")
	private UUID apiKey;

	@Column(name = "\"user\"")
	private UUID user;

	@Column
	private UUID org;

	@Column(name = "requested_from")
	private String requestedFrom;

	/** Reported by the CLI (host name, os, time zone, client) and observed by the server (ip) at device-code time. */
	@Type(JsonBinaryType.class)
	@Column(name = "device_info", columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> deviceInfo;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(name = "approved_date")
	private ZonedDateTime approvedDate;

	@Column(name = "delivered_date")
	private ZonedDateTime deliveredDate;

	@Column(name = "expires_date", nullable = false)
	private ZonedDateTime expiresDate;

	@Column(name = "last_used_date")
	private ZonedDateTime lastUsedDate;

	@Column(name = "revoked_date")
	private ZonedDateTime revokedDate;
}
