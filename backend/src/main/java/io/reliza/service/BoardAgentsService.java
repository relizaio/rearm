/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.UsageTotals;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Who works a board (task RD3-5): every session that worked or polled it, what each is doing, what it cost in a
 * window, and whether a staleness rule names it -- the Agents tab. A join of what the board already keeps: the
 * tasks' sessions and assignments, the sessions' board activity (RD3-4), the spend breakdown (RD2-8) and the
 * staleness breaches (RD3-4).
 */
@Service
public class BoardAgentsService {

	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentService agentService;
	@Autowired private AgentBudgetService agentBudgetService;
	@Autowired private BoardStalenessService boardStalenessService;

	/** A session whose last poll of the board is this recent is WAITING for work; older, it is IDLE. */
	static final java.time.Duration WAITING_WITHIN = java.time.Duration.ofMinutes(10);

	public enum StateKind { WORKING, WAITING, IDLE, CLOSED }

	/**
	 * What the session is doing on the board: WORKING a task since its assignment; WAITING since its last poll;
	 * IDLE since its last activity; CLOSED at its close, by whom.
	 */
	public record AgentState(StateKind kind, UUID taskUuid, String taskKey, ZonedDateTime since, AgentActor closedBy) {}

	/** A staleness rule that names the session or its task, with the ALERT it posts. */
	public record StaleMark(String rule, String message) {}

	/** One row of the Agents tab. */
	public record AgentRow(UUID session, UUID agent, String agentName, List<String> roles, AgentState state,
			ZonedDateTime lastPollAt, ZonedDateTime lastOfferAt, ZonedDateTime lastActivityAt, int tasksCompleted,
			UsageTotals tokens, long costMicros, Double cacheShare, List<StaleMark> stale) {}

	/** Cache read tokens over input plus cache read plus cache write; null when none were counted. */
	static Double cacheShare(UsageTotals t) {
		if (null == t) return null;
		long denominator = t.inputTokens() + t.cacheReadTokens() + t.cacheWriteTokens();
		return denominator <= 0 ? null : (double) t.cacheReadTokens() / denominator;
	}

