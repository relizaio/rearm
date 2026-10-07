/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.QuestionFrame;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ReleaseData.DocumentRef;
import lombok.extern.slf4j.Slf4j;

/**
 * The loop between roles, run by the board rather than by the coordinator.
 *
 * <p>After every hop the next step is mechanical: a clean pass goes to the role whose inputs are
 * now satisfied, open items go to whoever produces the thing they are about, an answer unwinds one
 * level of the question stack, and a task with every required role passed completes. None of that
 * is judgment. What is judgment -- intake, splitting, ordering across tasks, and the exceptions
 * this cannot resolve -- stays with the coordinator, which is why the coordinator seat still
 * exists and why every path here can hand a task back to it.
 *
 * <h2>Why the graph is derived</h2>
 *
 * Roles already say what they need ({@code requiredInputs}) and what they make
 * ({@code producesOutputs}). An edge list would be the same information written twice, and the
 * copy that goes stale is the one nothing reads until a task routes into a wall. So the next role
 * is computed from what the task now has, and the answering role is whichever active role produces
 * the questioned type.
 *
 * <h2>Why nothing here writes the board</h2>
 *
 * This runs inside the task transaction, under the task row lock. Board writes take the board lock,
 * and publish takes board-then-task. Every board effect is collected into {@link BoardEffects} and
 * applied by the caller after commit, in the normal lock order.
 */
@Slf4j
@Service
public class AgentRoutingService {

	@Autowired @Lazy private AgentTaskService agentTaskService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private AgentBudgetService agentBudgetService;
	@Autowired @Lazy private AgentDeliveryService agentDeliveryService;
	@Autowired @Lazy private BoardEffectsApplier boardEffectsApplier;

	/** What routing decided, so the caller can apply the board half after it commits. */
	public record Routed(AgentTaskData task, BoardEffects effects) {}

	/** Why a loop stopped, which decides what the exhaustion path says. */
	public enum StopReason {
		CYCLE_CAP("cycle cap"),
		NO_PROGRESS("no progress"),
		BUDGET("budget");

		private final String label;

		StopReason(String label) { this.label = label; }

		@Override public String toString() { return label; }
	}

	/**
	 * Route a task whose hop has just closed.
	 *
	 * <p>Called from sign-off, return, hold lift and human review, inside their transaction and
	 * after their own save, so the task this reads already carries the hop that just ended.
	 */
	public Routed route(AgentTaskData td, AgentBoardData board, SignOff closing) {
		return route(td, board, closing, null);
	}

	/**
	 * A person's release of a hold, for one routing pass (task 4c566d0d). An argument rather than
	 * state: it lives for this pass and is gone, so the next identical stop fires as before.
	 *
	 * @param skipLoopStops the no-progress and cycle-cap stops are not checked this once -- the
	 *        release of the hold they placed is the person overriding them. The budget still is:
	 *        money is not overridden by a release.
	 * @param toRole the role to route to instead of the one routing would pick; null lets routing
	 *        pick. Active on the board: the release resolves and checks it before calling.
	 */
	public record RouteOverride(boolean skipLoopStops, UUID toRole) {}

	/** As {@link #route(AgentTaskData, AgentBoardData, SignOff)}, with a release's override. */
	public Routed route(AgentTaskData td, AgentBoardData board, SignOff closing, RouteOverride override) {
		return route(td, board, closing, override, List.of());
	}

	/**
	 * As above, knowing which reviewed documents a guard kept from READY_TO_SHIP in this hop (task
	 * a8b519a0): when that leaves the next role unable to run, the coordinator is told which round
	 * waits on a person and why, rather than only that no role can run.
	 */
	public Routed route(AgentTaskData td, AgentBoardData board, SignOff closing, RouteOverride override,
			List<AgentDocumentService.RefusedPromotion> refused) {
		BoardEffects effects = new BoardEffects(board.getUuid());
		try {
			return new Routed(decide(td, board, closing, override, null == refused ? List.of() : refused, effects),
					effects);
		} catch (RuntimeException e) {
			// Routing must never lose a hop that already happened. The sign-off is saved; failing
			// here would roll it back and the agent would have no way to record its work again.
			log.error("Routing failed for task {} on board {}; leaving it to the coordinator",
					td.getUuid(), board.getUuid(), e);
			toCoordinator(td, "routing failed: " + e.getMessage(), effects);
			return new Routed(td, effects);
		}
	}

	/**
	 * Route a task after a person's decision round left something new blocking (operator-actions
	 * D9).
	 *
	 * <p>Rule 2 of §2.2: QUEUED for the role that produces what the round is about, with the round
	 * pinned so the producer reads it as a binding. No cycle round is counted and no loop stop is
	 * checked, because a person's review item is not an agent loop. No frame is pushed either: nobody
	 * is waiting on an answer, and once the producer passes the task goes forward as usual.
	 *
	 * <p>With no {@code about}, or no active role producing it, the task goes to the coordinator,
	 * as questions nobody produces do. On a board with no coordinator that is where a person
	 * authorizes it. So does a round that would not fit the budget: a person asked for this work,
	 * and completing the task under policy over what they just filed would undo their decision.
	 */
	public Routed routeDecided(AgentTaskData td, AgentBoardData board, ReleaseData round) {
		BoardEffects effects = new BoardEffects(board.getUuid());
		try {
			DocumentRef doc = round.getDocument();
			RearmSpecificationType about = null != doc && null != doc.reviewItems()
					&& null != doc.reviewItems().about() ? doc.reviewItems().about().specification() : null;
			// A round that names nothing it is about goes back to whoever made the work, as a
			// reviewer's does: a person filing a P1 should not need the document vocabulary.
			Maker maker = null == about ? makerOf(td, board, null) : null;
			if (null != maker && null == maker.role()) {
				toCoordinator(td, "a person's review item decisions left blocking items about the work itself, but "
						+ maker.whyNone(), effects);
				return new Routed(td, effects);
			}
			Optional<AgentTaskRoleConfigData> producer = null != maker ? Optional.of(maker.role())
					: activeRoles(board).stream().filter(rc -> produces(rc, about)).findFirst();
			if (producer.isEmpty()) {
				toCoordinator(td, "a person's review item decisions left blocking items about " + about
						+ ", which no active role on this board produces", effects);
				return new Routed(td, effects);
			}
			if (!agentBudgetService.nextRoundFits(td, board, producer.get())) {
				toCoordinator(td, "a person's review item decisions need another " + producer.get().getName()
						+ " round, which the budget does not cover", effects);
				return new Routed(td, effects);
			}
			effects.info("Review items decided on task " + label(td) + "; back to "
					+ producer.get().getName());
			return new Routed(queueFor(td, board, producer.get(), StatusTrigger.AUTHORIZE, doc, effects,
					"review items decided; back to " + producer.get().getName()), effects);
		} catch (RuntimeException e) {
			log.error("Routing a review item decision failed for task {} on board {}; leaving it to the"
					+ " coordinator", td.getUuid(), board.getUuid(), e);
			toCoordinator(td, "routing the review item decision failed: " + e.getMessage(), effects);
			return new Routed(td, effects);
		}
	}

	/**
	 * Route a task after a person's decision round left nothing blocking (operator-actions D9 as
	 * amended, gaps §1.8): forward from that round, as the board would have at a clean close.
	 *
	 * <p>The frames the rejected round pushed -- the reviewer waiting on its producer, or the one a
	 * coordinator case pushed -- are popped, since what they waited on is settled; the person's
	 * round already carries the items' new statuses. A frame of another kind on top is a question
	 * nobody answered, and the task stays where it is.
	 *
	 * <p>No cycle is counted and no loop stop checked: a person's decision is not an agent loop.
	 * The transition is the board's; the event names the person.
	 */
	public Routed routeCleared(AgentTaskData td, AgentBoardData board, ReleaseData round, AgentActor by) {
		BoardEffects effects = new BoardEffects(board.getUuid());
		try {
			popFramesOf(td, round.getDocument().specification());
			List<QuestionFrame> stack = td.getQuestionStack();
			String who = null != by && StringUtils.isNotBlank(by.name()) ? by.name() : "a person";
			if (!stack.isEmpty()) {
				effects.info("Review items decided on task " + label(td) + " by " + who
						+ "; nothing blocks, but a question upstream is still open, so the task stays put");
				return new Routed(td, effects);
			}
			AgentTaskData routed = forward(td, board, activeRoles(board), null, List.of(), effects);
			String where = switch (routed.getStatus()) {
				case QUEUED -> "queued for " + routed.getRole();
				case COMPLETED -> "completed";
				case DELIVERING -> "delivering: waiting for its PR(s) to merge";
				case AWAITING_COORDINATOR -> "to the coordinator";
				default -> String.valueOf(routed.getStatus()).toLowerCase(java.util.Locale.ROOT);
			};
			effects.info("Review items decided on task " + label(td) + " by " + who + "; nothing blocks -- " + where);
			return new Routed(routed, effects);
		} catch (RuntimeException e) {
			log.error("Routing a cleared review item decision failed for task {} on board {}; leaving it to"
					+ " the coordinator", td.getUuid(), board.getUuid(), e);
			toCoordinator(td, "routing the cleared review item decision failed: " + e.getMessage(), effects);
			return new Routed(td, effects);
		}
	}

