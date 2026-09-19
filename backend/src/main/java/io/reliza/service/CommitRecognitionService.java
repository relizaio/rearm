/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AttestationData;
import io.reliza.model.AttestationData.ActorType;
import io.reliza.model.AttestationData.SubjectType;
import io.reliza.model.SignatureVerificationData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.AttributionState;
import io.reliza.model.SignatureVerificationData.SignatureSubjectType;
import io.reliza.model.SignatureVerificationData.SignatureVerificationState;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether anybody is accountable for a commit.
 *
 * <p>A commit is <em>recognized</em> when one of three things is true: it is signed by an enrolled
 * key, it is attributed to a live agent session by its trailers, or somebody has claimed it in an
 * attestation. Unsigned, unattributed and unclaimed is a commit nobody will answer for, and that
 * is the state every rule in this feature is written about.
 *
 * <p>The predicate is deliberately shared-layer and computed on demand from the three sources
 * rather than stored on the commit: a verdict can change when a key is enrolled or revoked, and
 * an attestation can arrive or be revoked long after the commit was written. A stored flag would
 * be wrong quietly.
 */
@Slf4j
@Service
public class CommitRecognitionService {

	@Autowired private SignatureVerificationService signatureVerificationService;
	@Autowired private AttestationService attestationService;
	@Autowired private AgentSessionService agentSessionService;

	/** How a commit stands, and why -- the "why" is what a refusal quotes back to the reader. */
	public record Recognition(boolean recognized, ClaimState claimState, String detail,
			ActorType claimActorType, UUID claimActor) {

		public static Recognition of(boolean recognized, ClaimState state, String detail) {
			return new Recognition(recognized, state, detail, null, null);
		}
	}

	/** What the attestations say about a commit, independent of signature and attribution. */
	public enum ClaimState {
		/** Nobody has said anything. */
		NONE,
		/** An active MINE claim, and no contradiction. */
		MINE,
		/** An active NOT_MINE disowning and no MINE. */
		NOT_MINE,
		/** MINE and NOT_MINE together, or MINE from two different principals. */
		CONFLICT
	}

	public Recognition recognize(SourceCodeEntryData sced) {
		if (sced == null) return Recognition.of(false, ClaimState.NONE, "no commit record");
		Map<UUID, Recognition> one = recognizeAll(sced.getOrg(), List.of(sced));
		return one.getOrDefault(sced.getUuid(), Recognition.of(false, ClaimState.NONE, "unknown"));
	}

	/**
	 * Recognition for a set of commits with three batched reads rather than three per commit.
	 * The release activation asks for every commit of a release at once, and so does the lock
	 * that captures the causes of a failing build.
	 */
	public Map<UUID, Recognition> recognizeAll(UUID org, Collection<SourceCodeEntryData> sces) {
		Map<UUID, Recognition> out = new HashMap<>();
		if (sces == null || sces.isEmpty()) return out;
		List<UUID> uuids = sces.stream().filter(s -> s != null && s.getUuid() != null)
				.map(SourceCodeEntryData::getUuid).toList();
		if (uuids.isEmpty()) return out;
		// Three batched reads for the whole set, not three per commit: the branch-history
		// variables ask about every commit of the last N releases at once, and per-commit
		// lookups there turn a four-query hydration into a hundred.
		Map<UUID, List<AttestationData>> attestations =
				attestationService.attestationsBySubjects(org, SubjectType.SCE, uuids);
		Map<UUID, SignatureVerificationData> signatures = signatureVerificationService
				.findLatestBySubjects(SignatureSubjectType.SCE, uuids);
		Set<UUID> blockedSessions = blockedSessions(sces);
		for (SourceCodeEntryData sced : sces) {
			if (sced == null || sced.getUuid() == null) continue;
			out.put(sced.getUuid(), recognizeOne(sced,
					attestations.getOrDefault(sced.getUuid(), List.of()),
					Optional.ofNullable(signatures.get(sced.getUuid())), blockedSessions));
		}
		return out;
	}