	/** The board's agents, open sessions first, then closed, each by last activity, newest first. */
	public List<AgentRow> agents(AgentBoardData board, ZonedDateTime from, ZonedDateTime to, ZonedDateTime now) {
		List<AgentTaskData> tasks = agentTaskService.listByBoard(board.getUuid(), null);
		AgentBudgetService.SpendBreakdown spend = agentBudgetService.breakdown(board, from, to);
		Map<UUID, AgentBudgetService.SessionSpend> spendBySession = new HashMap<>();
		for (AgentBudgetService.SessionSpend s : spend.bySession()) spendBySession.put(s.session(), s);

		Map<UUID, AgentSessionData> sessions = new java.util.LinkedHashMap<>();
		Set<UUID> ids = new LinkedHashSet<>();
		for (AgentTaskData td : tasks) {
			if (null != td.getSessions()) ids.addAll(td.getSessions());
			if (null != td.getAssignment() && null != td.getAssignment().session()) ids.add(td.getAssignment().session());
		}
		for (AgentSessionData s : agentSessionService.listWithBoardActivity(board.getUuid())) {
			if (board.getOrg().equals(s.getOrg())) sessions.put(s.getUuid(), s);
		}
		UUID seat = null == board.getCoordinatorSeat() ? null : board.getCoordinatorSeat().session();
		if (null != seat) ids.add(seat);
		ids.addAll(spendBySession.keySet());
		for (UUID id : ids) {
			if (null == id || sessions.containsKey(id)) continue;
			agentSessionService.getSessionData(id).filter(s -> board.getOrg().equals(s.getOrg()))
					.ifPresent(s -> sessions.put(id, s));
		}

		Map<UUID, List<StaleMark>> staleBySession = staleMarks(board, tasks, seat, now);
		Map<UUID, String> names = new HashMap<>();
		List<AgentRow> rows = new ArrayList<>();
		for (AgentSessionData s : sessions.values()) {
			rows.add(row(board, tasks, s, spendBySession.get(s.getUuid()), staleBySession.getOrDefault(s.getUuid(), List.of()),
					names, now));
		}
		rows.sort(Comparator.comparing((AgentRow r) -> r.state().kind() == StateKind.CLOSED)
				.thenComparing(AgentRow::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())));
		return rows;
	}

	private AgentRow row(AgentBoardData board, List<AgentTaskData> tasks, AgentSessionData s,
			AgentBudgetService.SessionSpend spent, List<StaleMark> stale, Map<UUID, String> names, ZonedDateTime now) {
		UUID id = s.getUuid();
		AgentSessionData.BoardActivity activity = null == s.getBoardActivity() ? null
				: s.getBoardActivity().get(board.getUuid().toString());
		ZonedDateTime lastPoll = null == activity ? null : activity.lastPollAt();
		ZonedDateTime lastOffer = null == activity ? null : activity.lastOfferAt();

		AgentTaskData working = null;
		LinkedHashSet<String> roles = new LinkedHashSet<>();
		int completed = 0;
		List<ZonedDateTime> moments = new ArrayList<>(Stream.of(lastPoll, lastOffer, s.getLastActivityAt(), s.getClosedAt())
				.filter(Objects::nonNull).toList());
		for (AgentTaskData td : tasks) {
			AgentTaskData.TaskAssignment a = td.getAssignment();
			if (null != a && id.equals(a.session())) {
				if (null == working || (null != a.assignedAt() && a.assignedAt().isAfter(working.getAssignment().assignedAt()))) {
					working = td;
				}
				if (null != a.role()) roles.add(a.role());
				if (null != a.assignedAt()) moments.add(a.assignedAt());
			}
			boolean signed = false;
			for (AgentTaskData.SignOff so : td.getSignOffs()) {
				if (!id.equals(so.session())) continue;
				signed = true;
				if (null != so.role()) roles.add(so.role());
				if (null != so.signedOffAt()) moments.add(so.signedOffAt());
			}
			for (AgentTaskData.TaskReturn tr : td.getReturns()) {
				if (!id.equals(tr.session())) continue;
				if (null != tr.role()) roles.add(tr.role());
				if (null != tr.returnedAt()) moments.add(tr.returnedAt());
			}
			if (signed && td.getStatus() == TaskStatus.COMPLETED) completed++;
		}
		// A session that polled but never worked here shows the roles it asked for.
		if (roles.isEmpty() && null != activity && null != activity.roles()) roles.addAll(activity.roles());
		ZonedDateTime lastActivity = moments.stream().max(Comparator.naturalOrder()).orElse(s.getStartedAt());

		AgentState state;
		if (s.getStatus() == AgentSessionData.SessionStatus.CLOSED) {
			state = new AgentState(StateKind.CLOSED, null, null, s.getClosedAt(), s.getClosedBy());
		} else if (null != working) {
			// The key alone (RD3-5 T-1): a consumer passes it on as a key, so the title stays out of it.
			state = new AgentState(StateKind.WORKING, working.getUuid(), working.getKey(), working.getAssignment().assignedAt(), null);
		} else if (null != lastPoll && !lastPoll.isBefore(now.minus(WAITING_WITHIN))) {
			state = new AgentState(StateKind.WAITING, null, null, lastPoll, null);
		} else {
			state = new AgentState(StateKind.IDLE, null, null, lastActivity, null);
		}
		UsageTotals tokens = null == spent ? null : spent.tokens();
		String name = names.computeIfAbsent(s.getAgent(), a -> null == a ? null
				: agentService.getAgentData(a).map(ad -> Optional.ofNullable(ad.getDisplayName()).filter(n -> !n.isBlank())
						.orElse(ad.getName())).orElse(null));
		return new AgentRow(id, s.getAgent(), name, List.copyOf(roles), state, lastPoll, lastOffer, lastActivity, completed,
				tokens, null == spent ? 0L : spent.costMicros(), cacheShare(tokens), stale);
	}

	/**
	 * The standing breaches that name a session: a stalled hop names its session; a silent seat names the seat; a
	 * stuck delivery names whichever session holds that task (none, while it delivers). An unstaffed role names no
	 * session. Nothing when the board watches no staleness.
	 */
	Map<UUID, List<StaleMark>> staleMarks(AgentBoardData board, List<AgentTaskData> tasks, UUID seat, ZonedDateTime now) {
		Map<UUID, List<StaleMark>> out = new HashMap<>();
		AgentBoardData.Staleness st = board.getStaleness();
		if (null == st || !st.anyRule()) return out;
		Map<UUID, UUID> holderOfTask = new HashMap<>();
		for (AgentTaskData td : tasks) {
			if (null != td.getAssignment() && null != td.getAssignment().session()) {
				holderOfTask.put(td.getUuid(), td.getAssignment().session());
			}
		}
		for (BoardStalenessService.Breach b : boardStalenessService.breaches(board, st, now)) {
			String[] parts = b.key().split(":");
			String rule = parts[0];
			UUID session = null;
			try {
				if (BoardStalenessService.HOP_NO_PROGRESS.equals(rule) && parts.length > 2) session = UUID.fromString(parts[2]);
				else if (BoardStalenessService.SEAT_SILENT.equals(rule)) session = seat;
				else if (BoardStalenessService.DELIVERY_STUCK.equals(rule) && parts.length > 1) {
					session = holderOfTask.get(UUID.fromString(parts[1]));
				}
			} catch (IllegalArgumentException e) {
				session = null;
			}
			if (null != session) out.computeIfAbsent(session, k -> new ArrayList<>()).add(new StaleMark(rule, b.message()));
		}
		return out;
	}
}
