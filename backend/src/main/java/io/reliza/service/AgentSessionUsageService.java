/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentSession;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.SessionStatus;
import io.reliza.model.AgentSessionData.UsageByModel;
import io.reliza.model.AgentSessionData.UsageTotals;
import io.reliza.model.AgentSessionUsage;
import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.ModelAssertionState;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.SessionUsageAttribution;
import io.reliza.model.ModelVariants;
import io.reliza.model.SessionUsageHosting;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentSessionRepository;
import io.reliza.repositories.AgentSessionUsageRepository;
import io.reliza.repositories.AgentTaskRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Takes usage reports from agents and turns them into rows that can be summed.
 *
 * <p>The shape of a report follows from what a client can honestly observe: a delta of its
 * transcript since it last reported, which may span several models and cross a pricing threshold.
 * So one report carries several lines, each becoming one row that prices under exactly one entry,
 * all sharing the client's sequence number.
 *
 * <p>Everything here is server-side on purpose. A client states what it consumed; it does not get
 * to state which task that was against unless it actually held the task, nor how much is too much
 * to be plausible.
 */
@Slf4j
@Service
public class AgentSessionUsageService {

	@Autowired private AgentSessionUsageRepository repository;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentTaskRepository agentTaskRepository;
	@Autowired private io.reliza.repositories.AgentTaskRoleConfigRepository roleConfigRepository;
	@Autowired private AgentBoardRepository agentBoardRepository;
	@Autowired private AgentSessionRepository agentSessionRepository;

	@PersistenceContext
	private EntityManager entityManager;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private ModelPricingService pricingService;
	@Autowired @org.springframework.context.annotation.Lazy private HopUsageRefresher hopUsageRefresher;
	@Autowired @org.springframework.context.annotation.Lazy private CoordinatorShareService coordinatorShareService;

	/**
	 * A SessionEnd hook can fire after the agent already closed the session explicitly. Refusing
	 * that report would drop the last turn of every well-behaved session, so a closed session
	 * still accepts reports for a while.
	 */
	@Value("${relizaprops.agentUsageCloseGraceSeconds:600}")
	private long closeGraceSeconds;

	/**
	 * Ceiling per API request, not per report. A fixed per-report cap would refuse a legitimate
	 * first-run backfill: a long session can carry billions of cache-read tokens across thousands
	 * of requests. What is implausible is a single request exceeding the largest context window
	 * by a wide margin, which is what a parse gone wrong looks like.
	 */
	@Value("${relizaprops.agentUsageMaxTokensPerRequest:1200000}")
	private long maxTokensPerRequest;

	/** One line of a report: what one model cost, at one hosting, in one context band. */
	public record UsageLine(String model, SessionUsageHosting hosting, Long contextBand,
			int requests, long inputTokens, long outputTokens, long cacheReadTokens,
			long cacheWriteTokens, Long reasoningTokens, Long maxRequestContextTokens,
			Long minRequestContextTokens, Long reportedCostMicros) {}

	/** The whole report: session-level facts plus its lines. */
	public record UsageReport(UUID sessionUuid, String clientSessionId, long clientSeq,
			SessionUsageSource source, ZonedDateTime windowStart, ZonedDateTime windowEnd,
			Integer turns, Integer toolCalls, Integer wallSeconds, String reasoningLevel,
			UUID taskUuid, Map<String, Object> raw, List<UsageLine> lines) {}

	/**
	 * @param accepted rows inserted
	 * @param duplicates lines whose key already existed; a retried delta reports all duplicates
	 * @param refused one message per line that could not be filed, with the reason
	 * @param hopAllowanceMicros the allowance of the hop the session is working now, when its role
	 *        sets one; null otherwise
	 * @param hopSpentMicros what that hop has cost so far, from the rows reported in it; null when
	 *        there is no allowance to compare against. The only moment a running agent can be told
	 *        it is over, since the server cannot stop a hop mid-flight.
	 */
	public record UsageAck(int accepted, int duplicates, List<String> refused,
			SessionUsageAttribution attribution, UUID task, Long hopAllowanceMicros, Long hopSpentMicros) {

		public UsageAck(int accepted, int duplicates, List<String> refused,
				SessionUsageAttribution attribution, UUID task) {
			this(accepted, duplicates, refused, attribution, task, null, null);
		}
	}

