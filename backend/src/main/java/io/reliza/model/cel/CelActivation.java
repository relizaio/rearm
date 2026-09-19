/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.cel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

/**
 * The shapes a CEL expression sees, as Java objects.
 *
 * <p>The activation handed to the runtime is a nested {@code Map<String, Object>}, and it stays
 * one: the map is a published contract -- customer expressions are written against these exact
 * key names, and absent-key and empty-string semantics are part of what they rely on. What these
 * records buy is on the Java side of that boundary. A key name is written once, in a component
 * name the compiler checks; the default for a missing value is decided once, in the factory that
 * builds the record, instead of at every call site that used to repeat
 * {@code x != null ? x.toString() : ""}; and a shape can be constructed and asserted in a test
 * without going through a map at all.
 *
 * <p>Each record therefore has exactly one {@code toMap()}, and those are the only places a key
 * string exists. {@code ActivationShapeTest} pins the resulting shape path by path.
 *
 * <p>Not native CEL types, deliberately. cel-java can bind plain Java objects through
 * {@code CelNativeTypesExtensions}, which would remove the conversion entirely -- but it also
 * changes how fields resolve and how the checker treats an expression that names something the
 * object does not have. That is a change to the contract customers author against, and it wants
 * its own decision rather than arriving as a side effect of tidying up construction.
 */
public final class CelActivation {

	private CelActivation() {}

	/** Null-safe string: every string in the activation is present and empty rather than absent. */
	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}

	/** {@code commit.signature.*} -- what is known about who signed this commit. */
	public record Signature(String state, String format, String signedByOwnerType,
			String signedByOwnerUuid, String keyFingerprint, String verifiedAt) {

		/** No verification on record. UNSIGNED rather than empty: it is a verdict, not a gap. */
		public static Signature unsigned() {
			return new Signature("UNSIGNED", "", "", "", "", "");
		}

		public static Signature of(String state, String format, String ownerType, UUID ownerUuid,
				String keyFingerprint, Object verifiedAt) {
			return new Signature(StringUtils.defaultIfEmpty(state, "UNSIGNED"), str(format),
					str(ownerType), str(ownerUuid), str(keyFingerprint), str(verifiedAt));
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("state", state);
			m.put("format", format);
			m.put("signedByOwnerType", signedByOwnerType);
			m.put("signedByOwnerUuid", signedByOwnerUuid);
			m.put("keyFingerprint", keyFingerprint);
			m.put("verifiedAt", verifiedAt);
			return m;
		}
	}

	/** {@code commit.attribution.*} -- whether a human owner was resolved for the commit. */
	public record Attribution(String state, boolean resolved, boolean rejected, String reason) {

		public static Attribution of(String state, String reason, String resolvedName,
				String rejectedName) {
			String s = StringUtils.defaultIfEmpty(state, "UNATTRIBUTED");
			return new Attribution(s, s.equals(resolvedName), s.equals(rejectedName), str(reason));
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("state", state);
			m.put("resolved", resolved);
			m.put("rejected", rejected);
			m.put("reason", reason);
			return m;
		}
	}

	/** {@code commit.attestation.*} -- the claim, if any, somebody has filed on this commit. */
	public record Attestation(String state, String actorType, String actor, String detail) {

		/** Nobody has claimed it, or recognition was not computed for this activation. */
		public static Attestation none() {
			return new Attestation("NONE", "", "", "");
		}

		public static Attestation of(String state, String actorType, UUID actor, String detail) {
			return new Attestation(StringUtils.defaultIfEmpty(state, "NONE"), str(actorType),
					str(actor), str(detail));
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("state", state);
			m.put("actorType", actorType);
			m.put("actor", actor);
			m.put("detail", detail);
			return m;
		}
	}

	/**
	 * One commit, in the shape shared by {@code release.commits[]}, {@code release.headCommit}
	 * and {@code branch.commits[]} -- one record so a rule written against one reads identically
	 * against the others.
	 */
	public record Commit(String uuid, String commit, String vcsBranch, String commitAuthor,
			String commitEmail, String commitMessage, String agent, String agentSession,
			Signature signature, Attribution attribution, boolean recognized,
			Attestation attestation) {

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", uuid);
			m.put("commit", commit);
			m.put("vcsBranch", vcsBranch);
			m.put("commitAuthor", commitAuthor);
			m.put("commitEmail", commitEmail);
			m.put("commitMessage", commitMessage);
			m.put("agent", agent);
			m.put("agentSession", agentSession);
			m.put("signature", signature.toMap());
			m.put("attribution", attribution.toMap());
			m.put("recognized", recognized);
			m.put("attestation", attestation.toMap());
			return m;
		}
	}

	/** {@code release.agentSessions[]} -- one session, with its policy state aggregated. */
	public record Session(String uuid, String clientSessionId, String agent, String agentName,
			String status, List<String> failedPolicies, List<String> awaitingPolicies) {

		public boolean hasFailedPolicy() { return !failedPolicies.isEmpty(); }

		public boolean hasAwaitingPolicy() { return !awaitingPolicies.isEmpty(); }

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", uuid);
			m.put("clientSessionId", clientSessionId);
			m.put("agent", agent);
			m.put("agentName", agentName);
			m.put("status", status);
			m.put("failedPolicies", failedPolicies);
			m.put("awaitingPolicies", awaitingPolicies);
			m.put("hasFailedPolicy", hasFailedPolicy());
			m.put("hasAwaitingPolicy", hasAwaitingPolicy());
			return m;
		}
	}

	/** {@code release.dependencies[]} -- one direct parent release. */
	public record Dependency(String release, String version, String lifecycle, String component,
			String componentName, boolean external, long maturity, boolean supported,
			String specification) {

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("release", release);
			m.put("version", version);
			m.put("lifecycle", lifecycle);
			m.put("component", component);
			m.put("componentName", componentName);
			m.put("external", external);
			m.put("maturity", maturity);
			m.put("supported", supported);
			m.put("specification", specification);
			return m;
		}
	}

	/** {@code branch.*} -- the branch's recent history and whether it is locked. */
	public record BranchHistory(boolean locked, List<Commit> commits, long unrecognizedCommits) {

		/** What a caller that did not ask for branch history sees: present, and empty. */
		public static BranchHistory empty() {
			return new BranchHistory(false, List.of(), 0L);
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("locked", locked);
			List<Map<String, Object>> cs = new ArrayList<>();
			commits.forEach(c -> cs.add(c.toMap()));
			m.put("commits", cs);
			m.put("unrecognizedCommits", unrecognizedCommits);
			return m;
		}
	}
}
