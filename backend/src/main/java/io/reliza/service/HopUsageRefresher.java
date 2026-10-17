/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Folds usage reported after a hop closed into that hop's snapshot (gaps §1.22).
 *
 * <p>A hop's usage is taken when it signs off or returns, from the rows reported by then. The CLI
 * reports at session close, after the sign-off, so without this the snapshot stays empty and
 * everything that reads it -- the hop's cost on the task, the per-role estimate, the over-allowance
 * alert -- never sees what the hop cost. Budgets read the rows and do not depend on it.
 *
 * <h2>Which hop a late row belongs to</h2>
 *
 * A closed hop owns the rows its session reported on the task from the hop's start up to that
 * session's next assignment on the task, open-ended for the last. So a row reported after a
 * sign-off lands on it, and a row reported once the same session holds the task again is the live
 * hop's, which takes its own snapshot when it ends.
 *
 * <h2>What changes on the record</h2>
 *
 * Only {@code usage}, and only with more rows: the role, session, times, outcome, note, outputs
 * and prompt version of a sign-off or return never change. Old rows keep their cost because each
 * is priced under the entry dated at its report. The allowance stamped when the hop ended is kept,
 * and {@code refreshedAt} says the figure was filled in later.
 *
 * <p>Runs after the report commits, in its own transaction, so the task row is never locked while
 * the report holds the session row: the sign-off path locks the task and only reads rows, and the
 * two cannot meet in the opposite order.
 */
@Slf4j
@Service
public class HopUsageRefresher {

	/** Itself, so REQUIRES_NEW goes through the proxy; see {@link BoardEffectsApplier#self}. */
	@Autowired @Lazy private HopUsageRefresher self;