	private static final int RAW_CAP_BYTES = 16 * 1024;

	@Transactional(rollbackFor = RelizaException.class)
	public UsageAck report(AgentSessionData session, UsageReport report, WhoUpdated wu)
			throws RelizaException {
		requireReportable(session);
		if (null == report.lines() || report.lines().isEmpty()) {
			throw new RelizaException("A usage report must carry at least one line");
		}
		Long maxSeq = repository.findMaxClientSeq(session.getUuid());
		if (null != maxSeq && report.clientSeq() < maxSeq) {
			// Below the high-water mark means an out-of-order or replayed backfill. Accepting it
			// would re-file windows already counted, so it is refused rather than deduped.
			throw new RelizaException("clientSeq " + report.clientSeq() + " is below the last accepted "
					+ maxSeq + " for this session");
		}

		AttributionResult attribution = attribute(session, report);
		int accepted = 0;
		int duplicates = 0;
		List<String> refused = new ArrayList<>();
		List<AgentSessionUsage> inserted = new ArrayList<>();
		for (UsageLine line : report.lines()) {
			try {
				AgentSessionUsage row = insertLine(session, report, line, attribution, wu);
				if (null != row) {
					accepted++;
					inserted.add(row);
				} else {
					duplicates++;
				}
			} catch (RelizaException e) {
				refused.add(e.getMessage());
			}
		}
		if (accepted > 0) {
			rebuildSessionTotals(session, wu);
			agentSessionService.touch(session.getUuid(), wu);
			// After this report commits, in its own transaction: a hop that already closed and had
			// these rows still to come gets them (gaps §1.22).
			if (null != attribution.task()) {
				hopUsageRefresher.refreshAfterCommit(attribution.task(), session.getUuid(), wu);
			}
			if (attribution.attribution() == SessionUsageAttribution.COORDINATOR) {
				apportionCoordinatorDelta(session, attribution.board(), inserted, wu);
			}
		}
		Long[] hop = hopAllowanceAndSpend(session);
		return new UsageAck(accepted, duplicates, refused, attribution.attribution(), attribution.task(),
				hop[0], hop[1]);
	}

	/**
	 * The allowance of the hop this session holds and what the hop has cost so far (hop allowance
	 * design §3.4). Both null unless the session holds exactly one assignment and its role sets an
	 * allowance: with none there is no hop, and with two the server cannot tell which one a report
	 * belongs to, as attribution already says.
	 */
	private Long[] hopAllowanceAndSpend(AgentSessionData session) {
		List<AgentTask> assigned = agentTaskRepository.findByAssignedSession(session.getUuid().toString());
		if (assigned.size() != 1) return new Long[] { null, null };
		AgentTaskData td = AgentTaskData.dataFromRecord(assigned.get(0));
		AgentTaskData.TaskAssignment a = td.getAssignment();
		if (null == a) return new Long[] { null, null };
		Long allowance = roleConfigRepository.findByBoardAndName(td.getBoard().toString(), a.role())
				.map(AgentTaskRoleConfigData::dataFromRecord)
				.map(AgentTaskRoleConfigData::getHopBudgetMicros)
				.orElse(null);
		if (null == allowance) return new Long[] { null, null };
		Long spent = hopSnapshot(td.getUuid(), session.getUuid(), a.assignedAt()).derivedCostMicros();
		return new Long[] { allowance, null == spent ? 0L : spent };
	}

	private void requireReportable(AgentSessionData session) throws RelizaException {
		if (session.getStatus() == SessionStatus.BLOCKED) {
			throw new RelizaException("Session " + session.getUuid() + " is blocked and accepts no usage");
		}
		if (session.getStatus() == SessionStatus.CLOSED) {
			ZonedDateTime closedAt = session.getClosedAt();
			if (null == closedAt
					|| closedAt.plusSeconds(closeGraceSeconds).isBefore(ZonedDateTime.now())) {
				throw new RelizaException("Session " + session.getUuid()
						+ " closed more than " + closeGraceSeconds + "s ago and accepts no usage");
			}
		}
	}

