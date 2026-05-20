/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * A natural person (or external bot) the org has enrolled signing keys
 * for. The {@link #email} is the admin-side natural key — it's the
 * column the operator uses to find / manage a committer and the source
 * of the {@code (org, lower(email))} uniqueness constraint. It is NOT
 * what the verifier matches signatures by: in v1 the cryptographic
 * match goes via signature → fingerprint → {@code signing_keys} row →
 * {@code (ownerType, ownerUuid)}, and the commit's author/committer
 * header is not consulted. The principal a signature must verify
 * against (SSH allowed_signers line, GPG user-id) lives on
 * {@link SigningKeyData#getIdentity()} — typically equal to this email
 * but not enforced.
 *
 * <p>{@link #user} is optional: a committer can exist without a ReARM
 * user (external contributors), and a committer record outlives the
 * user record so commits authored by ex-employees keep verifying.
 *
 * <p>Email aliases over time live in {@link #aliases} so they share the
 * committer row instead of colliding on uniqueness.
 *
 * <p>v1.1 follow-ups (see {@code ai-plans/agentic/README.md §12.8}):
 * trust-on-first-use enrolment will use the commit author email +
 * observed fingerprint as the binding signal; an optional CEL-shaped
 * policy may cross-check the commit author against the matched
 * committer's email + aliases.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommitterData extends RelizaDataParent implements RelizaObject {

	public enum CommitterStatus {
		ACTIVE,
		ARCHIVED
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/** Display name. */
	@JsonProperty
	private String name;

	/**
	 * Primary email — the admin-side natural key for this committer
	 * row. {@code (org, lower(email))} is the uniqueness boundary
	 * (V42 partial unique index). NOT the runtime signature-match key
	 * — see the class javadoc and {@link SigningKeyData#getIdentity()}.
	 */
	@JsonProperty
	private String email;

	/**
	 * Optional binding to a ReARM users row. May be null for external
	 * contributors, or for committers whose user record was archived.
	 */
	@JsonProperty
	private UUID user;

	/**
	 * Historical email aliases — same committer, different addresses
	 * over time. Each entry is normalised lowercase.
	 */
	@JsonProperty
	private List<String> aliases = new ArrayList<>();

	@JsonProperty(CommonVariables.STATUS_FIELD)
	private CommitterStatus status = CommitterStatus.ACTIVE;

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static CommitterData dataFromRecord(Committer c) {
		if (c.getSchemaVersion() != 0) {
			throw new IllegalStateException("Committer schema version is " + c.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = c.getRecordData();
		CommitterData cd = Utils.OM.convertValue(recordData, CommitterData.class);
		cd.setUuid(c.getUuid());
		cd.setCreatedDate(c.getCreatedDate());
		return cd;
	}
}
