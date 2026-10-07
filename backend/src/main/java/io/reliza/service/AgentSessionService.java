/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.EntityManager;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
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

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private AgentService agentService;

	@Autowired
	private AgentTaskService agentTaskService;

	@Autowired
	private AgentBoardService agentBoardService;

	@Autowired
	@Lazy
	private AgentBoardNotifier boardNotifier;

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

	/**
	 * Release everything a closing session holds in the agent task
	 * board world: task assignments (returned to the coordinator with
	 * SESSION_CLOSED records) and any coordinator seat. Runs on every
	 * close path — agent close, idle auto-close, operator force-close.
	 * Best-effort: a failure here must not block the close itself.
	 */
	private void releaseAgentTaskHolds(UUID sessionUuid, String note, WhoUpdated wu) {
		try {
			agentTaskService.unassignForSession(sessionUuid, note, wu);
			agentBoardService.releaseSeatsForSession(sessionUuid, note, wu);
		} catch (Exception e) {
			log.error("Failed to release task/board holds for closing session {}", sessionUuid, e);
		}
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
	 * Per-status session counts for one ROOT agent, aggregated in SQL.
	 * Statuses with no sessions are absent from the map.
	 */
	public Map<SessionStatus, Long> countByAgentPerStatus(UUID rootAgentUuid) {
		if (rootAgentUuid == null) return Map.of();
		Map<SessionStatus, Long> counts = new EnumMap<>(SessionStatus.class);
		for (Object[] row : repository.countByAgentGroupedByStatus(rootAgentUuid.toString())) {
			String status = (String) row[0];
			if (status == null) {
				log.error("session row with null status for agent {}", rootAgentUuid);
				continue;
			}
			try {
				counts.put(SessionStatus.valueOf(status), ((Number) row[1]).longValue());
			} catch (IllegalArgumentException e) {
				log.error("Unrecognized session status {} in counts for agent {}", status, rootAgentUuid, e);
			}
		}
		return counts;
	}

	/**
	 * Most recent {@code lastActivityAt} across one ROOT agent's
	 * sessions, computed in SQL. Null when the agent has no sessions
	 * with recorded activity.
	 */
	public ZonedDateTime maxLastActivityAt(UUID rootAgentUuid) {
		if (rootAgentUuid == null) return null;
		BigDecimal epochSeconds = repository.maxLastActivityAtForAgent(rootAgentUuid.toString());
		if (epochSeconds == null) return null;
		// same mapper that wrote the numeric, so the max round-trips to
		// the identical instant AgentSessionData reports for that row
		return Utils.OM.convertValue(epochSeconds, ZonedDateTime.class);
	}

	/**
	 * Open a new session for a ROOT agent. If {@code clientSessionId}
	 * matches an existing OPEN session for the same root, that session
	 * is returned (idempotent — supports agent crash recovery).
	 *
	 * Throws when {@code rootAgentUuid} resolves to a SUB agent —
	 * sub-agents share the root's session and don't open their own.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData initialize(UUID orgUuid, UUID rootAgentUuid, UUID apiKeyUuid,
			String clientSessionId, String title, UUID parentSessionUuid, UUID modelUuid, WhoUpdated wu) throws RelizaException {
		return initialize(orgUuid, rootAgentUuid, apiKeyUuid, clientSessionId, title, parentSessionUuid,
				modelUuid, null, wu);
	}

	/**
	 * As above, recording the agent tool's own session id when the client reported one. See
	 * {@link AgentSessionData.ProviderSession}.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData initialize(UUID orgUuid, UUID rootAgentUuid, UUID apiKeyUuid,
			String clientSessionId, String title, UUID parentSessionUuid, UUID modelUuid,
			ProviderSessionInput providerSession, WhoUpdated wu) throws RelizaException {
		return initialize(orgUuid, rootAgentUuid, apiKeyUuid, clientSessionId, title, parentSessionUuid,
				modelUuid, providerSession, null, wu);
	}

	/**
	 * As above, recording how the session came to be opened. See
	 * {@link AgentSessionData.SessionOrigin}; the caller resolves it from the request, which this
	 * service never sees.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData initialize(UUID orgUuid, UUID rootAgentUuid, UUID apiKeyUuid,
			String clientSessionId, String title, UUID parentSessionUuid, UUID modelUuid,
			ProviderSessionInput providerSession, AgentSessionData.SessionOrigin origin, WhoUpdated wu)
			throws RelizaException {
		if (orgUuid == null) throw new RelizaException("Session requires an org");
		if (rootAgentUuid == null) throw new RelizaException("Session requires an agent");
		AgentData agent = agentService.getAgentData(rootAgentUuid)
				.orElseThrow(() -> new RelizaException("Agent not found: " + rootAgentUuid));
		if (agent.getAgentType() != AgentType.ROOT) {
			throw new RelizaException(
					"Only ROOT agents can open sessions; agent " + rootAgentUuid + " is a SUB");
		}

		ProviderSessionInput validProvider = validateProviderSession(providerSession);

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
		// Model is a property of the chat, not the durable agent. The
		// caller resolved it from the agent's declared (model, version,
		// vendor) triple; assertion stays at the data default DECLARED
		// since the agent self-reported it.
		seed.setModel(modelUuid);
		ZonedDateTime now = ZonedDateTime.now();
		seed.setStartedAt(now);
		seed.setLastActivityAt(now);
		// Before policy evaluation, so the row a BLOCK persists still says which conversation
		// made the refused attempt.
		if (null != validProvider) {
			seed.recordProviderSession(validProvider.toRecord(now));
		}
		seed.setOrigin(origin);

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
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData initialize(UUID orgUuid, UUID rootAgentUuid, UUID apiKeyUuid,
			String clientSessionId, String title, WhoUpdated wu) throws RelizaException {
		return initialize(orgUuid, rootAgentUuid, apiKeyUuid, clientSessionId, title, null, null, wu);
	}

	/**
	 * The explicit keep-alive ({@code rearm agent session touch}, and every poll of {@code rearm agent
	 * wait}). An activity stamp, not a revision (task RD3-1): within {@link #ACTIVITY_THROTTLE} of the
	 * stored stamp it writes nothing, unless an idle warning is pending, which any call with the
	 * session's id clears; otherwise one targeted update of the stamp. Neither writes an audit row.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData touch(UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		return touch(sessionUuid, ZonedDateTime.now(), wu);
	}

	/** {@link #touch(UUID, WhoUpdated)} as of {@code now}: the sweep's and the tests' clock. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData touch(UUID sessionUuid, ZonedDateTime now, WhoUpdated wu) throws RelizaException {
		AgentSessionData sd = repository.findById(sessionUuid).map(AgentSessionData::dataFromRecord)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		if (sd.getStatus() != SessionStatus.OPEN) {
			log.debug("touch on {} session {} — ignored", sd.getStatus(), sessionUuid);
			return sd;
		}
		if (null == sd.getIdleWarnedAt() && withinThrottle(sd.getLastActivityAt(), now)) return sd;
		repository.stampActivity(sessionUuid, epochSecondsOf(now));
		return repository.findById(sessionUuid).map(AgentSessionData::dataFromRecord)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
	}

	/** The agent closing its own session. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData close(UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		return close(sessionUuid, AgentActor.ofSession(sessionUuid), "closed by the agent", wu);
	}

	/**
	 * Close a session, saying who closed it and why (task 6e7fe6fe): the agent itself, or a person
	 * force-closing it. The idle sweep closes through {@link #idleSweep}.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData close(UUID sessionUuid, AgentActor closedBy, String reason, WhoUpdated wu)
			throws RelizaException {
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
		sd.setClosedBy(closedBy);
		sd.setCloseReason(reason);
		// An agent closing its own session is the only close that can carry a complete account:
		// it stops when its work stops, and its final flush lands inside the grace window. The
		// idle scheduler and an operator force-close both cut the tail off, and enforcement later
		// has to know the difference between "spent little" and "told us little".
		sd.setUsageCompleteness(null != sd.getUsageTotals() && sd.getUsageTotals().reports() > 0
				? AgentSessionData.UsageCompleteness.COMPLETE
				: AgentSessionData.UsageCompleteness.NONE);
		releaseAgentTaskHolds(sessionUuid, releaseNote(sessionUuid, reason), wu);
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
				log.error("Policy evaluation failed on session close for session {}", sessionUuid, e);
			}
		}
		return saveData(sd, wu);
	}

	// ---------- Activity and the idle sweep (task 6e7fe6fe) ----------

	/** A session's stamp is rewritten at most this often, so a poll every minute costs one write per five. */
	public static final Duration ACTIVITY_THROTTLE = Duration.ofMinutes(5);

	/** The idle warning comes this long before the close, or half the window when that is shorter. */
	public static final Duration IDLE_WARNING_LEAD = Duration.ofHours(2);

	/** The idle sweep looks at sessions idle at least this long: the earliest warning on the shortest window. */
	private static final Duration SWEEP_LOOKBACK = Duration.ofMinutes(30);

	/**
	 * Add boards to the ones a session worked (board-permissions.md D15). Locked and refreshed like
	 * every session write; after the task transaction has committed, never inside it, since a close
	 * locks the session before its tasks. Only a write when something is new.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RelizaException.class)
	public void recordBoardsWorked(UUID sessionUuid, java.util.Collection<UUID> boards) {
		if (null == sessionUuid || null == boards || boards.isEmpty()) return;
		AgentSession locked = repository.findByIdWriteLocked(sessionUuid).orElse(null);
		if (null == locked) return;
		entityManager.refresh(locked);
		AgentSessionData sd = AgentSessionData.dataFromRecord(locked);
		if (null == sd.getBoardsWorked()) sd.setBoardsWorked(new java.util.LinkedHashSet<>());
		if (!sd.getBoardsWorked().addAll(boards)) return;
		saveData(sd, WhoUpdated.getAutoWhoUpdated());
	}

	/**
	 * A call made with the session's id, on any board or session verb: the session is working.
	 * Writes only when the stamp is older than {@link #ACTIVITY_THROTTLE}, in a transaction of its
	 * own so it neither joins nor extends the caller's.
	 *
	 * <p>Only the session's own key makes it active (round 2, T-1): a coordinator's or an admin's
	 * read of a worker's session is not that worker working, and counting it would keep a stalled
	 * holder open and clear its warning. {@code callerKey} is asked for only when the stamp is due,
	 * so a legacy basic-auth caller pays for resolving its key once per throttle window, not per call.
	 *
	 * <p>One targeted update of the stamp (task RD3-1), no revision and no audit row. It is guarded on
	 * OPEN and on a later stamp, so a close the sweep committed after the first read is never written
	 * back over as OPEN (T-3), without a lock or a refresh.
	 *
	 * @return whether the stamp was written
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RelizaException.class)
	public boolean recordActivity(UUID sessionUuid, Supplier<UUID> callerKey, ZonedDateTime now) {
		if (null == sessionUuid) return false;
		AgentSessionData current = repository.findById(sessionUuid).map(AgentSessionData::dataFromRecord).orElse(null);
		if (null == current || current.getStatus() != SessionStatus.OPEN) return false;
		if (withinThrottle(current.getLastActivityAt(), now)) return false;
		UUID caller = null == callerKey ? null : callerKey.get();
		if (null == caller || !caller.equals(current.getApiKey())) return false;
		// One targeted update (task RD3-1): guarded on OPEN and on a later stamp, so a close committed
		// since the read is never written back over, with no lock and no revision.
		return repository.stampActivity(sessionUuid, epochSecondsOf(now)) > 0;
	}

	/**
	 * Whether {@code last} is recent enough that a keep-alive at {@code now} writes nothing. A second
	 * of slack: the stamp comes back from JSONB a hair off what was written, and a poll landing
	 * exactly on the fifth minute should write, not wait for the sixth.
	 */
	private static boolean withinThrottle(ZonedDateTime last, ZonedDateTime now) {
		return null != last && last.isAfter(now.minus(ACTIVITY_THROTTLE).plusSeconds(1));
	}

	/** How often a session's poll of one board is written (task RD3-4): enough for thresholds counted in minutes. */
	static final java.time.Duration BOARD_POLL_THROTTLE = java.time.Duration.ofMinutes(5);

	/**
	 * A session polled these boards for work (task RD3-4): the poll time and its declared roles, per
	 * board, at most once per {@link #BOARD_POLL_THROTTLE}; the board it was offered a task on also
	 * gets the offer time. Bookkeeping only: no revision, no audit row, and a failure is logged rather
	 * than failing the poll.
	 */
	public void recordBoardPolls(UUID sessionUuid, java.util.Collection<UUID> boards, List<String> roles, UUID offeredOn,
			ZonedDateTime now) {
		if (null == sessionUuid) return;
		try {
			String rolesJson = Utils.OM.writeValueAsString(null == roles ? List.of() : roles);
			BigDecimal at = epochSecondsOf(now);
			BigDecimal throttle = epochSecondsOf(now.minus(BOARD_POLL_THROTTLE));
			for (UUID b : boards) {
				if (b.equals(offeredOn)) repository.stampBoardOffer(sessionUuid, b.toString(), at, rolesJson);
				else repository.stampBoardPoll(sessionUuid, b.toString(), at, rolesJson, throttle);
			}
		} catch (Exception e) {
			log.error("Could not record the board polls of session {}", sessionUuid, e);
		}
	}

	/** Every session that has polled the board, open or closed (task RD3-5). */
	public List<AgentSessionData> listWithBoardActivity(UUID board) {
		return repository.findWithBoardActivity(board.toString()).stream().map(AgentSessionData::dataFromRecord).toList();
	}

	/** The roles each OPEN session declared when it last polled the board since {@code since}; empty means any role. */
	public List<List<String>> rolesPolledSince(UUID board, ZonedDateTime since) {
		List<List<String>> out = new java.util.ArrayList<>();
		for (String json : repository.rolesPolledSince(board.toString(), epochSecondsOf(since))) {
			try {
				out.add(Utils.OM.readValue(json, new tools.jackson.core.type.TypeReference<List<String>>() {}));
			} catch (Exception e) {
				log.error("Unreadable roles on a board poll of board {}", board, e);
			}
		}
		return out;
	}

	/** An instant as the numeric epoch seconds, with nanos, that Jackson writes into the session's JSONB. */
	static BigDecimal epochSecondsOf(ZonedDateTime at) {
		return BigDecimal.valueOf(at.toEpochSecond()).add(BigDecimal.valueOf(at.getNano(), 9));
	}

	/** What one run of the idle sweep did. */
	public record IdleSweep(int warned, int closed) {}

	/** The window a session is judged by: its org's, doubled while it holds a task or a seat. */
	record IdleWindow(Duration window, boolean holder, int orgHours) {
		Duration lead() {
			return leadOf(orgHours);
		}
	}

	/** The warning's lead for an org window: two hours, or half the window when that is shorter. */
	static Duration leadOf(int orgHours) {
		Duration half = Duration.ofHours(orgHours).dividedBy(2);
		return half.compareTo(IDLE_WARNING_LEAD) < 0 ? half : IDLE_WARNING_LEAD;
	}

	private int orgHoursOf(UUID org, Map<UUID, Integer> orgHours) {
		return orgHours.computeIfAbsent(org, o -> getOrganizationService.getOrganizationData(o)
				.map(OrganizationData::getSettings)
				.map(OrganizationData.Settings::getAgentSessionIdleCloseHoursOrDefault)
				.orElse(OrganizationData.Settings.AGENT_SESSION_IDLE_CLOSE_HOURS_DEFAULT));
	}

	/**
	 * When the sweep closes an open session unless it calls again (task RD2-15): the rule the sweep
	 * decides by, read for one session. Null for a closed session or one with no activity yet.
	 */
	public ZonedDateTime idleCloseAt(AgentSessionData sd) {
		if (null == sd || sd.getStatus() != SessionStatus.OPEN || null == sd.getLastActivityAt()) return null;
		return sd.getLastActivityAt().plus(idleWindowOf(sd, new HashMap<>()).window());
	}

	private IdleWindow idleWindowOf(AgentSessionData sd, Map<UUID, Integer> orgHours) {
		int hours = orgHoursOf(sd.getOrg(), orgHours);
		boolean holder = agentTaskService.hasAssignmentsForSession(sd.getUuid())
				|| agentBoardService.holdsAnySeat(sd.getUuid());
		return new IdleWindow(Duration.ofHours(holder ? 2L * hours : hours), holder, hours);
	}

	/**
	 * The hourly sweep over OPEN sessions (task 6e7fe6fe). Each is judged by its org's idle window
	 * (agentSessionIdleCloseHours, 24 by default), or twice that while it holds a task assignment or
	 * a coordinator seat. Past the window it is closed by the system, with the idle time as its
	 * reason, and its tasks go back to QUEUED for their roles. Within {@link #IDLE_WARNING_LEAD} of
	 * it, the session is warned once: a notification, and an ALERT on every board where it holds
	 * work. Any activity spends the warning.
	 *
	 * <p>Not one transaction (round 2, T-2): the candidates are read without locks, and each is
	 * decided in a short transaction of its own ({@link #idleSweepOf}), so a session's lock is held
	 * for its own decision only and a call from it during the sweep neither waits for the run nor
	 * is lost.
	 */
	public IdleSweep idleSweep(ZonedDateTime now, WhoUpdated wu) {
		return idleSweepOf(idleCandidates(now), now, wu);
	}

	/**
	 * The sessions the sweep decides on at {@code now}: OPEN, and at or past their org's warning
	 * point. Read without locks, and only to choose: each is re-read under its lock when decided.
	 */
	public List<UUID> idleCandidates(ZonedDateTime now) {
		ZonedDateTime lookback = now.minus(SWEEP_LOOKBACK);
		// JSONB storage for ZonedDateTime is epoch-seconds-with-nanos (Jackson default), so the
		// cutoff has to land in the same numeric domain -- see findOpenSessionsIdleBefore.
		double cutoffEpochSeconds = lookback.toEpochSecond() + (lookback.getNano() / 1_000_000_000.0);
		Map<UUID, Integer> orgHours = new HashMap<>();
		// A read to skip by, never to decide on: a session not yet at its org's warning point needs
		// nothing, whatever it holds (holding only lengthens the window). The decision re-reads the
		// row under its lock.
		return repository.findOpenSessionsIdleBefore(cutoffEpochSeconds).stream()
				.map(AgentSessionData::dataFromRecord)
				.filter(sd -> {
					int hours = orgHoursOf(sd.getOrg(), orgHours);
					return !sd.getLastActivityAt().plus(Duration.ofHours(hours)).minus(leadOf(hours)).isAfter(now);
				})
				.map(AgentSessionData::getUuid)
				.toList();
	}

	/**
	 * The sweep over the named sessions, as of {@code now}: each in a transaction of its own that
	 * locks the row, re-reads it fresh, and closes it, warns it or leaves it (round 2, T-2/T-3).
	 */
	public IdleSweep idleSweepOf(List<UUID> sessions, ZonedDateTime now, WhoUpdated wu) {
		Map<UUID, Integer> orgHours = new HashMap<>();
		TransactionTemplate one = new TransactionTemplate(transactionManager);
		one.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		int warned = 0;
		int closed = 0;
		for (UUID sessionUuid : sessions) {
			try {
				IdleDecision d = one.execute(status -> {
					try {
						return idleDecide(sessionUuid, orgHours, now, wu);
					} catch (RelizaException e) {
						throw new IllegalStateException(e);
					}
				});
				if (IdleDecision.CLOSED == d) closed++;
				else if (IdleDecision.WARNED == d) warned++;
			} catch (Exception e) {
				log.error("idle sweep failed for session {}", sessionUuid, e);
			}
		}
		return new IdleSweep(warned, closed);
	}

	private enum IdleDecision { CLOSED, WARNED, LEFT }

	/** One session's decision, inside its own transaction, on the row as it is now. */
	private IdleDecision idleDecide(UUID sessionUuid, Map<UUID, Integer> orgHours, ZonedDateTime now,
			WhoUpdated wu) throws RelizaException {
		AgentSession locked = repository.findByIdWriteLocked(sessionUuid).orElse(null);
		if (locked == null) return IdleDecision.LEFT;
		// The lock statement does not discard a copy already in the persistence context: re-read, so
		// a call that stamped the session after the candidates were read rescues it (T-3).
		entityManager.refresh(locked);
		AgentSessionData sd = AgentSessionData.dataFromRecord(locked);
		if (sd.getStatus() != SessionStatus.OPEN || null == sd.getLastActivityAt()) return IdleDecision.LEFT;
		IdleWindow w = idleWindowOf(sd, orgHours);
		ZonedDateTime closesAt = sd.getLastActivityAt().plus(w.window());
		if (!closesAt.isAfter(now)) {
			closeIdle(sd, w, now, wu);
			return IdleDecision.CLOSED;
		}
		if (null == sd.getIdleWarnedAt() && !closesAt.minus(w.lead()).isAfter(now)) {
			warnIdle(sd, closesAt, now, wu);
			return IdleDecision.WARNED;
		}
		return IdleDecision.LEFT;
	}

	private void closeIdle(AgentSessionData sd, IdleWindow w, ZonedDateTime now, WhoUpdated wu) {
		String reason = "idle since " + sd.getLastActivityAt().withZoneSameInstant(ZoneOffset.UTC)
				.truncatedTo(ChronoUnit.MINUTES) + ", window " + w.window().toHours() + " h"
				+ (w.holder() ? " (twice the org's " + w.orgHours() + " h: it held a task or a seat)" : "");
		sd.setStatus(SessionStatus.CLOSED);
		sd.setClosedAt(now);
		sd.setClosedBy(AgentActor.system("idle-sweep"));
		sd.setCloseReason(reason);
		// The scheduler closed this, not the agent: whatever the agent had not yet reported is lost,
		// so the account is incomplete even when reports arrived.
		sd.setUsageCompleteness(AgentSessionData.UsageCompleteness.INCOMPLETE);
		// Same CLOSE-kind verdict lock as the explicit close path: the session went terminal
		// without the agent ever filing what it owed.
		if (policyHook != null) {
			try {
				AgentData agent = agentService.getAgentData(sd.getAgent()).orElse(null);
				if (agent != null) {
					appendPolicyEvents(sd, policyHook.evaluateOnSessionClose(sd, agent));
				}
			} catch (Exception e) {
				log.error("Policy evaluation failed on idle autoclose for session {}", sd.getUuid(), e);
			}
		}
		saveData(sd, wu);
		releaseAgentTaskHolds(sd.getUuid(), releaseNote(sd.getUuid(), "auto-closed " + reason), wu);
	}

	private void warnIdle(AgentSessionData sd, ZonedDateTime closesAt, ZonedDateTime now, WhoUpdated wu) {
		// Bookkeeping, not a revision (task RD3-1).
		repository.stampIdleWarning(sd.getUuid(), epochSecondsOf(now));
		sd.setIdleWarnedAt(now);
		String agentName = agentService.getAgentData(sd.getAgent()).map(AgentData::getName).orElse("an agent");
		String title = null == sd.getTitle() ? sd.getUuid().toString() : sd.getTitle();
		String message = "Session " + title + " (" + sd.getUuid() + ") of " + agentName + " has been idle since "
				+ utcMinute(sd.getLastActivityAt()) + " and closes at " + utcMinute(closesAt)
				+ "; any call with its session id, or rearm agent session touch, keeps it open";
		agentTaskService.idleWarningOnHoldingBoards(sd.getUuid(), message, wu);
		agentBoardService.idleWarningOnSeatBoards(sd.getUuid(), message, wu);
		boardNotifier.sessionIdleWarning(sd, agentName, message);
	}

	private static String utcMinute(ZonedDateTime t) {
		return t.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).toString();
	}

	/** What a task released by a closing session says about it. */
	private static String releaseNote(UUID sessionUuid, String reason) {
		return "session " + sessionUuid + " " + (null == reason ? "closed" : reason) + "; re-assignable";
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
	@Transactional(rollbackFor = RelizaException.class)
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
		sd.markActive(ZonedDateTime.now());
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
	 *
	 * REQUIRES_NEW, never the caller's transaction. Both callers in
	 * {@link SourceCodeEntryService} sit inside one addrelease request
	 * that walks a whole commit list: the merge path runs in the
	 * request-wide tx (connection A) and {@code createSourceCodeEntry}
	 * runs REQUIRES_NEW (connection B). If this method joined A while
	 * handling an already-registered commit, A would hold the session
	 * row lock until the release committed; the next never-registered
	 * commit on B would then wait for that lock while A's thread waits
	 * for B to return -- a self-deadlock Postgres cannot detect, which
	 * only ends on statement timeout and leaves A rollback-only
	 * ({@code UnexpectedRollbackException}, whole release lost). On its
	 * own connection the row lock is held for milliseconds and released
	 * before the caller continues.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RelizaException.class)
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
			sd.markActive(ZonedDateTime.now());
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

	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData updateMeta(UUID sessionUuid, String title, String clientSessionId,
			WhoUpdated wu) throws RelizaException {
		return updateMeta(sessionUuid, title, clientSessionId, null, wu);
	}

	/**
	 * As above, also recording a provider session. Appends rather than replaces: a session
	 * resumed in a new conversation keeps the id of the one that opened it.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData updateMeta(UUID sessionUuid, String title, String clientSessionId,
			ProviderSessionInput providerSession, WhoUpdated wu) throws RelizaException {
		ProviderSessionInput validProvider = validateProviderSession(providerSession);
		AgentSession s = repository.findByIdWriteLocked(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		AgentSessionData sd = AgentSessionData.dataFromRecord(s);
		if (title != null) sd.setTitle(title);
		if (null != validProvider) {
			recordProviderSession(sd, validProvider);
		}
		if (StringUtils.isNotBlank(clientSessionId)) {
			if (!AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.matcher(clientSessionId).matches()) {
				throw new RelizaException("clientSessionId '" + clientSessionId
						+ "' contains illegal characters — must match "
						+ AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.pattern()
						+ " (alphanumeric, dot, underscore, dash).");
			}
			sd.setClientSessionId(clientSessionId);
		}
		sd.markActive(ZonedDateTime.now());
		return saveData(sd, wu);
	}

	/**
	 * A provider session as a client reports it, before ReARM stamps when it heard of it.
	 */
	public static record ProviderSessionInput(String provider, String id, String remoteId) {
		AgentSessionData.ProviderSession toRecord(ZonedDateTime reportedAt) {
			return new AgentSessionData.ProviderSession(provider, id, remoteId, reportedAt);
		}
	}

	/** Tool names are short identifiers, stored lower-cased so {@code Claude-Code} and {@code claude-code} are one tool. */
	static final Pattern PROVIDER_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	/**
	 * Provider ids are opaque, so the rule is only what keeps them safe to store, display and
	 * paste: printable ASCII, no whitespace, bounded. Claude Code's are a uuid and
	 * {@code session_<base58>}; both fit with room to spare.
	 */
	static final Pattern PROVIDER_SESSION_ID_PATTERN = Pattern.compile("[\\x21-\\x7E]{1,256}");

	/**
	 * Enough for a long-running session resumed many times; a client appending a fresh id on
	 * every call is a bug, and this is where it surfaces.
	 */
	static final int MAX_PROVIDER_SESSIONS = 32;

	/**
	 * Normalise and check a reported provider session. Null in, null out: reporting is optional.
	 * A report with a provider but no id -- or the reverse -- is refused rather than half-kept,
	 * since neither half is useful alone.
	 */
	static ProviderSessionInput validateProviderSession(ProviderSessionInput in) throws RelizaException {
		if (null == in) return null;
		String provider = StringUtils.trimToNull(in.provider());
		String id = StringUtils.trimToNull(in.id());
		String remoteId = StringUtils.trimToNull(in.remoteId());
		if (null == provider && null == id && null == remoteId) return null;
		if (null == provider || null == id) {
			throw new RelizaException("providerSession needs both provider and id"
					+ " (got provider=" + provider + ", id=" + id + ")");
		}
		provider = provider.toLowerCase(Locale.ROOT);
		if (!PROVIDER_PATTERN.matcher(provider).matches()) {
			throw new RelizaException("providerSession.provider '" + provider + "' must match "
					+ PROVIDER_PATTERN.pattern());
		}
		if (!PROVIDER_SESSION_ID_PATTERN.matcher(id).matches()) {
			throw new RelizaException("providerSession.id must be 1-256 printable ASCII characters"
					+ " with no whitespace");
		}
		if (null != remoteId && !PROVIDER_SESSION_ID_PATTERN.matcher(remoteId).matches()) {
			throw new RelizaException("providerSession.remoteId must be 1-256 printable ASCII"
					+ " characters with no whitespace");
		}
		return new ProviderSessionInput(provider, id, remoteId);
	}

	/**
	 * Append a validated provider session to a session this transaction holds. A repeat is a
	 * no-op, a repeat that adds the remote id fills it in, and a repeat that names a different
	 * remote id is refused: one local session has one hosted identity, so two means one of the
	 * reports is wrong, and keeping either silently would make the record lie.
	 */
	private void recordProviderSession(AgentSessionData sd, ProviderSessionInput ps) throws RelizaException {
		List<AgentSessionData.ProviderSession> existing = null != sd.getProviderSessions()
				? sd.getProviderSessions() : List.of();
		for (AgentSessionData.ProviderSession cur : existing) {
			if (cur.provider().equals(ps.provider()) && cur.id().equals(ps.id())
					&& null != cur.remoteId() && null != ps.remoteId()
					&& !cur.remoteId().equals(ps.remoteId())) {
				throw new RelizaException("Session " + sd.getUuid() + " already records " + ps.provider()
						+ " session " + ps.id() + " with remote id " + cur.remoteId()
						+ "; refusing a second remote id " + ps.remoteId());
			}
		}
		boolean known = existing.stream().anyMatch(cur -> cur.provider().equals(ps.provider())
				&& cur.id().equals(ps.id()));
		if (!known && existing.size() >= MAX_PROVIDER_SESSIONS) {
			throw new RelizaException("Session " + sd.getUuid() + " already records "
					+ MAX_PROVIDER_SESSIONS + " provider sessions; open a new ReARM session instead");
		}
		sd.recordProviderSession(ps.toRecord(ZonedDateTime.now()));
	}

	/**
	 * Sessions in an org that ran in the given provider session, matched on either the local or
	 * the remote id -- a human usually holds the remote one, a hook the local one. Newest first.
	 */
	public List<AgentSessionData> listByProviderSession(UUID orgUuid, String providerSessionId) {
		String id = StringUtils.trimToNull(providerSessionId);
		if (null == orgUuid || null == id) return List.of();
		return repository.findByOrgAndProviderSessionId(orgUuid.toString(), id).stream()
				.map(AgentSessionData::dataFromRecord)
				.toList();
	}

	@Transactional(rollbackFor = RelizaException.class)
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