	/**
	 * The seat's spend in this report, split among the tasks it coordinated once the report commits
	 * (D16). Only what this report added: earlier deltas were apportioned when they arrived.
	 */
	private void apportionCoordinatorDelta(AgentSessionData session, UUID boardUuid,
			List<AgentSessionUsage> inserted, WhoUpdated wu) {
		long delta = 0;
		ZonedDateTime reportedAt = null;
		Map<UUID, ModelOntologyData> models = new HashMap<>();
		for (AgentSessionUsage row : inserted) {
			ModelOntologyData model = models.computeIfAbsent(row.getModel(),
					k -> modelOntologyService.getModelOntologyData(k).orElse(null));
			Long cost = pricingService.derive(model, row).costMicros();
			if (null != cost) delta += cost;
			if (null == reportedAt || row.getReportedAt().isAfter(reportedAt)) reportedAt = row.getReportedAt();
		}
		coordinatorShareService.apportionAfterCommit(boardUuid, session.getUuid(), delta, reportedAt, wu);
	}

	/** @return the row inserted, or null when the line was already filed */
	private AgentSessionUsage insertLine(AgentSessionData session, UsageReport report, UsageLine line,
			AttributionResult attribution, WhoUpdated wu) throws RelizaException {
		validateLine(line);
		ModelOntologyService.ResolvedModel resolved = resolveModel(session, line, wu);
		UUID modelUuid = resolved.model().getUuid();
		SessionUsageHosting hosting = null != line.hosting() ? line.hosting()
				: (null != resolved.hosting() ? resolved.hosting() : SessionUsageHosting.DIRECT);
		// Absent means the base band. A client that cannot band its requests -- an agent reporting
		// explicit numbers, say -- says nothing rather than guessing, and the base rate is the
		// honest reading of that: a long-context rate must never apply to a request nobody
		// measured.
		long band = null != line.contextBand() ? line.contextBand() : 0L;

		if (repository.findBySessionAndClientSeqAndModelAndHostingAndContextBand(
				session.getUuid(), report.clientSeq(), modelUuid, hosting, band).isPresent()) {
			return null;
		}

		AgentSessionUsage row = new AgentSessionUsage();
		row.setOrg(session.getOrg());
		row.setSession(session.getUuid());
		row.setAgent(session.getAgent());
		row.setTask(attribution.task());
		row.setBoard(attribution.board());
		row.setModel(modelUuid);
		row.setSource(null != report.source() ? report.source() : SessionUsageSource.SELF_REPORTED);
		row.setHosting(hosting);
		row.setContextBand(band);
		row.setClientSeq(report.clientSeq());
		row.setReportedAt(ZonedDateTime.now());
		row.setRequests(line.requests());
		row.setInputTokens(line.inputTokens());
		row.setOutputTokens(line.outputTokens());
		row.setCacheReadTokens(line.cacheReadTokens());
		row.setCacheWriteTokens(line.cacheWriteTokens());
		row.setMaxRequestContextTokens(null != line.maxRequestContextTokens()
				? line.maxRequestContextTokens() : contextOf(line));
		row.setMinRequestContextTokens(null != line.minRequestContextTokens()
				? line.minRequestContextTokens() : 0);
		row.setTurns(null != report.turns() ? report.turns() : line.requests());
		row.setToolCalls(null != report.toolCalls() ? report.toolCalls() : 0);
		row.setWallSeconds(null != report.wallSeconds() ? report.wallSeconds() : 0);
		row.setReportedCostMicros(line.reportedCostMicros());

		Map<String, Object> rd = new LinkedHashMap<>();
		rd.put("windowStart", null != report.windowStart() ? report.windowStart().toString() : null);
		rd.put("windowEnd", null != report.windowEnd() ? report.windowEnd().toString() : null);
		rd.put("modelDeclared", line.model());
		// Written only when the string carried something, so a plain model does not store an
		// object of four nulls. The consumer reads absent and empty the same way.
		if (null != resolved.variants() && !resolved.variants().isEmpty()) {
			rd.put("variants", Utils.OM.convertValue(resolved.variants(), LinkedHashMap.class));
		}
		rd.put("reasoningLevel", report.reasoningLevel());
		rd.put("reasoningTokens", line.reasoningTokens());
		rd.put("attribution", attribution.attribution().name());
		rd.put("childOf", session.getParentSession());
		rd.put("raw", cappedRaw(report.raw()));
		row.setRecordData(rd);

		// Not wrapped in a catch for the unique-key violation, which would be theatre twice over.
		// The row carries an assigned uuid, so Hibernate defers the insert to commit and the
		// violation surfaces outside any try here; and a failed statement poisons the Postgres
		// transaction, so swallowing it and inserting the report's remaining lines could not work
		// regardless.
		//
		// Idempotency comes from the pre-check above for the ordinary retry, and the unique index
		// is the backstop for the narrow race of two hooks submitting one delta at the same
		// instant: it fails that report, the CLI resends, and the pre-check then reports every
		// line as a duplicate. The delta is never double-counted, which is the property that
		// matters; it just costs one extra round trip in a case that needs two hooks racing on
		// one session.
		repository.save(row);
		return row;
	}

