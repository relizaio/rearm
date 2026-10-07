/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor.ActorKind;
import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AgentTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Apportions the coordinator's spend to tasks (board-mechanics D16, gaps §1.24).
 *
 * <p>A coordinator seat takes no assignments, so its usage rows carry the board and no task: they
 * count toward the board's spend and toward no task's. That makes every task look cheaper than it
 * was by its share of the coordinating. Each coordinator usage delta is therefore split equally
 * among the tasks the seat moved since the previous delta -- else the board's open tasks -- and
 * added to their {@code coordinatorEstimateMicros}, frozen: nothing here is ever recomputed.
 *
 * <p>The shares are the same money as the seat's rows, seen per task. Board spend reads the rows
 * only, and never adds the shares, so the sum of task spends is not the board's spend and is not
 * meant to be.
 *
 * <p>The board keeps the totals the §5.3 overhead is computed from: the apportioned micros and the
 * coordinator-caused transitions they were split over. Their ratio is added to every projected
 * round.
 *
 * <p>Runs after the report commits, in its own transaction, taking the board row before the task
 * rows -- the order the publish path takes them in.
 */
@Slf4j
@Service
public class CoordinatorShareService {

	/** Itself, so REQUIRES_NEW goes through the proxy; see {@link BoardEffectsApplier#self}. */
	@Autowired @Lazy private CoordinatorShareService self;

	@Autowired private AgentBoardRepository agentBoardRepository;
	@Autowired private AgentTaskRepository agentTaskRepository;
	@Autowired @Lazy private AgentBoardService agentBoardService;
	@Autowired @Lazy private AgentTaskService agentTaskService;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Who gets a delta and how many transitions it covers.
	 *
	 * @param recipients the tasks, in uuid order
	 * @param transitions the coordinator-caused transitions in the window; 0 when the recipients
	 *        are the fallback of open tasks, which says nothing about per-transition cost
	 */
	record Recipients(List<UUID> recipients, long transitions) {}

	/**
	 * Apportion {@code deltaMicros} once the current transaction commits; inline when there is none,
	 * so a direct call behaves the same.
	 */
	public void apportionAfterCommit(UUID boardUuid, UUID seatSession, long deltaMicros,
			ZonedDateTime reportedAt, WhoUpdated wu) {
		if (deltaMicros <= 0) return;
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			apportion(boardUuid, seatSession, deltaMicros, reportedAt, wu);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				apportion(boardUuid, seatSession, deltaMicros, reportedAt, wu);
			}
		});
	}

	/**
	 * Never throws: the report has committed and been acknowledged, and its rows already count at
	 * board level. A lost share makes the tasks look cheaper, which is what they looked before D16,
	 * so it is logged at error level rather than failing anything.
	 */
	public void apportion(UUID boardUuid, UUID seatSession, long deltaMicros, ZonedDateTime reportedAt,
			WhoUpdated wu) {
		try {
			self.apportionOnce(boardUuid, seatSession, deltaMicros, reportedAt, wu);
		} catch (Exception e) {
			log.error("Apportioning a coordinator delta of {} micros on board {} (seat session {}) failed;"
					+ " the tasks' coordinator estimates miss it", deltaMicros, boardUuid, seatSession, e);
		}
	}

	/** @return the shares written, by task */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Map<UUID, Long> apportionOnce(UUID boardUuid, UUID seatSession, long deltaMicros,
			ZonedDateTime reportedAt, WhoUpdated wu) throws RelizaException {
		AgentBoard lockedBoard = agentBoardRepository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		// The lock statement does not discard a copy already in the persistence context.
		entityManager.refresh(lockedBoard);
		AgentBoardData board = AgentBoardData.dataFromRecord(lockedBoard);

		List<AgentTaskData> tasks = agentTaskService.listByBoard(boardUuid, null);
		Recipients who = recipients(tasks, seatSession, board.getCoordinatorShareCursor(), reportedAt);
		Map<UUID, Long> shares = split(deltaMicros, who.recipients());
		for (Map.Entry<UUID, Long> share : shares.entrySet()) {
			AgentTask lockedTask = agentTaskRepository.findByIdWriteLocked(share.getKey())
					.orElseThrow(() -> new RelizaException("Task not found: " + share.getKey()));
			entityManager.refresh(lockedTask);
			AgentTaskData td = AgentTaskData.dataFromRecord(lockedTask);
			long had = null == td.getCoordinatorEstimateMicros() ? 0 : td.getCoordinatorEstimateMicros();
			td.setCoordinatorEstimateMicros(had + share.getValue());
			agentTaskService.saveData(td, wu);
		}
		if (!shares.isEmpty()) {
			board.setCoordinatorSharesMicros(board.getCoordinatorSharesMicros() + deltaMicros);
			board.setCoordinatorTransitions(board.getCoordinatorTransitions() + who.transitions());
		}
		// Advanced even when nobody got a share: the rows already count at board level, and the
		// next delta must not be split over this window again.
		board.setCoordinatorShareCursor(reportedAt);
		agentBoardService.saveData(board, wu);
		return shares;
	}

	/**
	 * The tasks the seat moved after {@code after} and up to {@code upTo}, with the number of
	 * transitions; else every open task, with none counted; else nobody.
	 */
	static Recipients recipients(List<AgentTaskData> tasks, UUID seatSession, ZonedDateTime after,
			ZonedDateTime upTo) {
		List<UUID> moved = new ArrayList<>();
		long transitions = 0;
		for (AgentTaskData td : tasks) {
			long n = 0;
			for (StatusChange sc : td.getStatusHistory()) {
				if (null == sc.actor() || sc.actor().kind() != ActorKind.SESSION
						|| !seatSession.equals(sc.actor().uuid()) || null == sc.at()) continue;
				if (null != after && !sc.at().isAfter(after)) continue;
				if (sc.at().isAfter(upTo)) continue;
				n++;
			}
			if (n > 0) {
				moved.add(td.getUuid());
				transitions += n;
			}
		}
		if (!moved.isEmpty()) return new Recipients(sorted(moved), transitions);
		List<UUID> open = tasks.stream()
				.filter(td -> td.getStatus() != TaskStatus.COMPLETED && td.getStatus() != TaskStatus.CANCELLED)
				.map(AgentTaskData::getUuid)
				.toList();
		return new Recipients(sorted(open), 0);
	}

	/**
	 * An equal integer split. The remainder -- fewer micros than there are recipients -- goes to the
	 * earliest recipient in uuid order (as text), so a rerun gives the same shares; it is too small to
	 * matter otherwise.
	 */
	static Map<UUID, Long> split(long deltaMicros, List<UUID> recipients) {
		Map<UUID, Long> shares = new LinkedHashMap<>();
		if (recipients.isEmpty() || deltaMicros <= 0) return shares;
		List<UUID> ordered = sorted(recipients);
		long each = deltaMicros / ordered.size();
		long remainder = deltaMicros % ordered.size();
		for (int i = 0; i < ordered.size(); i++) {
			long share = each + (i == 0 ? remainder : 0);
			if (share > 0) shares.put(ordered.get(i), share);
		}
		return shares;
	}

	private static List<UUID> sorted(List<UUID> uuids) {
		List<UUID> copy = new ArrayList<>(uuids);
		copy.sort(Comparator.comparing(UUID::toString));
		return copy;
	}
}