	private Recognition recognizeOne(SourceCodeEntryData sced, List<AttestationData> attestations,
			Optional<SignatureVerificationData> sig, Set<UUID> blockedSessions) {
		ClaimState claim = claimState(attestations);
		// The standing claim, not the first MINE row ever written: a principal who disowned a
		// commit later has no standing claim, and their old row must not be reported as one.
		Optional<AttestationData> mine = standingClaim(attestations);

		// 1. Signed by a key we know, and verified. The strongest form of accountability.
		boolean signedAndKnown = sig.isPresent()
				&& sig.get().getVerdict() == SignatureVerificationState.VERIFIED
				&& sig.get().getOwnerUuid() != null;

		// 2. Attributed by its trailers to a session that is not blocked. A blocked session is
		// one we have already decided not to trust, so its claim on a commit proves nothing.
		boolean attributed = sced.getAttributionState() == AttributionState.RESOLVED
				&& sced.getAgentSession() != null
				&& !blockedSessions.contains(sced.getAgentSession());

		// 3. Somebody claimed it. A disowning or a conflict is not a claim.
		boolean claimed = claim == ClaimState.MINE;

		if (signedAndKnown) {
			return new Recognition(true, claim, "signed by an enrolled key",
					mine.map(AttestationData::getActorType).orElse(null),
					mine.map(AttestationData::getActorUuid).orElse(null));
		}
		if (attributed) {
			return new Recognition(true, claim, "attributed to an agent session",
					mine.map(AttestationData::getActorType).orElse(null),
					mine.map(AttestationData::getActorUuid).orElse(null));
		}
		if (claimed) {
			AttestationData m = mine.get();
			return new Recognition(true, claim, "claimed by attestation",
					m.getActorType(), m.getActorUuid());
		}
		// The detail is the text a refusal shows, so it says what is missing rather than what is.
		StringBuilder why = new StringBuilder();
		why.append(sig.isEmpty() || sig.get().getVerdict() == null
				? "unsigned"
				: "signature " + sig.get().getVerdict().name().toLowerCase().replace('_', ' '));
		why.append(sced.getAttributionState() == AttributionState.REJECTED
				? ", attribution rejected"
				: ", no attribution");
		if (claim == ClaimState.NOT_MINE) why.append(", disowned");
		if (claim == ClaimState.CONFLICT) why.append(", conflicting claims");
		return Recognition.of(false, claim, why.toString());
	}

	/**
	 * What the claims add up to, one principal at a time.
	 *
	 * <p>Each principal's latest word is the only one that counts for them: somebody who disowns a
	 * commit and then realises it was theirs after all has changed their mind, not started an
	 * argument, and the log keeps both statements either way. Reading every attestation as
	 * simultaneous made that ordinary sequence come out CONFLICT, which is a state nothing
	 * resolves automatically -- so a person correcting themselves could not get out of it.
	 *
	 * <p>What is left is a real disagreement: two different principals each saying the commit is
	 * theirs. That one is not resolvable by rule and is exactly what an administrator should look
	 * at. One principal disowning while another claims is not a disagreement -- A saying "not
	 * mine" and B saying "mine" agree with each other -- so it reads as claimed by B.
	 */
	public ClaimState claimState(List<AttestationData> attestations) {
		Collection<AttestationData> standing = latestPerActor(attestations);
		if (standing.isEmpty()) return ClaimState.NONE;
		long claimants = standing.stream().filter(AttestationData::claimsSubject).count();
		if (claimants > 1) return ClaimState.CONFLICT;
		if (claimants == 1) return ClaimState.MINE;
		if (standing.stream().anyMatch(AttestationData::disownsSubject)) return ClaimState.NOT_MINE;
		return ClaimState.NONE;
	}

	/**
	 * Each principal's latest word, in the order they first spoke. Shared with the read surfaces:
	 * what the UI has to show for a contested commit is exactly this set -- who says what now --
	 * and computing it twice from different rules is how the two would drift apart.
	 */
	public Collection<AttestationData> latestPerActor(List<AttestationData> attestations) {
		if (attestations == null || attestations.isEmpty()) return List.of();
		Map<UUID, AttestationData> latest = new LinkedHashMap<>();
		for (AttestationData a : attestations) {
			if (a == null || (!a.claimsSubject() && !a.disownsSubject())) continue;
			AttestationData held = latest.get(a.getActorUuid());
			if (held == null || isNewer(a, held)) latest.put(a.getActorUuid(), a);
		}
		return latest.values();
	}

	/** Newer by creation time; a row without one loses, so a missing date cannot win by accident. */
	private static boolean isNewer(AttestationData candidate, AttestationData held) {
		if (candidate.getCreatedDate() == null) return false;
		if (held.getCreatedDate() == null) return true;
		return candidate.getCreatedDate().isAfter(held.getCreatedDate());
	}

	/** The attestation that makes this commit accountable, if one does: the standing MINE claim. */
	public Optional<AttestationData> standingClaim(List<AttestationData> attestations) {
		return latestPerActor(attestations).stream().filter(AttestationData::claimsSubject).findFirst();
	}

	/**
	 * The blocked sessions among those the commits claim, read once for the whole set. A session
	 * we have decided not to trust cannot lend a commit its accountability.
	 */
	private Set<UUID> blockedSessions(Collection<SourceCodeEntryData> sces) {
		Set<UUID> sessionUuids = sces.stream().filter(s -> s != null && s.getAgentSession() != null)
				.map(SourceCodeEntryData::getAgentSession).collect(java.util.stream.Collectors.toSet());
		if (sessionUuids.isEmpty()) return Set.of();
		Set<UUID> blocked = new java.util.HashSet<>();
		for (UUID sessionUuid : sessionUuids) {
			try {
				agentSessionService.getSessionData(sessionUuid).ifPresent(sd -> {
					if (sd.getStatus() != null && "BLOCKED".equals(sd.getStatus().name())) {
						blocked.add(sessionUuid);
					}
				});
			} catch (Exception e) {
				log.error("Could not resolve agent session {} while recognizing a commit: {}",
						sessionUuid, e.getMessage(), e);
			}
		}
		return blocked;
	}
}