	/** Pop the frames on top of the stack that wait on a round of {@code spec}. */
	private void popFramesOf(AgentTaskData td, RearmSpecificationType spec) {
		List<QuestionFrame> stack = td.getQuestionStack();
		while (!stack.isEmpty() && roundOf(stack.get(stack.size() - 1), spec)) {
			stack.remove(stack.size() - 1);
		}
	}

	/** Whether a frame waits on a round of {@code spec}. */
	private boolean roundOf(QuestionFrame frame, RearmSpecificationType spec) {
		if (null == frame.questionsRelease()) return false;
		return sharedReleaseService.getReleaseData(frame.questionsRelease())
				.map(ReleaseData::getDocument)
				.map(d -> d.specification() == spec)
				.orElse(false);
	}

	/**
	 * Route a task whose top frame a human has just answered (D6).
	 *
	 * <p>The human's entry into the same unwind the answering role's PASS takes. It pops the frame
	 * whatever the frame's {@code answeringRole} says, because the case this exists for is the one
	 * where no role produced the questioned input and the coordinator escalated -- there is no
	 * sign-off to route on, and {@link #answers} would never match.
	 *
	 * <p>The answer round is pinned on the re-queued asker, so the agent reads the answers as a
	 * binding rather than having to go and find them.
	 */
	public Routed routeAnswered(AgentTaskData td, AgentBoardData board, UUID answerRelease) {
		BoardEffects effects = new BoardEffects(board.getUuid());
		try {
			if (td.getQuestionStack().isEmpty()) {
				toCoordinator(td, "an answer arrived for a task with nothing waiting on one",
						effects);
				return new Routed(td, effects);
			}
			QuestionFrame frame = td.getQuestionStack().remove(td.getQuestionStack().size() - 1);
			return new Routed(toAskerAfterAnswer(td, board, frame, answerRelease, effects), effects);
		} catch (RuntimeException e) {
			log.error("Routing an answer failed for task {} on board {}; leaving it to the"
					+ " coordinator", td.getUuid(), board.getUuid(), e);
			toCoordinator(td, "routing the answer failed: " + e.getMessage(), effects);
			return new Routed(td, effects);
		}
	}

	private AgentTaskData decide(AgentTaskData td, AgentBoardData board, SignOff closing,
			RouteOverride override, List<AgentDocumentService.RefusedPromotion> refused, BoardEffects effects) {
		List<AgentTaskRoleConfigData> roles = activeRoles(board);
		List<BoardReviewItem> blocking = blockingItems(td, board, closing);
		AgentTaskRoleConfigData named = null == override || null == override.toRole() ? null
				: roles.stream().filter(rc -> rc.getUuid().equals(override.toRole())).findFirst().orElse(null);

		if (!blocking.isEmpty()) {
			return routeUpstream(td, board, roles, closing, blocking, override, named, effects);
		}
		if (null != named) {
			// Nothing open, and the person named where the task goes: there, with the last hop's
			// output pinned, if the next round fits the budget.
			String budget = budgetStop(td, board, named, "the next " + named.getName() + " round");
			if (null != budget) return holdStopped(td, budget, List.of(), effects);
			return queueFor(td, board, named, StatusTrigger.AUTHORIZE,
					null == closing ? null : latestRoundOfOutput(td, board, closing), effects,
					"sent to " + named.getName() + " as the person named");
		}
		// A rejection that says nothing about what is wrong is an exception, not a route.
		//
		// Without this it falls through to the forward path, which picks the first role with no
		// current pass whose inputs are satisfied -- and the role that just rejected has no pass,
		// so the board re-queues the task to the role that just refused it. Nothing counts that as
		// a cycle and nothing checks a budget on the forward path, so it spins, paying for a hop
		// every time, until a human notices. A rejection with no index is a hop saying "I will not
		// do this and I am not telling you why", which is precisely what the coordinator is for.
		if (null != closing && SignOffOutcome.REJECTED == closing.outcome()) {
			DocumentRef own = outputWithItems(td, board, closing);
			if (null == own || !agentDocumentService.rejectionDecidedOver(td, board.getBlockingPriority(), closing)) {
				toCoordinator(td, "the " + closing.role() + " hop rejected the task without recording"
						+ " what is wrong, so there is nothing to route on", effects);
				return td;
			}
			// A person decided over this rejection -- typically while the task was on hold, and this
			// is the release re-routing (gaps §1.8, shape 4). The frames it pushed are settled, and
			// the task moves on as it would have at a clean close.
			popFramesOf(td, own.specification());
		}
		// Nothing open: if this hop answered a question, the asker gets its answer back.
		if (!td.getQuestionStack().isEmpty() && answers(td, closing)) {
			return popToAsker(td, board, roles, closing, effects);
		}
		return forward(td, board, roles, closing, refused, effects);
	}

	/** Forward from a clean close: an investigation's own path, else the work pipeline (task RD4-12). */
	private AgentTaskData forward(AgentTaskData td, AgentBoardData board, List<AgentTaskRoleConfigData> roles,
			SignOff closing, List<AgentDocumentService.RefusedPromotion> refused, BoardEffects effects) {
		return td.isInvestigation() ? investigationForward(td, board, roles, closing, effects)
				: routeForward(td, board, roles, closing, refused, effects);
	}

	// ---------- an investigation (task RD4-12) ----------

	/**
	 * An investigation's forward path: the investigating role until it has passed with its report, then the
	 * reviewing role when one was asked for, then complete -- no delivery step, since it links no PRs. The board's
	 * work pipeline, and its REQUIRED roles, do not apply. A review older than the newest investigating pass
	 * reviewed an earlier report and runs again.
	 */
	private AgentTaskData investigationForward(AgentTaskData td, AgentBoardData board,
			List<AgentTaskRoleConfigData> roles, SignOff closing, BoardEffects effects) {
		AgentTaskData.Investigation inv = td.getInvestigation();
		AgentTaskRoleConfigData investigator = roles.stream()
				.filter(rc -> inv.investigatedBy(rc.getUuid(), rc.getName())).findFirst().orElse(null);
		SignOff made = td.lastSignOffForRole(inv.roleUuid(), inv.role());
		boolean madeStands = null != made && !td.reopenedSince(made) && SignOffOutcome.PASSED == made.outcome();
		if (!madeStands) {
			if (null == investigator) {
				toCoordinator(td, "the investigating role " + inv.role() + " is no longer active on this board", effects);
				return td;
			}
			String budget = budgetStop(td, board, investigator, "the next " + investigator.getName() + " round");
			if (null != budget) return holdStopped(td, budget, List.of(), effects);
			return queueFor(td, board, investigator, StatusTrigger.AUTHORIZE, null, effects,
					"investigation; to " + investigator.getName());
		}
		if (null != inv.review()) {
			SignOff reviewed = td.lastSignOffForRole(inv.reviewUuid(), inv.review());
			boolean reviewStands = null != reviewed && !td.reopenedSince(reviewed)
					&& SignOffOutcome.PASSED == reviewed.outcome() && null != reviewed.signedOffAt()
					&& null != made.signedOffAt() && reviewed.signedOffAt().isAfter(made.signedOffAt());
			if (!reviewStands) {
				AgentTaskRoleConfigData reviewer = roles.stream()
						.filter(rc -> inv.reviewedBy(rc.getUuid(), rc.getName())).findFirst().orElse(null);
				if (null == reviewer) {
					toCoordinator(td, "the reviewing role " + inv.review() + " is no longer active on this board", effects);
					return td;
				}
				String budget = budgetStop(td, board, reviewer, "the " + reviewer.getName() + " review");
				if (null != budget) return holdStopped(td, budget, List.of(), effects);
				DocumentRef report = new DocumentRef(RearmSpecificationType.BOARD_INVESTIGATION_REPORT, null, null, null, null,
						null, td.getUuid(), null, null, null);
				effects.info("Investigation " + label(td) + ": report in; to " + reviewer.getName() + " for review");
				return queueFor(td, board, reviewer, StatusTrigger.AUTHORIZE, report, effects,
						"report in; to " + reviewer.getName() + " for review");
			}
		}
		// A correction a person filed is still theirs to decide, as on a work task (operator-actions D25).
		List<String> corrections = openPersonCorrections(td);
		if (!corrections.isEmpty()) {
			toCoordinator(td, "corrections filed by a person are still open: " + corrections, effects);
			return td;
		}
		// The INFO is the return's, which names both tasks once the report is pinned (design §3.4).
		agentTaskService.completeInvestigation(td, StatusTrigger.COMPLETE, AgentActor.system("routing"),
				"report in" + (null == inv.review() ? "" : "; the " + inv.review() + " review passed"), effects);
		return td;
	}

