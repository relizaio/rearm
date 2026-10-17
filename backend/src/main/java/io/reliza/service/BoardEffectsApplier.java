/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.WhoUpdated;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies a task operation's board effects after that operation commits.
 *
 * <h2>The lock order this exists to keep</h2>
 *
 * {@code AgentDocumentService.publish} takes the board row and then the task row, and says so at
 * both lock sites: board before task, always. Every task mutation takes the task row first. So a
 * task path that writes the board inline -- posting an alert, locking for budget -- asks for the
 * two in the opposite order, and two of those meeting on one board deadlock. That inversion was
 * already present in the human-gate alert, which called {@code postEvent} while holding the task
 * lock; it moves onto this mechanism rather than being patched where it stood.
 *
 * <h2>What is lost, and why it is acceptable</h2>
 *
 * The effects are not atomic with the task change any more. If the task commits and the board
 * write then fails, an alert is missing. That is the right way round: alerts are advisory and a
 * human can see the same state on the task, whereas a deadlock stalls two live agents and needs an
 * operator. The budget pause is a signal, not the control -- the check that actually refuses work
 * reads the rollups at routing and at assignment.
 *
 * <p>Applied on {@code afterCommit}, so a rolled-back task change posts nothing.
 */
@Slf4j
@Service
public class BoardEffectsApplier {

	/**
	 * Itself, so the REQUIRES_NEW below actually starts a transaction.
	 *
	 * <p>Spring's transaction advice lives on the proxy, and a plain {@code this.apply(...)} does
	 * not go through it. Without this the after-commit application runs with no transaction at all
	 * and the affinity upsert fails with "No active transaction for update or delete query" --
	 * which the catch below then swallows, so every board effect would be silently lost while the
	 * tests that assert the task state still passed.
	 */
	@Autowired @Lazy private BoardEffectsApplier self;

	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentRoleHistoryService roleHistoryService;
	@Autowired @Lazy private AgentSessionService agentSessionService;

	@Autowired @Lazy private AgentDocumentService agentDocumentService;

	@Autowired @Lazy private AgentTaskService agentTaskService;

	/**
	 * Register effects to be applied once the current transaction commits.
	 *
	 * <p>Applied inline when there is no transaction, which is what a test that calls a service
	 * directly does -- the behaviour has to be the same either way or the tests would prove
	 * something the application never runs.
	 */
	public void applyAfterCommit(BoardEffects effects, WhoUpdated wu) {
		if (null == effects || effects.isEmpty()) return;
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			self.apply(effects, wu);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				self.apply(effects, wu);
			}
		});
	}

	/**
	 * One new transaction, taking the board lock in the normal order.
	 *
	 * <p>Retried once: the usual failure is losing a race for the board row, which a second
	 * attempt wins. A second failure is logged with everything needed to see what was lost,
	 * because silence here would make a missing alert indistinguishable from nothing happening.
	 */
	public void apply(BoardEffects effects, WhoUpdated wu) {
		applyEffects(effects, wu);
		// Each report return in its own transaction, after the board's effects (task RD4-12): it writes the
		// commissioning task, and a failure there must not take the board's events with it.
		for (java.util.UUID investigation : effects.returnReportEntries()) {
			try {
				agentTaskService.returnInvestigation(investigation, wu);
			} catch (Exception e) {
				log.error("Returning the report of investigation {} failed", investigation, e);
			}
		}
	}

	private void applyEffects(BoardEffects effects, WhoUpdated wu) {
		try {
			self.applyOnce(effects, wu);
		} catch (Exception first) {
			// A SECOND transaction, not a second attempt inside the first. Postgres refuses every
			// statement after a failed one in the same transaction, so a retry that shared it
			// could only ever fail again -- the retry would look like resilience and be dead code.
			log.error("Applying board effects to {} failed; retrying in a new transaction",
					effects.board(), first);
			try {
				self.applyOnce(effects, wu);
			} catch (Exception second) {
				log.error("Board effects for {} were lost: {} event(s), pause={}, roleHistory={},"
						+ " policyRound={}", effects.board(), effects.events().size(),
						effects.isPauseForBudget(), effects.roleHistoryEntry(),
						effects.policyRoundEntry(), second);
			}
		}
	}

	// Rolls back on any exception, checked included: it runs in its own transaction and apply()
	// retries it, so a checked failure halfway through must not commit the effects it had
	// applied, or the retry would apply them twice.
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void applyOnce(BoardEffects effects, WhoUpdated wu) throws Exception {
		// The affinity row first: it is the only effect another request reads on a hot path, and
		// it does not touch the board row at all.
		if (null != effects.roleHistoryEntry()) {
			roleHistoryService.record(effects.roleHistoryEntry());
		}
		if (null != effects.sessionWorkedEntry()) {
			agentSessionService.recordBoardsWorked(effects.sessionWorkedEntry(), List.of(effects.board()));
		}
		for (BoardEffects.Event e : effects.events()) {
			agentBoardService.postEvent(effects.board(), e.kind(), e.message(),
					AgentActor.system("routing"), e.notifies(), wu);
		}
		if (null != effects.policyRoundEntry()) {
			// Under the board lock, like every other publish: the shared document component is
			// get-or-create, and two tasks reaching a policy stop at once would otherwise race to
			// create it.
			agentDocumentService.cutPolicyRound(effects.policyRoundEntry().task(),
					effects.policyRoundEntry().reason(), wu);
		}
		if (null != effects.closeQuestionsEntry()) {
			// Same reason as the policy round: publishing is board-first. Runs after the events
			// above so the board row is written before the task row is locked, keeping the
			// board-then-task order this applier shares with the publish path.
			BoardEffects.CloseQuestions cq = effects.closeQuestionsEntry();
			agentDocumentService.cutUnwindRound(cq.task(), cq.questionsRelease(),
					cq.answeringRelease(), wu);
		}
		if (null != effects.softAlertCrossedMicros()) {
			agentBoardService.markSoftAlertPosted(effects.board(), wu);
		}
		if (effects.isPauseForBudget()) {
			agentBoardService.setPause(effects.board(), BoardPauseLevel.OPERATOR, true,
					effects.pauseReason(), AgentActor.system("budget"), wu);
		}
	}
}