	private void validateLine(UsageLine line) throws RelizaException {
		if (line.requests() < 1) {
			throw new RelizaException("A usage line must cover at least one request");
		}
		long[] counters = {line.inputTokens(), line.outputTokens(), line.cacheReadTokens(),
				line.cacheWriteTokens(), null != line.reasoningTokens() ? line.reasoningTokens() : 0};
		for (long c : counters) {
			if (c < 0) throw new RelizaException("Usage counters cannot be negative");
		}
		long ceiling = Math.multiplyExact((long) line.requests(), maxTokensPerRequest);
		for (long c : counters) {
			if (c > ceiling) {
				throw new RelizaException("Usage line reports " + c + " tokens over " + line.requests()
						+ " request(s), above the plausible ceiling of " + ceiling
						+ "; treated as a parse error rather than a turn");
			}
		}
	}

	private long contextOf(UsageLine line) {
		return line.inputTokens() + line.cacheReadTokens() + line.cacheWriteTokens();
	}

	private ModelOntologyService.ResolvedModel resolveModel(AgentSessionData session, UsageLine line,
			WhoUpdated wu) throws RelizaException {
		if (StringUtils.isNotBlank(line.model())) {
			return modelOntologyService.resolve(session.getOrg(), line.model(), null, wu);
		}
		// No model on the line: the session's declaration is the best available answer.
		UUID declared = session.getModel();
		if (null == declared) {
			throw new RelizaException("Usage line names no model and the session declared none");
		}
		ModelOntologyData model = modelOntologyService.getModelOntologyData(declared)
				.orElseThrow(() -> new RelizaException("Session model " + declared + " not found"));
		return new ModelOntologyService.ResolvedModel(model, ModelVariants.NONE, null);
	}

	private Object cappedRaw(Map<String, Object> raw) {
		if (null == raw || raw.isEmpty()) return null;
		String json = Utils.OM.writeValueAsString(raw);
		if (json.length() <= RAW_CAP_BYTES) return raw;
		// Kept as text rather than dropped: a truncated payload still tells an operator what the
		// client thought it was sending.
		return Map.of("truncated", true, "payload", json.substring(0, RAW_CAP_BYTES));
	}

	private record AttributionResult(UUID task, UUID board, SessionUsageAttribution attribution) {}