	// ---------- 2.2.1 forward ----------

	private AgentTaskData routeForward(AgentTaskData td, AgentBoardData board,
			List<AgentTaskRoleConfigData> roles, SignOff closing,
			List<AgentDocumentService.RefusedPromotion> refused, BoardEffects effects) {
		Optional<AgentTaskRoleConfigData> next = roles.stream()
				.filter(rc -> !investigatesOnly(rc))
				.filter(rc -> !hasCurrentPass(td, board, rc))
				.filter(rc -> agentTaskInputService.satisfied(board, rc, td))
				.findFirst();
		if (next.isPresent()) {
			// The forward path pays for a hop exactly as the upstream path does. Checking only
			// the upstream one would mean a task budget never stops normal progress, which is
			// most of what a task spends.
			String budget = budgetStop(td, board, next.get(), "the next " + next.get().getName() + " round");
			if (null != budget) return holdStopped(td, budget, List.of(), effects);
			return queueFor(td, board, next.get(), StatusTrigger.AUTHORIZE, null, effects,
					(null == closing ? "" : closing.role() + " " + closing.outcome().name().toLowerCase() + "; ")
							+ "next " + next.get().getName());
		}
		List<String> missing = requiredWithoutCurrentPass(td, board, roles);
		if (missing.isEmpty()) {
			return complete(td, board, closing, effects);
		}
		// A required role that cannot be reached: its inputs are not satisfied and nothing left to
		// run produces them. That is a board configured with a gap, not a task in trouble, and a
		// human has to see it -- so it goes to the coordinator rather than parking as exhausted.
		toCoordinator(td, "no role can run and required role(s) have not passed: " + missing
				+ waitingOn(td, board, roles, missing, refused), effects);
		return td;
	}

	/**
	 * What each unreachable required role waits on (task a8b519a0, T-1), so the coordinator -- or a
	 * person reading only the ALERT -- learns the next step rather than only that nothing can run.
	 * A document round below the role's floor is named with its lifecycle, the guard's refusal when
	 * a guard kept it there in this hop, and that a person promotes it before the role is
	 * authorized; any other unmet input is named with the reason the input check gives.
	 */
	private String waitingOn(AgentTaskData td, AgentBoardData board, List<AgentTaskRoleConfigData> roles,
			List<String> missing, List<AgentDocumentService.RefusedPromotion> refused) {
		List<String> parts = new ArrayList<>();
		for (String name : missing) {
			AgentTaskRoleConfigData rc = roles.stream().filter(r -> name.equals(r.getName())).findFirst().orElse(null);
			if (null == rc) continue;
			for (AgentTaskInputService.InputVerdict v : agentTaskInputService.evaluate(board, rc, td)) {
				if (v.met()) continue;
				RequiredInput ri = v.requirement();
				ReleaseData below = InputKind.DOCUMENT == ri.kind() && null != ri.specification()
						? newestRoundOf(td, ri.specification())
								.filter(rd -> AgentTaskInputService.maturity(rd.getLifecycle())
										< AgentTaskInputService.maturity(ri.minLifecycle()))
								.orElse(null)
						: null;
				if (null == below) {
					parts.add(name + " waits on " + (null != ri.specification() ? ri.specification() : ri.kind())
							+ ": " + v.unmetReason());
					continue;
				}
				String why = refused.stream().filter(p -> below.getUuid().equals(p.release())).findFirst()
						.map(p -> " — not promoted: " + p.reason()).orElse("");
				parts.add(name + " waits on " + roundLabel(below) + " at " + ri.minLifecycle() + ", which is "
						+ below.getLifecycle() + why + "; a person promotes it to " + ri.minLifecycle()
						+ ", then authorize the " + name);
			}
		}
		return parts.isEmpty() ? "" : "; " + String.join("; ", parts);
	}

