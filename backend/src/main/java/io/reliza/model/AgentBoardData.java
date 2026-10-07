/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * Agent task board — the unit of workflow governance for
 * hub-and-spoke task distribution over an external tracker. All
 * configuration is board-level (roles, WIP limits, sources, locks);
 * the org level holds only the board list. Full design:
 * backend/ai-plans/agentic/task-boards.md.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentBoardData extends RelizaDataParent implements RelizaObject {

	public enum BoardStatus { ACTIVE, ARCHIVED }

	/**
	 * How strictly coordinator-set priority binds worker assignment.
	 * LAX (default): the poll offers work in priority order but a
	 * worker may assign any eligible task by uuid — priority is
	 * advisory, which suits cooperative fleets and cherry-picking
	 * related work. STRICT: assignment is refused unless the task is
	 * the top eligible offer for that agent, making the coordinator's
	 * ordering governance rather than convention.
	 */
	public enum PriorityType { LAX, STRICT }

	/**
	 * Two-tier pause. A pause stops NEW assignments only — polls skip the
	 * board and assignment is rejected; in-flight assignments finish
	 * and sign off into the frozen queue. The coordinator can pause and
	 * resume at COORDINATOR level; only an operator can set or lift an
	 * OPERATOR pause.
	 */
	public enum BoardPauseLevel { NONE, COORDINATOR, OPERATOR }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record BoardPause(BoardPauseLevel level, String reason,
			AgentActor pausedBy, ZonedDateTime pausedAt) implements Serializable {}

	/**
	 * Singleton coordinator seat: one session holds it per board, until
	 * that session closes (any close path). The seat-holding session
	 * may not take task assignments.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record CoordinatorSeat(UUID session, UUID agent,
			ZonedDateTime claimedAt) implements Serializable {}

	public enum BoardEventKind { PAUSED, RESUMED, ALERT, INFO }

	/**
	 * Board-level notice for humans: pauses and resumes post one; the coordinator posts ALERT/INFO via
	 * the seat. Kept in the event log only (task RD3-2), and served on the board as its newest
	 * events.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record BoardEvent(BoardEventKind kind, String message,
			AgentActor actor, ZonedDateTime eventAt) implements Serializable {}

	/**
	 * Consecutive repeated rounds at which a task parks (§4.3). One means the first repeat parks:
	 * a round that asks the same open ids as the round before it has not moved, and another turn
	 * of the same loop costs a hop to learn the same thing.
	 */
	public static final int DEFAULT_NO_PROGRESS_REPEATS = 1;

	/** Rounds allowed between one pair of roles before the task parks (§4.2). */
	public static final int DEFAULT_CYCLE_CAP = 3;

	/** Fraction of a budget at which the board posts one alert and nothing stops (§5.4). */
	public static final int DEFAULT_SOFT_ALERT_PERCENT = 80;

		public static final int DEFAULT_PER_AGENT_WIP_LIMIT = 2;

	/** Days the event log keeps a board's events when the board does not say (task 04dedcc5). */
	public static final int DEFAULT_EVENT_RETENTION_DAYS = 15;

	/**
	 * What every task on this board may spend together, in USD micros; null means no board limit.
	 * Not derived from the task budgets and not a sum of them -- a board may cap a set of tasks
	 * that individually have no cap.
	 */
	/**
	 * The board file that last applied to this board: spec hash, when, and where it came from.
	 * Null on a board never applied from a file (declarative-boards D9).
	 */
	@JsonProperty
	private DeclarativeProvenance declarative;

	@JsonProperty
	private Long budgetMicros;

	/** Percent of a budget at which the board alerts; {@link #DEFAULT_SOFT_ALERT_PERCENT} when null. */
	@JsonProperty
	private Integer softAlertPercent;

	/** Rounds between one pair of roles before a task parks; {@link #DEFAULT_CYCLE_CAP} when null. */
	@JsonProperty
	private Integer cycleCap;

	/** Repeated rounds at which a task parks; {@link #DEFAULT_NO_PROGRESS_REPEATS} when null. */
	@JsonProperty
	private Integer noProgressRepeatsToStop;

	/**
	 * Whether a no-progress or cycle-cap stop parks for the coordinator first, which may release it
	 * once per stop kind per task or escalate it (task c0a2134c). Null is the default, on; false
	 * sends every stop to the operator, as before.
	 */
	@JsonProperty
	private Boolean coordinatorStopLift;

	/** {@link #coordinatorStopLift} with its default resolved. */
	@JsonIgnore
	public boolean getEffectiveCoordinatorStopLift() {
		return !Boolean.FALSE.equals(coordinatorStopLift);
	}

	/**
	 * Days the board's event log keeps an event (task 04dedcc5): a daily sweep deletes older ones so
	 * the log cannot hog the database. Null is {@link #DEFAULT_EVENT_RETENTION_DAYS}; 0 keeps
	 * everything.
	 */
	@JsonProperty
	private Integer eventRetentionDays;

	/** {@link #eventRetentionDays} with its default resolved; 0 keeps everything. */
	@JsonIgnore
	public int getEffectiveEventRetentionDays() {
		return null == eventRetentionDays ? DEFAULT_EVENT_RETENTION_DAYS : eventRetentionDays;
	}

	/**
	 * The newest event the retention sweep has deleted from this board's log: its seq and when it
	 * happened. Null until the sweep deletes something. A reader whose cursor is below it has lost
	 * events; the seq is shared by every board's log, so the board's own oldest row cannot say that.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record EventLogPruned(Long throughSeq, ZonedDateTime throughAt) implements Serializable {}

	@JsonProperty
	private EventLogPruned eventLogPruned;

	/**
	 * Minutes a task may wait on a person -- a gate, an operator hold, a question nobody can answer --
	 * before the board sends AGENT_TASK_QUEUE_AGE (task 82880ea6). Null or 0 is off, the default.
	 */
	@JsonProperty
	private Integer humanQueueAgeMinutes;

	/**
	 * Staleness thresholds (task RD3-4), minutes each, null off. They only ALERT; nothing here changes
	 * a task. A person releases a stalled assignment by hand.
	 *
	 * @param roleUnstaffedMinutes a task QUEUED for a role this long while no session of that role polled
	 *        the board within the same span
	 * @param hopNoProgressMinutes a task ASSIGNED this long with nothing from the holder since the
	 *        assignment: no document, no sign-off, no question, no usage report
	 * @param deliveryStuckMinutes a task DELIVERING this long with a linked PR not delivered, or nothing
	 *        delivered at all
	 * @param seatSilentMinutes a task waiting on the coordinator (PENDING_INTAKE or AWAITING_COORDINATOR)
	 *        this long while the seat is held
	 * @param repeatMinutes how long a standing breach stays quiet before it is alerted again; null is 240
	 * @param investigationOverdueMinutes an investigation task (task RD4-12) not completed or cancelled this long
	 *        after its deadline; 0 alerts as soon as the deadline passes
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Staleness(Integer roleUnstaffedMinutes, Integer hopNoProgressMinutes,
			Integer deliveryStuckMinutes, Integer seatSilentMinutes, Integer repeatMinutes,
			Integer investigationOverdueMinutes) implements Serializable {

		private static final long serialVersionUID = 20260930L;
		public static final int DEFAULT_REPEAT_MINUTES = 240;

		/** Without the investigation rule: every block from before it (task RD4-12). */
		public Staleness(Integer roleUnstaffedMinutes, Integer hopNoProgressMinutes, Integer deliveryStuckMinutes,
				Integer seatSilentMinutes, Integer repeatMinutes) {
			this(roleUnstaffedMinutes, hopNoProgressMinutes, deliveryStuckMinutes, seatSilentMinutes, repeatMinutes, null);
		}

		public int effectiveRepeatMinutes() {
			return null == repeatMinutes ? DEFAULT_REPEAT_MINUTES : repeatMinutes;
		}

		/** Whether any rule is on. */
		public boolean anyRule() {
			return null != roleUnstaffedMinutes || null != hopNoProgressMinutes || null != deliveryStuckMinutes
					|| null != seatSilentMinutes || null != investigationOverdueMinutes;
		}

		public boolean isEmpty() {
			return !anyRule() && null == repeatMinutes;
		}
	}

	@JsonProperty
	private Staleness staleness;

	/**
	 * One rung of the board's decomposition ladder (task RD3-6): its number, 0 first and without gaps, its name
	 * (requirements, solution, components, modules...) and what it means on this board.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record LadderLevel(Integer number, String name, String description) implements Serializable {
		private static final long serialVersionUID = 20260929L;
	}

	/**
	 * The board's level ladder (task RD3-6), opt-in: with one, every task has a level (the board default is 0 when
	 * unset) and the served prompts carry a ladder section; without one, levels stay null and are refused.
	 *
	 * @param levels the rungs, 0 first
	 * @param prompt the ladder section served on this board, overriding the default text; null serves the default
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Ladder(List<LadderLevel> levels, String prompt) implements Serializable {
		private static final long serialVersionUID = 20260929L;

		public boolean isEmpty() {
			return null == levels || levels.isEmpty();
		}
	}

	@JsonProperty
	private Ladder ladder;

	/** Whether the board declares a ladder: levels mean something here only then (task RD3-6). */
	public boolean hasLadder() {
		return null != ladder && !ladder.isEmpty();
	}

	/** The rung with this number, when the board has a ladder and the number is on it. */
	public java.util.Optional<LadderLevel> ladderLevel(Integer number) {
		if (!hasLadder() || null == number || number < 0 || number >= ladder.levels().size()) return java.util.Optional.empty();
		return java.util.Optional.of(ladder.levels().get(number));
	}

	/**
	 * When each standing breach was last alerted, epoch seconds keyed by "rule:subject" (task RD3-4).
	 * Written by the sweep through a targeted update, not a revision; a breach that clears leaves it.
	 */
	@JsonProperty
	private Map<String, Long> stalenessAlerted;

	/**
	 * Priority number at or below which an open item blocks, 1 being the most urgent. Null is
	 * strict: every open item blocks. An item above the setting is recorded and reported and never
	 * sends the task back a step.
	 */
	@JsonProperty
	private Integer blockingPriority;

	/**
	 * Priority number at or below which an open item prevents a policy completion. Null is strict,
	 * so any open item at exhaustion goes to a human rather than completing under policy.
	 */
	@JsonProperty
	private Integer completionPriority;

	/**
	 * Whether the soft-alert line has already been reported.
	 *
	 * <p>Crossing it is a one-off event, not a state: without this the board would post the same
	 * alert after every hop for the rest of its life, which is how an alert stops being read.
	 */
	@JsonProperty
	private boolean softAlertPosted;

	/**
	 * The element id families documents on this board use (gaps §2.A): {@code REQ-F-012} is a
	 * requirement because REQ maps to requirement. The board's own entries override or extend
	 * {@link #DEFAULT_ELEMENT_FAMILIES}; null means the defaults alone.
	 */
	public static final Map<String, String> DEFAULT_ELEMENT_FAMILIES = java.util.Collections.unmodifiableMap(
			new LinkedHashMap<>() {{
				put("REQ", "requirement");
				put("FN", "function");
				put("PBS", "product");
				put("IBS", "interface");
				put("IF", "interface");
				put("DS", "data");
				put("TEST", "test");
				put("T", "test");
				put("GLOSS", "glossary");
				put("ADR", "decision");
				put("UC", "use-case");
				put("CONOPS", "concept");
				// Grammar 1.2 (task RD4-6): defined only in their own index types, referenced everywhere else.
				put("Q", "question");
				put("F", "review-item");
			}});

	@JsonProperty
	private Map<String, String> elementFamilies;

	/**
	 * The board's own {@code definedIn} lists, by prefix (grammar 1.2, task RD4-6): the specification types that
	 * define the prefix's ids, in order of precedence. A prefix absent here takes its family's default
	 * ({@link ElementFamilies#DEFAULT_DEFINED_IN}); an empty list means its ids are only ever referenced. Null
	 * means no overrides.
	 */
	@JsonProperty
	private Map<String, List<RearmSpecificationType>> elementFamilyDefinedIn;

	/**
	 * What the board does with the element checks (gaps §2.A, task 2e0fffa6); null means
	 * {@link ElementCheckPolicy#DEFAULTS}: every check reports, none blocks.
	 */
	@JsonProperty
	private ElementCheckPolicy elementCheckPolicy;

	/**
	 * How a task on this board proves it was delivered (task 18c5c293). PR_ROWS (the default): its
	 * linked PRs' rows on this ReARM, merged, or a declaration for each. DECLARED: the linked PRs
	 * register elsewhere, so a declaration per PR (a merged row here counts too). NONE: no PRs; the
	 * task completes at its last pass, or with awaitDeclaration, once a push or release is declared.
	 */
	public enum DeliveryMode { PR_ROWS, DECLARED, NONE }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record DeliveryPolicy(DeliveryMode mode, Boolean awaitDeclaration, MergeProcedure merge) implements Serializable {
		public static final DeliveryPolicy DEFAULT = new DeliveryPolicy(DeliveryMode.PR_ROWS, false, null);
	}

	/** Who merges a board's PRs (task 71a3dd22). */
	public enum MergeBy { COORDINATOR, ROLE, PERSON }

	/** How a board's PRs are merged: a merge commit, a squash, a rebase, or a fast-forward. */
	public enum MergeMethod { MERGE, SQUASH, REBASE, FAST_FORWARD }

	/** Which of several passed tasks merges first. */
	public enum MergeOrder { NOTE_ORDER, OLDEST_PASS_FIRST }

	/**
	 * The board's merge procedure (task 71a3dd22): who merges, how, whether only at the tested head,
	 * whether each merge is declared, and in which order. As declared, each part null meaning its
	 * default; the service resolves the defaults, since who merges by default depends on the roles.
	 *
	 * @param by COORDINATOR, PERSON, or ROLE:&lt;name&gt; -- one string, as the board file writes it
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record MergeProcedure(String by, MergeMethod method, Boolean atTestedHead,
			Boolean requireDeclaration, MergeOrder order) implements Serializable {

		/** The kind {@link #by} names; null when unset or unreadable. */
		public MergeBy byKind() {
			if (null == by || by.isBlank()) return null;
			String b = by.strip();
			int colon = b.indexOf(':');
			String kind = (colon < 0 ? b : b.substring(0, colon)).strip().toUpperCase(java.util.Locale.ROOT);
			try {
				MergeBy k = MergeBy.valueOf(kind);
				if (MergeBy.ROLE == k && (colon < 0 || b.substring(colon + 1).isBlank())) return null;
				if (MergeBy.ROLE != k && colon >= 0) return null;
				return k;
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		/** The role {@link #by} names when it is ROLE:&lt;name&gt;; null otherwise. */
		public String byRole() {
			if (MergeBy.ROLE != byKind()) return null;
			return by.strip().substring(by.strip().indexOf(':') + 1).strip();
		}
	}

	/** Null is {@link DeliveryPolicy#DEFAULT}; a null part of a set policy is its default too. */
	@JsonProperty
	private DeliveryPolicy deliveryPolicy;

	/** {@link #deliveryPolicy} with its defaults resolved. */
	@JsonIgnore
	public DeliveryPolicy getEffectiveDeliveryPolicy() {
		if (null == deliveryPolicy) return DeliveryPolicy.DEFAULT;
		return new DeliveryPolicy(null == deliveryPolicy.mode() ? DeliveryMode.PR_ROWS : deliveryPolicy.mode(),
				Boolean.TRUE.equals(deliveryPolicy.awaitDeclaration()), deliveryPolicy.merge());
	}

	/** The board's check policy, or the defaults. */
	@JsonIgnore
	public ElementCheckPolicy getEffectiveElementCheckPolicy() {
		return null == elementCheckPolicy ? ElementCheckPolicy.DEFAULTS : elementCheckPolicy;
	}

	/** The defaults with this board's entries over them: what the CLI parses against. */
	@JsonIgnore
	public Map<String, String> getEffectiveElementFamilies() {
		Map<String, String> effective = new LinkedHashMap<>(DEFAULT_ELEMENT_FAMILIES);
		if (null != elementFamilies) effective.putAll(elementFamilies);
		return effective;
	}

	/** Every effective family prefix with its defining types (grammar 1.2): the board's list, else the default. */
	@JsonIgnore
	public List<ElementFamilies.Entry> getEffectiveElementFamilyEntries() {
		return ElementFamilies.entries(getEffectiveElementFamilies(), elementFamilyDefinedIn);
	}

	/** {@link #getEffectiveElementFamilyEntries()} as prefix to defining types. */
	@JsonIgnore
	public Map<String, List<RearmSpecificationType>> getEffectiveElementFamilyDefinedIn() {
		return ElementFamilies.definedInByPrefix(getEffectiveElementFamilyEntries());
	}

	/**
	 * The families as the board declares them, for the board file and the {@code elementFamilies} read: a prefix
	 * with its own {@code definedIn} as {@code {family, definedIn}}, any other as its family's name. Null when the
	 * board declares nothing.
	 */
	@JsonIgnore
	public Map<String, Object> getDeclaredElementFamilies() {
		if ((null == elementFamilies || elementFamilies.isEmpty())
				&& (null == elementFamilyDefinedIn || elementFamilyDefinedIn.isEmpty())) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		if (null != elementFamilies) {
			for (Map.Entry<String, String> e : elementFamilies.entrySet()) {
				List<RearmSpecificationType> own = null == elementFamilyDefinedIn ? null : elementFamilyDefinedIn.get(e.getKey());
				out.put(e.getKey(), null == own ? e.getValue() : declared(e.getValue(), own));
			}
		}
		if (null != elementFamilyDefinedIn) {
			for (Map.Entry<String, List<RearmSpecificationType>> e : elementFamilyDefinedIn.entrySet()) {
				if (out.containsKey(e.getKey())) continue;
				out.put(e.getKey(), declared(DEFAULT_ELEMENT_FAMILIES.get(e.getKey()), e.getValue()));
			}
		}
		return out;
	}

	private static Map<String, Object> declared(String family, List<RearmSpecificationType> definedIn) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (null != family) m.put("family", family);
		m.put("definedIn", definedIn.stream().map(Enum::name).toList());
		return m;
	}

	/**
	 * Coordinator spend apportioned to tasks so far, in micros (D16). The same money as the seat's
	 * usage rows, which the board already counts: this is the numerator of the overhead, not spend.
	 */
	@JsonProperty
	private long coordinatorSharesMicros;

	/** Coordinator-caused transitions the apportioned deltas were split over (D16), the denominator. */
	@JsonProperty
	private long coordinatorTransitions;

	/** When the last apportioned coordinator delta was reported; the next one splits over what came after. */
	@JsonProperty
	private ZonedDateTime coordinatorShareCursor;

	/**
	 * The board's average coordinator share per transition (board-mechanics §5.3), added to every
	 * projected round: 0 until a delta has been split over transitions. Computed, never stored.
	 */
	@JsonIgnore
	public long getCoordinatorOverheadMicros() {
		return coordinatorTransitions > 0 ? coordinatorSharesMicros / coordinatorTransitions : 0;
	}

		public int effectiveCycleCap() {
		return null != cycleCap ? cycleCap : DEFAULT_CYCLE_CAP;
	}

	public int effectiveNoProgressRepeats() {
		return null != noProgressRepeatsToStop ? noProgressRepeatsToStop : DEFAULT_NO_PROGRESS_REPEATS;
	}

	public int effectiveSoftAlertPercent() {
		return null != softAlertPercent ? softAlertPercent : DEFAULT_SOFT_ALERT_PERCENT;
	}


	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;

	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;

	@JsonProperty(CommonVariables.STATUS_FIELD)
	private BoardStatus status = BoardStatus.ACTIVE;

	/**
	 * Wired tracker repos, e.g. {@code github:owner/repo}. The
	 * coordinator's discovery scope; registration validates a task's
	 * externalRef against them when the list is non-empty.
	 */
	@JsonProperty
	private List<String> sources = new ArrayList<>();

	/**
	 * Where this board's documents are written: the uuid of an org-scoped {@code VcsRepository}.
	 *
	 * <p>A uuid rather than a URI string, and that is the whole point. ReARM already keeps one
	 * repository row per canonical URI, unique per org, and components point at it the same way.
	 * Holding the string here made the board a SECOND source of truth that had to be canonicalised
	 * at every comparison -- and made "the CLI must mirror the server's canonicaliser exactly" a
	 * requirement, since a publish was refused whenever the two spelled one repository differently.
	 *
	 * <p>With the row as the identity, equality is a uuid comparison: a remote written as ssh on
	 * one machine and https on another resolves to the same row, and a repository that later
	 * changes host or shape keeps its identity rather than silently becoming a different one. The
	 * board mutation still ACCEPTS a URI and resolves it once, at save.
	 */
	@JsonProperty
	private UUID documentsRepo;

	/**
	 * Where each document type lands inside {@link #documentsRepo}, as path templates.
	 *
	 * <p>Placeholders: {@code {key}} (the task's key, RD-42), {@code {round}}, {@code {type}} (lower
	 * case), {@code {component}} (slugged component name). {@code {task}} is gone
	 * (board-documents.md D11): a stored template still carrying it reads as {@code {key}}
	 * ({@link #normaliseDocumentPaths}). The board's documents root goes in front of whatever a
	 * template resolves to ({@link #documentsRoot}). Empty means the
	 * defaults by scope apply ({@link #documentPathTemplate}); a board overrides only the types it
	 * cares about.
	 *
	 * <p>The template is resolved by the server on request and recorded as used: the release
	 * records the path that was actually committed, so a template change never rewrites history.
	 */
	@JsonProperty
	private Map<RearmSpecificationType, String> documentPaths = new LinkedHashMap<>();

	/**
	 * The prefix of the board's task keys, {@code RD} in {@code RD-42} (board-documents.md D8):
	 * upper-case letters and digits, 2 to 8, claimed in the organization's registry. Derived from
	 * the board name when not given.
	 */
	@JsonProperty
	private String taskPrefix;

	/** Every prefix the board has held, oldest first; old keys keep theirs (D10). */
	@JsonProperty
	private List<String> taskPrefixHistory = new ArrayList<>();

	/** Whether a group takes new tasks (task-groups-and-tags.md D6); done is derived, never set. */
	public enum GroupStatus { OPEN, CLOSED }

	/**
	 * A batch of the board's tasks (task-groups-and-tags.md §2.1, task RD2-29): a key people type,
	 * a display order, the groups it waits on, the level its tasks default to, and whether it takes
	 * new tasks. Edited only under the board's lock.
	 */
	public static record TaskGroup(UUID uuid, String key, String name, String description, int order,
			List<UUID> dependsOn, Integer defaultWorkLevel, GroupStatus status, ZonedDateTime createdAt)
			implements Serializable {

		public TaskGroup {
			if (null == dependsOn) dependsOn = List.of();
			if (null == status) status = GroupStatus.OPEN;
		}
	}

	/** The board's groups, in no particular order; {@code order} is the display order. */
	@JsonProperty
	private List<TaskGroup> groups = new ArrayList<>();

	/** The group of this board with this key, if any. */
	public java.util.Optional<TaskGroup> groupByKey(String key) {
		if (null == key || null == groups) return java.util.Optional.empty();
		return groups.stream().filter(g -> key.equals(g.key())).findFirst();
	}

	/** The group of this board with this uuid, if any. */
	public java.util.Optional<TaskGroup> groupByUuid(UUID uuid) {
		if (null == uuid || null == groups) return java.util.Optional.empty();
		return groups.stream().filter(g -> uuid.equals(g.uuid())).findFirst();
	}

	/** The last task number stamped; the next task gets one more. */
	@JsonProperty
	private int nextTaskNumber = 0;

	/**
	 * Whether every task of the board has a number. A board from before keys has not: its first
	 * read numbers its tasks in registration order (D14) and sets this.
	 */
	@JsonProperty
	private boolean tasksNumbered = false;

	/**
	 * The perspectives the board hangs off (board-permissions.md D7): real perspectives, or PRODUCT
	 * components used as perspectives. A PERSPECTIVE grant on any of them covers the board. Read by
	 * the coverage walk; setting it, with the membership and consent rules, is the perspectives
	 * child's (D8, D9). Empty covers the board from organization scope only (D11).
	 */
	@JsonProperty
	private List<UUID> perspectives = new ArrayList<>();

	/**
	 * The board's document components, one per specification (board-documents.md D3). Written when
	 * the board creates a document component, and once when it adopts a component it already had
	 * from before this map existed; resolved through it ever after, so a second board on the same
	 * target, or on the same documents repository, never reads this board's series.
	 *
	 * <p>State, not configuration: the board file and export do not carry it.
	 */
	@JsonProperty
	private Map<RearmSpecificationType, UUID> documentComponents = new LinkedHashMap<>();

	/**
	 * Whether a specification the map has no entry for may adopt a legacy component of the target
	 * (board-documents.md D3, task RD2-33). Every board created since writes false: it has no legacy
	 * to adopt, and its first publish of each specification creates its own component. Absent -- a
	 * board from before -- reads true. State, not configuration: never in the file or the export.
	 */
	@JsonProperty
	private Boolean adoptsLegacyDocuments;

	/** True for a board without the field: one created before the flag existed. */
	public Boolean getAdoptsLegacyDocuments() {
		return !Boolean.FALSE.equals(adoptsLegacyDocuments);
	}

	/**
	 * The board file's {@code documents} block (board-documents.md D2, D6). Null is the defaults:
	 * the document components are named after the board, and its documents sit at the repository root.
	 */
	@JsonProperty
	private DocumentsConfig documents;

	/**
	 * How the board names and places its documents. {@code prefix} replaces the board name in the
	 * document components' names ({@code <prefix>-<specification>}), both slugged. {@code shared}
	 * marks a documents repository serving several boards, whose default root is
	 * {@code boards/{board}/}; {@code root} sets the root outright, empty for the repository root.
	 */
	public static record DocumentsConfig(String prefix, Boolean shared, String root) implements Serializable {
		/** Nothing set: no block at all. */
		public boolean isEmpty() {
			return null == prefix && null == shared && null == root;
		}
	}

	/** What a board's document component names start with: the prefix, else the board name, slugged. */
	public String documentComponentStem() {
		String stem = null != documents && null != documents.prefix() ? documents.prefix() : name;
		return slug(stem);
	}

	/** The name this board gives its document component for a specification: {@code <stem>-<specification>}. */
	public String documentComponentName(RearmSpecificationType spec) {
		return documentComponentStem() + "-" + slug(spec.name());
	}

	/** Lower case, each run of anything but {@code [a-z0-9]} a single dash, no dash at either end. */
	public static String slug(String s) {
		if (null == s) return "";
		return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
	}

	/**
	 * Defaults for the built-in index types, overridable per board. They are always task-scoped:
	 * one task's rounds sit together.
	 */
	public static final Map<RearmSpecificationType, String> DEFAULT_DOCUMENT_PATHS = Map.of(
			RearmSpecificationType.BOARD_REVIEW_ITEMS, "review-items/{key}/round-{round}.md",
			RearmSpecificationType.BOARD_TEST_REPORT, "tests/{key}/run-{round}.md",
			RearmSpecificationType.BOARD_QUESTIONS, "questions/{key}/round-{round}.md");

	/**
	 * The per-type names the boards have used, for a type produced at TASK scope (board-documents.md
	 * §3): consulted at TASK scope only, so they do not make a type task-scoped as the index types are.
	 */
	public static final Map<RearmSpecificationType, String> WELL_KNOWN_TASK_PATHS = Map.of(
			RearmSpecificationType.ARCHITECTURE, "design/{key}/architecture-{round}.md",
			RearmSpecificationType.DETAILED_DESIGN, "impl/{key}/notes-{round}.md",
			RearmSpecificationType.BOARD_INVESTIGATION_REPORT, "investigations/{key}/report-{round}.md");

	/** Default for any other type a role on the board produces at TASK scope: one file per round. */
	public static final String DEFAULT_TASK_DOCUMENT_PATH = "design/{key}/{type}-{round}.md";

	/** Default for a type at COMPONENT scope: one file per document series. */
	public static final String DEFAULT_COMPONENT_DOCUMENT_PATH = "docs/{type}/{component}.md";

	/** The built-in index types, which are task-scoped whatever the roles declare. */
	public static boolean isIndexType(RearmSpecificationType spec) {
		return DEFAULT_DOCUMENT_PATHS.containsKey(spec);
	}

	/**
	 * The template for a type at the scope the board produces it at: the board's override, else
	 * the index type's default, else the default for the scope (gaps §1.18). Before, every type but
	 * the two index kinds fell back to the component file, so a type produced per task shared one
	 * file across every task unless the operator wrote a {@code {task}} template.
	 */
	public String documentPathTemplate(RearmSpecificationType spec, InputScope scope) {
		if (null != documentPaths && documentPaths.containsKey(spec)) return documentPaths.get(spec);
		if (isIndexType(spec)) return DEFAULT_DOCUMENT_PATHS.get(spec);
		if (InputScope.TASK != scope) return DEFAULT_COMPONENT_DOCUMENT_PATH;
		return WELL_KNOWN_TASK_PATHS.getOrDefault(spec, DEFAULT_TASK_DOCUMENT_PATH);
	}

	/**
	 * Templates with {@code {task}} rewritten to {@code {key}} (board-documents.md D11): applied when
	 * a board is loaded and wherever templates come in, so nothing downstream ever sees {@code {task}}.
	 * A stored row is rewritten on its next save, never by a migration (D14). Null stays null.
	 */
	public static Map<RearmSpecificationType, String> normaliseDocumentPaths(Map<RearmSpecificationType, String> paths) {
		if (null == paths) return null;
		Map<RearmSpecificationType, String> out = new LinkedHashMap<>();
		paths.forEach((k, v) -> out.put(k, null == v ? null : v.replace("{task}", "{key}")));
		return out;
	}

	/**
	 * Where this board's documents sit in the repository (board-documents.md D6): the root it sets;
	 * else {@code boards/<slug of the board name>/} when the repository is shared; else nothing. A
	 * non-empty root ends with one slash; an explicit empty root is the repository's own root.
	 */
	public String documentsRoot() {
		String root = null != documents && null != documents.root() ? documents.root()
				: null != documents && Boolean.TRUE.equals(documents.shared()) ? "boards/{board}/" : "";
		root = root.replace("{board}", slug(name));
		root = root.replaceAll("^/+", "");
		if (!root.isEmpty() && !root.endsWith("/")) root += "/";
		return root;
	}

	/**
	 * Served prompt of the implicit coordinator role. The coordinator
	 * is structural — always present, never a role-config row, never
	 * assignable as a task role.
	 */
	@JsonProperty
	private String coordinatorPrompt;

	/**
	 * Verbs the coordinator seat performs itself on this board (gaps §1.20) -- in practice
	 * PR_MERGE, occasionally CODE_PUSH on a docs-only board. The tracker verbs are implicit and
	 * never listed. An unverified declaration, like a role's capabilities: its one consumer is the
	 * delivery-loop alert, which no longer warns about a verb the coordinator carries.
	 */
	@JsonProperty
	private List<AgentTaskRoleConfigData.AgentCapability> coordinatorCapabilities = new ArrayList<>();

	@JsonProperty
	private BoardPause pause;

	@JsonProperty
	private CoordinatorSeat coordinatorSeat;

	/** Max concurrently ASSIGNED tasks per agent on this board. */
	@JsonProperty
	private int perAgentWipLimit = DEFAULT_PER_AGENT_WIP_LIMIT;

	/** Whether coordinator priority is advisory (LAX) or enforced at assignment (STRICT). */
	@JsonProperty
	private PriorityType priorityType = PriorityType.LAX;

	/**
	 * The component this board builds -- the node of the product tree the work belongs to.
	 * Required on creation; null only on boards that predate the field. Documents and
	 * implementation components hang off this node as dependencies of its feature set, so
	 * the target is what a document requirement resolves against.
	 *
	 * <p>Not restricted to PRODUCT: a board may build a leaf component. It must be active
	 * and belong to the board's own organization.
	 */
	@JsonProperty
	private UUID target;

	/**
	 * Level applied to tasks of this board that do not declare one. The level of a node is
	 * its depth in the product tree, and a board normally works one node, so the default is
	 * usually the whole story; a task overrides it when a board legitimately spans levels --
	 * the decomposition work that defines level n+1 while sitting on the level-n board.
	 */
	@JsonProperty
	private Integer defaultWorkLevel;

	/** Resolution mode applied to requirements of this board that do not name one. */
	@JsonProperty
	private InputResolution defaultInputResolution = InputResolution.LATEST_PASSING;

	@JsonIgnore
	public boolean isPaused() {
		return pause != null && pause.level() != null && pause.level() != BoardPauseLevel.NONE;
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static AgentBoardData dataFromRecord(AgentBoard b) {
		if (b.getSchemaVersion() != 0) {
			throw new IllegalStateException("AgentBoard schema version is " + b.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = b.getRecordData();
		AgentBoardData bd = Utils.OM.convertValue(recordData, AgentBoardData.class);
		// No loaded board carries {task} (board-documents.md D11); the row is rewritten on its next save.
		bd.setDocumentPaths(normaliseDocumentPaths(bd.getDocumentPaths()));
		bd.setUuid(b.getUuid());
		bd.setCreatedDate(b.getCreatedDate());
		return bd;
	}
}
