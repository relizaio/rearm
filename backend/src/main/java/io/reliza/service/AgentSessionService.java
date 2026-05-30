/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentData.AgentType;
import io.reliza.model.AgentSession;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.SessionStatus;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.repositories.AgentSessionRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Lifecycle for {@link AgentSession}. The session always belongs to a
 * ROOT agent — {@link #initialize} rejects SUB agents (their commits
 * inherit the root's session via the commit-trailer attribution in
 * PR 2, but they never open sessions of their own).
 *
 * {@code lastActivityAt} is bumped on every state-changing mutation
 * and on explicit {@link #touch} so the dashboard "connected" pill
 * stays honest and the 72h inactivity autoclose path in PR 2/3
 * has a single field to scan.
 */
@Service
@Slf4j
public class AgentSessionService {

	@Autowired
	private AuditService auditService;

	@Autowired
	private AgentService agentService;

	/**
	 * Optional saas-side policy hook. Present only in SAAS builds —
	 * CE backend has no implementation, leaving session lifecycle
	 * un-gated by policies. Wired here rather than inside the
	 * GraphQL data fetcher so the {@code recordCommit} /
	 * {@code uploadAndAttachArtifacts} paths get evaluation even
	 * when invoked from background flows (e.g. PR 2's trailer-driven
	 * attribution).
	 */
	@Autowired(required = false)
	private AgentPolicyHook policyHook;

	@Autowired
	private ArtifactService artifactService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	private final AgentSessionRepository repository;

	AgentSessionService(AgentSessionRepository repository) {
		this.repository = repository;
	}

	public Optional<AgentSession> getSession(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<AgentSessionData> getSessionData(UUID uuid) {
		return getSession(uuid).map(AgentSessionData::dataFromRecord);
	}

	public Optional<AgentSessionData> getByClientSessionId(UUID orgUuid, UUID rootAgentUuid, String clientSessionId) {
		if (orgUuid == null || rootAgentUuid == null || StringUtils.isBlank(clientSessionId)) return Optional.empty();
		return repository.findByOrgAgentAndClientSessionId(
				orgUuid.toString(), rootAgentUuid.toString(), clientSessionId).map(AgentSessionData::dataFromRecord);
	}

	public List<AgentSessionData> listByOrg(UUID orgUuid, List<String> statuses) {
		if (orgUuid == null) return List.of();
		Iterable<AgentSession> rows = (statuses == null || statuses.isEmpty())
				? repository.findByOrg(orgUuid.toString())
				: repository.findByOrgAndStatuses(orgUuid.toString(), statuses);
		return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
				.map(AgentSessionData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<AgentSessionData> listByAgent(UUID rootAgentUuid, List<String> statuses) {
		if (rootAgentUuid == null) return List.of();
		Iterable<AgentSession> rows = (statuses == null || statuses.isEmpty())
				? repository.findByAgent(rootAgentUuid.toString())
				: repository.findByAgentAndStatuses(rootAgentUuid.toString(), statuses);
		return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
				.map(AgentSessionData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Open a new session for a ROOT agent. If {@code clientSessionId}
	 * matches an existing OPEN session for the same root, that session
	 * is returned (idempotent — supports agent crash recovery).
	 *
	 * Throws when {@code rootAgentUuid} resolves to a SUB agent —
	 * sub-agents share the root's session and don't open their own.
	 */
	@Transactional
	public AgentSessionData initialize(UUID orgUuid, UUID rootAgentUuid, UUID apiKeyUuid,
			String clientSessionId, String title, UUID parentSessionUuid, WhoUpdated wu) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("Session requires an org");
		if (rootAgentUuid == null) throw new RelizaException("Session requires an agent");
		AgentData agent = agentService.getAgentData(rootAgentUuid)
				.orElseThrow(() -> new RelizaException("Agent not found: " + rootAgentUuid));
		if (agent.getAgentType() != AgentType.ROOT) {
			throw new RelizaException(
					"Only ROOT agents can open sessions; agent " + rootAgentUuid + " is a SUB");
		}

		String effectiveClientId = StringUtils.isNotBlank(clientSessionId) ? clientSessionId : null;
		if (effectiveClientId != null && !AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.matcher(effectiveClientId).matches()) {
			throw new RelizaException("clientSessionId '" + effectiveClientId
					+ "' contains illegal characters — must match "
					+ AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.pattern()
					+ " (alphanumeric, dot, underscore, dash). See agentic plan \u00a76 for the on-the-wire trailer encoding that bounds this constraint.");
		}
		if (effectiveClientId != null) {
			// clientSessionId is permanently unique within (org, agent).
			// A previous session at ANY status (OPEN / CLOSED / BLOCKED)
			// reserves the id forever. Agents that retry after a
			// BLOCKED / CLOSED predecessor must mint a fresh id so the
			// audit trail threads attempts cleanly (link the retry via
			// parentSession on the new row).
			Optional<AgentSession> match = repository.findByOrgAgentAndClientSessionId(
					orgUuid.toString(), rootAgentUuid.toString(), effectiveClientId);
			if (match.isPresent()) {
				AgentSessionData existing = AgentSessionData.dataFromRecord(match.get());
				throw new RelizaException("clientSessionId '" + effectiveClientId
						+ "' is already used by session " + existing.getUuid()
						+ " (status=" + existing.getStatus() + "). clientSessionId is unique forever per (org, agent);"
						+ " pick a fresh id. If you are retrying after a BLOCKED/CLOSED predecessor, set"
						+ " parentSession=" + existing.getUuid() + " on the new init.");
			}
		}
		AgentSessionData seed = new AgentSessionData();
		UUID newUuid = UUID.randomUUID();
		AgentSession a = new AgentSession();
		a.setUuid(newUuid);
		seed.setOrg(orgUuid);
		seed.setAgent(rootAgentUuid);
		seed.setApiKey(apiKeyUuid);
		seed.setClientSessionId(effectiveClientId != null ? effectiveClientId : newUuid.toString());
		seed.setTitle(title);
		seed.setStatus(SessionStatus.OPEN);
		seed.setParentSession(parentSessionUuid);
		ZonedDateTime now = ZonedDateTime.now();
		seed.setStartedAt(now);
		seed.setLastActivityAt(now);

		// Evaluate input policies BEFORE persist. On BLOCK-severity
		// failure, the row STILL persists — at status=BLOCKED — so the
		// audit trail records the rejected attempt with its full
		// policyEvents history. Trailer-attribution refuses to bind
		// against BLOCKED sessions. CE backends have no policyHook bean
		// and skip the whole evaluation. WARN-severity FAILED and
		// PASSED verdicts go on the seed as-is.
		boolean blocked = false;
		if (policyHook != null) {
			try {
				List<AgentPolicyHook.PolicyEvent> events =
						policyHook.evaluateOnSessionInit(seed, agent);
				appendPolicyEvents(seed, events);
			} catch (AgentPolicyHook.PolicyBlockedException pb) {
				log.info("Session initialize rejected by policy {}: {} (persisting at BLOCKED)",
						pb.getFailingEvent().policyName(),
						pb.getMessage());
				appendPolicyEvent(seed, pb.getFailingEvent());
				seed.setStatus(SessionStatus.BLOCKED);
				seed.setClosedAt(now);
				blocked = true;
			}
		}

		Map<String, Object> recordData = Utils.dataToRecord(seed);
		try {
			AgentSession saved = save(a, recordData, wu);
			AgentSessionData persisted = AgentSessionData.dataFromRecord(saved);
			log.info("AgentSession uuid={} clientId='{}' agent={} org={} status={}",
					persisted.getUuid(), persisted.getClientSessionId(), rootAgentUuid, orgUuid,
					persisted.getStatus());
			return persisted;
		} catch (DataIntegrityViolationException e) {
			// Concurrent init on the same clientSessionId. With the
			// permanent-uniqueness rule the winner row is whatever lives
			// in DB now; surface a clear error rather than returning a
			// session the caller didn't ask for.
			log.info("Concurrent session initialize race for clientId='{}' agent={} — rejecting loser",
					seed.getClientSessionId(), rootAgentUuid);
			Optional<AgentSession> winnerOpt = repository.findByOrgAgentAndClientSessionId(
					orgUuid.toString(), rootAgentUuid.toString(), seed.getClientSessionId());
			if (winnerOpt.isPresent()) {
				AgentSessionData winnerData = AgentSessionData.dataFromRecord(winnerOpt.get());
				throw new RelizaException("clientSessionId '" + seed.getClientSessionId()
						+ "' was claimed by a concurrent session " + winnerData.getUuid()
						+ " (status=" + winnerData.getStatus() + "). Retry with a fresh id.");
			}
			throw new RelizaException("Session initialize race detected but no winning row found");
		}
	}

	/**
	 * Back-compat overload for callers that don't pass a parent
	 * session pointer. Equivalent to passing {@code null} for
	 * {@code parentSessionUuid}.
	 */
	@Transactional
	public AgentSessionData initialize(UUID orgUuid, UUID rootAgentUuid, UUID apiKeyUuid,
			String clientSessionId, String title, WhoUpdated wu) throws RelizaException {
		return initialize(orgUuid, rootAgentUuid, apiKeyUuid, clientSessionId, title, null, wu);
	}

	@Transactional
	public AgentSessionData touch(UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		AgentSession s = repository.findByIdWriteLocked(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		AgentSessionData sd = AgentSessionData.dataFromRecord(s);
		if (sd.getStatus() == SessionStatus.CLOSED || sd.getStatus() == SessionStatus.BLOCKED) {
			log.debug("touch on {} session {} — ignored", sd.getStatus(), sessionUuid);
			return sd;
		}
		sd.setLastActivityAt(ZonedDateTime.now());
		return saveData(sd, wu);
	}

	@Transactional
	public AgentSessionData close(UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		AgentSession s = repository.findByIdWriteLocked(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		AgentSessionData sd = AgentSessionData.dataFromRecord(s);
		if (sd.getStatus() == SessionStatus.CLOSED || sd.getStatus() == SessionStatus.BLOCKED) {
			return sd;
		}
		ZonedDateTime now = ZonedDateTime.now();
		sd.setStatus(SessionStatus.CLOSED);
		sd.setClosedAt(now);
		sd.setLastActivityAt(now);
		// CLOSE-kind agent policies lock their verdict here. Same
		// best-effort shape as the other hook calls — log on failure
		// but don't block the close. The session is in CLOSED status
		// at this point, so the CEL evaluator sees the closing state.
		if (policyHook != null) {
			try {
				AgentData agent = agentService.getAgentData(sd.getAgent()).orElse(null);
				if (agent != null) {
					List<AgentPolicyHook.PolicyEvent> events =
							policyHook.evaluateOnSessionClose(sd, agent);
					appendPolicyEvents(sd, events);
				}
			} catch (Exception e) {
				log.warn("Policy evaluation failed on session close for session {}: {}",
						sessionUuid, e.getMessage());
			}
		}
		return saveData(sd, wu);
	}

	/**
	 * Autoclose OPEN sessions with no activity since {@code idleCutoff}.
	 * Sets {@code status=CLOSED}, {@code closedAt=now}, leaves
	 * {@code lastActivityAt} pointing at the historical idle stamp
	 * (the closure itself isn't agent-driven activity — preserving the
	 * idle stamp keeps the audit honest about when the agent actually
	 * stopped).
	 *
	 * <p>Returns the count of rows closed. Driven by
	 * {@code SchedulingService.autocloseIdleAgentSessions()} which runs
	 * under an advisory lock so concurrent replicas don't race.
	 */
	@Transactional
	public int autoCloseIdleSessions(ZonedDateTime idleCutoff, WhoUpdated wu) {
		// JSONB storage for ZonedDateTime is epoch-seconds-with-nanos
		// (Jackson default), so the cutoff has to land in the same
		// numeric domain — see AgentSessionRepository.findOpenSessionsIdleBefore.
		double cutoffEpochSeconds = idleCutoff.toEpochSecond() + (idleCutoff.getNano() / 1_000_000_000.0);
		List<AgentSession> idle = repository.findOpenSessionsIdleBefore(cutoffEpochSeconds);
		int closed = 0;
		ZonedDateTime now = ZonedDateTime.now();
		for (AgentSession s : idle) {
			try {
				AgentSession locked = repository.findByIdWriteLocked(s.getUuid()).orElse(null);
				if (locked == null) continue;
				AgentSessionData sd = AgentSessionData.dataFromRecord(locked);
				// Re-check under lock — another touch may have raced
				// past the query and rescued the session.
				if (sd.getStatus() != SessionStatus.OPEN) continue;
				if (sd.getLastActivityAt() != null && sd.getLastActivityAt().isAfter(idleCutoff)) continue;
				sd.setStatus(SessionStatus.CLOSED);
				sd.setClosedAt(now);
				// Same CLOSE-kind verdict lock as the explicit close
				// path. Idle-autoclose is a normal close from the
				// policy engine's standpoint; the session went terminal
				// without the agent ever filing what it owed.
				if (policyHook != null) {
					try {
						AgentData agent = agentService.getAgentData(sd.getAgent()).orElse(null);
						if (agent != null) {
							List<AgentPolicyHook.PolicyEvent> events =
									policyHook.evaluateOnSessionClose(sd, agent);
							appendPolicyEvents(sd, events);
						}
					} catch (Exception e) {
						log.warn("Policy evaluation failed on idle autoclose for session {}: {}",
								s.getUuid(), e.getMessage());
					}
				}
				saveData(sd, wu);
				closed++;
			} catch (Exception e) {
				log.error("autoclose failed for session {}", s.getUuid(), e);
			}
		}
		return closed;
	}

	/**
	 * Upload one or more artifact files via {@link ArtifactService} and
	 * bind each resulting artifact uuid to the session. Artifacts created
	 * via this path carry {@code belongsTo=AGENT_SESSION} and do not
	 * appear on any release or component — the session owns them outright.
	 *
	 * Rejects when the session is BLOCKED (audit-only — can't accept new
	 * artifacts) or CLOSED (terminal). Re-evaluates input + output
	 * policies on each artifact attach so an orientation-required gate
	 * flips from PENDING to PASSED as soon as the AGENTIC_REPORT lands.
	 */
	@Transactional
	public AgentSessionData uploadAndAttachArtifacts(UUID sessionUuid,
			List<Map<String, Object>> artifactsInput, WhoUpdated wu) throws RelizaException {
		if (artifactsInput == null || artifactsInput.isEmpty()) {
			throw new RelizaException("artifacts is required and must be non-empty");
		}
		AgentSession s = repository.findByIdWriteLocked(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		AgentSessionData sd = AgentSessionData.dataFromRecord(s);
		if (sd.getStatus() == SessionStatus.BLOCKED) {
			throw new RelizaException("Session " + sessionUuid + " is BLOCKED (failed an INPUT policy on init)"
					+ " — cannot add artifacts. Mint a fresh clientSessionId via sessionInitializeProgrammatic"
					+ " (set parentSession=" + sessionUuid + " to thread the audit trail).");
		}
		if (sd.getStatus() == SessionStatus.CLOSED) {
			throw new RelizaException("Session " + sessionUuid + " is CLOSED — cannot add artifacts.");
		}
		OrganizationData od = getOrganizationService.getOrganizationData(sd.getOrg())
				.orElseThrow(() -> new RelizaException("Org not found for session: " + sd.getOrg()));
		// Session-owned artifacts have no release/component context, so
		// name/group/version on RebomOptions are null. belongsTo is the
		// signal both for storage routing and for downstream policy/audit
		// classification.
		RebomOptions rebomOptions = new RebomOptions(null, null, null,
				ArtifactBelongsTo.AGENT_SESSION, null, StripBom.FALSE, null);
		List<UUID> artIds = artifactService.uploadListOfArtifacts(od, artifactsInput, rebomOptions, wu);
		for (UUID artId : artIds) {
			sd.addArtifact(artId);
		}
		sd.setLastActivityAt(ZonedDateTime.now());
		if (policyHook != null) {
			try {
				AgentData agent = agentService.getAgentData(sd.getAgent()).orElse(null);
				if (agent != null) {
					for (UUID artId : artIds) {
						List<AgentPolicyHook.PolicyEvent> events =
								policyHook.evaluateOnArtifactAttach(sd, agent, artId);
						appendPolicyEvents(sd, events);
					}
				}
			} catch (Exception e) {
				log.warn("Policy evaluation failed on artifact upload for session {}: {}",
						sessionUuid, e.getMessage());
			}
		}
		return saveData(sd, wu);
	}

	/**
	 * Record an SCE on the session's commits list. Called by
	 * {@link SourceCodeEntryService} when the PR 2 commit-trailer
	 * parser resolves a new SCE to this session. Bumps
	 * {@code lastActivityAt} on OPEN sessions so the dashboard
	 * "connected" pill stays fresh. Idempotent — repeated calls on
	 * the same SCE are no-ops.
	 *
	 * Defensive: a CLOSED session that receives a late commit (e.g.
	 * close-races-a-final-push) still records the SCE so the historical
	 * attribution survives, but does NOT re-open the session — CLOSED
	 * is terminal by design (§3.2).
	 */
	@Transactional
	public AgentSessionData recordCommit(UUID sessionUuid, UUID sceUuid, WhoUpdated wu)
			throws RelizaException {
		if (sceUuid == null) throw new RelizaException("sceUuid is required");
		AgentSession s = repository.findByIdWriteLocked(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		AgentSessionData sd = AgentSessionData.dataFromRecord(s);
		boolean alreadyPresent = sd.getCommits() != null && sd.getCommits().contains(sceUuid);
		if (alreadyPresent) return sd;
		sd.addCommit(sceUuid);
		if (sd.getStatus() == SessionStatus.OPEN) {
			sd.setLastActivityAt(ZonedDateTime.now());
		}
		// Re-evaluate output policies (PR 4) when a new commit is
		// attributed. Same shape as attachArtifact — failures are
		// recorded but never block the underlying mutation.
		if (policyHook != null) {
			try {
				AgentData agent = agentService.getAgentData(sd.getAgent()).orElse(null);
				if (agent != null) {
					List<AgentPolicyHook.PolicyEvent> events =
							policyHook.evaluateOnCommitAttributed(sd, agent, sceUuid);
					appendPolicyEvents(sd, events);
				}
			} catch (Exception e) {
				log.warn("Policy evaluation failed on commit attribution for session {}: {}",
						sessionUuid, e.getMessage());
			}
		}
		return saveData(sd, wu);
	}

	@Transactional
	public AgentSessionData updateMeta(UUID sessionUuid, String title, String clientSessionId,
			WhoUpdated wu) throws RelizaException {
		AgentSession s = repository.findByIdWriteLocked(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		AgentSessionData sd = AgentSessionData.dataFromRecord(s);
		if (title != null) sd.setTitle(title);
		if (StringUtils.isNotBlank(clientSessionId)) {
			if (!AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.matcher(clientSessionId).matches()) {
				throw new RelizaException("clientSessionId '" + clientSessionId
						+ "' contains illegal characters — must match "
						+ AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.pattern()
						+ " (alphanumeric, dot, underscore, dash).");
			}
			sd.setClientSessionId(clientSessionId);
		}
		sd.setLastActivityAt(ZonedDateTime.now());
		return saveData(sd, wu);
	}

	@Transactional
	public AgentSessionData saveData(AgentSessionData sd, WhoUpdated wu) {
		AgentSession s = repository.findById(sd.getUuid())
				.orElseGet(() -> {
					AgentSession fresh = new AgentSession();
					fresh.setUuid(sd.getUuid() != null ? sd.getUuid() : UUID.randomUUID());
					return fresh;
				});
		Map<String, Object> recordData = Utils.dataToRecord(sd);
		AgentSession saved = save(s, recordData, wu);
		return AgentSessionData.dataFromRecord(saved);
	}

	/**
	 * Append {@link AgentPolicyHook.PolicyEvent} verdicts to the
	 * session's policyEvents log. Stored as typed records so the
	 * ZonedDateTime field round-trips through the DateTime GraphQL
	 * coercer.
	 */
	private void appendPolicyEvents(AgentSessionData sd, List<AgentPolicyHook.PolicyEvent> events) {
		if (events == null || events.isEmpty()) return;
		for (AgentPolicyHook.PolicyEvent ev : events) {
			sd.addPolicyEvent(ev);
		}
	}

	private void appendPolicyEvent(AgentSessionData sd, AgentPolicyHook.PolicyEvent event) {
		if (event == null) return;
		sd.addPolicyEvent(event);
	}

	private AgentSession save(AgentSession s, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<AgentSession> existing = repository.findById(s.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.AGENT_SESSIONS, s);
			s.setRevision(s.getRevision() + 1);
			s.setLastUpdatedDate(ZonedDateTime.now());
		}
		s.setRecordData(recordData);
		s = (AgentSession) WhoUpdated.injectWhoUpdatedData(s, wu);
		return repository.save(s);
	}
}