	/**
	 * Which task the tokens belong to.
	 *
	 * <p>Explicit beats implicit, and an explicit claim is checked: a client may name only a task
	 * its session actually held. Implicit works when the session has exactly one assignment --
	 * with two, the server genuinely cannot tell, and says so rather than splitting or guessing.
	 */
	private AttributionResult attribute(AgentSessionData session, UsageReport report) throws RelizaException {
		if (null != report.taskUuid()) {
			// Load the named task by primary key and check the relationship in memory, rather than
			// scanning the task table with jsonb containment predicates. This runs on every report
			// -- once per agent turn across every session in the org -- so the difference is a
			// single indexed row against a full scan of the org's tasks.
			AgentTaskData td = agentTaskRepository.findById(report.taskUuid())
					.map(AgentTaskData::dataFromRecord)
					.filter(t -> t.getOrg().equals(session.getOrg()))
					.orElseThrow(() -> new RelizaException("Task " + report.taskUuid() + " not found"));
			if (!sessionTouched(td, session.getUuid())) {
				throw new RelizaException("Task " + report.taskUuid()
						+ " was never assigned to session " + session.getUuid()
						+ "; refusing to attribute usage to it");
			}
			return new AttributionResult(td.getUuid(), td.getBoard(), SessionUsageAttribution.EXPLICIT);
		}

		List<AgentTask> assigned = agentTaskRepository.findByAssignedSession(session.getUuid().toString());
		if (assigned.size() == 1) {
			AgentTaskData td = AgentTaskData.dataFromRecord(assigned.get(0));
			return new AttributionResult(td.getUuid(), td.getBoard(), SessionUsageAttribution.IMPLICIT);
		}
		if (assigned.size() > 1) {
			log.debug("Session {} holds {} assignments and named no task; usage stays unattributed",
					session.getUuid(), assigned.size());
			return new AttributionResult(null, null, SessionUsageAttribution.UNATTRIBUTED);
		}

		// No assignment, but the session may be holding a board's coordinator seat -- a seat-holder
		// is barred from taking assignments, so this is the ONLY way its spend is ever attributed.
		// Coordinating is real work and often a large share of a board's cost; leaving it
		// unattributed dropped it out of every board rollup, making the board look cheaper than it
		// is by exactly the amount the coordinator spent.
		List<AgentBoard> seats = agentBoardRepository.findBySeatSession(session.getUuid().toString());
		if (!seats.isEmpty()) {
			if (seats.size() > 1) {
				// One session may coordinate several boards. Attributing the spend to one of them
				// would be a guess, so it goes to none -- but say so, because the alternative is
				// silently charging one board for work done across several.
				log.info("Session {} holds the coordinator seat on {} boards; its usage is not "
						+ "attributed to any of them", session.getUuid(), seats.size());
				return new AttributionResult(null, null, SessionUsageAttribution.UNATTRIBUTED);
			}
			AgentBoardData bd = AgentBoardData.dataFromRecord(seats.get(0));
			// Board without a task: the row joins the board's period rollup and no task total.
			return new AttributionResult(null, bd.getUuid(), SessionUsageAttribution.COORDINATOR);
		}
		return new AttributionResult(null, null, SessionUsageAttribution.UNATTRIBUTED);
	}

	/**
	 * Display name of a model, from the rows the rollup already loaded.
	 *
	 * <p>Carried on the breakdown so a consumer rendering "what did this session spend, by model"
	 * does not have to fetch the org's whole catalogue to turn uuids into names -- which is what
	 * the UI would otherwise do on the session, board and org views alike.
	 */
	private String modelNameOf(UUID model, Map<UUID, ModelOntologyData> cache) {
		ModelOntologyData mod = cache.computeIfAbsent(model,
				k -> modelOntologyService.getModelOntologyData(k).orElse(null));
		if (null == mod) return null;
		String version = mod.getVersion();
		boolean versioned = StringUtils.isNotBlank(version)
				&& !ModelOntologyData.UNKNOWN_VERSION.equalsIgnoreCase(version);
		return versioned ? mod.getName() + " " + version : mod.getName();
	}

	/**
	 * Did this session ever hold this task?
	 *
	 * <p>The current assignment, any sign-off and any return -- a session that signed a hop off has
	 * released the assignment but still did the work, so a late report naming that task is
	 * legitimate and must not be refused.
	 */
	private boolean sessionTouched(AgentTaskData td, UUID sessionUuid) {
		if (null != td.getAssignment() && sessionUuid.equals(td.getAssignment().session())) return true;
		boolean signed = td.getSignOffs().stream()
				.anyMatch(so -> sessionUuid.equals(so.session()));
		if (signed) return true;
		return td.getReturns().stream().anyMatch(r -> sessionUuid.equals(r.session()));
	}

