/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A sticky refusal of write operations on a component or one of its branches.
 *
 * <p>Every other gate in ReARM evaluates a point in time -- a trigger sees one release, a guard
 * sees one promotion -- so a rule about a commit is defeated by pushing another commit on top of
 * it. A lock is the state that outlives the build: raised by a rule or by hand, it refuses the
 * next build outright and stays until somebody resolves the cause and says so on the record.
 *
 * <p>Stored as a JSONB list on the component or the branch, released locks included, so the
 * recent history reads without a join. The durable record of who released what and why is the
 * {@code LOCK_RELEASE} attestation, not this row -- which is why the list can be pruned.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComponentLock(
		@JsonProperty UUID uuid,
		@JsonProperty Scope scope,
		/** Set when {@link Scope#BRANCH}; the branch the lock sits on. */
		@JsonProperty UUID branch,
		@JsonProperty Status status,
		/** Shown verbatim in every refusal, so it is written for whoever hits it. */
		@JsonProperty String reason,
		@JsonProperty Origin origin,
		/** POLICY: the output event that raised it, which is also the idempotence key. */
		@JsonProperty UUID outputEvent,
		@JsonProperty List<LockCause> causes,
		/**
		 * Causes dropped by the retention cap. Above zero the checklist is incomplete, so the
		 * automatic release path refuses to act on it -- see {@link #automaticReleaseEligible()}.
		 */
		@JsonProperty int droppedCauses,
		@JsonProperty UnlockLevel unlockLevel,
		@JsonProperty AttestationRequirement attestationRequirement,
		/**
		 * The strongest level any cause has ever implied, kept because the cause that implied it
		 * may since have been dropped by the cap. A floor, never lowered: a disowned commit is an
		 * incident, and revoking the disowning does not un-happen it.
		 */
		@JsonProperty UnlockLevel escalatedLevel,
		@JsonProperty WhoUpdated raisedBy,
		@JsonProperty ZonedDateTime raisedAt,
		/** The LOCK_RELEASE attestation that released it. */
		@JsonProperty UUID releaseAttestation,
		@JsonProperty ZonedDateTime releasedAt) implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Locks on a component apply to all of it; locks on a branch apply to that branch alone. */
	public enum Scope { COMPONENT, BRANCH }

	public enum Status { ACTIVE, RELEASED }

	public enum Origin { MANUAL, POLICY }

	/** Who may release a lock. Ordered: later members are stricter. */
	public enum UnlockLevel { AGENT, HUMAN, ADMIN }

	/** What must be true of the causes before a release is allowed at all. */
	public enum AttestationRequirement {
		/** The level alone decides. */
		NONE,
		/** Every cause subject carries an active MINE attestation from any recognized principal. */
		ANY,
		/** Every cause subject carries an active MINE attestation filed by a user. */
		HUMAN
	}

	/** What kind of thing a cause points at. */
	public enum SubjectType { SCE, RELEASE }

	/**
	 * One thing that has to be resolved. A policy lock raised for a failing build carries the
	 * release plus every unrecognized commit in it, and folds later failing builds of the same
	 * rule into the same lock, which is how a lock comes to have many.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LockCause(
			@JsonProperty SubjectType subjectType,
			@JsonProperty UUID subjectUuid,
			/** Human text carried into the refusal: "unsigned, no attribution". */
			@JsonProperty String detail) implements Serializable {
		private static final long serialVersionUID = 1L;
	}

	/** Retention caps. Locks live on rows read on every build, so the list is bounded. */
	public static final int MAX_CAUSES = 50;
	public static final int MAX_RELEASED_LOCKS = 5;

	public boolean active() {
		return status == Status.ACTIVE;
	}

	/**
	 * The level that actually applies: the declared one, never below the escalation floor. The
	 * caller adds anything the causes it still holds imply on top.
	 */
	public UnlockLevel effectiveLevel() {
		UnlockLevel declared = unlockLevel == null ? UnlockLevel.ADMIN : unlockLevel;
		if (escalatedLevel == null) return declared;
		return escalatedLevel.ordinal() > declared.ordinal() ? escalatedLevel : declared;
	}

	/**
	 * Whether an attestation may release this lock without a person. False once a cause has been
	 * dropped: the checklist is no longer complete, so meeting it proves nothing. The rule that
	 * raised the lock is still the backstop -- if the dropped commits are still unrecognized, the
	 * next build locks again.
	 */
	public boolean automaticReleaseEligible() {
		return droppedCauses == 0 && effectiveLevel() == UnlockLevel.AGENT;
	}

	public static ComponentLock manual(Scope scope, UUID branch, String reason, UnlockLevel level,
			AttestationRequirement requirement, WhoUpdated wu) {
		return new ComponentLock(UUID.randomUUID(), scope, branch, Status.ACTIVE, reason, Origin.MANUAL,
				null, List.of(), 0, level, requirement, null, wu, ZonedDateTime.now(), null, null);
	}

	public static ComponentLock policy(Scope scope, UUID branch, String reason, UUID outputEvent,
			List<LockCause> causes, UnlockLevel level, AttestationRequirement requirement, WhoUpdated wu) {
		List<LockCause> held = causes == null ? List.of() : causes;
		int dropped = Math.max(0, held.size() - MAX_CAUSES);
		if (dropped > 0) held = held.subList(0, MAX_CAUSES);
		return new ComponentLock(UUID.randomUUID(), scope, branch, Status.ACTIVE, reason, Origin.POLICY,
				outputEvent, List.copyOf(held), dropped, level, requirement, null, wu,
				ZonedDateTime.now(), null, null);
	}

	/**
	 * This lock with more causes folded in, deduplicated by subject, capped, and with the count
	 * of what would not fit. Returns this lock unchanged when everything supplied is already a
	 * cause -- a rule that keeps failing on the same commits must not grow the row.
	 */
	public ComponentLock withCauses(List<LockCause> additional) {
		if (additional == null || additional.isEmpty()) return this;
		List<LockCause> merged = new ArrayList<>(causes == null ? List.of() : causes);
		int dropped = droppedCauses;
		boolean changed = false;
		for (LockCause c : additional) {
			if (c == null || c.subjectUuid() == null) continue;
			if (merged.stream().anyMatch(existing -> c.subjectUuid().equals(existing.subjectUuid()))) continue;
			if (merged.size() >= MAX_CAUSES) {
				dropped++;
			} else {
				merged.add(c);
			}
			changed = true;
		}
		if (!changed) return this;
		return new ComponentLock(uuid, scope, branch, status, reason, origin, outputEvent,
				List.copyOf(merged), dropped, unlockLevel, attestationRequirement, escalatedLevel,
				raisedBy, raisedAt, releaseAttestation, releasedAt);
	}

	/** This lock with its escalation floor raised, or unchanged when it is already at least that. */
	public ComponentLock withEscalation(UnlockLevel level) {
		if (level == null) return this;
		if (escalatedLevel != null && escalatedLevel.ordinal() >= level.ordinal()) return this;
		return new ComponentLock(uuid, scope, branch, status, reason, origin, outputEvent, causes,
				droppedCauses, unlockLevel, attestationRequirement, level, raisedBy, raisedAt,
				releaseAttestation, releasedAt);
	}

	public ComponentLock released(UUID lockReleaseAttestation) {
		return new ComponentLock(uuid, scope, branch, Status.RELEASED, reason, origin, outputEvent,
				causes, droppedCauses, unlockLevel, attestationRequirement, escalatedLevel, raisedBy,
				raisedAt, lockReleaseAttestation, ZonedDateTime.now());
	}
}