	/**
	 * The task's newest live round of a document kind, prose or index -- what a promotion would
	 * have moved. Cancelled and rejected rounds are no one's next step.
	 */
	private Optional<ReleaseData> newestRoundOf(AgentTaskData td, RearmSpecificationType spec) {
		List<UUID> linked = null == td.getReleases() ? List.of() : td.getReleases();
		for (int i = linked.size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(linked.get(i));
			if (ord.isEmpty()) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null == doc || doc.specification() != spec || !td.getUuid().equals(doc.task()) || doc.superseded()) continue;
			if (AgentTaskInputService.maturity(ord.get().getLifecycle()) < 0) continue;
			return ord;
		}
		return Optional.empty();
	}

	private static String roundLabel(ReleaseData rd) {
		DocumentRef doc = rd.getDocument();
		if (null == doc) return "release " + rd.getUuid();
		return doc.specification() + (null == doc.round() ? "" : " round " + doc.round());
	}

	// ---------- 2.2.2 upstream ----------

	/**
	 * Rule 2: items a hop left open go back to whoever produces what they are about. A review or
	 * test round that names nothing it is about is about the work itself, so it goes back to the
	 * role that made the work (board-mechanics §3.1; gaps §1.16) -- the newest earlier hop by a
	 * role that is not itself a reviewer. An explicit {@code about} still wins. BOARD_QUESTIONS always
	 * name an input and get no default.
	 */
	private AgentTaskData routeUpstream(AgentTaskData td, AgentBoardData board,
			List<AgentTaskRoleConfigData> roles, SignOff closing, List<BoardReviewItem> blocking,
			RouteOverride override, AgentTaskRoleConfigData named, BoardEffects effects) {
		DocumentRef questioned = latestRoundOfOutput(td, board, closing);
		RearmSpecificationType about = null != questioned && null != questioned.reviewItems()
				&& null != questioned.reviewItems().about()
						? questioned.reviewItems().about().specification()
						: null;
		UUID askingRole = closing.roleUuid();

		boolean aboutTheWork = null == about && null != questioned && isReviewIndex(questioned.specification());
		Maker maker = aboutTheWork ? makerOf(td, board, closing) : null;
		// A role the person named at a release replaces the one routing would pick; the frame, the
		// cycle count and the pinned round follow it exactly as they would the computed producer.
		List<AgentTaskRoleConfigData> producers = null != named ? List.of(named)
				: null != about ? roles.stream().filter(rc -> produces(rc, about)).toList()
				: null != maker && null != maker.role() ? List.of(maker.role()) : List.of();
		if (producers.isEmpty()) {
			// Nobody on this board makes the thing being asked about. The coordinator names a role
			// or escalates; the frame stays pushed with no answering role so the stack still shows
			// what is waiting.
			pushFrame(td, askingRole, closing, questioned, null);
			toCoordinator(td, null != maker ? "open items about the work itself, but " + maker.whyNone()
					: "open items about " + (null == about ? "the work itself" : about)
							+ ", which no active role on this board produces", effects);
			return td;
		}
		AgentTaskRoleConfigData answering = producers.get(0);

		// A release of the hold this stop placed is the person overriding it, once (4c566d0d). The
		// cycle is still counted below, so the cap keeps bounding the loop.
		Stop stop = null != override && override.skipLoopStops() ? null
				: stopFor(td, board, closing, answering, questioned, blocking);
		if (null != stop) {
			return exhausted(td, board, stop.reason(), stop.detail(), blocking, askingRole, effects);
		}
		String budget = budgetStop(td, board, answering, "the next " + answering.getName() + " round");
		if (null != budget) return holdStopped(td, budget, blocking, effects);

		countCycle(td, askingRole, answering.getUuid());
		pushFrame(td, askingRole, closing, questioned, answering.getUuid());
		return queueFor(td, board, answering, StatusTrigger.AUTHORIZE, questioned, effects,
				"open " + String.join(", ", blocking.stream().map(BoardReviewItem::id).filter(Objects::nonNull).toList())
						+ "; back to " + answering.getName());
	}

	// ---------- 2.2.3 the answer comes back ----------

	private AgentTaskData popToAsker(AgentTaskData td, AgentBoardData board,
			List<AgentTaskRoleConfigData> roles, SignOff closing, BoardEffects effects) {
		QuestionFrame frame = td.getQuestionStack().get(td.getQuestionStack().size() - 1);
		td.getQuestionStack().remove(td.getQuestionStack().size() - 1);
		// The answer was a new round of a PROSE document, which carries no index and so closed
		// nothing. The board closes the asked ids on its behalf (D11), or they read OPEN for the
		// life of the task even though they were answered.
		UUID answeringRelease = null != closing && null != closing.outputs()
				&& !closing.outputs().isEmpty()
						? closing.outputs().get(closing.outputs().size() - 1)
						: null;
		// A review item frame is read whether or not the pass published anything: its INFO, and the answer mark
		// of task RD4-13, do not depend on an output.
		if (null != frame.questionsRelease()) {
			Optional<DocumentRef> asked = sharedReleaseService.getReleaseData(frame.questionsRelease())
					.map(ReleaseData::getDocument);
			if (asked.isEmpty()) {
				// Routing must never lose a hop: the frame is popped and the asker re-queued anyway.
				log.error("Question frame release {} on task {} could not be read; popped without closing",
						frame.questionsRelease(), td.getUuid());
			} else if (RearmSpecificationType.BOARD_QUESTIONS == asked.get().specification()) {
				// D11: an answer closes a question, so the board closes the asked ids on the
				// answering document's behalf.
				if (null != answeringRelease) effects.closeQuestions(td.getUuid(), frame.questionsRelease(), answeringRelease);
			} else {
				// A review item frame (task bc7fc25a): a fix claim is not a verification. No round is
				// cut; the reviewer's next round closes or re-raises its ids.
				BoardReviewItemIndex idx = asked.get().reviewItems();
				List<String> open = null == idx ? List.of()
						: idx.openReviewItems().stream().map(BoardReviewItem::id).filter(Objects::nonNull).toList();
				String reviewer = roles.stream().filter(rc -> rc.getUuid().equals(frame.askingRole()))
						.map(AgentTaskRoleConfigData::getName).findFirst().orElse("the asking role");
				String ids = String.join(", ", open);
				RearmSpecificationType about = null != idx && null != idx.about() ? idx.about().specification() : null;
				AgentTaskRoleConfigData answering = roles.stream()
						.filter(rc -> rc.getUuid().equals(closing.roleUuid())).findFirst().orElse(null);
				if (null != about && null != answering && produces(answering, about)) {
					// The hop answered a review item about its own document (task RD4-13). It answered the
					// work rather than made it, which a later rejection about nothing needs to know.
					markAnswered(td, closing, frame.questionsRelease());
					if (closing.saysNoChange()) {
						effects.info("Task " + label(td) + ": " + closing.role() + " passed, nothing to build; back to "
								+ reviewer + (ids.isEmpty() ? "" : " to verify " + ids));
						return backToAsker(td, board, roles, frame,
								closing.role() + " passed, nothing to build; back to " + reviewer, effects);
					}
					DocumentRef round = newRoundOf(closing, about);
					AgentTaskRoleConfigData builder = null == round ? null : builderAfter(roles, answering, about);
					if (null != builder && !builder.getUuid().equals(frame.askingRole())) {
						return toBuilder(td, board, closing, builder, reviewer, ids, round, effects);
					}
				}
				effects.info(closing.role() + " passed on task " + label(td) + "; back to " + reviewer
						+ " to verify" + (ids.isEmpty() ? "" : " " + ids));
			}
		}
		return backToAsker(td, board, roles, frame, null, effects);
	}

	/**
	 * Queue the task for the role that asked, the frame already popped.
	 *
	 * @param note the status note, or null for "{role} passed; back to {asker}"
	 */
	private AgentTaskData backToAsker(AgentTaskData td, AgentBoardData board, List<AgentTaskRoleConfigData> roles,
			QuestionFrame frame, String note, BoardEffects effects) {
		SignOff closing = td.getSignOffs().isEmpty() ? null : td.getSignOffs().get(td.getSignOffs().size() - 1);
		Optional<AgentTaskRoleConfigData> asker = roles.stream()
				.filter(rc -> rc.getUuid().equals(frame.askingRole())).findFirst();
		if (asker.isEmpty()) {
			toCoordinator(td, "the role that asked is no longer active on this board", effects);
			return td;
		}
		String budget = budgetStop(td, board, asker.get(), "returning to " + asker.get().getName());
		if (null != budget) return holdStopped(td, budget, List.of(), effects);
		return queueFor(td, board, asker.get(), StatusTrigger.AUTHORIZE, null, effects, null != note ? note
				: (null == closing ? "" : closing.role() + " passed; ") + "back to " + asker.get().getName());
	}

	// ---------- 2.2.4 an answer that changes what is built (task RD4-13) ----------

	/**
	 * Record on the closing sign-off that it answered the review item round {@code reviewItems}. The closing
	 * hop is the task's last sign-off on every path that routes on it; replaying the same hop (a hold
	 * release) finds it marked already and changes nothing.
	 */
	private void markAnswered(AgentTaskData td, SignOff closing, UUID reviewItems) {
		if (null == reviewItems) return;
		List<SignOff> hops = td.getSignOffs();
		int at = hops.lastIndexOf(closing);
		if (at < 0 || reviewItems.equals(hops.get(at).answered())) return;
		hops.set(at, hops.get(at).withAnswered(reviewItems));
	}

	/** The round of {@code spec} among the hop's outputs, if it published one. */
	private DocumentRef newRoundOf(SignOff closing, RearmSpecificationType spec) {
		DocumentRef found = null;
		for (UUID r : closing.outputs()) {
			DocumentRef doc = sharedReleaseService.getReleaseData(r).map(ReleaseData::getDocument).orElse(null);
			if (null != doc && doc.specification() == spec) found = doc;
		}
		return found;
	}

	/**
	 * The role that builds from a new round of {@code spec}: the first active role after the answering
	 * one in the board's order whose required inputs include {@code spec}. A role that declares no
	 * required inputs is not skipped, so a board that declares none falls back to the next role in
	 * order. Null when no role after it qualifies; the caller then returns the task to the filer, as it
	 * does when the first to qualify is the filer itself.
	 */
	private AgentTaskRoleConfigData builderAfter(List<AgentTaskRoleConfigData> roles,
			AgentTaskRoleConfigData answering, RearmSpecificationType spec) {
		for (AgentTaskRoleConfigData rc : roles) {
			if (rc.getOrderIndex() <= answering.getOrderIndex() || rc.getUuid().equals(answering.getUuid())) continue;
			List<RequiredInput> ins = rc.getRequiredInputs();
			if (null == ins || ins.isEmpty() || ins.stream().anyMatch(ri -> ri.specification() == spec)) return rc;
		}
		return null;
	}

	/**
	 * A pass that answered a review item about the signing role's own document with a new round, and did
	 * not say the round changes nothing: the role that builds from the round goes before the filer
	 * re-checks, or the filer finds the round unbuilt and rejects to it anyway (seen on RD4-4). The
	 * review items stay OPEN: they are the filer's, and the forward path brings the task back to the filer
	 * once the builder has passed, since the filer's rejection is not a pass. The new round is pinned on
	 * the builder beside the review item round the task already carries, which names why it came.
	 */
	private AgentTaskData toBuilder(AgentTaskData td, AgentBoardData board, SignOff closing,
			AgentTaskRoleConfigData builder, String filer, String ids, DocumentRef round, BoardEffects effects) {
		String budget = budgetStop(td, board, builder, "the next " + builder.getName() + " round");
		if (null != budget) return holdStopped(td, budget, List.of(), effects);
		effects.info("Task " + label(td) + ": " + closing.role() + " passed with a new round; to " + builder.getName()
				+ (ids.isEmpty() ? "" : "; " + ids + " stay" + (ids.contains(",") ? "" : "s") + " open until the "
						+ filer + " re-checks"));
		return queueFor(td, board, builder, StatusTrigger.AUTHORIZE, round, effects,
				closing.role() + " passed with a new round; to " + builder.getName());
	}

	/**
	 * Back to whoever asked, with the answer round pinned.
	 *
	 * <p>Shares the stops and the budget check with {@link #popToAsker}: a human answering does not
	 * buy the task another round past a cap or a budget, it only supplies the answer that was
	 * missing.
	 */
	private AgentTaskData toAskerAfterAnswer(AgentTaskData td, AgentBoardData board,
			QuestionFrame frame, UUID answerRelease, BoardEffects effects) {
		Optional<AgentTaskRoleConfigData> asker = activeRoles(board).stream()
				.filter(rc -> rc.getUuid().equals(frame.askingRole())).findFirst();
		if (asker.isEmpty()) {
			toCoordinator(td, "the question was answered but the role that asked is no longer"
					+ " active on this board", effects);
			return td;
		}
		String budget = budgetStop(td, board, asker.get(), "returning to " + asker.get().getName());
		if (null != budget) return holdStopped(td, budget, List.of(), effects);
		effects.info("Question answered on task " + label(td) + "; back to "
				+ asker.get().getName());
		DocumentRef pin = new DocumentRef(RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null,
				null, td.getUuid(), null, null, null);
		return queueFor(td, board, asker.get(), StatusTrigger.AUTHORIZE, pin, effects,
				"question answered; back to " + asker.get().getName());
	}

	// ---------- stops ----------

	/** A stop, and what it was about in words an operator can act on without opening the task. */
	private record Stop(StopReason reason, String detail) {}

	/**
	 * Whether the loop between the asking role and the one that would answer has to stop.
	 *
	 * <p>The detail names the rule that tripped, with its count and the board's setting, so the
	 * hold says what would have avoided it (gaps §1.28): a no-progress stop on a narrowed review item
	 * carried under its id reads as a stuck task unless the reason says the id did not move.
	 */
	private Stop stopFor(AgentTaskData td, AgentBoardData board, SignOff closing,
			AgentTaskRoleConfigData answering, DocumentRef questioned, List<BoardReviewItem> blocking) {
		UUID askingRole = closing.roleUuid();
		String key = AgentTaskData.cycleKey(askingRole, answering.getUuid());
		int rounds = td.getCycles().getOrDefault(key, 0);
		int cap = board.effectiveCycleCap();
		if (rounds + 1 > cap) {
			return new Stop(StopReason.CYCLE_CAP, closing.role() + " ↔ " + answering.getName()
					+ " went " + rounds + " round(s) (cap " + cap + ")");
		}
		int repeats = repeatsWithoutProgress(td, board, askingRole, questioned, blocking);
		int limit = board.effectiveNoProgressRepeats();
		if (repeats >= limit) {
			return new Stop(StopReason.NO_PROGRESS, ids(blocking) + " stayed OPEN after a "
					+ answering.getName() + " round (repeat " + repeats + " of " + limit + ");"
					+ " a partly fixed item is RESOLVED and re-raised under a new id");
		}
		return null;
	}

	/**
	 * Consecutive rounds that asked for exactly the same things.
	 *
	 * <p>Ids only. A new id is movement even if the count is unchanged, and an id that was closed
	 * and reopened is not progress even though the round in between looked different. Comparing
	 * counts, or comparing the whole item, would get both of those backwards.
	 */
	private int repeatsWithoutProgress(AgentTaskData td, AgentBoardData board, UUID askingRole,
			DocumentRef closing, List<BoardReviewItem> blocking) {
		if (null == closing || null == closing.reviewItems()) return 0;
		Set<String> now = ids(blocking);
		if (now.isEmpty()) return 0;
		int repeats = 0;
		for (BoardReviewItemIndex earlier : earlierRounds(td, closing.specification(), askingRole)) {
			Set<String> then = ids(earlier.blockingReviewItems(board.getBlockingPriority()));
			if (then.isEmpty() || !then.equals(now)) break;
			repeats++;
		}
		return repeats;
	}

	/** Releases this role declared as outputs of its own hops. */
	private Set<UUID> roundsProducedBy(AgentTaskData td, UUID roleUuid) {
		Set<UUID> out = new LinkedHashSet<>();
		for (SignOff so : td.getSignOffs()) {
			if (!roleUuid.equals(so.roleUuid()) || null == so.outputs()) continue;
			out.addAll(so.outputs());
		}
		return out;
	}

	private Set<String> ids(List<BoardReviewItem> items) {
		Set<String> out = new LinkedHashSet<>();
		items.forEach(f -> out.add(f.id()));
		return out;
	}

	/** Indexes of the same type on this task, newest first, excluding the one that just closed. */
	/**
	 * Earlier rounds of this type on this task by the role that is asking, newest first, excluding
	 * the one that just closed.
	 *
	 * <p><b>The asking role's own rounds, and nothing else.</b> The no-progress rule asks one
	 * question -- did this role ask for exactly what it asked for last time -- so it may only
	 * compare that role's rounds against each other.
	 *
	 * <p>Two kinds of round would otherwise reset the count, both of them closing items and so
	 * looking like progress by construction. The board's and a human's: a policy round, an answer
	 * round, the unwind round of D11. And the ANSWERING agent's: a role that answers by publishing
	 * a BOARD_QUESTIONS round of its own closes the ids it was asked about, so the sequence "coder asks
	 * q1, designer resolves q1, coder re-asks q1" compared the coder's re-ask against the
	 * designer's round, found an empty open set, and read a loop going nowhere as movement. Only
	 * the cycle cap bounded it.
	 *
	 * <p>A round belongs to the role whose sign-off declared it as an output; board-cut and
	 * human-cut rounds belong to no sign-off and so are excluded by the same rule.
	 */
	private List<BoardReviewItemIndex> earlierRounds(AgentTaskData td, RearmSpecificationType spec,
			UUID askingRole) {
		List<BoardReviewItemIndex> out = new ArrayList<>();
		if (null == td.getReleases() || null == askingRole) return out;
		Set<UUID> ownRounds = roundsProducedBy(td, askingRole);
		List<ReleaseData> rounds = new ArrayList<>();
		for (UUID r : td.getReleases()) {
			if (!ownRounds.contains(r)) continue;
			sharedReleaseService.getReleaseData(r).ifPresent(rd -> {
				DocumentRef doc = rd.getDocument();
				// One entry per round: a replaced version is its round's earlier copy (task RD4-7).
				if (null != doc && doc.specification() == spec && null != doc.reviewItems() && !doc.superseded()) {
					rounds.add(rd);
				}
			});
		}
		rounds.sort(Comparator.comparing((ReleaseData rd) -> rd.getDocument().round(),
				Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
		// index 0 is the round that just closed
		for (int i = 1; i < rounds.size(); i++) out.add(rounds.get(i).getDocument().reviewItems());
		return out;
	}

	// ---------- completion ----------

	private AgentTaskData complete(AgentTaskData td, AgentBoardData board, SignOff closing,
			BoardEffects effects) {
		// The same rule the coordinator's complete enforces. A split parent whose children are
		// still open is not finished because its own roles passed -- the work is in the children,
		// and auto-completing over them would close an epic whose parts are unbuilt.
		List<UUID> incomplete = agentTaskService.incompleteChildren(td);
		if (!incomplete.isEmpty()) {
			toCoordinator(td, "every required role passed, but child task(s) are still open: "
					+ incomplete, effects);
			return td;
		}
		// A correction a person filed at a gate is something they asked for, not a reviewer's note:
		// completing over it would close it silently (operator-actions D25). They finish it by
		// deciding it, or by their own complete, which applies the completion priority (D5).
		List<String> corrections = openPersonCorrections(td);
		if (!corrections.isEmpty()) {
			toCoordinator(td, "corrections filed by a person are still open: " + corrections, effects);
			return td;
		}
		// Completed only when delivered: a linked PR still open waits in DELIVERING, a closed one
		// goes back to the coordinator (task 9af9d722).
		AgentDeliveryService.Settled s = agentDeliveryService.settle(td, StatusTrigger.COMPLETE,
				AgentActor.system("routing"), null);
		if (s.status() == TaskStatus.COMPLETED) {
			effects.info("Task " + label(td) + " completed: every required role passed"
					+ (null != closing ? ", last hop " + closing.role() : ""));
		}
		if (null != s.info()) effects.info(s.info());
		if (null != s.alert()) effects.alert(s.alert());
		return td;
	}

	/** Open items a person filed or decided, in the latest round of the review and test indexes. */
	private List<String> openPersonCorrections(AgentTaskData td) {
		List<String> ids = new ArrayList<>();
		for (RearmSpecificationType spec : List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
				RearmSpecificationType.BOARD_TEST_REPORT)) {
			agentDocumentService.latestRoundRelease(td, spec).ifPresent(rd -> rd.getDocument().reviewItems()
					.openReviewItems().stream().filter(BoardReviewItem::decidedByPerson).forEach(f -> ids.add(f.id())));
		}
		return ids;
	}

	/**
	 * Park a queued task the budget no longer affords (board-mechanics D25): D15's OPERATOR hold
	 * and a board ALERT, naming whatever blocking items its latest review and test rounds still
	 * hold. Never completed under policy (gaps §1.23): the round it could not afford never ran, so
	 * nothing converged -- §6.2's completion is for loops that did.
	 *
	 * <p>Its own transaction, because the caller is refusing: {@code assign} throws after this,
	 * which rolls back everything it wrote, and the park has to survive that. Re-checked under the
	 * lock, so two pollers do not park a task twice and a task usage has just made affordable is
	 * not parked at all.
	 *
	 * @return the parked task, or null when there was nothing to park: the task is no longer
	 *         QUEUED, or it fits after all
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RelizaException.class)
	public AgentTaskData parkOverBudget(UUID taskUuid, AgentBoardData board, WhoUpdated wu)
			throws RelizaException {
		AgentTaskData td = agentTaskService.lockedTask(taskUuid);
		if (td.getStatus() != TaskStatus.QUEUED) return null;
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), td.getRole()).orElse(null);
		if (null == rc) return null;
		AgentBudgetService.Projection p = agentBudgetService.project(td, board, rc);
		if (p.fits()) return null;
		List<BoardReviewItem> blocking = new ArrayList<>();
		for (RearmSpecificationType spec : List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
				RearmSpecificationType.BOARD_TEST_REPORT)) {
			agentDocumentService.latestRoundRelease(td, spec).ifPresent(rd -> blocking.addAll(
					rd.getDocument().reviewItems().blockingReviewItems(board.getBlockingPriority())));
		}
		BoardEffects effects = new BoardEffects(td.getBoard());
		// Says where the stop happened, so an operator can tell it from a routing-time stop.
		holdStopped(td, StopReason.BUDGET + " at assignment (" + agentBudgetService.refusal(p, rc) + ")",
				blocking, effects);
		AgentTaskData saved = agentTaskService.saveData(td, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * A review loop that stopped without converging: the cycle cap or no progress (§6.2). Never a
	 * budget stop, which holds on every path (gaps §1.23): the round it could not afford never ran.
	 *
	 * <p>Either the remaining items are below what the board says must be resolved and every REQUIRED
	 * role has a current pass -- in which case the task completes and the items are recorded as
	 * accepted by policy, not by a person -- or a human has to look. Nothing is silently dropped
	 * either way.
	 *
	 * <p>The asking role is left out of the required-role check: its rejection is what the policy is
	 * accepting, so it has no pass by construction. The check is for roles that never ran -- a cap
	 * between the architect and a reviewer must not complete a task whose coder never started.
	 */
	private AgentTaskData exhausted(AgentTaskData td, AgentBoardData board, StopReason stop,
			String detail, List<BoardReviewItem> blocking, UUID askingRole, BoardEffects effects) {
		List<BoardReviewItem> mustResolve = blocking.stream()
				.filter(f -> blocksCompletion(f, board))
				.toList();
		List<String> notPassed = activeRoles(board).stream()
				.filter(rc -> RoleNecessity.REQUIRED == rc.getNecessity())
				.filter(rc -> !rc.getUuid().equals(askingRole))
				.filter(rc -> !hasCurrentPass(td, board, rc))
				.map(AgentTaskRoleConfigData::getName)
				.toList();
		if (!notPassed.isEmpty()) {
			// The stop's own detail (#606) goes with it: a no-progress stop that also left a required
			// role unrun still says which ids did not move, not only which role never ran.
			return holdStopped(td, board, AgentTaskData.HoldStop.valueOf(stop.name()),
					stop + " with required role(s) not passed: " + notPassed, detail, mustResolve, effects);
		}
		if (mustResolve.isEmpty()) {
			// What is left open is recorded as accepted by policy, in a round the board authors.
			// Collected as an effect: publishing takes the board lock first, and this runs under
			// the task lock, so cutting it here would invert the order the publish path depends on.
			effects.policyRound(td.getUuid(), "policy: stopped by " + stop);
			AgentDeliveryService.Settled s = agentDeliveryService.settle(td, StatusTrigger.POLICY_COMPLETE,
					AgentActor.system("routing"), null);
			if (null != s.info()) effects.info(s.info());
			if (null != s.alert()) effects.alert(s.alert());
			effects.alert("Task " + label(td) + (s.status() == TaskStatus.COMPLETED ? " completed" : " passed")
					+ " under policy (" + stop + "): "
					+ blocking.size() + " item(s) left open and accepted by policy");
			return td;
		}
		return holdStopped(td, board, AgentTaskData.HoldStop.valueOf(stop.name()), stop.toString(), detail,
				mustResolve, effects);
	}

	/** A budget stop: always the operator's (D15), whatever the board says about loop stops. */
	private AgentTaskData holdStopped(AgentTaskData td, String stop, List<BoardReviewItem> open, BoardEffects effects) {
		return holdStopped(td, null, AgentTaskData.HoldStop.BUDGET, stop, null, open, effects);
	}

	/**
	 * The human half of a stop: a hold naming the stop and any open items, with the detail right
	 * after the stop's label so a reader sees the rule before the list:
	 * {@code stopped by no progress: [T-1] stayed OPEN after a coder round (repeat 1 of 1); …}.
	 *
	 * <p>Which level (task c0a2134c): on a board that lets the coordinator release a stop (the
	 * default), the first no-progress or cycle-cap stop of its kind on a task parks at COORDINATOR
	 * level with an INFO -- the coordinator reads the feed and nobody needs paging. Once a stop of
	 * that kind has been released, by anyone, the next is the operator's, with an ALERT as before.
	 * Budget stops are always the operator's.
	 */
	/** What a loop stop's hold says while the coordinator may release it; an escalation rewrites it (RD2-21). */
	static final String COORDINATOR_MAY_LIFT = "; the coordinator may lift it once";

	private AgentTaskData holdStopped(AgentTaskData td, AgentBoardData board, AgentTaskData.HoldStop kind,
			String stop, String detail, List<BoardReviewItem> open, BoardEffects effects) {
		String what = null == detail ? stop : stop + ": " + detail;
		String items = open.isEmpty() ? ""
				: (null == detail ? " with " : " — ") + open.size() + " item(s) still open: " + ids(open);
		boolean loop = AgentTaskData.HoldStop.NO_PROGRESS == kind || AgentTaskData.HoldStop.CYCLE_CAP == kind;
		boolean granted = loop && null != board && board.getEffectiveCoordinatorStopLift();
		boolean liftedOnce = granted && td.stopLiftsOf(kind) > 0;
		boolean coordinators = granted && !liftedOnce;
		String why = coordinators ? COORDINATOR_MAY_LIFT : liftedOnce ? "; lifted once already" : "";
		td.setHold(new AgentTaskData.TaskHold(coordinators ? AgentTaskData.HoldLevel.COORDINATOR
				: AgentTaskData.HoldLevel.OPERATOR, AgentTaskData.HoldKind.MANUAL, null,
				"stopped by " + what + items + why, AgentActor.system("routing"), ZonedDateTime.now(), kind));
		td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, AgentActor.system("routing"));
		td.setAssignment(null);
		String tail = (open.isEmpty() ? "" : ": " + ids(open)) + (null == detail ? "" : " (" + detail + ")");
		if (coordinators) {
			effects.info("Task " + label(td) + " stopped by " + stop + "; the coordinator may lift it once"
					+ " or escalate it" + tail);
		} else {
			effects.alert("Task " + label(td) + " stopped by " + stop + " and needs a human" + tail
					+ (liftedOnce ? "; lifted once already" : ""));
		}
		return td;
	}


	/**
	 * Null when the next round of {@code rc} fits the budget; otherwise the stop's reason, with the
	 * projection's numbers so the hold says what did not fit.
	 */
	private String budgetStop(AgentTaskData td, AgentBoardData board, AgentTaskRoleConfigData rc, String what) {
		if (agentBudgetService.nextRoundFits(td, board, rc)) return null;
		AgentBudgetService.Projection p = agentBudgetService.project(td, board, rc);
		return StopReason.BUDGET + ": " + what + " does not fit (" + agentBudgetService.refusal(p, rc) + ")";
	}

	private boolean blocksCompletion(BoardReviewItem f, AgentBoardData board) {
		Integer limit = board.getCompletionPriority();
		if (null == limit) return true;
		return null == f.priority() || f.priority() <= limit;
	}

	// ---------- helpers ----------

	private List<AgentTaskRoleConfigData> activeRoles(AgentBoardData board) {
		return agentBoardService.listRoleConfigs(board.getUuid()).stream()
				.filter(AgentTaskRoleConfigData::isActive)
				.sorted(Comparator.comparingInt(AgentTaskRoleConfigData::getOrderIndex))
				.toList();
	}

	private boolean produces(AgentTaskRoleConfigData rc, RearmSpecificationType spec) {
		return null != rc.getProducesOutputs() && rc.getProducesOutputs().stream()
				.anyMatch(o -> o.specification() == spec);
	}

	/**
	 * Whether a role's pass still stands.
	 *
	 * <p>A pass is about the versions the hop was bound to. When one of them has moved since --
	 * typically because an answer republished the design that was reviewed -- the pass describes a
	 * document nobody reviewed, so the role runs again.
	 *
	 * <p>A rejection a person has decided over counts as a pass (D23; gaps §1.8): the person's
	 * decision round reads PASSED, and re-queueing the role would pay for a hop to repeat what the
	 * person already settled. Staleness applies to it as to any pass.
	 */
	boolean hasCurrentPass(AgentTaskData td, AgentBoardData board, AgentTaskRoleConfigData rc) {
		SignOff last = td.lastSignOffForRole(rc.getUuid(), rc.getName());
		if (null == last) return false;
		// Reopened to this role since: it was sent back to redo its part and must pass again.
		if (td.reopenedSince(last)) return false;
		if (SignOffOutcome.PASSED != last.outcome()
				&& !agentDocumentService.rejectionDecidedOver(td, board.getBlockingPriority(), last)) {
			return false;
		}
		return !inputsMovedSince(td, rc, last);
	}

	/**
	 * Whether anything this role reads has a newer release than the pass that read it.
	 *
	 * <p>Compared by TIME against the task's own releases rather than through
	 * {@code regressedBindings}: that reads the current assignment's bindings, and by the time
	 * routing runs the assignment has been cleared, so it answers empty for every task and the
	 * staleness check silently never fires. Matching its message text by role name was worse
	 * still -- a role called "qa" matches any message containing those three letters.
	 *
	 * <p>The case this exists for: a reviewer passes, a later hop's question makes the architect
	 * republish the design, and the pass now describes a document nobody reviewed. Under strict
	 * routing that has to be reviewed again.
	 *
	 * <p><b>Only an agent's publication at the input's floor counts</b> (operator-actions D18). A
	 * draft left behind by a hop that returned is not a version anyone handed over, and would
	 * re-queue a reviewer whose pass still stands; the same version counts once a sign-off
	 * promotes it. A round the board cut -- a person's decisions, an answer, a policy or unwind
	 * round -- names no session and never counts: it needs no rework, or reaches the producer
	 * through routing, whose next publication is what stales the passes that read it.
	 */
	private boolean inputsMovedSince(AgentTaskData td, AgentTaskRoleConfigData rc, SignOff pass) {
		if (null == pass.signedOffAt() || null == rc.getRequiredInputs()
				|| rc.getRequiredInputs().isEmpty() || null == td.getReleases()) {
			return false;
		}
		// The floor the input binding applies, per type: the highest where a role reads a type
		// twice. Never below DRAFT, so a reservation mid-cut or a cancelled release is not a version.
		Map<RearmSpecificationType, Integer> floors = new LinkedHashMap<>();
		rc.getRequiredInputs().forEach(ri -> {
			if (null != ri.specification()) {
				floors.merge(ri.specification(), Math.max(AgentTaskInputService.maturity(ri.minLifecycle()),
						AgentTaskInputService.maturity(ReleaseLifecycle.DRAFT)), Math::max);
			}
		});
		if (floors.isEmpty()) return false;
		for (UUID r : td.getReleases()) {
			Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(r);
			if (rd.isEmpty()) continue;
			DocumentRef doc = rd.get().getDocument();
			if (null == doc || null == doc.session() || !floors.containsKey(doc.specification())) continue;
			if (AgentTaskInputService.maturity(rd.get().getLifecycle()) < floors.get(doc.specification())) {
				continue;
			}
			ZonedDateTime created = rd.get().getCreatedDate();
			if (null != created && created.isAfter(pass.signedOffAt())) return true;
		}
		return false;
	}

	private List<String> requiredWithoutCurrentPass(AgentTaskData td, AgentBoardData board,
			List<AgentTaskRoleConfigData> roles) {
		return roles.stream()
				.filter(rc -> RoleNecessity.REQUIRED == rc.getNecessity())
				.filter(rc -> !hasCurrentPass(td, board, rc))
				.map(AgentTaskRoleConfigData::getName)
				.toList();
	}

	private static final Set<RearmSpecificationType> INDEX_KINDS = Set.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
			RearmSpecificationType.BOARD_TEST_REPORT, RearmSpecificationType.BOARD_QUESTIONS);

	/** Review and test rounds default to the work; BOARD_QUESTIONS always name an input. */
	private static boolean isReviewIndex(RearmSpecificationType spec) {
		return RearmSpecificationType.BOARD_REVIEW_ITEMS == spec || RearmSpecificationType.BOARD_TEST_REPORT == spec;
	}

	/**
	 * Whether a role reviews rather than makes: everything it declares it produces is an index
	 * (review items, a test report, questions), or it declares nothing and reads an index. A role that
	 * produces both a document and an index made something, so it counts as a maker; so does a role
	 * that declares neither outputs nor an index input, which boards in practice do not leave.
	 */
	static boolean isReviewer(AgentTaskRoleConfigData rc) {
		// A report is what the role makes when asked for one, not what it does on a work task (task RD4-12): a tester
		// that also investigates still reviews.
		List<ProducedOutput> outs = null == rc.getProducesOutputs() ? null : rc.getProducesOutputs().stream()
				.filter(o -> RearmSpecificationType.BOARD_INVESTIGATION_REPORT != o.specification()).toList();
		if (null != outs && !outs.isEmpty()) {
			return outs.stream().allMatch(o -> INDEX_KINDS.contains(o.specification()));
		}
		List<RequiredInput> ins = rc.getRequiredInputs();
		return null != ins && ins.stream().anyMatch(ri -> INDEX_KINDS.contains(ri.specification()));
	}

	/**
	 * Whether a role is there only to be asked for reports (task RD4-12): everything it produces is an
	 * BOARD_INVESTIGATION_REPORT. The work pipeline never routes a work task to it.
	 */
	static boolean investigatesOnly(AgentTaskRoleConfigData rc) {
		List<ProducedOutput> outs = rc.getProducesOutputs();
		return null != outs && !outs.isEmpty()
				&& outs.stream().allMatch(o -> RearmSpecificationType.BOARD_INVESTIGATION_REPORT == o.specification());
	}

	/** The role that made the work, or why there is none to send items back to. */
	private record Maker(AgentTaskRoleConfigData role, String whyNone) {}

	/**
	 * The newest earlier hop on the task by a role that is not a reviewer, skipping the closing
	 * hop. The sign-off history says who actually produced what the reviewer looked at; the roles'
	 * order is advisory and a board may have two makers. A human gate's verdict is recorded under
	 * the gated role, so it counts as that role. A maker since deactivated is not replaced by an
	 * older one -- that would send the items to a role that did not make this work.
	 */
	private Maker makerOf(AgentTaskData td, AgentBoardData board, SignOff closing) {
		List<AgentTaskRoleConfigData> all = agentBoardService.listRoleConfigs(board.getUuid());
		// An investigation's work is its report, made by the investigating role (task RD4-12): a review's items go
		// back to it, whatever else the role does on work tasks.
		if (td.isInvestigation()) {
			AgentTaskData.Investigation inv = td.getInvestigation();
			AgentTaskRoleConfigData made = all.stream().filter(rc -> inv.investigatedBy(rc.getUuid(), rc.getName()))
					.findFirst().orElse(null);
			if (null == made || !made.isActive()) {
				return new Maker(null, "the investigating role, " + inv.role() + ", is no longer active; authorise a role");
			}
			return new Maker(made, null);
		}
		List<SignOff> hops = td.getSignOffs();
		for (int i = hops.size() - 1; i >= 0; i--) {
			SignOff so = hops.get(i);
			if (null != closing && so.equals(closing)) continue;
			Optional<AgentTaskRoleConfigData> rc = all.stream()
					.filter(r -> null != so.roleUuid() ? so.roleUuid().equals(r.getUuid()) : r.getName().equals(so.role()))
					.findFirst();
			// A hop that answered a review item about its own document answered the work; it did not make
			// it (task RD4-13), so items about the work go past it to the role that did.
			if (rc.isEmpty() || isReviewer(rc.get()) || null != so.answered()) continue;
			if (!rc.get().isActive()) {
				return new Maker(null, "the role that made it, " + rc.get().getName()
						+ ", is no longer active; name what the items are about, or authorise a role");
			}
			return new Maker(rc.get(), null);
		}
		return new Maker(null, "there is no earlier hop on this task to send the items back to; name what"
				+ " they are about, or authorise a role");
	}

	/** The items in this hop's outputs that the board says must be dealt with. */
	private List<BoardReviewItem> blockingItems(AgentTaskData td, AgentBoardData board, SignOff closing) {
		DocumentRef doc = latestRoundOfOutput(td, board, closing);
		if (null == doc || null == doc.reviewItems()) return List.of();
		return doc.reviewItems().blockingReviewItems(board.getBlockingPriority());
	}

	/**
	 * The newest round of the index this hop published: the hop's own round, unless a later round
	 * of the same index -- a person's decisions, a board-cut policy round -- has superseded it.
	 *
	 * <p>Routing replays from a stored sign-off on a hold lift. Reading the hop's own copy there
	 * made decisions a person took while the task was on hold invisible, and the task went back
	 * upstream on items already dismissed (gaps §1.8, shape 4).
	 */
	private DocumentRef latestRoundOfOutput(AgentTaskData td, AgentBoardData board, SignOff closing) {
		DocumentRef own = outputWithItems(td, board, closing);
		if (null == own) return null;
		return agentDocumentService.latestRoundRelease(td, own.specification())
				.map(ReleaseData::getDocument).orElse(own);
	}

	/**
	 * The output of this hop that carries an index, if any: a BOARD_QUESTIONS round that asks (an item
	 * blocking at the board's priority) first, else the last index-bearing output. A reviewer who rejects and asks at once routes on the question --
	 * nothing can be fixed until it is answered -- whatever order the outputs were listed in
	 * (gaps §1.17).
	 */
	private DocumentRef outputWithItems(AgentTaskData td, AgentBoardData board, SignOff closing) {
		if (null == closing || null == closing.outputs()) return null;
		DocumentRef found = null;
		for (UUID r : closing.outputs()) {
			Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(r);
			if (rd.isEmpty()) continue;
			if (AgentTaskService.asksQuestion(rd.get(), board.getBlockingPriority())) return rd.get().getDocument();
			DocumentRef doc = rd.get().getDocument();
			if (null != doc && null != doc.reviewItems()) found = doc;
		}
		return found;
	}

	/**
	 * Whether this hop answered what the top frame is waiting on.
	 *
	 * <p>The answering role passing is the signal, not the ids: an answer is usually a new round
	 * of a PROSE document -- a design, an interface -- which carries no index and therefore cannot
	 * close an id. Requiring one would mean no design change could ever answer a question about
	 * the design.
	 *
	 * <p>PASSED specifically. A rejection from the answering role is that role saying it could not
	 * do the work, which is not an answer; popping on it would send the asker back to a question
	 * still open, and the no-progress rule would then park a task whose upstream never spoke.
	 */
	private boolean answers(AgentTaskData td, SignOff closing) {
		QuestionFrame frame = td.getQuestionStack().get(td.getQuestionStack().size() - 1);
		return null != closing && SignOffOutcome.PASSED == closing.outcome()
				&& null != closing.roleUuid() && closing.roleUuid().equals(frame.answeringRole());
	}

	/**
	 * Record who is waiting on whom -- once per outstanding question.
	 *
	 * <p>Idempotent on (asking role, questions release), because routing is replayed on paths that
	 * did not produce a new hop: releasing a hold re-routes from the LAST sign-off, and when that
	 * sign-off is the rejection that asked the question, the naive push stacked a second frame for
	 * a question already on the stack. One of the two then kept {@code answeringRole = null}
	 * forever, the unwind matched the wrong one, and the asker never got its answer back.
	 */
	private void pushFrame(AgentTaskData td, UUID askingRole, SignOff closing,
			DocumentRef questioned, UUID answeringRole) {
		UUID release = null;
		if (null != closing && null != closing.outputs() && !closing.outputs().isEmpty()) {
			release = closing.outputs().get(closing.outputs().size() - 1);
		}
		if (!td.getQuestionStack().isEmpty()) {
			QuestionFrame top = td.getQuestionStack().get(td.getQuestionStack().size() - 1);
			if (java.util.Objects.equals(top.askingRole(), askingRole)
					&& java.util.Objects.equals(top.questionsRelease(), release)) {
				// Already asked. Keep the frame that is there, including whoever has since been
				// named to answer it.
				return;
			}
		}
		td.getQuestionStack().add(new QuestionFrame(askingRole, closing.session(), closing.agent(),
				release, answeringRole, ZonedDateTime.now()));
	}

	private void countCycle(AgentTaskData td, UUID askingRole, UUID answeringRole) {
		String key = AgentTaskData.cycleKey(askingRole, answeringRole);
		Map<String, Integer> cycles = null != td.getCycles() ? td.getCycles() : new LinkedHashMap<>();
		cycles.merge(key, 1, Integer::sum);
		td.setCycles(cycles);
	}

	/**
	 * Queue a task for a role, pinning the document the next hop has to read.
	 *
	 * <p>The pin matters on the upstream path: an architect asked about a design rarely declares
	 * BOARD_QUESTIONS as an input of its own, so without this the questions round is not among its
	 * bindings and the hop is handed a task with no statement of what it is being asked. Added to
	 * the task's own requirements rather than to the role's, because it is true of this task and
	 * not of the role.
	 */
	/** Board-level spend signals: one alert at the soft line, a lock when the board is over. */
	private void noteBoardSpend(AgentTaskData td, AgentBoardData board, BoardEffects effects) {
		if (null == board.getBudgetMicros()) return;
		long spent = agentBudgetService.spentOnBoard(board);
		if (spent > board.getBudgetMicros()) {
			effects.pauseForBudget("budget: the board has spent " + spent + " of "
					+ board.getBudgetMicros() + " micros");
			return;
		}
		if (AgentBudgetService.softAlertDue(board, spent)) {
			effects.softAlertCrossed(spent);
			effects.alert(AgentBudgetService.softAlertMessage(board, spent));
		}
	}

	/**
	 * @param note why routing sent it there, with the role (RD2-23): the status history's row reads
	 *        "queued → queued · routing: review items decided; back to designer" rather than a bare trigger
	 */
	private AgentTaskData queueFor(AgentTaskData td, AgentBoardData board,
			AgentTaskRoleConfigData rc, StatusTrigger trigger, DocumentRef pinned,
			BoardEffects effects, String note) {
		noteBoardSpend(td, board, effects);
		if (null != pinned) {
			List<RequiredInput> required = new ArrayList<>(
					null != td.getRequiredInputs() ? td.getRequiredInputs() : List.of());
			RequiredInput pin = new RequiredInput(InputKind.DOCUMENT, pinned.specification(),
					InputScope.TASK, null, null, InputResolution.STRICT_LATEST);
			if (required.stream().noneMatch(ri -> ri.mergeKey().equals(pin.mergeKey()))) {
				required.add(pin);
				td.setRequiredInputs(required);
			}
		}
		td.setRole(rc.getName());
		td.setRoleUuid(rc.getUuid());
		td.setAssignment(null);
		td.transitionStatus(TaskStatus.QUEUED, trigger, AgentActor.system("routing"), note);
		return td;
	}

	private void toCoordinator(AgentTaskData td, String why, BoardEffects effects) {
		td.setAssignment(null);
		// Only when it is not already there. A human rejection at a gate records HUMAN_REJECT and
		// lands on AWAITING_COORDINATOR; routing then agrees there is nothing to route, and a
		// second row for the same state would say RETURN over the top of the verdict that
		// actually happened -- losing why it went there, and splitting one wait into two in every
		// cycle-time view.
		if (TaskStatus.AWAITING_COORDINATOR != td.getStatus()) {
			td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.RETURN,
					AgentActor.system("routing"), why);
		}
		effects.alert("Task " + label(td) + " needs the coordinator: " + why);
	}

	private String label(AgentTaskData td) {
		return td.label();
	}
}