	/**
	 * Recompute the session's rollup from its rows.
	 *
	 * <p>A full rebuild rather than an increment: the rollup is a cache, rebuilding it is a scan
	 * of one indexed session, and an incremental update that drifts from the table is worse than
	 * a slightly more expensive write. Cost is derived per row under the entry that applied when
	 * the row was reported, so a later rate change does not rewrite history.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentSessionData rebuildSessionTotals(AgentSessionData session, WhoUpdated wu)
			throws RelizaException {
		// Serialise rebuilds for one session on the session row itself.
		//
		// Two hooks can report concurrently for the same session -- a Stop and a SessionEnd firing
		// together is the ordinary way that happens. Both then scan the usage table and both write
		// a rollup; whichever commits second overwrites the first with a total computed before the
		// other's row existed, leaving the cache one row short until some later report rebuilds it.
		// Harmless mid-session, not harmless on the FINAL report, which is the one nothing comes
		// after to correct.
		//
		// The lock is taken before the scan so the second writer reads rows the first has already
		// committed, rather than racing it.
		//
		// AND THE SESSION IS RE-READ FROM UNDER THE LOCK. Taking the lock and then writing from the
		// AgentSessionData the caller passed in would fix the totals and corrupt everything else:
		// that object was loaded before the lock, saveData rewrites the whole record from it, so
		// any field another writer changed in between -- an artifact attached, a hold set, a status
		// moved -- is silently reverted. The refresh is required as well as the lock: with
		// open-in-view the fetcher's earlier read already put this row in the persistence context,
		// and a locking query returns that managed instance rather than the state it just read from
		// the database. Same rule as the release-approval path.
		AgentSession locked = agentSessionRepository.findByIdWriteLocked(session.getUuid())
				.orElseThrow(() -> new RelizaException("Session not found: " + session.getUuid()));
		entityManager.refresh(locked);
		AgentSessionData fresh = AgentSessionData.dataFromRecord(locked);
		List<AgentSessionUsage> rows = repository.findBySessionOrderByReportedAtAsc(session.getUuid());
		Map<UUID, long[]> perModel = new LinkedHashMap<>();
		Map<UUID, Long> perModelCost = new LinkedHashMap<>();
		Set<UUID> priceVersions = new LinkedHashSet<>();
		Map<UUID, ModelOntologyData> modelCache = new HashMap<>();
		long in = 0, out = 0, cr = 0, cw = 0, cost = 0;
		int requests = 0, turns = 0, toolCalls = 0, wallSeconds = 0;
		boolean costComplete = true;
		boolean anyCost = false;
		boolean mismatch = false;

		for (AgentSessionUsage r : rows) {
			in += r.getInputTokens(); out += r.getOutputTokens();
			cr += r.getCacheReadTokens(); cw += r.getCacheWriteTokens();
			requests += r.getRequests(); turns += r.getTurns();
			toolCalls += r.getToolCalls(); wallSeconds += r.getWallSeconds();

			long[] m = perModel.computeIfAbsent(r.getModel(), k -> new long[6]);
			m[0] += r.getInputTokens(); m[1] += r.getOutputTokens();
			m[2] += r.getCacheReadTokens(); m[3] += r.getCacheWriteTokens();
			m[4] += r.getRequests(); m[5] += r.getTurns();

			ModelOntologyData model = modelCache.computeIfAbsent(r.getModel(),
					k -> modelOntologyService.getModelOntologyData(k).orElse(null));
			ModelPricingService.DerivedCost derived = pricingService.derive(model, r);
			if (null == derived.costMicros()) {
				costComplete = false;
			} else {
				cost += derived.costMicros();
				anyCost = true;
				perModelCost.merge(r.getModel(), derived.costMicros(), Long::sum);
				if (null != derived.priceVersion()) priceVersions.add(derived.priceVersion());
			}
			if (null != session.getModel() && !session.getModel().equals(r.getModel())
					&& isObserved(r.getSource())) {
				mismatch = true;
			}
		}

		List<UsageByModel> byModel = new ArrayList<>();
		perModel.forEach((model, m) -> byModel.add(new UsageByModel(model, modelNameOf(model, modelCache),
				m[0], m[1], m[2], m[3], (int) m[4], (int) m[5], perModelCost.get(model))));

		// null rather than 0 when nothing priced: a zero cost reads as "this work was free",
		// which is a different claim from "nobody has told us what this model costs".
		// Applied to the copy read under the lock, never to the caller's.
		fresh.setUsageTotals(new UsageTotals(in, out, cr, cw, requests, turns, toolCalls,
				wallSeconds, rows.size(), byModel, anyCost ? cost : null,
				new ArrayList<>(priceVersions), costComplete));
		fresh.setModelMismatch(mismatch);
		// An agent's own claim about which model it ran is the declaration, not evidence about
		// it; only a transcript, a collector or the provider upgrades the assertion.
		if (!mismatch && !rows.isEmpty() && rows.stream().anyMatch(r -> isObserved(r.getSource()))) {
			fresh.setModelAssertion(ModelAssertionState.RUNTIME_OBSERVED);
		}
		return agentSessionService.saveData(fresh, wu);
	}

	/**
	 * What one hop consumed: the rows attributed to this task, from this session, between the
	 * assignment opening and now.
	 *
	 * <p>Taken at sign-off or return and stored on the append-only record. Filled in afterwards
	 * only by {@link HopUsageRefresher}, when rows reported after the hop ended belong to it, and
	 * never re-priced: each row derives its cost under the entry dated at its report, so
	 * recomputing old rows gives their old numbers and a price correction restates nothing.
	 */
	public AgentTaskData.HopUsage hopSnapshot(UUID taskUuid, UUID sessionUuid, ZonedDateTime from) {
		return hopSnapshot(taskUuid, sessionUuid, from, null);
	}

