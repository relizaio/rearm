/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Who did something on a board: the identity written onto a lock, a board event, a hold and
 * a human sign-off.
 *
 * <p>These four fields used to be {@code String}, and every writer invented its own encoding:
 * {@code "coordinator-session:" + sessionUuid}, a user's email, the bare literal
 * {@code "operator"}, {@code "system:humanGate"}. A reader wanting the session that locked a
 * board had to know the prefix convention and split on a colon; a reader wanting to tell a
 * human from an agent had to guess from the shape of the text. The operator pause, which took
 * the literal, recorded no identity at all -- an audit field naming nobody.
 *
 * <p>So the kind is declared and the identity is carried in the field that suits it: {@code uuid}
 * for a session or a user row, {@code name} for what a human should read. Both may be present;
 * {@code kind} is never null.
 *
 * @param kind which identity space uuid belongs to
 * @param uuid the session or user uuid, when the writer knew it
 * @param name display text -- an email for a user, a detail for a system actor
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentActor(@JsonProperty("kind") ActorKind kind,
		@JsonProperty("uuid") UUID uuid,
		@JsonProperty("name") String name) implements Serializable {

	private static final long serialVersionUID = 20260920L;

	/**
	 * SESSION is an agent session; USER a human; SYSTEM the application acting on its own
	 * (a scheduler, a session-close sweep) with no one to attribute it to.
	 */
	public enum ActorKind { SESSION, USER, SYSTEM }

	/** An agent session, identified by its uuid. */
	public static AgentActor ofSession(UUID session) {
		return new AgentActor(ActorKind.SESSION, session, null);
	}

	/** A human. The uuid is the user row when the caller has it; the name is what gets displayed. */
	public static AgentActor ofUser(UUID user, String email) {
		return new AgentActor(ActorKind.USER, user, email);
	}

	/** The application itself. {@code detail} says which part, e.g. "humanGate". */
	public static AgentActor system(String detail) {
		return new AgentActor(ActorKind.SYSTEM, null, detail);
	}

	/**
	 * Read a stored actor that predates this record.
	 *
	 * <p>The four fields live in JSONB, so every board, event, hold and sign-off written before
	 * this change holds a bare string. Without this, reading one of those rows fails
	 * deserialization and takes the whole board with it -- so the historical encodings are
	 * decoded here rather than migrated in the database, which would have to be repeated in every
	 * environment and would still miss a row written by an older node mid-deploy.
	 *
	 * <p>Jackson picks this up for a JSON string and the canonical constructor for a JSON object,
	 * so new rows are unaffected.
	 */
	@JsonCreator
	public static AgentActor fromLegacy(String stored) {
		if (StringUtils.isBlank(stored)) return null;
		if (stored.startsWith("coordinator-session:")) {
			String raw = stored.substring("coordinator-session:".length());
			try {
				return ofSession(UUID.fromString(raw));
			} catch (IllegalArgumentException e) {
				// Not a uuid after the prefix: keep the text rather than losing the record.
				return new AgentActor(ActorKind.SESSION, null, raw);
			}
		}
		if (stored.startsWith("system:")) {
			return system(stored.substring("system:".length()));
		}
		// A bare uuid is a session: that is what StatusChange.actor held before it was typed,
		// and none of the other fields ever stored one -- they wrote a prefix, an email or the
		// "operator" literal.
		try {
			return ofSession(UUID.fromString(stored));
		} catch (IllegalArgumentException notAUuid) {
			// Everything else was a human: an email from the JWT paths, or the "operator" literal.
			return new AgentActor(ActorKind.USER, null, stored);
		}
	}

	/** One line naming this actor, for log lines and event messages. */
	public String display() {
		if (StringUtils.isNotBlank(name)) return name;
		if (null != uuid) return kind + " " + uuid;
		return kind.toString();
	}
}
