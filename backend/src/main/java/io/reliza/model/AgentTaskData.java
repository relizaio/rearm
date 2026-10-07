/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * One unit of tracker-originated work, routed hub-and-spoke through
 * its board's coordinator: every role hop is authorized by the
 * coordinator, every sign-off and return redirects back to it. The
 * tracker stays the human interface; this row is ReARM's coordination
 * and audit record. Full design:
 * backend/ai-plans/agentic/task-boards.md.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTaskData extends RelizaDataParent implements RelizaObject {

	public enum TaskStatus {
		/** Registered from the tracker; awaiting coordinator authorization. */
		PENDING_INTAKE,
		/** Authorized for a role with an order; claimable by workers. */
		QUEUED,
		/** Bound to one open session working the queued role. */
		ASSIGNED,
		/** A sign-off or return landed; the coordinator decides the next hop. */
		AWAITING_COORDINATOR,
		/**
		 * Every required role passed (or a person or the policy completed it), and a linked PR is
		 * not merged yet. Not terminal: dependents wait for COMPLETED, which is merged code. Left on
		 * the PR's merge (COMPLETED) or close (back to the coordinator).
		 */
		DELIVERING,
		/**
		 * Coordinator put the task on hold pending human input (typical
		 * trigger: a NEEDS_HUMAN return). Excluded from polls; no
		 * effect on other tasks. The conversation lives on the task's
		 * own tracker issue; release returns it to AWAITING_COORDINATOR.
		 */
		ON_HOLD,
		/** Coordinator completed the task. Terminal. */
		COMPLETED,
		/** Coordinator or operator cancelled. Terminal. */
		CANCELLED
	}

	public enum SignOffOutcome {
		/** Work of this role hop done as asked. */
		PASSED,
		/** The previous role's work is wrong; coordinator decides the bounce. */
		REJECTED
	}

	/** Why an assigned agent handed the task back to the coordinator. */
	public enum TaskReturnReason {
		TASK_UNCLEAR,
		ROLE_MISMATCH,
		MISSING_CAPABILITY,
		BLOCKED_ON_DEPENDENCY,
		NEEDS_HUMAN,
		/** System-generated: the holding session closed (agent close, idle auto-close, or operator force-close). */
		SESSION_CLOSED,
		OTHER
	}

	/**
	 * The single live assignment. Bound to session liveness — no
	 * lease: at most one open session per task, no reassignment while
	 * that session is open, released on any session close path.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record TaskAssignment(UUID session, UUID agent, String role, UUID roleUuid,
			ZonedDateTime assignedAt, String promptVersion,
			List<ResolvedInput> resolvedInputs,
			@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> prHeads,
			@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, BaseHead> baseHeads,
			@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> linkedHeads) implements Serializable {

		public TaskAssignment(UUID session, UUID agent, String role, UUID roleUuid,
				ZonedDateTime assignedAt, String promptVersion) {
			this(session, agent, role, roleUuid, assignedAt, promptVersion, List.of());
		}

		/**
		 * Without the heads (task RD4-2): an assignment recorded before them, whose sign-off the head check
		 * skips, since it has nothing to compare with.
		 */
		public TaskAssignment(UUID session, UUID agent, String role, UUID roleUuid,
				ZonedDateTime assignedAt, String promptVersion, List<ResolvedInput> resolvedInputs) {
			this(session, agent, role, roleUuid, assignedAt, promptVersion, resolvedInputs, null, null, null);
		}

		/**
		 * The same assignment with one more input bound (task RD4-12): an investigation's report returned to the
		 * hop that commissioned it while that hop still runs.
		 */
		public TaskAssignment withInput(ResolvedInput input) {
			List<ResolvedInput> inputs = new ArrayList<>(null == resolvedInputs ? List.of() : resolvedInputs);
			inputs.add(input);
			return new TaskAssignment(session, agent, role, roleUuid, assignedAt, promptVersion, inputs, prHeads,
					baseHeads, linkedHeads);
		}

		/** The same assignment with the heads the hop starts from (task RD4-2). */
		public TaskAssignment withHeads(Map<String, String> prs, Map<String, BaseHead> bases) {
			return new TaskAssignment(session, agent, role, roleUuid, assignedAt, promptVersion, resolvedInputs,
					prs, bases, linkedHeads);
		}

		/**
		 * The same assignment with a PR the hop linked while it was in play (task RD4-2, design round 2): its
		 * head at link time (null while its row has no commit) and the base it starts from. The sign-off head
		 * check counts it as moved once its head differs from this one, or when it was opened during the hop.
		 */
		public TaskAssignment withLinked(String key, String head, BaseHead base) {
			Map<String, String> linked = new HashMap<>(null == linkedHeads ? Map.of() : linkedHeads);
			linked.put(key, head);
			Map<String, BaseHead> bases = new LinkedHashMap<>(null == baseHeads ? Map.of() : baseHeads);
			if (null != base) bases.put(key, base);
			return new TaskAssignment(session, agent, role, roleUuid, assignedAt, promptVersion, resolvedInputs,
					prHeads, bases, linked);
		}
	}

	/**
	 * The newest commit ReARM knew on a linked PR's target branch when a hop started (task RD4-2): the newest
	 * source code entry on that repository and branch, which CI writes when it reports a build of a merge.
	 * What "base moved: N commits since your round" counts from.
	 *
	 * @param vcs the PR's target repository
	 * @param branch the PR's target branch name
	 * @param commit the newest entry's sha; null when the branch had no entry at all
	 * @param at when ReARM recorded that entry; null with {@code commit}
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record BaseHead(UUID vcs, String branch, String commit, ZonedDateTime at) implements Serializable {
		private static final long serialVersionUID = 20260929L;
	}

	/**
	 * Append-only "I did the work" record per role hop, stored in
	 * ReARM. {@code promptVersion} pins the role prompt the agent
	 * assumed. No mandatory artifact in v1 (policy territory in v2).
	 * Human sign-offs (a HUMAN-kind role stage, or a human-gate
	 * verdict) carry null agent/session and the reviewer's identity in
	 * {@code reviewedBy} — non-null reviewedBy IS the human marker.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record SignOff(String role, UUID roleUuid, UUID agent, UUID session,
			ZonedDateTime assignedAt, ZonedDateTime signedOffAt,
			SignOffOutcome outcome, String note, String promptVersion,
			AgentActor reviewedBy, HopUsage usage, List<UUID> outputs, List<UUID> reviewedInputs,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<Promotion> promotions,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean noChange,
			@JsonInclude(JsonInclude.Include.NON_NULL) UUID answered,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean noCode,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, BaseHead> baseHeads)
			implements Serializable {

		/**
		 * Without the head marks (task RD4-2): every sign-off recorded before them, and every hop that neither
		 * said its round changed no code nor started with a linked PR in play.
		 */
		public SignOff(String role, UUID roleUuid, UUID agent, UUID session, ZonedDateTime assignedAt,
				ZonedDateTime signedOffAt, SignOffOutcome outcome, String note, String promptVersion,
				AgentActor reviewedBy, HopUsage usage, List<UUID> outputs, List<UUID> reviewedInputs,
				List<Promotion> promotions, Boolean noChange, UUID answered) {
			this(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note, promptVersion,
					reviewedBy, usage, outputs, reviewedInputs, promotions, noChange, answered, null, null);
		}

		/**
		 * Without the answer marks (task RD4-13): every sign-off recorded before them, and every hop
		 * that neither said its round changes nothing to build nor answered a review item about its own
		 * document.
		 */
		public SignOff(String role, UUID roleUuid, UUID agent, UUID session, ZonedDateTime assignedAt,
				ZonedDateTime signedOffAt, SignOffOutcome outcome, String note, String promptVersion,
				AgentActor reviewedBy, HopUsage usage, List<UUID> outputs, List<UUID> reviewedInputs,
				List<Promotion> promotions) {
			this(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note, promptVersion,
					reviewedBy, usage, outputs, reviewedInputs, promotions, null, null);
		}

		/**
		 * With the reviewed inputs but no promotions: every sign-off recorded before task fda2c9f1,
		 * and every hop that promoted nothing.
		 */
		public SignOff(String role, UUID roleUuid, UUID agent, UUID session, ZonedDateTime assignedAt,
				ZonedDateTime signedOffAt, SignOffOutcome outcome, String note, String promptVersion,
				AgentActor reviewedBy, HopUsage usage, List<UUID> outputs, List<UUID> reviewedInputs) {
			this(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note, promptVersion,
					reviewedBy, usage, outputs, reviewedInputs, null);
		}

		/**
		 * Without the reviewed inputs: every sign-off recorded before task 0192a587's round 2, and
		 * every hop that is not a reviewer's. {@code reviewedInputs} is what a reviewer's hop had
		 * pinned, kept so a gate acceptance can promote what was reviewed after the assignment is gone.
		 */
		public SignOff(String role, UUID roleUuid, UUID agent, UUID session, ZonedDateTime assignedAt,
				ZonedDateTime signedOffAt, SignOffOutcome outcome, String note, String promptVersion,
				AgentActor reviewedBy, HopUsage usage, List<UUID> outputs) {
			this(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note, promptVersion,
					reviewedBy, usage, outputs, null);
		}

		/** Pre-usage constructor, kept so existing call sites and stored rows read unchanged. */
		public SignOff(String role, UUID roleUuid, UUID agent, UUID session, ZonedDateTime assignedAt,
				ZonedDateTime signedOffAt, SignOffOutcome outcome, String note,
				String promptVersion, AgentActor reviewedBy) {
			this(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note, promptVersion,
					reviewedBy, null, List.of());
		}

		/** Pre-outputs constructor, for the same reason. */
		public SignOff(String role, UUID roleUuid, UUID agent, UUID session, ZonedDateTime assignedAt,
				ZonedDateTime signedOffAt, SignOffOutcome outcome, String note,
				String promptVersion, AgentActor reviewedBy, HopUsage usage) {
			this(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note, promptVersion,
					reviewedBy, usage, List.of());
		}

		/** Never null, so callers iterating a hop's outputs need no guard. */
		public SignOff {
			if (null == outputs) outputs = List.of();
		}

		/**
		 * The same sign-off with its usage filled in; nothing else about the hop changes. Before task
		 * fda2c9f1 this dropped reviewedInputs, so a gated reviewer's usage refresh at session close
		 * erased what the acceptance was to promote.
		 */
		public SignOff withUsage(HopUsage filled) {
			return new SignOff(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note,
					promptVersion, reviewedBy, filled, outputs, reviewedInputs, promotions, noChange, answered, noCode,
					baseHeads);
		}

		/**
		 * The same sign-off, marked as the answer to the review item round {@code reviewItems} about its own
		 * document (task RD4-13). Such a hop answered the work; it did not make it, so a later rejection
		 * that names nothing it is about goes past it to the role that did.
		 */
		public SignOff withAnswered(UUID reviewItems) {
			return new SignOff(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note,
					promptVersion, reviewedBy, usage, outputs, reviewedInputs, promotions, noChange, reviewItems, noCode,
					baseHeads);
		}

		/** Whether the hop said its round changes nothing downstream (task RD4-13). */
		public boolean saysNoChange() {
			return Boolean.TRUE.equals(noChange);
		}

		/** The same sign-off with what its review promoted, or a guard kept from promotion (task fda2c9f1). */
		public SignOff withPromotions(List<Promotion> made) {
			return new SignOff(role, roleUuid, agent, session, assignedAt, signedOffAt, outcome, note,
					promptVersion, reviewedBy, usage, outputs, reviewedInputs,
					null == made || made.isEmpty() ? null : List.copyOf(made), noChange, answered, noCode, baseHeads);
		}
	}

	/**
	 * One document a review tried to promote (task fda2c9f1): {@code promotedTo} the lifecycle it
	 * reached, or null with {@code refusedReason} the guard's words when a guard kept it where it was.
	 * Recorded on the sign-off whose review it was -- a reviewer's pass, or a person's acceptance at a
	 * gate -- because a guard's refusal is otherwise only a board event, which the board's event
	 * retention prunes.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Promotion(UUID release, ReleaseData.ReleaseLifecycle promotedTo,
			@JsonInclude(JsonInclude.Include.NON_NULL) String refusedReason) implements Serializable {
		private static final long serialVersionUID = 20260926L;

		public boolean refused() {
			return null != refusedReason;
		}
	}

	/**
	 * Two-tier hold, mirroring the board-lock design: a COORDINATOR
	 * hold is the coordinator's own parking; an OPERATOR hold cannot
	 * be lifted by the coordinator.
	 */
	public enum HoldLevel { COORDINATOR, OPERATOR }

	/**
	 * Why the task is held. MANUAL: coordinator/operator parked it.
	 * HUMAN_GATE: a gated role signed off and a human verdict
	 * (accept/reject via humanReview) — not a plain lift — must
	 * resolve it; {@code gateRole} names the pass under review.
	 */
	/**
	 * Why a task is on hold.
	 *
	 * <p>{@code QUESTION} is set only by the question escalation: the board could not find a role
	 * that produces what the top frame asks about, the coordinator escalated, and a human is
	 * expected to answer. It is distinguished from {@code MANUAL} because releasing a QUESTION
	 * hold with text turns that text into an answer round, and releasing any other hold must never
	 * do that -- an operator resuming a task they paused for unrelated reasons would otherwise
	 * have their note recorded as a human-authored answer to every open question.
	 */
	public enum HoldKind { MANUAL, HUMAN_GATE, QUESTION }

	/** The routing stop a hold was placed for (board-mechanics §6.2). */
	public enum HoldStop { CYCLE_CAP, NO_PROGRESS, BUDGET }

	/**
	 * Structured hold state; null when the task is not ON_HOLD.
	 *
	 * @param stop the routing stop that placed the hold; null on every other hold. Recorded rather
	 *        than read back from the reason or the holder, which a person's words or an escalation
	 *        (task c0a2134c) can change.
	 * @param returnTo the status the task goes back to when the hold is released; set only on the coordinator
	 *        seat's hold for an operator decision (task RD4-17), which parks a task in the state it was in --
	 *        DELIVERING stays DELIVERING -- rather than handing it back to the loop. Null on every other hold,
	 *        whose release returns the task to the coordinator as before.
	 * @param linked the PRs linked to the task while the seat had it parked for the operator (task RD4-19), oldest
	 *        first: preparation for the person's decision, never its answer. Null or empty on every other hold and on
	 *        a parked task nothing was linked to.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record TaskHold(HoldLevel level, HoldKind kind, String gateRole,
			String reason, AgentActor heldBy, ZonedDateTime heldAt, HoldStop stop, TaskStatus returnTo,
			List<HoldLink> linked) implements Serializable {

		/** A hold nothing was linked under. */
		public TaskHold(HoldLevel level, HoldKind kind, String gateRole, String reason, AgentActor heldBy,
				ZonedDateTime heldAt, HoldStop stop, TaskStatus returnTo) {
			this(level, kind, gateRole, reason, heldBy, heldAt, stop, returnTo, null);
		}

		/** A hold that returns the task to the coordinator on release. */
		public TaskHold(HoldLevel level, HoldKind kind, String gateRole, String reason, AgentActor heldBy,
				ZonedDateTime heldAt, HoldStop stop) {
			this(level, kind, gateRole, reason, heldBy, heldAt, stop, null);
		}

		/** A hold no routing stop placed. */
		public TaskHold(HoldLevel level, HoldKind kind, String gateRole, String reason, AgentActor heldBy,
				ZonedDateTime heldAt) {
			this(level, kind, gateRole, reason, heldBy, heldAt, null, null);
		}

		/** A no-progress or cycle-cap stop: the loop stops a release may route past once. */
		@JsonIgnore
		public boolean isLoopStop() {
			return HoldStop.NO_PROGRESS == stop || HoldStop.CYCLE_CAP == stop;
		}

		/**
		 * The same hold with one more PR linked under it (task RD4-19). Placed at the same moment by the same actor,
		 * so it is not a new hold: the needs-a-person notification is not sent again.
		 */
		public TaskHold withLinked(HoldLink link) {
			List<HoldLink> all = new ArrayList<>(null == linked ? List.of() : linked);
			all.add(link);
			return new TaskHold(level, kind, gateRole, reason, heldBy, heldAt, stop, returnTo, all);
		}
	}

	/**
	 * A PR linked to a task the coordinator seat parked for the operator (task RD4-19): recorded on the decision the
	 * person is making, since it changes the delivery set they are looking at, and never the answer to it.
	 *
	 * @param prUrl the PR as it was linked
	 * @param by who linked it: the linking key's agent
	 * @param at when
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record HoldLink(String prUrl, String by, ZonedDateTime at) implements Serializable {
		private static final long serialVersionUID = 20261001L;
	}


	/**
	 * What one hop consumed.
	 *
	 * <p>Computed from the usage rows attributed to this task within this assignment's window when
	 * the hop ends. Filled in again only when rows reported after that moment belong to the hop --
	 * the CLI reports at session close, after the sign-off -- and then only with more rows, never
	 * re-priced: each row keeps the price entry dated at its report, so a correction to a price
	 * never restates a hop. Prices move; this figure names the entries it used so it stays
	 * meaningful.
	 *
	 * @param costComplete false when some row in the window had no applicable price, making the
	 *        cost a lower bound rather than the answer
	 * @param allowanceMicros the role's allowance ({@code hopBudgetMicros}) when the hop ended, so
	 *        a later change to the role does not restate whether this hop went over; null when the
	 *        role set none, and on hops recorded before allowances were kept
	 * @param refreshedAt when rows reported after the hop ended were folded in; null when the
	 *        snapshot taken at the hop's end is still the whole story
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record HopUsage(long inputTokens, long outputTokens, long cacheReadTokens,
			long cacheWriteTokens, int requests, int turns, int reports,
			Long derivedCostMicros, List<UUID> priceVersions, boolean costComplete, Long allowanceMicros,
			ZonedDateTime refreshedAt) implements Serializable {

		/** Pre-allowance constructor, so existing call sites read unchanged. */
		public HopUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
				int requests, int turns, int reports, Long derivedCostMicros, List<UUID> priceVersions,
				boolean costComplete) {
			this(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, requests, turns, reports,
					derivedCostMicros, priceVersions, costComplete, null, null);
		}

		public static HopUsage empty() {
			return new HopUsage(0, 0, 0, 0, 0, 0, 0, null, List.of(), true);
		}

		/** The same usage, stamped with the allowance the hop was held to. */
		public HopUsage withAllowance(Long allowance) {
			return new HopUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, requests,
					turns, reports, derivedCostMicros, priceVersions, costComplete, allowance, refreshedAt);
		}

		/** The same usage, marked as filled in after the hop ended. */
		public HopUsage withRefreshedAt(ZonedDateTime at) {
			return new HopUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, requests,
					turns, reports, derivedCostMicros, priceVersions, costComplete, allowanceMicros, at);
		}
	}

	/** Append-only "I can't do this" record; distinct from a REJECTED sign-off. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record TaskReturn(String role, UUID roleUuid, UUID agent, UUID session,
			TaskReturnReason reason, String description,
			ZonedDateTime returnedAt, HopUsage usage, List<UUID> outputs) implements Serializable {

		/** Pre-usage constructor, kept so existing call sites and stored rows read unchanged. */
		public TaskReturn(String role, UUID roleUuid, UUID agent, UUID session, TaskReturnReason reason,
				String description, ZonedDateTime returnedAt) {
			this(role, roleUuid, agent, session, reason, description, returnedAt, null, List.of());
		}

		/** Pre-outputs constructor, for the same reason. */
		public TaskReturn(String role, UUID roleUuid, UUID agent, UUID session, TaskReturnReason reason,
				String description, ZonedDateTime returnedAt, HopUsage usage) {
			this(role, roleUuid, agent, session, reason, description, returnedAt, usage, List.of());
		}

		public TaskReturn {
			if (null == outputs) outputs = List.of();
		}

		/** The same return with its usage filled in; nothing else about the hop changes. */
		public TaskReturn withUsage(HopUsage filled) {
			return new TaskReturn(role, roleUuid, agent, session, reason, description, returnedAt, filled,
					outputs);
		}
	}

	/**
	 * One status transition, written atomically with the transition it
	 * describes. The intervals between rows are the cycle-time metrics
	 * nothing else records — notably AWAITING_COORDINATOR -> QUEUED
	 * (coordinator routing latency) and HOLD windows.
	 *
	 * <p>{@code actor} is who caused the transition, as {@link AgentActor}: the worker or
	 * coordinator session, the human who accepted at a gate, or the system on a sweep. It used to
	 * be a bare session uuid, which could only ever answer "which session", so every human-caused
	 * transition — HUMAN_ACCEPT, HUMAN_REJECT, HUMAN_SIGNOFF — recorded null and "who moved this
	 * task" had no answer for exactly the moves a person made. Rows written before that decode to
	 * a SESSION actor, since a bare uuid there was always a session.
	 */
	/**
	 * One unanswered question: who asked, about what, and who is expected to answer.
	 *
	 * @param askingRole the role config that raised the items
	 * @param askingSession the session that raised them, so the answer can be offered back to it
	 * @param askingAgent the agent behind that session, for affinity
	 * @param questionsRelease the index release carrying the items
	 * @param answeringRole the role that produces the questioned input; null while the coordinator
	 *        is deciding who that is
	 * @param askedAt when the frame was pushed, which is the clock a human sees as "waiting since"
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record QuestionFrame(UUID askingRole, UUID askingSession, UUID askingAgent,
			UUID questionsRelease, UUID answeringRole, ZonedDateTime askedAt) implements Serializable {

		private static final long serialVersionUID = 20260921L;

		/** The same frame with its answering role decided, which is what the coordinator sets. */
		public QuestionFrame answeredBy(UUID role) {
			return new QuestionFrame(askingRole, askingSession, askingAgent, questionsRelease,
					role, askedAt);
		}
	}

	/** The pair key a cycle count is stored under. One spelling, so both sides agree. */
	public static String cycleKey(UUID askingRole, UUID answeringRole) {
		return askingRole + ">" + answeringRole;
	}

		@JsonIgnoreProperties(ignoreUnknown = true)
	/**
	 * @param note why, when the actor said: a person completing or cancelling someone else's work,
	 *        or overriding a required role (operator-actions D20); null on rows written before it
	 * @param linked on the row that leaves a hold the coordinator seat placed for the operator, the PRs linked while
	 *        it stood (task RD4-19), so the record of the decision keeps them once the hold is gone; null otherwise
	 */
	public static record StatusChange(TaskStatus from, TaskStatus to,
			ZonedDateTime at, StatusTrigger trigger, AgentActor actor, String note, List<HoldLink> linked)
			implements Serializable {

		/** A row that leaves no hold with PRs linked under it. */
		public StatusChange(TaskStatus from, TaskStatus to, ZonedDateTime at, StatusTrigger trigger,
				AgentActor actor, String note) {
			this(from, to, at, trigger, actor, note, null);
		}

		/** A row with nothing said. */
		public StatusChange(TaskStatus from, TaskStatus to, ZonedDateTime at, StatusTrigger trigger,
				AgentActor actor) {
			this(from, to, at, trigger, actor, null);
		}
	}

	/**
	 * What caused a status transition.
	 *
	 * <p>An enum rather than a string because the cycle-time metrics filter on it: "time from
	 * AUTHORIZE to ASSIGN" is a question you ask of this field, and asking it of free text means
	 * every caller agreeing on spelling forever. The set is closed by construction -- only this
	 * codebase writes transitions.
	 */
	public enum StatusTrigger {
		/** The task was registered from a tracker reference; its first row. */
		REGISTER,
		/** Coordinator queued the task for a role. */
		AUTHORIZE,
		/** A worker session took it. */
		ASSIGN,
		/** A hop signed off; the task redirects to the coordinator. */
		SIGNOFF,
		/** A hop handed it back with a reason. */
		RETURN,
		/** Coordinator marked it finished. */
		COMPLETE,
		/** Coordinator abandoned it. */
		CANCEL,
		/** Split into children. */
		SPLIT,
		/** A hold was raised. */
		HOLD,
		/** A hold was lifted. */
		LIFT_HOLD,
		/** A sign-off met a human gate and is parked for a verdict. */
		HUMAN_GATE,
		/** A human accepted at a gate. */
		HUMAN_ACCEPT,
		/** A human rejected at a gate. */
		HUMAN_REJECT,
		/** A human signed the hop off directly, on a HUMAN role. */
		HUMAN_SIGNOFF,
		/** The working session closed, releasing the assignment. System-generated. */
		SESSION_CLOSED,
		/** A person unassigned the task, back to the queue, with a reason (task RD3-4). */
		UNASSIGN,
		/**
		 * The board completed a task whose loop ran out -- a cycle cap, a no-progress stop or a
		 * budget -- with items left open below what the board says must be resolved. Distinct
		 * from COMPLETE: the work finished in one case and the loop stopped in the other, and a
		 * cycle-time view that conflated them would report the second as success.
		 */
		POLICY_COMPLETE,
		/**
		 * A completed task sent back to a role, with a reason, because its delivery could not land
		 * (a PR that no longer merges). A decision, not a loop: no cycle is counted.
		 */
		REOPEN,
		/** The work passed and a linked PR is not merged yet: into DELIVERING. */
		DELIVER_WAIT,
		/** Every linked PR merged: DELIVERING to COMPLETED. System-generated. */
		DELIVERED,
		/** A linked PR was closed without merging: to the coordinator. System-generated. */
		PR_CLOSED,
		/** A declaration said a delivery unit was abandoned: to the coordinator (task 18c5c293). */
		DELIVERY_ABANDONED
	}

	/** What a declaration says of a delivery unit (task 18c5c293). */
	/**
	 * What a declaration says of a unit. SUPERSEDED (task RD3-13): a linked PR closed unmerged and replaced by
	 * another linked PR on the same repository, named in {@link Delivery#supersededBy}; delivery counts the
	 * replacement and treats this one as absent.
	 */
	public enum DeliveryOutcome { DELIVERED, ABANDONED, SUPERSEDED }

	/**
	 * A declaration that a delivery unit landed, or never will (task 18c5c293): a linked PR merged
	 * where this ReARM cannot see it, or, on a board that delivers without PRs, a push or release.
	 *
	 * @param unit a linked PR's URL, or on a board delivering without PRs a branch or release reference
	 * @param commit the merged or pushed sha; required for DELIVERED
	 * @param by who declares: the coordinator's session, a merging role's session, or a person
	 * @param supersededBy for SUPERSEDED, the linked PR that replaces the unit (task RD3-13); null otherwise
	 * @param observed what the PR's tracker said when the declaration was made, for a PR no CI reported here
	 *        (task RD3-20); null when it was not read
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Delivery(String unit, String commit, DeliveryOutcome outcome, AgentActor by,
			ZonedDateTime at, String note, String supersededBy, TrackerObservation observed) implements Serializable {

		/** A declaration that names no successor: delivered or abandoned. */
		public Delivery(String unit, String commit, DeliveryOutcome outcome, AgentActor by, ZonedDateTime at,
				String note) {
			this(unit, commit, outcome, by, at, note, null, null);
		}

		/** A declaration with its successor, and no tracker read. */
		public Delivery(String unit, String commit, DeliveryOutcome outcome, AgentActor by, ZonedDateTime at,
				String note, String supersededBy) {
			this(unit, commit, outcome, by, at, note, supersededBy, null);
		}
	}

	/**
	 * A PR's state as its tracker reported it (task RD3-20): OPEN, CLOSED (without merging) or MERGED, where it
	 * was read, and when.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record TrackerObservation(String state, String source, ZonedDateTime at) implements Serializable {}

	/**
	 * What a task is for (task RD4-12). WORK is the ordinary task, and every task from before kinds. An
	 * INVESTIGATION is research one role commissions from another: its deliverable is a report, not code, so it
	 * links no PRs and has no delivery step, and it completes when the investigating role's pass carries the
	 * report (and the review, if one was asked for, passes). Set at commission, never changed.
	 */
	public enum TaskKind { WORK, INVESTIGATION }

	/** Where an investigation's report goes when it completes (task RD4-12). */
	public enum ReturnTo {
		/** Pinned on the commissioning task, which is offered back to the commissioning role. */
		TASK,
		/** Nowhere in particular: a standalone investigation, typically a person's. */
		NONE
	}

	/**
	 * Who commissioned an investigation (task RD4-12).
	 *
	 * @param role the commissioning role: the role the session held its task in, or, for a person commissioning
	 *        from a task, that task's role at the time; null for a person with no task
	 * @param session the commissioning session; null for a person
	 * @param task the task it was commissioned from, which the report returns to; null when none
	 * @param by who commissioned it: the session, or the person
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record CommissionedBy(String role, UUID roleUuid, UUID session, UUID task, AgentActor by)
			implements Serializable {
		private static final long serialVersionUID = 20260930L;
	}

	/**
	 * The investigation block of an INVESTIGATION task (task RD4-12).
	 *
	 * @param role the investigating role, which the task keeps whatever role it is queued for later
	 * @param deliverable what it delivers: BOARD_INVESTIGATION_REPORT
	 * @param review the role that reviews the report before it returns; null when none was asked for
	 * @param deadline when the report is due; the board's investigationOverdue rule alerts past it
	 * @param returnTo where the report goes
	 * @param report the report release the investigation completed with; null until then
	 * @param completedAt when it completed with its report
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Investigation(CommissionedBy commissionedBy, RearmSpecificationType deliverable,
			String role, UUID roleUuid, String review, UUID reviewUuid, ZonedDateTime deadline, ReturnTo returnTo,
			UUID report, ZonedDateTime completedAt) implements Serializable {
		private static final long serialVersionUID = 20260930L;

		/** The same block, completed with its report. */
		public Investigation withReport(UUID release, ZonedDateTime at) {
			return new Investigation(commissionedBy, deliverable, role, roleUuid, review, reviewUuid, deadline,
					returnTo, release, at);
		}

		/** Whether the role is the investigating role. */
		public boolean investigatedBy(UUID uuid, String name) {
			if (null != roleUuid && null != uuid) return roleUuid.equals(uuid);
			return null != role && role.equalsIgnoreCase(name);
		}

		/** Whether the role is the reviewing role. */
		public boolean reviewedBy(UUID uuid, String name) {
			if (null != reviewUuid && null != uuid) return reviewUuid.equals(uuid);
			return null != review && review.equalsIgnoreCase(name);
		}
	}

	/**
	 * A report an investigation brought back to this task (task RD4-12): pinned as an input when the
	 * investigation completed, or, when it was cancelled, a row saying no report is coming (design round 2 §2).
	 *
	 * @param report the report release; null on a cancelled investigation
	 * @param session the session that commissioned it, which a poll prefers when the task is offered back
	 * @param reoffered whether the return queued this task for the commissioning role again
	 * @param cancelled whether the investigation was cancelled and returned no report
	 * @param note what the cancel said; null otherwise
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record ReturnedReport(UUID investigation, String investigationKey, UUID report, UUID session,
			String role, ZonedDateTime at, boolean reoffered, boolean cancelled, String note) implements Serializable {
		private static final long serialVersionUID = 20260930L;

		/** A report that came back (the investigation completed with it). */
		public ReturnedReport(UUID investigation, String investigationKey, UUID report, UUID session, String role,
				ZonedDateTime at, boolean reoffered) {
			this(investigation, investigationKey, report, session, role, at, reoffered, false, null);
		}
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/** WORK unless commissioned as an INVESTIGATION (task RD4-12); a task from before kinds reads WORK. */
	@JsonProperty
	private TaskKind kind = TaskKind.WORK;

	/** The investigation block; null on a WORK task (task RD4-12). */
	@JsonProperty
	private Investigation investigation;

	/** Reports investigations commissioned from this task brought back, oldest first (task RD4-12). */
	@JsonProperty
	private List<ReturnedReport> reportsReturned = new ArrayList<>();

	/** Whether this is an investigation (task RD4-12). */
	@JsonIgnore
	public boolean isInvestigation() {
		return TaskKind.INVESTIGATION == kind && null != investigation;
	}

	public TaskKind getKind() {
		return null == kind ? TaskKind.WORK : kind;
	}

	public List<ReturnedReport> getReportsReturned() {
		return null == reportsReturned ? new ArrayList<>() : reportsReturned;
	}

	@JsonProperty
	private UUID board;

	/**
	 * Canonical tracker ref, e.g. {@code github:owner/repo#123}.
	 * Unique per org where present; null only on a draft split child
	 * until its tracker issue exists.
	 */
	@JsonProperty
	private String externalRef;

	@JsonProperty(CommonVariables.TITLE_FIELD)
	private String title;

	/**
	 * What the task is, beyond its one-line title (board-documents.md §4.5, task fceb1e57): free text,
	 * kept whole, set at registration. Null on tasks from before it.
	 */
	private String description;

	/**
	 * The task's number on its board, from the board's counter, stamped at registration (or, for a
	 * task from before keys, on its board's first read) and never changed (board-documents.md D7).
	 */
	@JsonProperty
	private Integer number;

	/**
	 * {@code <prefix>-<number>}, e.g. {@code RD-42}: the prefix the board had when the number was
	 * stamped. Immutable; a later prefix rename leaves it as it is (D10).
	 */
	@JsonProperty
	private String key;

	/** How events and alerts name the task: the key and the title, else the tracker ref or title. */
	/** The task's key, or its uuid on a board without keys: how a refusal names it. */
	public String keyOrUuid() {
		return null != key ? key : String.valueOf(uuid);
	}

	public String label() {
		if (null != key) {
			String t = null == title ? "" : title.strip();
			return t.isEmpty() ? key : key + " " + (t.length() > 60 ? t.substring(0, 59) + "\u2026" : t);
		}
		return null != externalRef && !externalRef.isBlank() ? externalRef : title;
	}

	@JsonProperty
	private String sourceUrl;

	@JsonProperty(CommonVariables.STATUS_FIELD)
	private TaskStatus status = TaskStatus.PENDING_INTAKE;

	/**
	 * Role the task is queued for / being worked in — set by the
	 * coordinator at authorization; retained after a sign-off as the
	 * "last role" until re-authorized.
	 */
	@JsonProperty
	private String role;

	/**
	 * The role config row {@link #role} names, so a reader can reach the prompt, capabilities and
	 * gate that applied without a second lookup by name. Null on tasks authorized before this
	 * field existed, and on a task that has not been authorized yet.
	 */
	@JsonProperty
	private UUID roleUuid;

	/** Coordinator-set priority within the board; polls serve lowest first among ELIGIBLE tasks. */
	@JsonProperty
	private int orderIndex;

	/**
	 * Tasks that must be COMPLETED before this one becomes eligible
	 * for assignment. Coordinator-declared (the architect proposes);
	 * enforced at the poll/assign gate so the coordinator can lay out
	 * the whole plan up front and the server releases work as
	 * dependencies land. A CANCELLED dependency does not satisfy the
	 * gate — the coordinator re-plans.
	 */
	@JsonProperty
	private List<UUID> dependsOn = new ArrayList<>();

	/**
	 * The group this task belongs to, one or none (task-groups-and-tags.md D2, task RD2-29): a
	 * TaskGroup of its board. A group that {@code dependsOn} another holds its tasks back from offers
	 * until that group has nothing open (D4).
	 */
	@JsonProperty
	private UUID group;

	/**
	 * Free labels (D8): the key is the label, lower case; the value is stored but not shown in v1.
	 * No routing, gating or permission meaning.
	 */
	@JsonProperty
	private List<CommonVariables.TagRecord> tags = new ArrayList<>();

	/** Hold state while ON_HOLD; null otherwise. */
	@JsonProperty
	private TaskHold hold;

	/**
	 * Per-task add-only human gate: the next sign-off, whatever the
	 * role, parks the task for human review (then the flag clears).
	 * The coordinator can only set it — more review, never less;
	 * clearing an unfired flag is operator-only.
	 */
	@JsonProperty
	private boolean requireHumanReview;

	@JsonProperty
	private TaskAssignment assignment;

	/**
	 * What this task may spend, in USD micros; null means only the board's limit applies.
	 * Explicit: never derived from the board's budget or from the role hop budgets.
	 */
	@JsonProperty
	private Long budgetMicros;

	/** Who last set or cleared budgetMicros, and when (task 6f1b348d); like orderSetBy. */
	@JsonProperty
	private AgentActor budgetSetBy;

	@JsonProperty
	private ZonedDateTime budgetSetAt;

	/**
	 * One reopen of a completed task: the role it went back to, when, by whom and why. The reopened
	 * role's passes from before {@code at} no longer count; every other role's passes stand until
	 * the redone hop republishes something they read.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Reopen(String role, UUID roleUuid, ZonedDateTime at, AgentActor by, String reason)
			implements Serializable {

		private static final long serialVersionUID = 20260925L;

		boolean isFor(SignOff so) {
			if (null != roleUuid && null != so.roleUuid()) return roleUuid.equals(so.roleUuid());
			return null != role && role.equalsIgnoreCase(so.role());
		}
	}

	/** Every reopen of this task, oldest first. */
	@JsonProperty
	private List<Reopen> reopens = new ArrayList<>();

	/** When the task was last reopened; null when never. */
	@JsonIgnore
	public ZonedDateTime getReopenedAt() {
		return null == reopens || reopens.isEmpty() ? null : reopens.get(reopens.size() - 1).at();
	}

	@JsonIgnore
	public int getReopenCount() {
		return null == reopens ? 0 : reopens.size();
	}

	/**
	 * Whether a reopen to this sign-off's role came after it, so it is no longer a pass: the role
	 * was sent back to redo its part and has to pass again.
	 */
	@JsonIgnore
	public boolean reopenedSince(SignOff so) {
		if (null == so || null == so.signedOffAt() || null == reopens) return false;
		return reopens.stream().anyMatch(r -> r.isFor(so) && null != r.at() && r.at().isAfter(so.signedOffAt()));
	}

	/** Coordinator spend apportioned to this task, frozen when computed and never recomputed. */
	@JsonProperty
	private Long coordinatorEstimateMicros;

	/** Model strength this task requires, overriding the role's when set. */
	@JsonProperty
	private Double requiredStrength;

	/** Who set {@link #requiredStrength}, and when. Not a status row: the status did not change. */
	@JsonProperty
	private AgentActor strengthSetBy;

	@JsonProperty
	private ZonedDateTime strengthSetAt;

	/**
	 * Who last set {@link #orderIndex}, and when, on either path: the coordinator orders tasks, and
	 * a person may too, after which the coordinator may reorder again (operator-actions D4).
	 */
	@JsonProperty
	private AgentActor orderSetBy;

	@JsonProperty
	private ZonedDateTime orderSetAt;

	/**
	 * Required roles a person completed the task without, by saying so (operator-actions D17).
	 * Empty unless that happened; the status row that completed it carries who and why.
	 */
	@JsonProperty
	private List<String> requiredRolesSkipped = new ArrayList<>();

	/**
	 * Rounds counted per pair of roles, keyed "askingRole>answeringRole".
	 *
	 * <p>Keyed by uuid pair rather than by name so a renamed or re-created role cannot silently
	 * reset a count, and a string key because this is JSONB and a map key has to be one.
	 */
	@JsonProperty
	private Map<String, Integer> cycles = new LinkedHashMap<>();

	/**
	 * Who is waiting on whom, innermost last.
	 *
	 * <p>A stack rather than a field because questions nest: a tester asks the coder, who cannot
	 * answer without asking the architect. Each answer pops one frame, which is what unwinds the
	 * chain back to the role that started it.
	 */
	@JsonProperty
	private List<QuestionFrame> questionStack = new ArrayList<>();

	/**
	 * Releases of a loop-stop hold on this task, by stop kind (task c0a2134c), whoever released it.
	 * The first no-progress or cycle-cap stop of a kind may park for the coordinator; once one of
	 * that kind has been released, the next is the operator's.
	 */
	@JsonProperty
	private Map<HoldStop, Integer> stopLifts = new LinkedHashMap<>();

	/**
	 * While DELIVERING, the newest commit last seen on each linked PR, by the PR's match key (task
	 * 3b97ccfd): taken when the task enters DELIVERING and moved on as the PR does, so a push after
	 * the pass is noticed once rather than on every sweep.
	 */
	@JsonProperty
	private Map<String, String> deliveringHeads = new LinkedHashMap<>();

	/** Releases so far of a stop of this kind. */
	@JsonIgnore
	public int stopLiftsOf(HoldStop stop) {
		return null == stopLifts ? 0 : stopLifts.getOrDefault(stop, 0);
	}

		@JsonProperty
	private List<SignOff> signOffs = new ArrayList<>();

	@JsonProperty
	private List<TaskReturn> returns = new ArrayList<>();

	/**
	 * An assignment a person ended by unassigning the task back to the queue (task RD3-4): the hop's
	 * session, when and by whom, why, and what it had spent. The unassigned session's later sign-off,
	 * publish or question on the task is refused with these facts.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Unassignment(String role, UUID roleUuid, UUID agent, UUID session,
			ZonedDateTime assignedAt, ZonedDateTime unassignedAt, AgentActor unassignedBy, String reason,
			HopUsage usage) implements Serializable {

		private static final long serialVersionUID = 20260928L;

		/** The words an unassigned session gets when it tries to carry on with the hop. */
		public String refusal(String task) {
			return "you were unassigned from " + task + " by "
					+ (null == unassignedBy ? "a person" : unassignedBy.display()) + " at "
					+ unassignedAt.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
					+ "; the task is queued again";
		}
	}

	@JsonProperty
	private List<Unassignment> unassignments = new ArrayList<>();

	public List<Unassignment> getUnassignments() {
		return null == unassignments ? new ArrayList<>() : unassignments;
	}

	/**
	 * The unassignment that ended this session's hop on the task, when the session does not hold the task
	 * now: what a refused sign-off, publish or question from it names.
	 */
	public java.util.Optional<Unassignment> unassignedFor(UUID session) {
		if (null == session) return java.util.Optional.empty();
		if (null != assignment && session.equals(assignment.session())) return java.util.Optional.empty();
		List<Unassignment> all = getUnassignments();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (session.equals(all.get(i).session())) return java.util.Optional.of(all.get(i));
		}
		return java.util.Optional.empty();
	}

	/** What the task shows while its holder waits on a person (task RD4-5), before the question. */
	public static final String AWAITING_THE_OPERATOR = "awaiting the operator: ";

	/**
	 * Whether the hold on this task is a hop its holder parked for the operator (task RD4-5): an OPERATOR-level
	 * MANUAL hold a session placed on the task it was working. Read from the task rather than stored, since
	 * nothing else reaches ON_HOLD from ASSIGNED through a session's HOLD: routing's stops are the system's, a
	 * human gate has its own kind and trigger, and the seat holds only tasks nobody is working.
	 */
	@JsonIgnore
	public boolean holdParksAHop() {
		if (TaskStatus.ON_HOLD != status || null == hold || HoldKind.MANUAL != hold.kind()
				|| HoldLevel.OPERATOR != hold.level() || null == hold.heldBy()
				|| AgentActor.ActorKind.SESSION != hold.heldBy().kind() || null == statusHistory) {
			return false;
		}
		for (int i = statusHistory.size() - 1; i >= 0; i--) {
			StatusChange c = statusHistory.get(i);
			if (TaskStatus.ON_HOLD == c.to()) {
				return TaskStatus.ASSIGNED == c.from() && StatusTrigger.HOLD == c.trigger();
			}
		}
		return false;
	}

	/**
	 * Whether the coordinator seat parked this task for an operator decision (task RD4-17): an OPERATOR-level
	 * MANUAL hold a session placed that records the status to return to. Only the seat's hold records one; a
	 * hop's own hold (RD4-5) resumes its holder instead, and a person's hold goes back to the coordinator.
	 */
	@JsonIgnore
	public boolean seatParkedForOperator() {
		return TaskStatus.ON_HOLD == status && null != hold && null != hold.returnTo() && HoldKind.MANUAL == hold.kind()
				&& HoldLevel.OPERATOR == hold.level() && null != hold.heldBy()
				&& AgentActor.ActorKind.SESSION == hold.heldBy().kind();
	}

	/**
	 * Whether the task waits on a person's answer to a question an agent asked: a hop its holder parked (RD4-5),
	 * or a task the seat parked (RD4-17). Either shows "awaiting the operator: question" as its hold's reason.
	 */
	@JsonIgnore
	public boolean awaitsTheOperator() {
		return holdParksAHop() || seatParkedForOperator();
	}

	/** The question a task awaiting the operator asks, without the words the task shows before it. */
	@JsonIgnore
	public String operatorQuestion() {
		return null == hold || null == hold.reason() ? null
				: StringUtils.removeStart(hold.reason(), AWAITING_THE_OPERATOR);
	}

	/** Delivery declarations, oldest first and append-only; the newest per unit counts (task 18c5c293). */
	@JsonProperty
	private List<Delivery> deliveries = new ArrayList<>();

	/**
	 * Superseded PRs the board has already been alerted about for merging after all (task RD3-13), so the
	 * sweep says it once. Normalised PR URLs.
	 */
	@JsonProperty
	private List<String> supersededMergedAlerted = new ArrayList<>();

	/** Parent task when this row was created by a coordinator split. */
	@JsonProperty
	private UUID parentTask;

	@JsonProperty
	private List<UUID> childTasks = new ArrayList<>();

	/** Every session that ever held the assignment (accumulates; boards link to sessions through this). */
	@JsonProperty
	private List<UUID> sessions = new ArrayList<>();

	@JsonProperty
	private List<String> prUrls = new ArrayList<>();

	/**
	 * The component this task produces: a document component when the task writes a
	 * specification, the implementation component when it writes code. Null while the work
	 * is still about something that does not exist yet -- the decomposition task that will
	 * define a node cannot name it before the gate materialises it.
	 */
	@JsonProperty
	private UUID producesComponent;

	/**
	 * Releases produced under this task, linked explicitly by the worker (the release
	 * counterpart of {@link #prUrls}). Append-only. What a downstream role waits on when it
	 * requires the output of this task rather than of the component at large.
	 */
	@JsonProperty
	private List<UUID> releases = new ArrayList<>();

	/**
	 * Work level: the depth in the product tree this task belongs to, declared rather
	 * than derived. Null falls back to the board's {@code defaultWorkLevel} on read (RD2-1: the
	 * default is never written here, so changing it moves every unset task). A declaration of
	 * intent, not a structural fact -- resolution of documents and releases always goes
	 * through the target node, never through this number.
	 */
	@JsonProperty
	private Integer workLevel;

	/** Who last set or cleared the work level after registration, and when (RD2-1); like budgetSetBy. */
	@JsonProperty
	private AgentActor workLevelSetBy;

	@JsonProperty
	private ZonedDateTime workLevelSetAt;

	/**
	 * Requirements this task adds on top of its role's. Add-only: the coordinator or the
	 * registering agent may demand more than the role does -- an architect saying this
	 * particular change waits on the test plan -- and clearing one is an operator act.
	 */
	@JsonProperty
	private List<RequiredInput> requiredInputs = new ArrayList<>();

	/** Session of the registering agent (usually the coordinator) — intake provenance. */
	@JsonProperty
	private UUID registeredBySession;

	/** Append-only status transition log, oldest first. */
	@JsonProperty
	private List<StatusChange> statusHistory = new ArrayList<>();

	@JsonProperty
	private ZonedDateTime completedAt;

	/**
	 * Record a transition and apply it: appends {current -> to} to the
	 * history and sets the status. All service-layer status changes go
	 * through this so the log can never drift from the state.
	 */
	public void transitionStatus(TaskStatus to, StatusTrigger trigger, AgentActor actor) {
		transitionStatus(to, trigger, actor, null);
	}

	/**
	 * Triggers whose row records an event even when the status does not change (RD3-16 architecture-2 §1).
	 * Each entry says why; adding one needs its reason here.
	 * <ul>
	 * <li>SPLIT: a task split while it already waits on the coordinator -- the natural state of one returned as
	 * too big -- keeps the split in its history, beside the children it now has.</li>
	 * </ul>
	 */
	public static final Set<StatusTrigger> RECORDING_TRIGGERS = EnumSet.of(StatusTrigger.SPLIT);

	/** As above, recording why (operator-actions D20). */
	public void transitionStatus(TaskStatus to, StatusTrigger trigger, AgentActor actor, String note) {
		// A row that pretends to be a transition and changes nothing is a bug in its caller (RD3-16). Two
		// kinds of same-state row record an event instead, and stay: routing a queued task on (to another
		// role, or back with a person's decisions) is a new hand-off whose row carries the reason and the
		// role; and a trigger in RECORDING_TRIGGERS (architecture-2 §1).
		if (null != to && to == this.status && TaskStatus.QUEUED != to && !RECORDING_TRIGGERS.contains(trigger)) {
			throw new IllegalStateException("A transition from " + to + " to itself (" + trigger
					+ ") changes nothing; its caller should not have made it");
		}
		if (this.statusHistory == null) this.statusHistory = new ArrayList<>();
		// The row leaving a hold keeps the PRs linked under it (task RD4-19): every release clears the hold after
		// this row, so they would otherwise go with it.
		List<HoldLink> linkedUnderHold = TaskStatus.ON_HOLD == this.status && TaskStatus.ON_HOLD != to
				&& null != hold && null != hold.linked() && !hold.linked().isEmpty() ? new ArrayList<>(hold.linked()) : null;
		this.statusHistory.add(new StatusChange(this.status, to, ZonedDateTime.now(), trigger, actor,
				StringUtils.isBlank(note) ? null : note, linkedUnderHold));
		this.status = to;
	}

	public void addSignOff(SignOff s) {
		if (this.signOffs == null) this.signOffs = new ArrayList<>();
		if (s != null) this.signOffs.add(s);
	}

	public void addReturn(TaskReturn r) {
		if (this.returns == null) this.returns = new ArrayList<>();
		if (r != null) this.returns.add(r);
	}

	public void addChildTask(UUID child) {
		if (this.childTasks == null) this.childTasks = new ArrayList<>();
		if (child != null && !this.childTasks.contains(child)) this.childTasks.add(child);
	}

	public void addSession(UUID session) {
		if (this.sessions == null) this.sessions = new ArrayList<>();
		if (session != null && !this.sessions.contains(session)) this.sessions.add(session);
	}

	public void addPrUrl(String prUrl) {
		if (this.prUrls == null) this.prUrls = new ArrayList<>();
		if (prUrl != null && !this.prUrls.contains(prUrl)) this.prUrls.add(prUrl);
	}

	public void addRelease(UUID release) {
		if (this.releases == null) this.releases = new ArrayList<>();
		if (release != null && !this.releases.contains(release)) this.releases.add(release);
	}

	/**
	 * Agent of the most recent AGENT sign-off, or null — the
	 * separation-of-duties reference point. Human sign-offs are
	 * skipped: an intervening human verdict must not launder the last
	 * working agent's identity.
	 */
	@JsonIgnore
	public UUID lastSignOffAgent() {
		if (signOffs == null || signOffs.isEmpty()) return null;
		for (int i = signOffs.size() - 1; i >= 0; i--) {
			if (signOffs.get(i).reviewedBy() == null) return signOffs.get(i).agent();
		}
		return null;
	}

	/** Most recent sign-off for a role (case-insensitive), or null — the necessity-check reference point. */
	@JsonIgnore
	public SignOff lastSignOffForRole(UUID roleUuid, String roleName) {
		if (signOffs == null) return null;
		for (int i = signOffs.size() - 1; i >= 0; i--) {
			SignOff so = signOffs.get(i);
			// The row wins when both sides have one: that is the point of recording it. The name
			// is the fallback, not the identity -- but it has to stay reachable, because a hop
			// recorded before roleUuid existed has none, and matching only on the uuid would make
			// its role read as never signed off. On a task mid-flight that means the completion
			// rule refuses a task whose work is done.
			if (null != roleUuid && null != so.roleUuid()) {
				if (roleUuid.equals(so.roleUuid())) return so;
			} else if (null != roleName && roleName.equalsIgnoreCase(so.role())) {
				return so;
			}
		}
		return null;
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static AgentTaskData dataFromRecord(AgentTask t) {
		if (t.getSchemaVersion() != 0) {
			throw new IllegalStateException("AgentTask schema version is " + t.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = t.getRecordData();
		AgentTaskData td = Utils.OM.convertValue(recordData, AgentTaskData.class);
		td.setUuid(t.getUuid());
		td.setCreatedDate(t.getCreatedDate());
		// The row's own update time (task 9540d3b6): AgentTaskService.saveData moves it on every save,
		// so every transition and every edit is a change a follower can ask about.
		td.setUpdatedDate(t.getLastUpdatedDate());
		return td;
	}
}