	/**
	 * As above, bounded at {@code to}: the same session's next assignment on the task, whose rows
	 * are that hop's rather than this one's. Null means open-ended.
	 */
	public AgentTaskData.HopUsage hopSnapshot(UUID taskUuid, UUID sessionUuid, ZonedDateTime from,
			ZonedDateTime to) {
		List<AgentSessionUsage> rows = repository.findForHop(taskUuid, sessionUuid,
				null != from ? from : ZonedDateTime.now().minusYears(1), to);
		if (rows.isEmpty()) return AgentTaskData.HopUsage.empty();
		Map<UUID, ModelOntologyData> modelCache = new HashMap<>();
		Set<UUID> priceVersions = new LinkedHashSet<>();
		long in = 0, out = 0, cr = 0, cw = 0, cost = 0;
		int requests = 0, turns = 0;
		boolean costComplete = true;
		boolean anyCost = false;
		for (AgentSessionUsage r : rows) {
			in += r.getInputTokens(); out += r.getOutputTokens();
			cr += r.getCacheReadTokens(); cw += r.getCacheWriteTokens();
			requests += r.getRequests(); turns += r.getTurns();
			ModelOntologyData model = modelCache.computeIfAbsent(r.getModel(),
					k -> modelOntologyService.getModelOntologyData(k).orElse(null));
			ModelPricingService.DerivedCost derived = pricingService.derive(model, r);
			if (null == derived.costMicros()) {
				costComplete = false;
			} else {
				cost += derived.costMicros();
				anyCost = true;
				if (null != derived.priceVersion()) priceVersions.add(derived.priceVersion());
			}
		}
		// Null, not zero, when nothing in the hop could be priced -- the same rule the session and
		// period rollups follow. Returning zero here was worse than inconsistent: taskUsage treats
		// a non-null cost as evidence something priced, so one unpriced hop made the whole task
		// read as free rather than as unpriced.
		return new AgentTaskData.HopUsage(in, out, cr, cw, requests, turns, rows.size(),
				anyCost ? cost : null, new ArrayList<>(priceVersions), costComplete);
	}

	/**
	 * A task's usage: the sum of its hop snapshots, plus any rows attributed to it that fell
	 * outside every hop.
	 *
	 * <p>A row reported after its hop closed is folded into that hop by {@link HopUsageRefresher},
	 * so the stragglers left are rows no hop can own -- which should not exist -- and they are
	 * counted and reported rather than quietly folded in or quietly dropped.
	 */
	public AgentTaskData.HopUsage taskUsage(AgentTaskData task) {
		long in = 0, out = 0, cr = 0, cw = 0;
		long cost = 0;
		int requests = 0, turns = 0, reports = 0;
		boolean costComplete = true;
		boolean anyCost = false;
		Set<UUID> priceVersions = new LinkedHashSet<>();
		List<AgentTaskData.HopUsage> hops = new ArrayList<>();
		task.getSignOffs().stream().map(AgentTaskData.SignOff::usage).filter(java.util.Objects::nonNull)
				.forEach(hops::add);
		task.getReturns().stream().map(AgentTaskData.TaskReturn::usage).filter(java.util.Objects::nonNull)
				.forEach(hops::add);
		for (AgentTaskData.HopUsage h : hops) {
			in += h.inputTokens(); out += h.outputTokens();
			cr += h.cacheReadTokens(); cw += h.cacheWriteTokens();
			requests += h.requests(); turns += h.turns(); reports += h.reports();
			if (null != h.derivedCostMicros()) { cost += h.derivedCostMicros(); anyCost = true; }
			if (!h.costComplete()) costComplete = false;
			priceVersions.addAll(h.priceVersions());
		}
		int rowsOnTask = repository.findByTask(task.getUuid()).size();
		if (rowsOnTask > reports) {
			log.info("Task {} has {} usage rows but its hops account for {}; the remainder was "
					+ "reported outside any assignment window", task.getUuid(), rowsOnTask, reports);
		}
		return new AgentTaskData.HopUsage(in, out, cr, cw, requests, turns, reports,
				anyCost ? cost : null, new ArrayList<>(priceVersions), costComplete);
	}