	@Autowired private AgentTaskRepository agentTaskRepository;
	@Autowired @Lazy private AgentTaskService agentTaskService;
	@Autowired @Lazy private AgentSessionUsageService usageService;
	@Autowired @Lazy private AgentBoardService agentBoardService;
	@Autowired @Lazy private AgentBudgetService agentBudgetService;
	@Autowired @Lazy private BoardEffectsApplier boardEffectsApplier;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Refresh the task's closed hops for this session once the current transaction commits; inline
	 * when there is none, so a direct call behaves the same.
	 *
	 * <p>Nothing is scheduled while the session holds the task: rows reported now are the live
	 * hop's, and every closed hop's window ends where the live one began. That is the common case
	 * -- reporting per turn during a hop -- and it costs one read.
	 */
	public void refreshAfterCommit(UUID taskUuid, UUID sessionUuid, WhoUpdated wu) {
		if (!hasClosedHopToFill(taskUuid, sessionUuid)) return;
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			refresh(taskUuid, sessionUuid, wu);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				refresh(taskUuid, sessionUuid, wu);
			}
		});
	}

	private boolean hasClosedHopToFill(UUID taskUuid, UUID sessionUuid) {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid).orElse(null);
		if (null == td) return false;
		if (null != td.getAssignment() && sessionUuid.equals(td.getAssignment().session())) return false;
		return td.getSignOffs().stream().anyMatch(so -> sessionUuid.equals(so.session()))
				|| td.getReturns().stream().anyMatch(tr -> sessionUuid.equals(tr.session()));
	}

	/**
	 * Never throws: the report it follows has committed and been acknowledged, and a snapshot left
	 * short is a display problem the next report can fix. Logged, so it is not silent.
	 */
	public void refresh(UUID taskUuid, UUID sessionUuid, WhoUpdated wu) {
		try {
			self.refreshTask(taskUuid, sessionUuid, wu);
		} catch (Exception e) {
			log.error("Filling the closed hops of task {} with usage reported late by session {} failed;"
					+ " their snapshots stay as they were", taskUuid, sessionUuid, e);
		}
	}

	/**
	 * Recompute each closed hop of this session on the task, oldest first, and replace the usage of
	 * those whose row count grew.
	 *
	 * @return how many hops were filled in
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public int refreshTask(UUID taskUuid, UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		AgentTask locked = agentTaskRepository.findByIdWriteLocked(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		// The lock statement does not discard a copy already in the persistence context.
		entityManager.refresh(locked);
		AgentTaskData td = AgentTaskData.dataFromRecord(locked);
		List<ZonedDateTime> starts = assignmentStarts(td, sessionUuid);
		ZonedDateTime now = ZonedDateTime.now();
		BoardEffects effects = new BoardEffects(td.getBoard());
		int filled = 0;

		List<SignOff> signOffs = new ArrayList<>(td.getSignOffs());
		for (int i = 0; i < signOffs.size(); i++) {
			SignOff so = signOffs.get(i);
			if (!sessionUuid.equals(so.session())) continue;
			HopUsage fresh = filledIn(td, sessionUuid, so.usage(), so.assignedAt(), so.signedOffAt(), starts, now);
			if (null == fresh) continue;
			signOffs.set(i, so.withUsage(fresh));
			alertIfNowOver(td, so.role(), so.usage(), fresh, effects);
			filled++;
		}

		List<TaskReturn> returns = new ArrayList<>(td.getReturns());
		for (int i = 0; i < returns.size(); i++) {
			TaskReturn tr = returns.get(i);
			if (!sessionUuid.equals(tr.session())) continue;
			// A return records no start; the ASSIGN transition that opened it does. With none -- rows
			// written before the history was kept -- the hop's window is unknown and nothing is
			// folded in, rather than rows from an earlier hop.
			ZonedDateTime from = AgentBudgetService.assignedAt(td, sessionUuid, tr.returnedAt());
			if (null == from) continue;
			HopUsage fresh = filledIn(td, sessionUuid, tr.usage(), from, tr.returnedAt(), starts, now);
			if (null == fresh) continue;
			returns.set(i, tr.withUsage(fresh));
			alertIfNowOver(td, tr.role(), tr.usage(), fresh, effects);
			filled++;
		}

		if (filled == 0) return 0;
		td.setSignOffs(signOffs);
		td.setReturns(returns);
		agentTaskService.saveData(td, wu);
		noteBoardSpend(td.getBoard(), effects);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return filled;
	}

	/** When this session was assigned the task, oldest first. */
	private static List<ZonedDateTime> assignmentStarts(AgentTaskData td, UUID sessionUuid) {
		List<ZonedDateTime> starts = new ArrayList<>();
		for (StatusChange sc : td.getStatusHistory()) {
			if (sc.trigger() == StatusTrigger.ASSIGN && null != sc.actor() && sessionUuid.equals(sc.actor().uuid())) {
				starts.add(sc.at());
			}
		}
		starts.sort(null);
		return starts;
	}

	/**
	 * The hop's usage over its whole window, or null when no row was added since it was taken. The
	 * window ends at the session's first assignment after the hop ended.
	 */
	private HopUsage filledIn(AgentTaskData td, UUID sessionUuid, HopUsage stored, ZonedDateTime from,
			ZonedDateTime ended, List<ZonedDateTime> starts, ZonedDateTime now) {
		ZonedDateTime to = starts.stream().filter(s -> s.isAfter(ended)).findFirst().orElse(null);
		HopUsage fresh = usageService.hopSnapshot(td.getUuid(), sessionUuid, from, to);
		int had = null == stored ? 0 : stored.reports();
		if (fresh.reports() <= had) return null;
		return fresh.withAllowance(null == stored ? null : stored.allowanceMicros()).withRefreshedAt(now);
	}

	/** The over-allowance ALERT a hop would have posted at its end, had its rows been in by then. */
	private void alertIfNowOver(AgentTaskData td, String role, HopUsage before, HopUsage after,
			BoardEffects effects) {
		Long wasOver = AgentBudgetService.overAllowanceMicros(before);
		if (null != wasOver && wasOver > 0) return;
		agentTaskService.alertIfOverAllowance(td, role, after, effects);
	}

	/** The soft-alert line, once per crossing, as routing posts it. */
	private void noteBoardSpend(UUID boardUuid, BoardEffects effects) {
		AgentBoardData board = agentBoardService.getBoardData(boardUuid).orElse(null);
		if (null == board || null == board.getBudgetMicros()) return;
		long spent = agentBudgetService.spentOnBoard(board);
		if (AgentBudgetService.softAlertDue(board, spent)) {
			effects.softAlertCrossed(spent);
			effects.alert(AgentBudgetService.softAlertMessage(board, spent));
		}
	}
}
