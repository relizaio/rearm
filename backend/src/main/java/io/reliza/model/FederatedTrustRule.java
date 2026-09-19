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

import tools.jackson.core.type.TypeReference;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import io.reliza.common.Utils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One trust rule of an organization: identity tokens from {@code issuer} whose claims satisfy
 * {@code matcher} act with {@code grant}. Edits bump {@code version}; access tokens minted through
 * the rule fingerprint the version, so an edit or a disable invalidates them at once. Pinning the
 * owner id on first use does not bump the version.
 */
@Data
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "federated_trust_rules")
public class FederatedTrustRule implements Serializable {
	private static final long serialVersionUID = 20260913L;

	public enum Provider { GITHUB_ACTIONS }
	public enum Status { ACTIVE, DISABLED }

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Provider provider = Provider.GITHUB_ACTIONS;

	@Column(nullable = false)
	private String issuer;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACTIVE;

	@Column(nullable = false)
	private int version = 1;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> matcher;

	@Type(JsonBinaryType.class)
	@Column(name = "grant_spec", columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> grantSpec;

	@Column(name = "pinned_owner_id")
	private String pinnedOwnerId;

	@Column(name = "expires_date")
	private ZonedDateTime expiresDate;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(name = "last_updated_date", nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(name = "last_used_date")
	private ZonedDateTime lastUsedDate;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "last_updated_by")
	private UUID lastUpdatedBy;

	public FederatedMatcher matcherOf() {
		FederatedMatcher m = matcher == null ? null : Utils.OM.convertValue(matcher, FederatedMatcher.class);
		return m == null ? new FederatedMatcher() : m;
	}

	public FederatedGrant grantOf() {
		FederatedGrant g = grantSpec == null ? null : Utils.OM.convertValue(grantSpec, FederatedGrant.class);
		return g == null ? new FederatedGrant() : g;
	}

	public void setMatcherOf(FederatedMatcher m) {
		this.matcher = Utils.OM.convertValue(m, new TypeReference<Map<String, Object>>() {});
	}

	public void setGrantOf(FederatedGrant g) {
		this.grantSpec = Utils.OM.convertValue(g, new TypeReference<Map<String, Object>>() {});
	}

	public boolean isUsable() {
		return status == Status.ACTIVE && (expiresDate == null || expiresDate.isAfter(ZonedDateTime.now()));
	}
}
