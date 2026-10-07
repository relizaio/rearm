/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;

/**
 * Who may read an agent session on the programmatic endpoint (gaps §1.13, task 0192a587).
 *
 * <p>A session carries the agent's artifacts, usage and origin, and a reviewer that can open the
 * producer's session reviews with the producer's account of the work in front of it -- the
 * independence the client asked for was by prompt only, since any key in the organization read any
 * session. Readable now by those with a reason: the key that opened it, a key with ADMIN on the
 * organization, and the key holding the coordinator seat of a board on which the session worked a
 * task. Everyone else is told the session does not exist, as for an unknown uuid, so a uuid seen
 * on a task record does not even confirm there is something behind it.
 */
@Service
public class AgentSessionVisibilityService {

	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentSessionService agentSessionService;

	/**
	 * Whether a key may read a session (board-permissions.md D15): its own key; the org's ADMIN keys;
	 * the key holding the coordinator seat of a board the session worked; or a key with BOARD_READ on
	 * such a board ({@code readsBoard}).
	 */
	public boolean mayRead(AgentSessionData session, UUID callerKey, boolean callerIsOrgAdmin,
			Predicate<UUID> readsBoard) {
		if (null == session) return false;
		if (callerIsOrgAdmin) return true;
		if (null != callerKey && callerKey.equals(session.getApiKey())) return true;
		for (UUID board : boardsWorked(session)) {
			if (null != callerKey && callerKey.equals(seatKeyOf(board))) return true;
			if (null != readsBoard && readsBoard.test(board)) return true;
		}
		return false;
	}

	/**
	 * Whether a person may read a session (D15): one that worked boards needs BOARD_READ on one of
	 * them (the org's admin passes through the resolver); one that never worked a board is outside
	 * the board model and keeps the organization read the caller already passed.
	 */
	public static boolean personMayRead(Set<UUID> boardsWorked, Predicate<UUID> readsBoard) {
		if (null == boardsWorked || boardsWorked.isEmpty()) return true;
		return boardsWorked.stream().anyMatch(readsBoard);
	}

	/**
	 * The boards a session worked: recorded at assignment; for a session from before, the boards of
	 * the tasks that list it, written once.
	 */
	public Set<UUID> boardsWorked(AgentSessionData session) {
		return boardsWorked(session, this::boardsWorkedIn);
	}

	/**
	 * As above, with the organization's derivation supplied ({@link #boardsWorkedIn}), so a field
	 * resolver over a session list derives it once per request rather than once per row.
	 */
	public Set<UUID> boardsWorked(AgentSessionData session, Function<UUID, Map<UUID, Set<UUID>>> derivedOf) {
		if (null != session.getBoardsWorked() && !session.getBoardsWorked().isEmpty()) {
			return session.getBoardsWorked();
		}
		Set<UUID> derived = derivedOf.apply(session.getOrg()).getOrDefault(session.getUuid(), Set.of());
		if (!derived.isEmpty()) agentSessionService.recordBoardsWorked(session.getUuid(), derived);
		return derived;
	}

	/** For a list of sessions: each one's boards worked, the recorded ones or else read from the tasks. */
	public Map<UUID, Set<UUID>> boardsWorked(UUID org, Collection<AgentSessionData> sessions) {
		Map<UUID, Set<UUID>> out = new HashMap<>();
		boolean anyUnrecorded = false;
		for (AgentSessionData s : sessions) {
			if (null != s.getBoardsWorked() && !s.getBoardsWorked().isEmpty()) out.put(s.getUuid(), s.getBoardsWorked());
			else anyUnrecorded = true;
		}
		if (anyUnrecorded) {
			Map<UUID, Set<UUID>> derived = boardsWorkedIn(org);
			for (AgentSessionData s : sessions) out.putIfAbsent(s.getUuid(), derived.getOrDefault(s.getUuid(), Set.of()));
		}
		return out;
	}

	/** Every session of the organization that worked a task, with the boards it worked them on. */
	public Map<UUID, Set<UUID>> boardsWorkedIn(UUID org) {
		Map<UUID, Set<UUID>> out = new HashMap<>();
		for (AgentBoardData board : agentBoardService.listByOrg(org)) {
			for (AgentTaskData td : agentTaskService.listByBoard(board.getUuid(), null)) {
				if (null == td.getSessions()) continue;
				for (UUID s : td.getSessions()) out.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(board.getUuid());
			}
		}
		return out;
	}

	/** One task a session worked, as the session page names it (task RD2-11). */
	public record TaskWorked(UUID uuid, String key, String title, String role, UUID board, String boardName) {}

	/**
	 * The tasks a session worked (task RD2-11): the tasks of its boards whose sessions list holds it,
	 * with the role it last worked each as -- read from the tasks rather than kept a second time, as
	 * {@link #boardsWorked} is for a session from before. Newest registered first.
	 */
	public List<TaskWorked> tasksWorked(AgentSessionData session, java.util.function.Predicate<UUID> readsBoard) {
		List<TaskWorked> out = new java.util.ArrayList<>();
		UUID s = session.getUuid();
		for (UUID board : boardsWorked(session)) {
			if (!readsBoard.test(board)) continue;
			String boardName = agentBoardService.getBoardData(board).map(AgentBoardData::getName).orElse(null);
			for (AgentTaskData td : agentTaskService.listByBoard(board, null)) {
				if (null == td.getSessions() || !td.getSessions().contains(s)) continue;
				out.add(new TaskWorked(td.getUuid(), td.getKey(), td.getTitle(), roleOf(td, s), board, boardName));
			}
		}
		out.sort(java.util.Comparator.comparing((TaskWorked t) -> t.key() == null ? "" : t.key()).reversed());
		return out;
	}

	/** The role the session last worked the task as: its open assignment, else its newest sign-off or return. */
	static String roleOf(AgentTaskData td, UUID session) {
		if (null != td.getAssignment() && session.equals(td.getAssignment().session())) return td.getAssignment().role();
		String role = null;
		java.time.ZonedDateTime at = null;
		for (AgentTaskData.SignOff so : td.getSignOffs()) {
			if (session.equals(so.session()) && (null == at || (null != so.signedOffAt() && so.signedOffAt().isAfter(at)))) {
				role = so.role();
				at = so.signedOffAt();
			}
		}
		for (AgentTaskData.TaskReturn tr : td.getReturns()) {
			if (session.equals(tr.session()) && (null == at || (null != tr.returnedAt() && tr.returnedAt().isAfter(at)))) {
				role = tr.role();
				at = tr.returnedAt();
			}
		}
		return role;
	}

	/** The key of the session holding a board's coordinator seat, if any. */
	private UUID seatKeyOf(UUID boardUuid) {
		AgentBoardData board = agentBoardService.getBoardData(boardUuid).orElse(null);
		if (null == board || null == board.getCoordinatorSeat() || null == board.getCoordinatorSeat().session()) return null;
		return agentSessionService.getSessionData(board.getCoordinatorSeat().session())
				.map(AgentSessionData::getApiKey).orElse(null);
	}
}