	/**
	 * Usage across a board or an org for a period.
	 *
	 * <p>Summed from the rows rather than from any cache: a period query is arbitrary, and a
	 * cache keyed on periods nobody asked for twice earns nothing. The indexes on
	 * {@code (board, reported_at)} and {@code (org, reported_at)} carry it.
	 */
	public UsageTotals boardUsage(UUID boardUuid, ZonedDateTime from, ZonedDateTime to) {
		return summarise(repository.findForBoardPeriod(boardUuid, from, to));
	}

	public UsageTotals orgUsage(UUID orgUuid, ZonedDateTime from, ZonedDateTime to) {
		return summarise(repository.findForOrgPeriod(orgUuid, from, to));
	}

	/** The totals of any set of rows, priced as the period rollups price them (task RD2-8's breakdown). */
	public UsageTotals summarise(List<AgentSessionUsage> rows) {
		if (rows.isEmpty()) return UsageTotals.empty();
		Map<UUID, long[]> perModel = new LinkedHashMap<>();
		Map<UUID, Long> perModelCost = new LinkedHashMap<>();
		Map<UUID, ModelOntologyData> modelCache = new HashMap<>();
		Set<UUID> priceVersions = new LinkedHashSet<>();
		long in = 0, out = 0, cr = 0, cw = 0, cost = 0;
		int requests = 0, turns = 0, toolCalls = 0, wallSeconds = 0;
		boolean costComplete = true;
		for (AgentSessionUsage r : rows) {
			in += r.getInputTokens(); out += r.getOutputTokens();
			cr += r.getCacheReadTokens(); cw += r.getCacheWriteTokens();
			requests += r.getRequests(); turns += r.getTurns();
			toolCalls += r.getToolCalls(); wallSeconds += r.getWallSeconds();
			long[] m = perModel.computeIfAbsent(r.getModel(), k -> new long[6]);
			m[0] += r.getInputTokens(); m[1] += r.getOutputTokens();
			m[2] += r.getCacheReadTokens(); m[3] += r.getCacheWriteTokens();
			m[4] += r.getRequests(); m[5] += r.getTurns();
			ModelOntologyData model = modelCache.computeIfAbsent(r.getModel(),
					k -> modelOntologyService.getModelOntologyData(k).orElse(null));
			ModelPricingService.DerivedCost derived = pricingService.derive(model, r);
			if (null == derived.costMicros()) {
				costComplete = false;
			} else {
				cost += derived.costMicros();
				perModelCost.merge(r.getModel(), derived.costMicros(), Long::sum);
				if (null != derived.priceVersion()) priceVersions.add(derived.priceVersion());
			}
		}
		List<UsageByModel> byModel = new ArrayList<>();
		perModel.forEach((model, m) -> byModel.add(new UsageByModel(model, modelNameOf(model, modelCache),
				m[0], m[1], m[2], m[3], (int) m[4], (int) m[5], perModelCost.get(model))));
		return new UsageTotals(in, out, cr, cw, requests, turns, toolCalls, wallSeconds,
				rows.size(), byModel, costComplete || !priceVersions.isEmpty() ? cost : null,
				new ArrayList<>(priceVersions), costComplete);
	}

	private static boolean isObserved(SessionUsageSource source) {
		return source == SessionUsageSource.TRANSCRIPT || source == SessionUsageSource.OTEL
				|| source == SessionUsageSource.PROVIDER;
	}
}
