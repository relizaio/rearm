/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.StrengthScale;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ComponentData;
import io.reliza.model.AgentTaskData.QuestionFrame;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskAssignment;
import io.reliza.model.AgentTaskData.TaskHold;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.service.ComponentLockService.LockedOperation;
import io.reliza.model.tracker.TrackerRef;
import io.reliza.model.tracker.TrackerSource;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentTaskRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Hub-and-spoke task lifecycle. The board's coordinator is the hub:
 * it authorizes and orders tasks, and every sign-off or return
 * redirects the task back to it for the next-hop decision. The
 * server enforces legality (locks, WIP limits, separation of duties,
 * session binding); routing judgment stays with the coordinator
 * agent. Full design: backend/ai-plans/agentic/task-boards.md.
 */
@Slf4j
@Service
public class AgentTaskService {

	@Autowired @org.springframework.context.annotation.Lazy
	private AgentBoardNotifier agentBoardNotifier;

	@Autowired private AgentTaskInputService agentTaskInputService;

	/** Reads a PR no CI reported here from its tracker (task RD3-20); absent in CE. */
	@Autowired(required = false) private PullRequestTrackerHook pullRequestTrackerHook;

	@Autowired private GetComponentService getComponentService;

	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private BranchService branchService;
	@Autowired private ComponentLockService componentLockService;

	@Autowired
	private AgentTaskRepository repository;

	/**
	 * Lazy: the usage service reads tasks to attribute reports, and this service writes them.
	 */
	@Lazy
	@Autowired
	private AgentSessionUsageService usageService;

	@Autowired
	private AgentBoardService agentBoardService;

	@Autowired @Lazy
	private AgentRoutingService routingService;

	// @Lazy: the document service autowires this one, so the pair would not construct otherwise.
	@Autowired @Lazy
	private AgentDocumentService agentDocumentService;

	@Autowired
	private AgentRoleHistoryService roleHistoryService;

	@Autowired @Lazy
	private AgentSessionService agentSessionService;

	@Autowired @Lazy
	private AgentBudgetService budgetService;

	@Autowired
	private ModelOntologyService modelOntologyService;

	@Autowired
	private BoardEffectsApplier boardEffectsApplier;

	@Autowired
	@org.springframework.context.annotation.Lazy
	private AgentDeliveryService agentDeliveryService;

	@Autowired
	private AuditService auditService;

	/**
	 * What a polling or assigned worker receives: the task, its role and the served prompt.
	 *
	 * @param hopBudgetMicros the role's allowance for this hop, so the worker knows what the hop is
	 *        expected to cost before it starts; null when the role sets none
	 */
	public record WorkerAssignment(AgentTaskData task, String role, UUID roleUuid, String rolePrompt,
			String promptVersion, List<ResolvedInput> resolvedInputs, Long hopBudgetMicros) {

		public WorkerAssignment(AgentTaskData task, String role, UUID roleUuid, String rolePrompt,
				String promptVersion) {
			this(task, role, roleUuid, rolePrompt, promptVersion, List.of(), null);
		}

		public WorkerAssignment(AgentTaskData task, String role, UUID roleUuid, String rolePrompt,
				String promptVersion, List<ResolvedInput> resolvedInputs) {
			this(task, role, roleUuid, rolePrompt, promptVersion, resolvedInputs, null);
		}
	}

	// ---------- Intake ----------

	/**
	 * Register a task for a tracker item — open to any org agent,
	 * idempotent on {@code (org, externalRef)}, so tracker re-scans
	 * are harmless. Lands PENDING_INTAKE; authorization is the
	 * coordinator's judgment. When the board declares sources, the
	 * externalRef must match one of them.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData register(AgentBoardData board, String externalRef, String title,
			String sourceUrl, UUID registeredBySession, UUID parentTask, UUID producesComponent,
			Integer level, List<RequiredInput> requiredInputs, WhoUpdated wu)
			throws RelizaException {
		return register(board, externalRef, title, sourceUrl, registeredBySession, parentTask,
				producesComponent, level, requiredInputs,
				null != registeredBySession ? AgentActor.ofSession(registeredBySession) : null, false, wu);
	}

	/**
	 * As above, naming who registered it. {@code requireRefOnSourcedBoard} is a person's rule
	 * (operator-actions D2): on a board with sources, the tracker is where work comes from, so a
	 * person names the issue as the coordinator would; the coordinator's intake keeps its own rule.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData register(AgentBoardData board, String externalRef, String title,
			String sourceUrl, UUID registeredBySession, UUID parentTask, UUID producesComponent,
			Integer level, List<RequiredInput> requiredInputs, AgentActor actor,
			boolean requireRefOnSourcedBoard, WhoUpdated wu) throws RelizaException {
		return register(board, externalRef, title, null, sourceUrl, registeredBySession, parentTask,
				producesComponent, level, requiredInputs, actor, requireRefOnSourcedBoard, wu);
	}

	/** A title is what a card, an event and a PR title show: one line (board-documents.md §4.5). */
	public static final int TITLE_MAX = 120;
	/** The rest goes in the description, kept whole up to this, so events and exports stay a sane size. */
	public static final int DESCRIPTION_MAX = 4000;

	/**
	 * A new task's title, trimmed, and its description, checked (task fceb1e57): a title of at most
	 * {@link #TITLE_MAX} characters on one line, a description of at most {@link #DESCRIPTION_MAX}.
	 * Only at creation: a task registered before the cap keeps its title (D14).
	 */
	static String checkedTitle(String title, String description) throws RelizaException {
		String t = null == title ? "" : title.strip();
		if (t.isEmpty()) throw new RelizaException("Task requires a title");
		if (t.contains("\n") || t.contains("\r")) {
			throw new RelizaException("A title is one line; put the rest in --description");
		}
		if (t.length() > TITLE_MAX) {
			throw new RelizaException("Titles are at most " + TITLE_MAX + " characters (this one is " + t.length()
					+ "); put the rest in --description");
		}
		if (null != description && description.length() > DESCRIPTION_MAX) {
			throw new RelizaException("A description is at most " + DESCRIPTION_MAX + " characters (this one is "
					+ description.length() + ")");
		}
		return t;
	}

	/** As above, with a description. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData register(AgentBoardData board, String externalRef, String title, String description,
			String sourceUrl, UUID registeredBySession, UUID parentTask, UUID producesComponent,
			Integer level, List<RequiredInput> requiredInputs, AgentActor actor,
			boolean requireRefOnSourcedBoard, WhoUpdated wu) throws RelizaException {
		return register(board, externalRef, title, description, sourceUrl, registeredBySession, parentTask,
				producesComponent, level, requiredInputs, actor, requireRefOnSourcedBoard, null, null, wu);
	}

	/**
	 * As above, into a group and with tags (task-groups-and-tags.md §2.2, §3, task RD2-29): the group by
	 * its key, refused when the board has none by that key or it is CLOSED; the new task's orderIndex
	 * after the group's last task (D5), which the coordinator's authorize or order overrides.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData register(AgentBoardData board, String externalRef, String title, String description,
			String sourceUrl, UUID registeredBySession, UUID parentTask, UUID producesComponent,
			Integer level, List<RequiredInput> requiredInputs, AgentActor actor,
			boolean requireRefOnSourcedBoard, String groupKey, List<CommonVariables.TagRecord> tags,
			WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(title)) throw new RelizaException("Task requires a title");
		if (requireRefOnSourcedBoard && null != board.getSources() && !board.getSources().isEmpty()
				&& StringUtils.isBlank(externalRef)) {
			throw new RelizaException("Board " + board.getName() + " takes its work from "
					+ String.join(", ", board.getSources()) + ": name the tracker issue (externalRef)"
					+ " this task is for");
		}
		if (StringUtils.isNotBlank(externalRef)) {
			// Parse before anything else: the stored value is the parser's rendering, not the text
			// as sent, which is what makes the (org, externalRef) key one row per issue rather
			// than one per spelling. github:Acme/Widget#042 and github:acme/widget#42 are one issue.
			externalRef = TrackerRef.parse(externalRef).canonical();
			validateRefAgainstSources(board, externalRef);
			Optional<AgentTask> existing = repository.findByOrgAndExternalRef(
					board.getOrg().toString(), externalRef);
			if (existing.isPresent()) return AgentTaskData.dataFromRecord(existing.get());
		}
		// After the lookup above: re-registering an issue from before the cap returns its task as it
		// was, whatever its title; only a task being created is held to the rule.
		title = checkedTitle(title, description);
		AgentTaskData td = new AgentTaskData();
		td.setOrg(board.getOrg());
		td.setBoard(board.getUuid());
		td.setExternalRef(StringUtils.isNotBlank(externalRef) ? externalRef : null);
		td.setTitle(title);
		td.setDescription(StringUtils.isBlank(description) ? null : description);
		td.setSourceUrl(sourceUrl);
		td.setParentTask(parentTask);
		td.setRegisteredBySession(registeredBySession);
		if (producesComponent != null) {
			validateProduces(producesComponent, board.getOrg());
			td.setProducesComponent(producesComponent);
		}
		// The declared level only: the board default is resolved on read (effectiveWorkLevel), never
		// written onto the task, so changing the default moves every unset task (RD2-1).
		td.setWorkLevel(checkedWorkLevel(board.getLadder(), level));
		if (StringUtils.isNotBlank(groupKey)) {
			AgentBoardData.TaskGroup g = groupToJoin(board, groupKey);
			td.setGroup(g.uuid());
			td.setOrderIndex(nextOrderInGroup(board.getUuid(), g.uuid()));
		}
		td.setTags(checkedTags(tags));
		if (requiredInputs != null && !requiredInputs.isEmpty()) td.setRequiredInputs(new ArrayList<>(requiredInputs));
		td.getStatusHistory().add(new AgentTaskData.StatusChange(null, TaskStatus.PENDING_INTAKE,
				ZonedDateTime.now(), StatusTrigger.REGISTER, actor));
		stampKey(td, board.getUuid(), wu);
		return saveData(td, wu);
	}

	// ---------- Investigations (task RD4-12) ----------

	/**
	 * What a commission asks for (task RD4-12).
	 *
	 * @param role the investigating role, by name: it must produce BOARD_INVESTIGATION_REPORT at TASK scope
	 * @param brief what to investigate: the task's description
	 * @param fromTask the task it is commissioned from, which the report returns to; required of a session
	 * @param inputs releases the investigation reads, pinned as its required inputs
	 * @param budgetMicros what it may spend; null takes the commissioning role's default
	 * @param deadline when the report is due; null sets none
	 * @param review the role that reviews the report; null takes the commissioning role's default
	 * @param returnTo TASK or NONE; null is TASK with a from-task, NONE without
	 */
	public record CommissionRequest(String role, String title, String brief, UUID fromTask, List<UUID> inputs,
			Long budgetMicros, ZonedDateTime deadline, String review, AgentTaskData.ReturnTo returnTo, Integer workLevel,
			String group, List<CommonVariables.TagRecord> tags) {}

	/**
	 * Commission an investigation (task RD4-12): a new INVESTIGATION task for a role that produces
	 * BOARD_INVESTIGATION_REPORT, with the brief as its description and the inputs pinned.
	 *
	 * <p>Two callers. A session commissions from the task it holds, in that task's role, and only a role that
	 * role's {@code commissions} names; the role's intake decides whether it is queued at once (AUTO) or waits for
	 * the coordinator (COORDINATOR). A person (the fetcher has checked BOARD_WRITE) may commission any role that
	 * produces the report, from a task or from none, and it is queued at once. Everything is checked before
	 * anything is written: a refused commission leaves the board as it was.
	 *
	 * @param sessionUuid the commissioning session; null for a person
	 * @param actor the session, or the person
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData commission(AgentBoardData board, CommissionRequest req, UUID sessionUuid, AgentActor actor,
			WhoUpdated wu) throws RelizaException {
		if (null == req || StringUtils.isBlank(req.role())) throw new RelizaException("A commission names the role to investigate");
		if (null == actor) throw new RelizaException("A commission needs who commissions it");
		if (board.getStatus() != AgentBoardData.BoardStatus.ACTIVE) {
			throw new RelizaException("Board " + board.getName() + " is " + board.getStatus() + "; it takes no new work");
		}
		AgentTaskData from = null;
		if (null != req.fromTask()) {
			from = getTaskData(req.fromTask()).orElseThrow(() -> new RelizaException("Task not found: " + req.fromTask()));
			if (!board.getUuid().equals(from.getBoard())) {
				throw new RelizaException("Task " + from.label() + " is on another board; commission from a task on "
						+ board.getName());
			}
		}
		AgentTaskRoleConfigData commissioner = null;
		if (null != sessionUuid) {
			if (null == from) {
				throw new RelizaException("A session commissions from the task it holds: name it with --from-task");
			}
			if (TaskStatus.ASSIGNED != from.getStatus() || null == from.getAssignment()
					|| !sessionUuid.equals(from.getAssignment().session())) {
				throw new RelizaException("This session does not hold " + from.label()
						+ "; a session commissions only from the task it is working");
			}
			final AgentTaskData held = from;
			commissioner = agentBoardService.getRoleConfig(board.getUuid(), held.getAssignment().role())
					.orElseThrow(() -> new RelizaException("Role " + held.getAssignment().role()
							+ " is no longer configured on board " + board.getName()));
			AgentTaskRoleConfigData.Commissions c = commissioner.getCommissions();
			if (null == c || !c.allows(req.role())) {
				throw new RelizaException("Role " + commissioner.getName() + " may not commission " + req.role().strip()
						+ (null == c ? ": it commissions nobody on this board"
								: ": its commissions name " + String.join(", ", c.roles())));
			}
		}
		AgentTaskRoleConfigData investigator = agentBoardService.getRoleConfig(board.getUuid(), req.role().strip())
				.orElseThrow(() -> new RelizaException("Role " + req.role().strip() + " is not configured on board "
						+ board.getName()));
		if (!investigator.isActive()) throw new RelizaException("Role " + investigator.getName() + " is inactive");
		if (AgentTaskRoleConfigData.RoleKind.HUMAN == investigator.getKind()) {
			throw new RelizaException("Role " + investigator.getName() + " is a HUMAN stage; an investigation is an agent's");
		}
		if (!investigator.producesInvestigationReport()) {
			throw new RelizaException("Role " + investigator.getName() + " does not produce "
					+ RearmSpecificationType.BOARD_INVESTIGATION_REPORT + " at TASK scope, so it cannot be asked for one");
		}
		AgentTaskRoleConfigData.Commissions c = null == commissioner ? null : commissioner.getCommissions();
		String reviewName = StringUtils.isNotBlank(req.review()) ? req.review().strip()
				: null != c && StringUtils.isNotBlank(c.review()) ? c.review() : null;
		AgentTaskRoleConfigData reviewer = null;
		if (null != reviewName) {
			reviewer = agentBoardService.getRoleConfig(board.getUuid(), reviewName)
					.orElseThrow(() -> new RelizaException("Review role " + reviewName + " is not configured on board "
							+ board.getName()));
			if (!reviewer.isActive()) throw new RelizaException("Review role " + reviewer.getName() + " is inactive");
			if (reviewer.getUuid().equals(investigator.getUuid())) {
				throw new RelizaException("Role " + investigator.getName() + " cannot review its own report");
			}
		}
		AgentTaskData.ReturnTo returnTo = null != req.returnTo() ? req.returnTo()
				: null != from ? AgentTaskData.ReturnTo.TASK : AgentTaskData.ReturnTo.NONE;
		if (AgentTaskData.ReturnTo.TASK == returnTo && null == from) {
			throw new RelizaException("returnTo TASK needs the task the report returns to (fromTask)");
		}
		boolean defaulted = null == req.budgetMicros();
		Long budget = defaulted && null != c ? c.defaultBudgetMicros() : req.budgetMicros();
		if (null != budget && budget < 0) throw new RelizaException("A task budget cannot be negative");
		// Capped by the board's (design §3.3): an explicit ask over it is refused, a role's default is cut to fit.
		if (null != budget && null != board.getBudgetMicros() && budget > board.getBudgetMicros()) {
			if (!defaulted) {
				throw new RelizaException("A budget of " + usd(budget) + " is over the board's " + usd(board.getBudgetMicros()));
			}
			budget = board.getBudgetMicros();
		}
		if (null != req.deadline() && !req.deadline().isAfter(ZonedDateTime.now())) {
			throw new RelizaException("The deadline " + req.deadline() + " has passed; name one in the future");
		}
		List<RequiredInput> pins = new ArrayList<>();
		for (UUID r : null == req.inputs() ? List.<UUID>of() : req.inputs()) {
			if (null == r) continue;
			ReleaseData rd = sharedReleaseService.getReleaseData(r)
					.orElseThrow(() -> new RelizaException("Input release not found: " + r));
			if (!board.getOrg().equals(rd.getOrg())) throw new RelizaException("Input release " + r + " is in another organization");
			RequiredInput pin = null != rd.getDocument()
					? RequiredInput.pinned(InputKind.DOCUMENT, rd.getDocument().specification(), r)
					: RequiredInput.pinned(InputKind.RELEASE, null, r);
			if (pins.stream().noneMatch(p -> p.mergeKey().equals(pin.mergeKey()))) pins.add(pin);
		}
		AgentTaskData.CommissionedBy by = new AgentTaskData.CommissionedBy(
				null != commissioner ? commissioner.getName() : null != from ? from.getRole() : null,
				null != commissioner ? commissioner.getUuid() : null != from ? from.getRoleUuid() : null,
				sessionUuid, null == from ? null : from.getUuid(), actor);
		AgentTaskData td = register(board, null, req.title(), req.brief(), null, sessionUuid, null, null, req.workLevel(),
				pins, actor, false, req.group(), req.tags(), wu);
		td = lockedTask(td.getUuid());
		td.setKind(AgentTaskData.TaskKind.INVESTIGATION);
		td.setInvestigation(new AgentTaskData.Investigation(by, RearmSpecificationType.BOARD_INVESTIGATION_REPORT,
				investigator.getName(), investigator.getUuid(), null == reviewer ? null : reviewer.getName(),
				null == reviewer ? null : reviewer.getUuid(), req.deadline(), returnTo, null, null));
		if (null != budget) {
			td.setBudgetMicros(budget);
			td.setBudgetSetBy(actor);
			td.setBudgetSetAt(ZonedDateTime.now());
		}
		td = saveData(td, wu);
		boolean auto = null == c || AgentTaskRoleConfigData.CommissionIntake.AUTO == c.effectiveIntake();
		if (auto) {
			td = authorize(td.getUuid(), board, investigator.getName(), null, null, null, null, null, actor, wu);
		}
		String who = null != commissioner ? commissioner.getName() : actor.display();
		BoardEffects effects = new BoardEffects(board.getUuid());
		effects.info("Investigation " + td.label() + " commissioned by " + who
				+ (null == from ? "" : " from " + from.label()) + " for " + investigator.getName()
				+ (null == reviewer ? "" : ", reviewed by " + reviewer.getName())
				+ (auto ? "; queued" : "; waiting for the coordinator's intake"));
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return td;
	}

	/**
	 * The report an investigation completed with, or would: its newest live BOARD_INVESTIGATION_REPORT round.
	 */
	public Optional<ReleaseData> investigationReport(AgentTaskData td) {
		List<UUID> linked = null == td.getReleases() ? List.of() : td.getReleases();
		for (int i = linked.size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(linked.get(i));
			if (ord.isEmpty()) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null == doc || RearmSpecificationType.BOARD_INVESTIGATION_REPORT != doc.specification()
					|| !td.getUuid().equals(doc.task()) || doc.superseded()) continue;
			if (ReleaseLifecycle.CANCELLED == ord.get().getLifecycle() || ReleaseLifecycle.REJECTED == ord.get().getLifecycle()) continue;
			return ord;
		}
		return Optional.empty();
	}

	/**
	 * Complete an investigation in place (task RD4-12): no delivery step, since it links no PRs. Stamps the
	 * report on its block and collects the return, which runs once this commits: the commissioning task is the
	 * other row, and locking it here would hold two task rows at once.
	 */
	void completeInvestigation(AgentTaskData td, StatusTrigger trigger, AgentActor actor, String note,
			BoardEffects effects) {
		ZonedDateTime now = ZonedDateTime.now();
		td.transitionStatus(TaskStatus.COMPLETED, trigger, actor, note);
		td.setCompletedAt(now);
		td.setAssignment(null);
		UUID report = investigationReport(td).map(ReleaseData::getUuid).orElse(null);
		td.setInvestigation(td.getInvestigation().withReport(report, now));
		if (null == report) {
			effects.info("Investigation " + td.label() + " completed without a report; nothing returns");
			return;
		}
		effects.returnReport(td.getUuid());
	}

	/**
	 * Bring a completed investigation's report back (task RD4-12, design §3.4): pinned as an input on the
	 * commissioning task, with an INFO naming both tasks; with returnTo TASK the commissioning task is offered back
	 * to its role as an answered question is -- queued for it again when it waits (QUEUED or with the coordinator),
	 * and when that role still holds it, the holder has the report among its inputs. A task in any other state
	 * gets the pin and the INFO only. "That role" is the role, not the session (design round 2 §1): any session of
	 * the commissioning role holding the task has the report bound on its hop; the commissioning session is only
	 * preferred by the poll. A cancelled investigation returns too, with no report ({@link #returnCancelled}).
	 * Idempotent: a report already returned changes nothing.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
			rollbackFor = RelizaException.class)
	public void returnInvestigation(UUID investigationUuid, WhoUpdated wu) throws RelizaException {
		AgentTaskData inv = getTaskData(investigationUuid).orElse(null);
		if (null == inv || !inv.isInvestigation()) return;
		if (TaskStatus.CANCELLED == inv.getStatus()) {
			returnCancelled(inv, wu);
			return;
		}
		if (TaskStatus.COMPLETED != inv.getStatus() || null == inv.getInvestigation().report()) return;
		AgentTaskData.Investigation block = inv.getInvestigation();
		AgentTaskData.CommissionedBy cb = block.commissionedBy();
		UUID report = block.report();
		String reportName = sharedReleaseService.getReleaseData(report).map(rd -> null == rd.getDocument()
				|| null == rd.getDocument().round() ? "its report" : "its report (round " + rd.getDocument().round() + ")")
				.orElse("its report");
		BoardEffects effects = new BoardEffects(inv.getBoard());
		if (null == cb || null == cb.task()) {
			effects.info("Investigation " + inv.label() + " completed: " + reportName + " is release " + report);
			boardEffectsApplier.applyAfterCommit(effects, wu);
			return;
		}
		AgentTaskData td = lockedTask(cb.task());
		if (td.getReportsReturned().stream().anyMatch(r -> investigationUuid.equals(r.investigation()))) return;
		List<RequiredInput> required = new ArrayList<>(null == td.getRequiredInputs() ? List.of() : td.getRequiredInputs());
		RequiredInput pin = RequiredInput.pinned(InputKind.DOCUMENT, RearmSpecificationType.BOARD_INVESTIGATION_REPORT, report);
		if (required.stream().noneMatch(ri -> ri.mergeKey().equals(pin.mergeKey()))) required.add(pin);
		td.setRequiredInputs(required);
		String where;
		boolean reoffered = false;
		if (AgentTaskData.ReturnTo.TASK != block.returnTo()) {
			where = "pinned on " + td.label();
		} else {
			AgentTaskRoleConfigData role = commissioningRole(td, cb);
			TaskAssignment a = td.getAssignment();
			if (TaskStatus.ASSIGNED == td.getStatus() && null != a) {
				if (heldByCommissioningRole(a, role, cb)) {
					ReleaseData rd = sharedReleaseService.getReleaseData(report).orElse(null);
					if (null != rd) {
						td.setAssignment(a.withInput(new ResolvedInput(InputKind.DOCUMENT,
								RearmSpecificationType.BOARD_INVESTIGATION_REPORT, rd.getComponent(), rd.getUuid(), rd.getVersion(),
								rd.getLifecycle())));
					}
					where = "pinned on " + td.label() + ", whose " + a.role() + " hop (session "
							+ a.session().toString().substring(0, 8) + ") has it among its inputs now";
				} else {
					where = "pinned on " + td.label() + ", which the " + a.role() + " role is working; not offered back";
				}
			} else if (null != role && role.isActive()
					&& (TaskStatus.QUEUED == td.getStatus() || TaskStatus.AWAITING_COORDINATOR == td.getStatus())) {
				boolean already = TaskStatus.QUEUED == td.getStatus() && role.getUuid().equals(td.getRoleUuid());
				td.setRole(role.getName());
				td.setRoleUuid(role.getUuid());
				td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, AgentActor.system("investigation"),
						"report back from " + inv.keyOrUuid() + "; to " + role.getName());
				reoffered = true;
				where = "pinned on " + td.label() + (already ? ", queued for " : ", back to ") + role.getName();
			} else {
				where = "pinned on " + td.label() + ", which is " + td.getStatus().name().toLowerCase(java.util.Locale.ROOT)
						.replace('_', ' ') + "; not offered back"
						+ (null != role && !role.isActive() ? " (role " + role.getName() + " is inactive)" : "");
			}
		}
		List<AgentTaskData.ReturnedReport> returned = new ArrayList<>(td.getReportsReturned());
		returned.add(new AgentTaskData.ReturnedReport(investigationUuid, inv.getKey(), report, cb.session(), cb.role(),
				ZonedDateTime.now(), reoffered));
		td.setReportsReturned(returned);
		saveData(td, wu);
		effects.info("Investigation " + inv.label() + " completed: " + reportName + " is " + where);
		boardEffectsApplier.applyAfterCommit(effects, wu);
	}

	/** The role an investigation was commissioned by, among the commissioning task's board roles; null if gone. */
	private AgentTaskRoleConfigData commissioningRole(AgentTaskData td, AgentTaskData.CommissionedBy cb) {
		if (null == cb.roleUuid() && null == cb.role()) return null;
		return agentBoardService.listRoleConfigs(td.getBoard()).stream()
				.filter(rc -> null != cb.roleUuid() ? cb.roleUuid().equals(rc.getUuid()) : rc.getName().equalsIgnoreCase(cb.role()))
				.findFirst().orElse(null);
	}

	/**
	 * Whether the hop holding the commissioning task is the commissioning role's (design round 2 §1): the role is the
	 * test, whichever of its sessions holds the task. Only without a role on record does the session decide.
	 */
	private static boolean heldByCommissioningRole(TaskAssignment a, AgentTaskRoleConfigData role,
			AgentTaskData.CommissionedBy cb) {
		if (null != role) {
			return null != a.roleUuid() ? role.getUuid().equals(a.roleUuid()) : role.getName().equalsIgnoreCase(a.role());
		}
		return null != cb.session() && cb.session().equals(a.session());
	}

	/**
	 * A cancelled investigation that reports back to a task returns with no report (design round 2 §2): the
	 * commissioning task stops waiting on it -- the investigation leaves its dependsOn, which the board added when
	 * the hop returned BLOCKED_ON_DEPENDENCY -- a reportsReturned row records the cancel and its note, and one INFO
	 * names both tasks. The task then moves as a completed investigation moves it: QUEUED or with the coordinator,
	 * queued for the commissioning role again; ASSIGNED, the INFO; any other state, the row and the INFO only. The
	 * asker decides what follows. No other task's dependsOn is touched: a dependency the coordinator set on a
	 * cancelled task stays unmet, for the coordinator to re-plan. Idempotent.
	 */
	private void returnCancelled(AgentTaskData inv, WhoUpdated wu) throws RelizaException {
		AgentTaskData.Investigation block = inv.getInvestigation();
		AgentTaskData.CommissionedBy cb = block.commissionedBy();
		if (null == cb || null == cb.task() || AgentTaskData.ReturnTo.TASK != block.returnTo()) return;
		AgentTaskData td = lockedTask(cb.task());
		if (td.getReportsReturned().stream().anyMatch(r -> inv.getUuid().equals(r.investigation()))) return;
		List<UUID> deps = new ArrayList<>(null == td.getDependsOn() ? List.of() : td.getDependsOn());
		deps.remove(inv.getUuid());
		td.setDependsOn(deps);
		AgentTaskRoleConfigData role = commissioningRole(td, cb);
		TaskAssignment a = td.getAssignment();
		String where;
		boolean reoffered = false;
		if (TaskStatus.ASSIGNED == td.getStatus() && null != a) {
			where = heldByCommissioningRole(a, role, cb)
					? "; its " + a.role() + " hop (session " + a.session().toString().substring(0, 8) + ") decides how to go on"
					: "; the " + a.role() + " role is working it";
		} else if (null != role && role.isActive()
				&& (TaskStatus.QUEUED == td.getStatus() || TaskStatus.AWAITING_COORDINATOR == td.getStatus())) {
			boolean already = TaskStatus.QUEUED == td.getStatus() && role.getUuid().equals(td.getRoleUuid());
			td.setRole(role.getName());
			td.setRoleUuid(role.getUuid());
			td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, AgentActor.system("investigation"),
					"investigation " + inv.keyOrUuid() + " cancelled, no report; to " + role.getName());
			reoffered = true;
			where = (already ? "; queued for " : "; back to ") + role.getName();
		} else {
			where = "; it is " + td.getStatus().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
					+ ", not offered back"
					+ (null != role && !role.isActive() ? " (role " + role.getName() + " is inactive)" : "");
		}
		List<AgentTaskData.ReturnedReport> returned = new ArrayList<>(td.getReportsReturned());
		returned.add(new AgentTaskData.ReturnedReport(inv.getUuid(), inv.getKey(), null, cb.session(), cb.role(),
				ZonedDateTime.now(), reoffered, true, cancelNote(inv)));
		td.setReportsReturned(returned);
		saveData(td, wu);
		BoardEffects effects = new BoardEffects(inv.getBoard());
		effects.info("Investigation " + inv.label() + " cancelled: no report; " + td.label() + " no longer waits on it"
				+ where);
		boardEffectsApplier.applyAfterCommit(effects, wu);
	}

	/** What the cancel of a task said: the note on its newest CANCELLED status row; null when it said nothing. */
	private static String cancelNote(AgentTaskData td) {
		List<AgentTaskData.StatusChange> history = null == td.getStatusHistory() ? List.of() : td.getStatusHistory();
		for (int i = history.size() - 1; i >= 0; i--) {
			if (TaskStatus.CANCELLED == history.get(i).to()) {
				return StringUtils.isBlank(history.get(i).note()) ? null : history.get(i).note().strip();
			}
		}
		return null;
	}

	/** Queue the return of a cancelled investigation that reports back to a task (design round 2 §2). */
	private static void returnIfInvestigation(AgentTaskData td, BoardEffects effects) {
		if (!td.isInvestigation() || AgentTaskData.ReturnTo.TASK != td.getInvestigation().returnTo()) return;
		AgentTaskData.CommissionedBy cb = td.getInvestigation().commissionedBy();
		if (null != cb && null != cb.task()) effects.returnReport(td.getUuid());
	}

	/**
	 * The investigations commissioned from this task that are still open and report back to it (task RD4-12):
	 * what a hop that returns to wait on them waits on.
	 */
	List<AgentTaskData> openInvestigationsFrom(AgentTaskData td) {
		return listByBoard(td.getBoard(), null).stream()
				.filter(AgentTaskData::isInvestigation)
				.filter(t -> null != t.getInvestigation().commissionedBy()
						&& td.getUuid().equals(t.getInvestigation().commissionedBy().task()))
				.filter(t -> AgentTaskData.ReturnTo.TASK == t.getInvestigation().returnTo())
				.filter(t -> TaskStatus.COMPLETED != t.getStatus() && TaskStatus.CANCELLED != t.getStatus())
				.toList();
	}

	// ---------- Task keys (board-documents.md §4) ----------

	/**
	 * Give a new task its number and key (D7), under the board lock, which serialises it with
	 * numbering and a prefix change. The number comes from the counter's in-place increment (task
	 * RD3-2), so a registration on a numbered board with a prefix writes no board revision; the
	 * board is saved only when it changed otherwise -- the first numbering of a board from before
	 * keys, or a prefix it had to take. A board from before keys numbers its existing tasks first,
	 * so the new one comes after them.
	 */
	private void stampKey(AgentTaskData td, UUID boardUuid, WhoUpdated wu) throws RelizaException {
		AgentBoardData bd = agentBoardService.lockBoard(boardUuid);
		boolean changed = false;
		if (!bd.isTasksNumbered()) {
			numberExisting(bd, wu);
			changed = true;
		}
		if (null == bd.getTaskPrefix()) {
			agentBoardService.applyTaskPrefix(bd, null, wu);
			changed = true;
		}
		// Saved before the draw, and with the counter as drawn so far, so the save cannot put back a
		// lower number than the one the update is about to hand out.
		if (changed) agentBoardService.saveData(bd, wu);
		int number = drawTaskNumber(bd);
		td.setNumber(number);
		td.setKey(bd.getTaskPrefix() + "-" + number);
	}

	/** The board's next number, from the counter in place; {@code bd} follows it for a later save. */
	private int drawTaskNumber(AgentBoardData bd) throws RelizaException {
		int n = agentBoardService.nextTaskNumber(bd.getUuid());
		bd.setNextTaskNumber(n);
		return n;
	}

	/**
	 * Number the tasks of a board from before keys (D14), the first time the board is read: in
	 * registration order, from the board's counter, under the board lock and each task's own lock,
	 * so a task saved concurrently cannot drop its new number. Idempotent: a numbered board returns
	 * at once, and a numbered task keeps its number.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public void ensureNumbered(UUID boardUuid, WhoUpdated wu) throws RelizaException {
		AgentBoardData quick = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		if (quick.isTasksNumbered()) return;
		AgentBoardData bd = agentBoardService.lockBoard(boardUuid);
		if (bd.isTasksNumbered()) return;
		numberExisting(bd, wu);
		agentBoardService.saveData(bd, wu);
	}

	/**
	 * Numbers the board's unnumbered tasks, each number drawn from the counter in place (task RD3-2);
	 * mutates {@code bd} (prefix, flag, and the counter as drawn) for the caller to save once.
	 */
	private void numberExisting(AgentBoardData bd, WhoUpdated wu) throws RelizaException {
		if (null == bd.getTaskPrefix()) agentBoardService.applyTaskPrefix(bd, null, wu);
		List<AgentTaskData> unnumbered = listByBoard(bd.getUuid(), null).stream()
				.filter(t -> null == t.getNumber())
				.sorted(java.util.Comparator.comparing(AgentTaskData::getCreatedDate,
						java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
						.thenComparing(t -> t.getUuid().toString()))
				.toList();
		for (AgentTaskData t : unnumbered) {
			AgentTaskData locked = lockedTask(t.getUuid());
			if (null != locked.getNumber()) continue;
			int number = drawTaskNumber(bd);
			locked.setNumber(number);
			locked.setKey(bd.getTaskPrefix() + "-" + number);
			saveData(locked, wu);
		}
		bd.setTasksNumbered(true);
	}

	/**
	 * The task a key names (D10, §4.3): the prefix to its board through the organization's registry,
	 * which keeps every prefix a board ever held, then the number on that board. Empty for a prefix
	 * nobody claimed, a number the board never stamped, or text that is not a key.
	 */
	public Optional<AgentTaskData> resolveKey(UUID orgUuid, String key) {
		if (null == orgUuid || StringUtils.isBlank(key)) return Optional.empty();
		String k = key.strip();
		int dash = k.lastIndexOf('-');
		if (dash <= 0 || dash == k.length() - 1) return Optional.empty();
		String prefix = k.substring(0, dash).toUpperCase(java.util.Locale.ROOT);
		String digits = k.substring(dash + 1);
		// Text that is not a key is not found, as an unknown key is.
		if (!digits.matches("[0-9]{1,9}")) return Optional.empty();
		int number = Integer.parseInt(digits);
		Optional<UUID> board = agentBoardService.boardOfTaskPrefix(orgUuid, prefix);
		if (board.isEmpty()) return Optional.empty();
		return listByBoard(board.get(), null).stream()
				.filter(t -> Integer.valueOf(number).equals(t.getNumber()))
				.findFirst();
	}

	/**
	 * A reference must belong to a source the board is wired to.
	 *
	 * <p>Compared through the parser rather than by text. The prefix match this replaces answered
	 * differently from the documents-repository check on the same list, so one board could accept
	 * {@code github:acme/widget} as its documents repository and refuse
	 * {@code github:acme/widget#42} as a task, on the strength of how its source happened to be
	 * spelled.
	 */
	private void validateRefAgainstSources(AgentBoardData board, String externalRef) throws RelizaException {
		List<String> sources = board.getSources();
		if (sources == null || sources.isEmpty()) return;
		TrackerRef ref = TrackerRef.parse(externalRef);
		List<TrackerSource> wired = new ArrayList<>();
		for (String raw : sources) {
			try {
				wired.add(TrackerSource.parse(raw));
			} catch (RelizaException e) {
				// A source stored before the grammar that the lenient reader still cannot make
				// sense of. Skip it rather than refusing every registration on this board, and say
				// so: the board needs fixing, the task does not.
				log.error("Board {} has an unreadable source '{}'; it matches nothing until fixed",
						board.getUuid(), raw, e);
			}
		}
		if (wired.stream().noneMatch(ref::belongsTo)) {
			throw new RelizaException("externalRef " + externalRef
					+ " does not belong to any wired source of board " + board.getName()
					+ " (" + wired.stream().map(TrackerSource::canonical).toList() + ")");
		}
	}

	// ---------- Coordinator hub operations (seat verified by the caller) ----------

	/**
	 * Authorize a task for a role with a priority order:
	 * PENDING_INTAKE or AWAITING_COORDINATOR -> QUEUED. Optional
	 * {@code dependsOn} replaces the task's dependency list — the
	 * coordinator can queue the whole plan up front; the poll/assign
	 * gate releases tasks as dependencies complete.
	 */
	/**
	 * The coordinator's authorize, which may also raise the task's strength requirement -- its way
	 * to say "this one needs a stronger model than the role usually does" (D20). {@code setStrength}
	 * false leaves the requirement alone.
	 *
	 * <p>Raise only. The value must be at least the task's current requirement: its override if it
	 * has one, else the role's floor. Lowering does more than admit weaker models, because the
	 * role's headroom moves down with the floor -- a role asking for 4 with headroom 1, overridden
	 * to 2, admits 2 to 3 and excludes the strong model the operator wanted. And nothing would
	 * catch a task quietly done by a weaker model, while budgets already cap what a stronger one
	 * costs. Clearing is refused for the same reason: it can lower. As elsewhere, the coordinator
	 * only tightens what people set; they keep full control through {@code agentTaskSetStrength}.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData authorize(UUID taskUuid, AgentBoardData board, String roleName,
			Integer orderIndex, List<UUID> dependsOn, UUID producesComponent, Integer level,
			List<RequiredInput> addRequiredInputs, boolean setStrength, Double requiredStrength,
			AgentActor actor, WhoUpdated wu) throws RelizaException {
		if (setStrength) {
			if (null == requiredStrength) {
				throw new RelizaException("The coordinator can raise a task's strength requirement but not"
						+ " clear it, since clearing can lower it; ask the operator");
			}
			StrengthScale.validate("requiredStrength", requiredStrength);
			AgentTaskData current = getTaskData(taskUuid)
					.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
			Double floor = null != current.getRequiredStrength() ? current.getRequiredStrength()
					: agentBoardService.getRoleConfig(board.getUuid(), roleName)
							.map(AgentTaskRoleConfigData::getRequiredStrength).orElse(null);
			if (null != floor && !StrengthScale.atLeast(requiredStrength, floor)) {
				throw new RelizaException("The coordinator can only raise a task's strength requirement: "
						+ StrengthScale.format(requiredStrength) + " is below the current "
						+ StrengthScale.format(floor)
						+ (null != current.getRequiredStrength() ? " set on this task" : " of role " + roleName)
						+ "; ask the operator to lower it");
			}
		}
		AgentTaskData td = authorize(taskUuid, board, roleName, orderIndex, dependsOn, producesComponent,
				level, addRequiredInputs, actor, wu);
		return setStrength ? setRequiredStrength(taskUuid, requiredStrength, actor, wu) : td;
	}

	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData authorize(UUID taskUuid, AgentBoardData board, String roleName,
			Integer orderIndex, List<UUID> dependsOn, UUID producesComponent, Integer level,
			List<RequiredInput> addRequiredInputs, AgentActor actor, WhoUpdated wu) throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, actor, "authorized", "for " + roleName, wu);
		requireStatus(td, taskUuid, TaskStatus.PENDING_INTAKE, TaskStatus.AWAITING_COORDINATOR);
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), roleName)
				.orElseThrow(() -> new RelizaException("Role " + roleName + " is not configured on board " + board.getName()));
		if (!rc.isActive()) throw new RelizaException("Role " + roleName + " is inactive on board " + board.getName());
		// An investigation is its investigating role's, and its reviewer's (task RD4-12): nobody else writes the report.
		if (td.isInvestigation() && !td.getInvestigation().investigatedBy(rc.getUuid(), rc.getName())
				&& !td.getInvestigation().reviewedBy(rc.getUuid(), rc.getName())) {
			throw new RelizaException("Task " + td.label() + " is an investigation for " + td.getInvestigation().role()
					+ (null == td.getInvestigation().review() ? "" : ", reviewed by " + td.getInvestigation().review())
					+ "; it cannot be authorized for " + rc.getName());
		}
		td.setRole(rc.getName());
		td.setRoleUuid(rc.getUuid());
		if (orderIndex != null) {
			td.setOrderIndex(orderIndex);
			td.setOrderSetBy(actor);
			td.setOrderSetAt(ZonedDateTime.now());
		}
		if (level != null) td.setWorkLevel(checkedWorkLevel(board.getLadder(), level));
		// add-only: the coordinator may demand more of this task than the role does, never less
		if (addRequiredInputs != null && !addRequiredInputs.isEmpty()) {
			List<RequiredInput> merged = new ArrayList<>(td.getRequiredInputs() == null
					? List.of() : td.getRequiredInputs());
			merged.addAll(addRequiredInputs);
			td.setRequiredInputs(merged);
		}
		if (producesComponent != null) {
			validateProduces(producesComponent, td.getOrg());
			td.setProducesComponent(producesComponent);
		}
		if (dependsOn != null) {
			validateDependencies(td, dependsOn);
			td.setDependsOn(new ArrayList<>(dependsOn));
		}
		// D7: a frame the board could not route has no answering role, so the unwind check never
		// matches and the asker would never get its answer back however well the authorised role
		// did. Naming the role here is the coordinator deciding who answers, which is what
		// authorising a task with a question open means.
		if (!td.getQuestionStack().isEmpty()) {
			int top = td.getQuestionStack().size() - 1;
			QuestionFrame frame = td.getQuestionStack().get(top);
			if (null == frame.answeringRole()) {
				td.getQuestionStack().set(top, frame.answeredBy(rc.getUuid()));
				// And the questions themselves, so the hop is not handed a task with no statement
				// of what it is being asked -- the same pin queueFor adds on the routed path.
				pinQuestions(td);
			}
		}
		// The one QUEUED transition that never projected (board-mechanics §5.3). Refused, not
		// parked: the task has not been queued, so it stays where it was and the caller has the why.
		if (AgentBudgetService.budgetApplies(td, board)) {
			AgentBudgetService.Projection p = budgetService.project(td, board, rc);
			if (!p.fits()) throw new RelizaException(budgetService.refusal(p, rc));
		}
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, actor);
		return saveData(td, wu);
	}

	private void validateDependencies(AgentTaskData td, List<UUID> dependsOn) throws RelizaException {
		for (UUID dep : dependsOn) {
			if (dep.equals(td.getUuid())) throw new RelizaException("Task cannot depend on itself");
			AgentTaskData dd = getTaskData(dep)
					.orElseThrow(() -> new RelizaException("Dependency task not found: " + dep));
			if (!td.getBoard().equals(dd.getBoard())) {
				throw new RelizaException("Dependency " + dep + " is on a different board");
			}
		}
		// cycle check: walk existing dependsOn edges from each declared dep. parent remembers the
		// task each node was first reached from, so on reaching this task again the loop can be
		// read back in order rather than naming only the task the caller already knows (F-012).
		java.util.Deque<UUID> stack = new java.util.ArrayDeque<>(dependsOn);
		java.util.Set<UUID> seen = new java.util.HashSet<>();
		Map<UUID, UUID> parent = new HashMap<>();
		Map<UUID, String> titles = new HashMap<>();
		titles.put(td.getUuid(), td.getTitle());
		for (UUID dep : dependsOn) parent.putIfAbsent(dep, td.getUuid());
		while (!stack.isEmpty()) {
			UUID cur = stack.pop();
			if (cur.equals(td.getUuid())) {
				throw new RelizaException(cycleMessage(td.getUuid(), parent, titles));
			}
			if (!seen.add(cur)) continue;
			getTaskData(cur).ifPresent(cd -> {
				titles.put(cur, cd.getTitle());
				if (cd.getDependsOn() != null) {
					for (UUID next : cd.getDependsOn()) {
						parent.putIfAbsent(next, cur);
						stack.push(next);
					}
				}
			});
		}
	}

	private static final int CYCLE_TITLE_CHARS = 40;

	/**
	 * "Dependency cycle: 487d02f1 (Run the budget projection…) → 6589725f (…) → 487d02f1", each
	 * task depending on the next, then the full uuids in order for scripts. The chain is read back
	 * from {@code start} through the first-reached parents, which lead to a declared dependency
	 * and from it to {@code start}.
	 */
	static String cycleMessage(UUID start, Map<UUID, UUID> parent, Map<UUID, String> titles) {
		List<UUID> loop = new ArrayList<>();
		for (UUID x = parent.get(start); x != null && !x.equals(start) && !loop.contains(x); x = parent.get(x)) {
			loop.add(x);
		}
		java.util.Collections.reverse(loop);
		loop.add(0, start);
		StringBuilder sb = new StringBuilder("Dependency cycle: ");
		for (UUID u : loop) sb.append(cycleNode(u, titles.get(u))).append(" → ");
		sb.append(u8(start)).append(". Each task depends on the next. [uuids: ");
		sb.append(loop.stream().map(UUID::toString).collect(Collectors.joining(", "))).append(']');
		return sb.toString();
	}

	private static String cycleNode(UUID u, String title) {
		if (StringUtils.isBlank(title)) return u8(u);
		String t = title.strip();
		if (t.length() > CYCLE_TITLE_CHARS) t = t.substring(0, CYCLE_TITLE_CHARS).stripTrailing() + "…";
		return u8(u) + " (" + t + ")";
	}

	private static String u8(UUID u) {
		return u.toString().substring(0, 8);
	}

	/** All dependencies COMPLETED (CANCELLED does not satisfy the gate). */
	public boolean dependenciesMet(AgentTaskData td) {
		if (td.getDependsOn() == null || td.getDependsOn().isEmpty()) return true;
		return td.getDependsOn().stream().allMatch(dep -> getTaskData(dep)
				.map(dd -> dd.getStatus() == TaskStatus.COMPLETED).orElse(false));
	}

	/**
	 * Park the task pending human input — MANUAL kind. Two-tier like
	 * board pauses: the coordinator sets COORDINATOR-level holds; the
	 * operator sets OPERATOR-level holds the coordinator cannot lift.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData hold(UUID taskUuid, HoldLevel level, String reason, AgentActor heldBy,
			WhoUpdated wu) throws RelizaException {
		return placeHold(taskUuid, level, HoldKind.MANUAL, reason, heldBy, wu);
	}

	/**
	 * Park the task because a question has nobody to answer it — QUESTION kind (D12).
	 *
	 * <p>Separate from {@link #hold} and not a flag on it, because the kind decides what a later
	 * release with text means: releasing a QUESTION hold with words records them as the answer to
	 * the open ids, and releasing any other hold must never do that. Refused unless the task
	 * really is waiting on an unrouted question, so the kind cannot be reached by an operator
	 * pausing a task for an unrelated reason.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData escalateQuestion(UUID taskUuid, HoldLevel level, String reason,
			AgentActor heldBy, WhoUpdated wu) throws RelizaException {
		AgentTaskData peek = getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		if (peek.getQuestionStack().isEmpty()) {
			throw new RelizaException("Task " + taskUuid + " has no open question to escalate");
		}
		return placeHold(taskUuid, level, HoldKind.QUESTION, reason, heldBy, wu);
	}

	/**
	 * The session working a task parks its own hop for a person (task RD4-5): an OPERATOR-level MANUAL hold
	 * whose reason is the question. The task shows "awaiting the operator: question", the people who write
	 * the board are told (the needs-a-person path in {@link #saveData}), and the hop stays assigned to the
	 * session, so its idle window is still the holder's. A person's release, with the answer as its note,
	 * gives the hop back to it. The caller has checked that the session holds the assignment.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData parkHopForOperator(UUID taskUuid, UUID sessionUuid, String question, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(question)) {
			throw new RelizaException("Parking a hop for the operator needs the question: say what the person is to decide");
		}
		AgentTaskData td = lockedTask(taskUuid);
		TaskAssignment a = requireAssignmentBySession(td, taskUuid, sessionUuid);
		AgentActor holder = AgentActor.ofSession(sessionUuid);
		String reason = AgentTaskData.AWAITING_THE_OPERATOR + question.strip();
		td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, holder, reason);
		td.setHold(new TaskHold(HoldLevel.OPERATOR, HoldKind.MANUAL, null, reason, holder, ZonedDateTime.now()));
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		effects.info("Task " + label(saved) + " parked by its " + a.role() + " hop, " + reason
				+ "; a person lifts the hold with the answer as the note");
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/** The states the coordinator seat parks a task in for an operator decision (task RD4-17): nobody is working it. */
	static final List<TaskStatus> SEAT_PARKS_FROM = List.of(TaskStatus.PENDING_INTAKE, TaskStatus.QUEUED,
			TaskStatus.AWAITING_COORDINATOR, TaskStatus.DELIVERING);

	/**
	 * The coordinator seat parks a task nobody is working for a person's decision (task RD4-17): an OPERATOR-level
	 * MANUAL hold with the question as its reason, as a hop parks itself (RD4-5), so the person sees one thing
	 * whoever asked: the task shows "awaiting the operator: question" and the board's writers are told (the
	 * needs-a-person path in {@link #saveData}). The hold records the status it was placed from; a person's
	 * release, with the answer as its note, returns the task there -- DELIVERING stays DELIVERING and its merge
	 * and declaration flow go on -- and a person acting on the task instead answers it by acting. The caller has
	 * checked that the session holds the seat.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData parkForOperator(UUID taskUuid, UUID seatSession, String question, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(question)) {
			throw new RelizaException("Parking a task for the operator needs the question: say what the person is to"
					+ " decide, the options you see and your recommendation");
		}
		AgentTaskData td = lockedTask(taskUuid);
		TaskStatus from = td.getStatus();
		if (!SEAT_PARKS_FROM.contains(from)) {
			throw new RelizaException("Task " + label(td) + " is " + from + "; the coordinator seat parks a task for the"
					+ " operator in " + SEAT_PARKS_FROM + (TaskStatus.ASSIGNED == from
							? ": the session working it parks its own hop" : ""));
		}
		AgentActor seat = AgentActor.ofSession(seatSession);
		String reason = AgentTaskData.AWAITING_THE_OPERATOR + question.strip();
		td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, seat, reason);
		td.setHold(new TaskHold(HoldLevel.OPERATOR, HoldKind.MANUAL, null, reason, seat, ZonedDateTime.now(), null, from));
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		effects.info("Task " + label(saved) + " parked by the coordinator, " + reason + "; a person lifts the hold with the"
				+ " answer as the note, or acts on it, and it returns to " + from);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * A task verb meets a task awaiting the operator (tasks RD4-5, RD4-17; RD4-17 architecture round 2 §1). A session
	 * is refused: nothing moves a task on while a person is deciding. On a task the seat parked, any person's verb is
	 * the answer, through this one path: the hold is lifted to the status it was placed from, with a row reading
	 * "action by person: summary"; an INFO names the question and that answer; and the verb then runs as it would on
	 * an unheld task, moving the task on when it moves it (a completion completes, a cancel cancels, an answer to the
	 * open questions routes). A person's verb on a hop's own hold runs as before: that hold is answered by its
	 * release, which gives the hop back to its holder.
	 *
	 * <p>Call it under the task's lock, before anything else is read from the task, and only where the verb then
	 * saves the task or throws: the INFO is posted after the commit, and a refusal rolls the release back with it.
	 *
	 * @param action what the person did, in the past tense ("cancelled", "declared")
	 * @param summary the action's own note or a summary of it; blank reads "no note"
	 */
	private void answeredByActing(AgentTaskData td, AgentActor actor, String action, String summary, WhoUpdated wu)
			throws RelizaException {
		if (!td.awaitsTheOperator()) return;
		if (null == actor || AgentActor.ActorKind.USER != actor.kind()) {
			throw new RelizaException("Task " + label(td) + " is " + td.getHold().reason()
					+ "; the person lifts the hold or acts");
		}
		if (!td.seatParkedForOperator()) return;
		String question = td.operatorQuestion();
		TaskStatus back = td.getHold().returnTo();
		String answer = actedAnswer(action, actor, summary);
		td.transitionStatus(back, StatusTrigger.LIFT_HOLD, actor, answer);
		td.setHold(null);
		BoardEffects effects = new BoardEffects(td.getBoard());
		effects.info("Task " + label(td) + " answered: " + answer + " (the question: " + question + "); the hold was"
				+ " lifted to " + back + " and the action went on from there");
		boardEffectsApplier.applyAfterCommit(effects, wu);
	}

	/** How a person's action reads as the answer to a question the seat asked (RD4-17): "action by person: summary". */
	static String actedAnswer(String action, AgentActor actor, String summary) {
		return action + " by " + (null == actor ? "a person" : actor.display()) + ": "
				+ (StringUtils.isBlank(summary) ? "no note" : summary.strip());
	}

	private AgentTaskData placeHold(UUID taskUuid, HoldLevel level, HoldKind kind, String reason,
			AgentActor heldBy, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(reason)) throw new RelizaException("Hold requires a reason");
		AgentTaskData td = lockedTask(taskUuid);
		requireStatus(td, taskUuid, TaskStatus.PENDING_INTAKE, TaskStatus.QUEUED, TaskStatus.AWAITING_COORDINATOR);
		td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, heldBy);
		td.setHold(new TaskHold(level, kind, null, reason, heldBy, ZonedDateTime.now()));
		return saveData(td, wu);
	}

	/**
	 * Release a MANUAL hold back to the coordinator's queue. A
	 * COORDINATOR-level actor cannot lift an OPERATOR hold, and nobody
	 * plain-releases a HUMAN_GATE hold — that verdict goes through
	 * {@link #humanReview} so it is recorded.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData liftHold(UUID taskUuid, HoldLevel actorLevel, AgentActor actor,
			WhoUpdated wu) throws RelizaException {
		return liftHold(taskUuid, actorLevel, actor, null, wu);
	}

	/**
	 * As above, with what the releasing human said.
	 *
	 * <p>The note is posted to the board feed rather than dropped. It is NOT an answer: on a
	 * QUESTION hold the answer goes through {@link #answer} and becomes a round the asking agent
	 * can read. This is the other holds, where there is nothing open to answer and the words are
	 * still worth keeping.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData liftHold(UUID taskUuid, HoldLevel actorLevel, AgentActor actor,
			String note, WhoUpdated wu) throws RelizaException {
		return liftHold(taskUuid, actorLevel, actor, note, null, wu);
	}

	/**
	 * As above, optionally to a named role (task 4c566d0d).
	 *
	 * <p>A hold routing placed because a loop stopped -- no progress, or the cycle cap -- is lifted
	 * past that stop once: re-running routing on the same sign-off would otherwise trip the same
	 * stop in the same transaction and re-hold the task with the same reason. The cycle is still
	 * counted, and the next identical stop fires as before. A budget hold is not overridden: the
	 * round must fit the budget, raised or not.
	 *
	 * @param role a role active on the board to route to instead of the one routing would pick;
	 *        null lets routing pick. Checked before anything is written.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData liftHold(UUID taskUuid, HoldLevel actorLevel, AgentActor actor,
			String note, String role, WhoUpdated wu) throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		requireStatus(td, taskUuid, TaskStatus.ON_HOLD);
		TaskHold h = td.getHold();
		if (h != null && h.kind() == HoldKind.HUMAN_GATE) {
			throw new RelizaException("Task " + taskUuid + " awaits a human review verdict on its "
					+ h.gateRole() + " sign-off; resolve via human review, not a hold lift");
		}
		if (h != null && h.level() == HoldLevel.OPERATOR && actorLevel == HoldLevel.COORDINATOR && td.seatParkedForOperator()) {
			throw new RelizaException("Task " + label(td) + " is " + h.reason() + "; a person lifts the hold with the answer"
					+ " as the note, or acts on it");
		}
		if (h != null && h.level() == HoldLevel.OPERATOR && actorLevel == HoldLevel.COORDINATOR) {
			// Said with what the coordinator CAN do: releasing a stop would route the same sign-off
			// into the same stop, and the person's decision is one click once it is recommended.
			throw new RelizaException("Task " + taskUuid + " carries an OPERATOR hold; the coordinator cannot lift it."
					+ " Post an ALERT with your recommendation for the operator, or cancel the task.");
		}
		if (td.holdParksAHop()) return answerParkedHop(td, actor, note, role, wu);
		if (td.seatParkedForOperator()) return answerSeatQuestion(td, actor, note, role, wu);
		UUID toRole = null;
		if (StringUtils.isNotBlank(role)) {
			toRole = agentBoardService.getRoleConfig(td.getBoard(), role.trim())
					.filter(AgentTaskRoleConfigData::isActive)
					.map(AgentTaskRoleConfigData::getUuid)
					.orElseThrow(() -> new RelizaException("Role " + role.trim() + " is not active on this board"));
		}
		boolean overridesStop = isLoopStop(h);
		// One lift per stop kind per task whoever gives it (task c0a2134c): the next stop of this
		// kind is the operator's.
		AgentTaskData.HoldStop released = overridesStop ? loopStopOf(h) : null;
		if (null != released) {
			td.getStopLifts().merge(released, 1, Integer::sum);
		}
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.LIFT_HOLD, actor);
		td.setHold(null);
		// Back into the loop rather than into the coordinator's lap: whatever parked it has been
		// dealt with, and the last hop still says what happens next.
		AgentRoutingService.RouteOverride override = overridesStop || null != toRole
				? new AgentRoutingService.RouteOverride(overridesStop, toRole) : null;
		AgentTaskData routed = routeAfterHop(td, lastSignOff(td), override, wu);
		if (StringUtils.isNotBlank(note) || null != override) {
			postReleaseNote(routed, note, actor, released, wu);
		}
		return routed;
	}

	/**
	 * A person answers the question a hop was parked on (task RD4-5). The note is required, since it is the
	 * answer: the status row reads "lifted by person: note", and an INFO names the task, the question and
	 * the answer. The hop goes back to its holder (ASSIGNED). When the holder's session closed meanwhile, the
	 * task is queued again for the same role, as the close would have done, with the answer on its record for
	 * the next session. A role is refused: nothing is routed, the hop resumes.
	 */
	private AgentTaskData answerParkedHop(AgentTaskData td, AgentActor actor, String note, String role, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(note)) {
			throw new RelizaException("Task " + label(td) + " is " + td.getHold().reason() + ". Lift it with your"
					+ " answer as the note: it is recorded on the task for the hop that asked.");
		}
		if (StringUtils.isNotBlank(role)) {
			throw new RelizaException("Task " + label(td) + " was parked by the hop working it, and lifting it gives"
					+ " the hop back to its holder rather than routing it; lift it without a role.");
		}
		String question = StringUtils.removeStart(td.getHold().reason(), AgentTaskData.AWAITING_THE_OPERATOR);
		String answer = note.strip();
		String row = "lifted by " + (null == actor ? "a person" : actor.display()) + ": " + answer;
		boolean resumes = null != td.getAssignment();
		td.transitionStatus(resumes ? TaskStatus.ASSIGNED : TaskStatus.QUEUED, StatusTrigger.LIFT_HOLD, actor, row);
		td.setHold(null);
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		effects.info("Task " + label(saved) + " answered by " + (null == actor ? "a person" : actor.display()) + ": "
				+ answer + " (the question: " + question + ")" + (resumes ? "; the hop resumes with its holder"
						: "; its session closed meanwhile, so it is queued again for " + saved.getRole()));
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * A person answers the question the coordinator seat parked a task on (task RD4-17). The note is required, as
	 * for a hop's question: the status row reads "lifted by person: note", and an INFO names the task, the
	 * question and the answer. The task returns to the status it was parked from and nothing is routed, so a role
	 * is refused; a DELIVERING task's merge and declaration flow resume, and the sweep settles it on its next tick.
	 */
	private AgentTaskData answerSeatQuestion(AgentTaskData td, AgentActor actor, String note, String role, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(note)) {
			throw new RelizaException("Task " + label(td) + " is " + td.getHold().reason() + ". Lift it with your"
					+ " answer as the note: it is recorded on the task for the coordinator that asked.");
		}
		TaskStatus back = td.getHold().returnTo();
		if (StringUtils.isNotBlank(role)) {
			throw new RelizaException("Task " + label(td) + " was parked by the coordinator, and lifting it returns it"
					+ " to " + back + " rather than routing it; lift it without a role.");
		}
		String question = td.operatorQuestion();
		String answer = note.strip();
		String who = null == actor ? "a person" : actor.display();
		td.transitionStatus(back, StatusTrigger.LIFT_HOLD, actor, "lifted by " + who + ": " + answer);
		td.setHold(null);
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		effects.info("Task " + label(saved) + " answered by " + who + ": " + answer + " (the question: " + question
				+ "); it returned to " + back);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * One INFO per document a review would have promoted and a guard kept from READY_TO_SHIP (task
	 * a8b519a0): INFO rather than ALERT, since a human-only guard refusing an agent is the guard
	 * working, and an alert per review would drown the feed.
	 */
	private static void notPromoted(List<AgentDocumentService.RefusedPromotion> refused, String by,
			BoardEffects effects) {
		for (AgentDocumentService.RefusedPromotion p : refused) {
			effects.info(p.label() + " reviewed by " + by + " but not promoted: " + p.reason()
					+ "; it stays ASSEMBLED until a person promotes it");
		}
	}

	/** A hold routing placed because a loop stopped: no progress, or the cycle cap (not the budget). */
	static boolean isLoopStop(TaskHold h) {
		return null != loopStopOf(h);
	}

	/**
	 * Which loop stop placed the hold, or null. Read from the hold's recorded stop; a hold stored
	 * before the stop was recorded is recognised as routing placed it, by its holder and reason.
	 */
	static AgentTaskData.HoldStop loopStopOf(TaskHold h) {
		if (null == h) return null;
		if (null != h.stop()) return h.isLoopStop() ? h.stop() : null;
		if (null == h.heldBy() || AgentActor.ActorKind.SYSTEM != h.heldBy().kind()
				|| !"routing".equals(h.heldBy().name()) || null == h.reason()) return null;
		if (h.reason().startsWith("stopped by " + AgentRoutingService.StopReason.NO_PROGRESS)) {
			return AgentTaskData.HoldStop.NO_PROGRESS;
		}
		if (h.reason().startsWith("stopped by " + AgentRoutingService.StopReason.CYCLE_CAP)) {
			return AgentTaskData.HoldStop.CYCLE_CAP;
		}
		return null;
	}

	/**
	 * The coordinator hands one of its holds to the operator (task c0a2134c): a judgement call --
	 * two roles disagree, an item needs accepting -- is a person's decision, and a coordinator
	 * that cannot release a hold must still be able to say so on the record rather than leave it.
	 *
	 * <p>Only a COORDINATOR-level MANUAL hold: an OPERATOR hold is already the operator's, a
	 * question hold already waits on a person's answer, and a human gate on a person's verdict. The
	 * level becomes OPERATOR, the kind and the recorded stop stay, so the operator's release still
	 * routes past a loop stop once. The reason keeps what the hold said, with the coordinator's
	 * recommendation after it, and an ALERT pages the operator with both.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData escalateHold(UUID taskUuid, String reason, AgentActor coordinator, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(reason)) {
			throw new RelizaException("An escalation needs a reason: say what the operator is to decide");
		}
		AgentTaskData td = lockedTask(taskUuid);
		requireStatus(td, taskUuid, TaskStatus.ON_HOLD);
		TaskHold h = td.getHold();
		if (null == h || HoldLevel.COORDINATOR != h.level() || HoldKind.MANUAL != h.kind()) {
			String what = null == h ? "no hold" : HoldKind.QUESTION == h.kind()
					? "a question hold, which already waits on a person's answer"
					: HoldKind.HUMAN_GATE == h.kind() ? "a human-review gate, which already waits on a person's verdict"
					: "an OPERATOR hold, which is already the operator's";
			throw new RelizaException("Task " + taskUuid + " is not on a coordinator hold: it carries " + what);
		}
		// A stop the coordinator could release says so; once escalated it is the operator's alone, so the
		// hold and the alert say that instead of both at once (RD2-21, sweep UI-47).
		String held = StringUtils.replace(h.reason(), AgentRoutingService.COORDINATOR_MAY_LIFT,
				"; escalated to the operator");
		String escalated = held + " — escalated by the coordinator: " + reason.strip();
		td.setHold(new TaskHold(HoldLevel.OPERATOR, h.kind(), h.gateRole(), escalated, coordinator,
				ZonedDateTime.now(), h.stop()));
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(td.getBoard());
		effects.alert("Task " + label(td) + " escalated to the operator by the coordinator: " + reason.strip()
				+ " (hold: " + held + ")");
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * Record a human verdict on a HUMAN_GATE hold: appends a human
	 * sign-off for the gated role (agent/session null, reviewer
	 * identity in reviewedBy) and returns the task to the coordinator
	 * — the human judges, the coordinator still routes (a REJECTED
	 * verdict typically re-queues the gated role for rework).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData humanReview(UUID taskUuid, boolean accept, String note, AgentActor reviewedBy,
			WhoUpdated wu) throws RelizaException {
		return humanReview(taskUuid, accept, note, reviewedBy, null, null, wu);
	}

	/**
	 * As above, with the review item decisions a person attaches to either verdict (operator-actions
	 * D10, amended for gaps §1.10).
	 *
	 * <p>The decision round is cut first and recorded as the output of the person's verdict, so the
	 * route after the hop reads it exactly as it reads a reviewer's review items. With a rejection,
	 * something blocking goes back to whoever produces what it is about (rule 2), where a bare
	 * rejection goes to the coordinator (rule 5). With an acceptance what the person files is a
	 * correction (task cac71351): open, carried until its producer closes it, never blocking, so the
	 * gated hop's outputs are handed over (D14) and the task goes on with the corrections open in
	 * the index -- on a strict board too, where there is no priority below the blocking one to file
	 * at. An acceptance that leaves an item already open and blocking undecided is refused: promoting
	 * work over a blocking item is what the blocking priority exists to prevent, and the route
	 * would send it back anyway.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData humanReview(UUID taskUuid, boolean accept, String note, AgentActor reviewedBy,
			List<AgentDocumentService.BoardReviewItemDecision> reviewItems, BoardReviewItemIndex.About about, WhoUpdated wu)
			throws RelizaException {
		if (null == reviewedBy) throw new RelizaException("Human review requires a reviewer identity");
		boolean deciding = null != reviewItems && !reviewItems.isEmpty();
		if (!deciding && null != about) {
			throw new RelizaException("about says what review item decisions are about; send it with them");
		}
		AgentTaskData td = lockedTask(taskUuid);
		requireStatus(td, taskUuid, TaskStatus.ON_HOLD);
		TaskHold h = td.getHold();
		if (h == null || h.kind() != HoldKind.HUMAN_GATE) {
			throw new RelizaException("Task " + taskUuid + " is not awaiting human review");
		}
		// The hold names the gated role; resolve it to its row so a human verdict joins the same
		// way an agent hop does. Absent (a role removed while the gate was open) leaves it null
		// rather than refusing the verdict -- a human waiting on a gate must not be blocked by
		// bookkeeping.
		UUID gateRoleUuid = agentBoardService.getRoleConfig(td.getBoard(), h.gateRole())
				.map(AgentTaskRoleConfigData::getUuid).orElse(null);
		SignOff gated = lastSignOffOfRole(td, h.gateRole());
		// A bare acceptance of a REJECTED hop (task RD2-25): refused while what the hop filed still
		// blocks, the same rule as an acceptance with decisions below. Before anything is written, so
		// the gate stays as it was. A person moves a task past a blocking review item only by deciding it.
		if (accept && !deciding && null != gated && gated.outcome() == SignOffOutcome.REJECTED) {
			List<BoardReviewItemIndex.BoardReviewItem> blocking = blockingIn(gated.outputs(), boardOf(td).getBlockingPriority());
			if (!blocking.isEmpty()) throw new RelizaException(rejection(blocking));
		}
		List<UUID> verdictOutputs = null;
		// Collected, not posted: the verdict holds the task row, and posting takes the board's.
		BoardEffects verdictEffects = new BoardEffects(td.getBoard());
		if (deciding) {
			Integer blockingPriority = boardOf(td).getBlockingPriority();
			// Filed at an acceptance, a review item is a correction (task cac71351): the person wants the
			// work done and the task to go on, so it travels open without blocking.
			AgentDocumentService.DecisionPlan plan = agentDocumentService.planDecisionRound(td,
					gatedReviewItemsSpec(gated), reviewItems, about, reviewedBy, blockingPriority, accept);
			if (null != plan.landed()) {
				throw new RelizaException("Task " + taskUuid + " has already been reviewed with these decisions");
			}
			// Before anything is written, so a refused acceptance leaves the gate exactly as it was.
			// What still blocks is an item already open that the person left undecided.
			List<BoardReviewItemIndex.BoardReviewItem> stillBlocking = accept ? plan.index().blockingReviewItems(blockingPriority)
					: List.of();
			if (!stillBlocking.isEmpty()) throw new RelizaException(rejection(stillBlocking));
			ReleaseData round = agentDocumentService.cutDecisionRound(td, plan, wu);
			td.addRelease(round.getUuid());
			verdictOutputs = List.of(round.getUuid());
		}
		List<AgentDocumentService.RefusedPromotion> refused = new ArrayList<>();
		// What the person's acceptance reviewed and promoted, recorded on their verdict (task fda2c9f1):
		// the gated hop's outputs, and what a gated reviewer had reviewed.
		List<UUID> verdictReviewed = new ArrayList<>();
		List<AgentTaskData.Promotion> verdictPromotions = new ArrayList<>();
		if (accept && null != gated) {
			// A person accepting at a gate reviewed what the hop handed over (D14): handed over,
			// then reviewed, so both lifecycle steps are recorded. With corrections too (#593, task
			// cac71351): corrections never block, so it is still an acceptance.
			agentDocumentService.promoteOutputs(gated.outputs(), wu);
			AgentDocumentService.Promotions ofOutputs = agentDocumentService.promoteReviewedRecording(gated.outputs(), wu);
			refused.addAll(ofOutputs.refused());
			verdictReviewed.addAll(gated.outputs());
			verdictPromotions.addAll(ofOutputs.made());
			// A gated reviewer: what it reviewed is reviewed now too (T-3), unless what the board
			// routes on -- the person's corrections round, else the reviewer's own index -- still
			// leaves an item blocking (T-2).
			List<UUID> routedOn = null != verdictOutputs ? verdictOutputs : gated.outputs();
			if (null != gated.reviewedInputs() && !leavesBlocking(routedOn, boardOf(td).getBlockingPriority())) {
				AgentDocumentService.Promotions ofReviewed = agentDocumentService.promoteReviewedRecording(
						gated.reviewedInputs(), wu);
				refused.addAll(ofReviewed.refused());
				gated.reviewedInputs().stream().filter(r -> !verdictReviewed.contains(r)).forEach(verdictReviewed::add);
				verdictPromotions.addAll(ofReviewed.made());
			}
			notPromoted(refused, reviewedBy.display(), verdictEffects);
		}
		td.addSignOff(new SignOff(h.gateRole(), gateRoleUuid, null, null, null, ZonedDateTime.now(),
				accept ? SignOffOutcome.PASSED : SignOffOutcome.REJECTED, note, null, reviewedBy, null,
				verdictOutputs, verdictReviewed.isEmpty() ? null : verdictReviewed,
				verdictPromotions.isEmpty() ? null : verdictPromotions));
		td.setHold(null);
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR,
				accept ? StatusTrigger.HUMAN_ACCEPT : StatusTrigger.HUMAN_REJECT, reviewedBy);
		// A human verdict ends a hop, so the board routes what follows exactly as after an agent
		// hop. Without this, every board with a gate drops back to manual routing after each
		// verdict -- and the coordinator prompt tells the coordinator not to touch queued tasks,
		// so those tasks would simply sit.
		return routeAfterHop(td, lastSignOff(td), null, verdictEffects, refused, wu);
	}

	/**
	 * Direct human sign-off on a task QUEUED in a HUMAN-kind role — no
	 * claim step in v1 (humans have no sessions to bind). Same hop
	 * contract as an agent sign-off: back to the coordinator.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData humanSignOff(UUID taskUuid, AgentBoardData board, SignOffOutcome outcome,
			String note, AgentActor reviewedBy, WhoUpdated wu) throws RelizaException {
		if (outcome == null) throw new RelizaException("Sign-off requires an outcome");
		if (null == reviewedBy) throw new RelizaException("Human sign-off requires a reviewer identity");
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, reviewedBy, "signed off", outcome + (StringUtils.isBlank(note) ? "" : ": " + note.strip()), wu);
		requireStatus(td, taskUuid, TaskStatus.QUEUED);
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), td.getRole())
				.orElseThrow(() -> new RelizaException("Role " + td.getRole() + " is not configured on board " + board.getName()));
		if (rc.getKind() != AgentTaskRoleConfigData.RoleKind.HUMAN) {
			throw new RelizaException("Task " + taskUuid + " is queued for AGENTIC role " + rc.getName()
					+ "; direct human sign-off applies to HUMAN roles only");
		}
		// Both sides of the merge: the hop records the role ROW it resolved to, and the
		// transition records WHO caused it.
		td.addSignOff(new SignOff(rc.getName(), rc.getUuid(), null, null, null, ZonedDateTime.now(),
				outcome, note, AgentBoardService.promptVersion(board, rc.getPrompt()), reviewedBy));
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.HUMAN_SIGNOFF, reviewedBy);
		return routeAfterHop(td, lastSignOff(td), wu);
	}

	/**
	 * Per-task add-only human gate: the next sign-off parks the task
	 * for human review. Coordinator may only set it (more review,
	 * never less); clearing an unfired flag is the operator's call.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setRequireHumanReview(UUID taskUuid, boolean value, boolean operatorActor,
			WhoUpdated wu) throws RelizaException {
		return setRequireHumanReview(taskUuid, value, operatorActor, null, wu);
	}

	/** As above, naming who set it: on a task awaiting the operator, a person's change answers the seat (RD4-17). */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setRequireHumanReview(UUID taskUuid, boolean value, boolean operatorActor,
			AgentActor by, WhoUpdated wu) throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, value ? "human review required" : "human review cleared",
				value ? "the next sign-off waits for a person" : "the next sign-off routes as usual", wu);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED
				|| td.getStatus() == TaskStatus.DELIVERING) {
			throw new RelizaException("Task " + taskUuid + " is " + td.getStatus());
		}
		if (!value && !operatorActor) {
			throw new RelizaException("The per-task human-review flag is add-only for the coordinator;"
					+ " clearing it is operator-only");
		}
		td.setRequireHumanReview(value);
		return saveData(td, wu);
	}

	/** Re-prioritize a queued or pending task without changing its role. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setOrder(UUID taskUuid, int orderIndex, WhoUpdated wu) throws RelizaException {
		return setOrder(taskUuid, orderIndex, null, wu);
	}

	/**
	 * Reorder, recording who did (operator-actions D4). Either path may reorder after the other:
	 * ordering stays the coordinator's job, and a person who needs a task to stay put holds it.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setOrder(UUID taskUuid, int orderIndex, AgentActor actor, WhoUpdated wu)
			throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, actor, "reordered", "order " + orderIndex, wu);
		td.setOrderIndex(orderIndex);
		td.setOrderSetBy(actor);
		td.setOrderSetAt(ZonedDateTime.now());
		return saveData(td, wu);
	}

	/**
	 * Coordinator subtasking: split a task into children on the same
	 * board. Children land PENDING_INTAKE (the coordinator authorizes
	 * each); the parent goes AWAITING_COORDINATOR and completion stays
	 * an explicit coordinator act once children finish.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public List<AgentTaskData> split(UUID taskUuid, AgentBoardData board,
			List<SplitChild> children, AgentActor actor, WhoUpdated wu) throws RelizaException {
		if (children == null || children.isEmpty()) throw new RelizaException("Split requires children");
		// The board before the parent: each child's registration stamps its key under the board
		// lock, and taking that after the parent's task lock would invert the board-before-task order.
		AgentBoardData lockedBoard = agentBoardService.lockBoard(board.getUuid());
		AgentTaskData td = lockedTask(taskUuid);
		requireStatus(td, taskUuid, TaskStatus.PENDING_INTAKE, TaskStatus.QUEUED, TaskStatus.AWAITING_COORDINATOR);
		// A child names its own group and tags or inherits the parent's (task-groups-and-tags.md D9).
		String parentGroup = lockedBoard.groupByUuid(td.getGroup()).map(AgentBoardData.TaskGroup::key).orElse(null);
		List<AgentTaskData> created = new ArrayList<>();
		for (SplitChild c : children) {
			if (StringUtils.isBlank(c.title())) throw new RelizaException("Every split child requires a title");
			// split children inherit the parent's level unless the coordinator names another
			Integer childLevel = null != c.workLevel() ? c.workLevel() : td.getWorkLevel();
			AgentTaskData child = register(lockedBoard, c.externalRef(), c.title(), c.description(),
					c.sourceUrl(), td.getRegisteredBySession(), td.getUuid(),
					c.producesComponent(), childLevel, null,
					null != td.getRegisteredBySession() ? AgentActor.ofSession(td.getRegisteredBySession()) : null,
					false, StringUtils.isNotBlank(c.group()) ? c.group() : parentGroup,
					null != c.tags() ? c.tags() : td.getTags(), wu);
			created.add(child);
			td.addChildTask(child.getUuid());
		}
		// second pass: sibling-index dependencies, declared at split time
		// so the coordinator can encode the architect's proposed ordering
		for (int i = 0; i < children.size(); i++) {
			List<Integer> depList = children.get(i).dependsOnSiblingIndexes();
			if (depList.isEmpty()) continue;
			List<UUID> deps = new ArrayList<>();
			for (Integer d : depList) {
				int idx = d;
				if (idx < 0 || idx >= created.size() || idx == i) {
					throw new RelizaException("Invalid dependsOnSiblingIndexes entry " + idx
							+ " for split child " + i);
				}
				deps.add(created.get(idx).getUuid());
			}
			AgentTaskData child = lockedTask(created.get(i).getUuid());
			validateDependencies(child, deps);
			child.setDependsOn(deps);
			created.set(i, saveData(child, wu));
		}
		// Written even when the task already waits on the coordinator (a task returned as too big): SPLIT is a
		// recording trigger, so the split stays in the history (RD3-16 architecture-2 §1).
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.SPLIT, actor);
		saveData(td, wu);
		return created;
	}

	/**
	 * Coordinator completes the task. Beyond child completion, this is
	 * where role necessity bites: every ACTIVE role with necessity
	 * REQUIRED must have its most recent sign-off on this task be
	 * PASSED ("unskippable" is discovered at "done", not at routing).
	 * Split parents are exempt once their children — each individually
	 * enforced — are all done; cancel is exempt entirely.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData complete(UUID taskUuid, String note, AgentActor actor, WhoUpdated wu) throws RelizaException {
		return complete(taskUuid, note, actor, false, wu);
	}

	/**
	 * Complete, by the coordinator or by a person. A person's complete adds three rules
	 * (operator-actions D5, D17, D23): it is refused while a review item that blocks completion is
	 * open, since an OPEN item on a completed task should keep meaning nobody looked; a required
	 * role whose rejection a person has since decided over counts as passed; and a required role
	 * that still has not passed can be skipped only by saying so, with a note. Only the user
	 * mutation passes {@code skipRequiredRoles}.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData complete(UUID taskUuid, String note, AgentActor actor, boolean skipRequiredRoles,
			WhoUpdated wu) throws RelizaException {
		boolean person = null != actor && AgentActor.ActorKind.USER == actor.kind();
		if (skipRequiredRoles && !person) {
			throw new RelizaException("Only a person can complete a task over its required roles");
		}
		if (skipRequiredRoles && StringUtils.isBlank(note)) {
			throw new RelizaException("Completing over required roles needs a note saying why");
		}
		AgentTaskData td = lockedTask(taskUuid);
		// A task awaiting the operator (RD4-17): a person's complete answers it, a session's is refused.
		answeredByActing(td, actor, "completed", note, wu);
		// Every rule is checked before anything is written (RD3-16): the task is completed, or the
		// caller is told why and the task, its history and the board are as they were.
		// QUEUED and ON_HOLD are here because this is the EXCEPTION path now. The board completes
		// a task that converged; a coordinator or a human calls this to end one that has not --
		// a queued role nobody needs to run, or a task parked by a cycle cap that a person has
		// decided to accept. Refusing those states would leave the exception with no way out
		// except releasing a hold into a loop that is already known to be going nowhere.
		requireStatus(td, taskUuid, TaskStatus.AWAITING_COORDINATOR, TaskStatus.PENDING_INTAKE,
				TaskStatus.QUEUED, TaskStatus.ON_HOLD, TaskStatus.DELIVERING);
		// DELIVERING: the actor says the delivery happened -- typically a PR merged by hand in a
		// repository whose CI does not report PRs, so the board will never see the merge. Not over a
		// unit closed or declared abandoned: that is not a merge nobody saw.
		if (td.getStatus() == TaskStatus.DELIVERING) {
			Optional<String> blocked = agentDeliveryService.blockedDelivery(td);
			if (blocked.isPresent()) throw new RelizaException(blocked.get());
			td.transitionStatus(TaskStatus.COMPLETED, StatusTrigger.COMPLETE, actor, note);
			td.setCompletedAt(ZonedDateTime.now());
			return saveData(td, wu);
		}
		List<UUID> incomplete = incompleteChildren(td);
		if (!incomplete.isEmpty()) {
			throw new RelizaException("Task " + label(td) + " has incomplete child task(s): "
					+ String.join(", ", incomplete.stream().map(this::labelOf).toList()));
		}
		AgentBoardData board = null;
		if (person) {
			board = agentBoardService.getBoardData(td.getBoard())
					.orElseThrow(() -> new RelizaException("Board not found for task " + taskUuid));
			List<String> blockers = completionBlockers(td, board);
			if (!blockers.isEmpty()) {
				throw new RelizaException("Task " + label(td) + " has open review item(s) that block completion: "
						+ String.join(", ", blockers) + ". Accept or dismiss them first.");
			}
		}
		List<String> skipped = List.of();
		if (td.getChildTasks() == null || td.getChildTasks().isEmpty()) {
			List<String> missing = person ? missingRequiredRolesForPerson(td, board) : missingRequiredRoles(td);
			if (!missing.isEmpty()) {
				if (!skipRequiredRoles) {
					throw new RelizaException("Task " + label(td) + " cannot be completed: required role(s)"
							+ " without a passing sign-off: " + missing
							+ (person ? ". To complete it anyway, skip them and say why." : ""));
				}
				skipped = missing;
			}
		}
		// An investigation has no delivery step (task RD4-12): it completes, and its report goes back.
		if (td.isInvestigation()) {
			if (!skipped.isEmpty()) td.setRequiredRolesSkipped(new ArrayList<>(skipped));
			BoardEffects effects = new BoardEffects(td.getBoard());
			completeInvestigation(td, StatusTrigger.COMPLETE, actor, note, effects);
			AgentTaskData saved = saveData(td, wu);
			boardEffectsApplier.applyAfterCommit(effects, wu);
			return saved;
		}
		// Skipping roles is not skipping delivery: an open PR still waits. A closed or abandoned one is
		// refused here, before any write (RD3-16): the ALERT was posted once, by the sweep or the pass
		// that found it, and a refused complete repeats nothing.
		Optional<String> blocked = agentDeliveryService.blockedDelivery(td);
		if (blocked.isPresent()) throw new RelizaException(blocked.get());
		AgentDeliveryService.Settled settled = agentDeliveryService.settle(td, StatusTrigger.COMPLETE, actor, note);
		if (!skipped.isEmpty()) {
			td.setRequiredRolesSkipped(new ArrayList<>(skipped));
		}
		AgentTaskData saved = saveData(td, wu);
		if (null != settled.info() || null != settled.alert()) {
			BoardEffects delivery = new BoardEffects(td.getBoard());
			if (null != settled.info()) delivery.info(settled.info());
			if (null != settled.alert()) delivery.alert(settled.alert());
			boardEffectsApplier.applyAfterCommit(delivery, wu);
		}
		if (!skipped.isEmpty()) {
			postBestEffort(saved, AgentBoardData.BoardEventKind.INFO, "Task " + label(saved)
					+ (settled.status() == TaskStatus.COMPLETED ? " completed" : " passed") + " by " + actor.name()
					+ " without required role(s) " + skipped + ": " + note,
					actor, wu);
		}
		return saved;
	}

	private static String label(AgentTaskData td) {
		return td.label();
	}

	/** A task named by its label, or its uuid when it cannot be read. */
	private String labelOf(UUID taskUuid) {
		return getTaskData(taskUuid).map(AgentTaskData::label).orElse(String.valueOf(taskUuid));
	}

	/** A board notice that must not fail the action it reports on. */
	/**
	 * A task's budget, set or cleared by a person (task 6f1b348d, gaps §1.3): what the task may
	 * spend, in USD micros, on top of the board's limit. Null clears it. Any status but the terminal
	 * ones.
	 *
	 * <p>A raise never releases a budget hold by itself: releasing the hold is the operator's
	 * explicit act, which re-runs routing (board-mechanics §6.2). The event says so.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setBudget(UUID taskUuid, Long budgetMicros, AgentActor by, WhoUpdated wu)
			throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, "budget set", null == budgetMicros ? "cleared" : usd(budgetMicros), wu);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + taskUuid + " is " + td.getStatus() + "; its budget is history");
		}
		if (null != budgetMicros && budgetMicros < 0) throw new RelizaException("A task budget cannot be negative");
		td.setBudgetMicros(budgetMicros);
		td.setBudgetSetBy(by);
		td.setBudgetSetAt(ZonedDateTime.now());
		AgentTaskData saved = saveData(td, wu);
		postBestEffort(saved, AgentBoardData.BoardEventKind.INFO, "Budget of task " + taskLabel(saved)
				+ (null == budgetMicros ? " cleared" : " set to " + usd(budgetMicros)) + " by " + by.name()
				+ (saved.getStatus() == TaskStatus.ON_HOLD ? "; lift the hold to resume" : ""), by, wu);
		return saved;
	}

	/** The highest level a task or a board default may have (RD2-1); the client ladder stops at 4. */
	public static final int MAX_LEVEL = 9;

	/** A level as given, or refused outside 0..{@link #MAX_LEVEL}; null passes. */
	public static Integer checkedWorkLevel(Integer level) throws RelizaException {
		if (null != level && (level < 0 || level > MAX_LEVEL)) {
			throw new RelizaException("workLevel is 0 to " + MAX_LEVEL);
		}
		return level;
	}

	/** Why a level is refused on a board with no ladder (task RD3-6). */
	public static final String NO_LADDER = "this board has no ladder, so its tasks carry no level; declare settings.ladder"
			+ " on the board to use levels";

	/**
	 * A level as a board takes it (task RD3-6): null passes; without a ladder any level is refused; with one, the
	 * level must be one of its rungs, and the refusal names them.
	 */
	public static Integer checkedWorkLevel(AgentBoardData.Ladder ladder, Integer level) throws RelizaException {
		if (null == level) return null;
		if (null == ladder || ladder.isEmpty()) throw new RelizaException(NO_LADDER);
		int rungs = ladder.levels().size();
		if (level < 0 || level >= rungs) {
			throw new RelizaException("level " + level + " is not on this board's ladder: " + ladderText(ladder));
		}
		return level;
	}

	/** The rungs as a sentence names them: "0 requirements, 1 solution, 2 components". */
	public static String ladderText(AgentBoardData.Ladder ladder) {
		if (null == ladder || ladder.isEmpty()) return "none";
		List<String> out = new ArrayList<>();
		for (int i = 0; i < ladder.levels().size(); i++) out.add(i + " " + ladder.levels().get(i).name());
		return String.join(", ", out);
	}

	/**
	 * A task's level as the board reads it (RD2-1): its own, else its group's default, else the board's default.
	 * The one place the fallback is resolved -- the poll, the snapshot and every read go through it.
	 *
	 * <p>Only on a board with a ladder (task RD3-6): there the board default is 0 when unset, so every task has a
	 * level; without one every level reads null -- a task that kept one from before is ignored -- and the poll's
	 * level rung does nothing. With no board to read, the task's own level.
	 */
	public static Integer effectiveWorkLevel(AgentTaskData td, AgentBoardData board) {
		if (null == board) return null == td ? null : td.getWorkLevel();
		if (!board.hasLadder()) return null;
		if (null != td && null != td.getWorkLevel()) return td.getWorkLevel();
		// The group rung (task-groups-and-tags.md D7, task RD2-29): the task's group's default level.
		Integer groupLevel = null == td ? null
				: board.groupByUuid(td.getGroup()).map(AgentBoardData.TaskGroup::defaultWorkLevel).orElse(null);
		if (null != groupLevel) return groupLevel;
		return null != board.getDefaultWorkLevel() ? board.getDefaultWorkLevel() : 0;
	}

	// ---------- Groups and tags on a task (task-groups-and-tags.md §2-§3, task RD2-29) ----------

	/** At most this many tags on one task. */
	public static final int MAX_TAGS = 20;
	private static final java.util.regex.Pattern TAG_KEY = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");

	/**
	 * Tags as given, normalised (D8): each key lower-cased and checked, no key twice, at most
	 * {@link #MAX_TAGS}; the value kept as sent; removable YES unless said.
	 */
	public static List<CommonVariables.TagRecord> checkedTags(List<CommonVariables.TagRecord> tags) throws RelizaException {
		List<CommonVariables.TagRecord> out = new ArrayList<>();
		if (null == tags) return out;
		Set<String> seen = new HashSet<>();
		for (CommonVariables.TagRecord t : tags) {
			String k = null == t || null == t.key() ? "" : t.key().strip().toLowerCase(java.util.Locale.ROOT);
			if (!TAG_KEY.matcher(k).matches()) {
				throw new RelizaException("A tag is 1 to 40 lower-case letters, digits, dots, underscores and hyphens,"
						+ " starting with a letter or digit (got '" + (null == t ? "" : t.key()) + "')");
			}
			if (!seen.add(k)) throw new RelizaException("Tag " + k + " is given twice");
			out.add(new CommonVariables.TagRecord(k, t.value(), t.removable()));
		}
		if (out.size() > MAX_TAGS) {
			throw new RelizaException("A task carries at most " + MAX_TAGS + " tags (got " + out.size() + ")");
		}
		return out;
	}

	/** The group a task may join by key: one of the board's, and OPEN. */
	private static AgentBoardData.TaskGroup groupToJoin(AgentBoardData board, String key) throws RelizaException {
		String k = key.strip().toLowerCase(java.util.Locale.ROOT);
		AgentBoardData.TaskGroup g = board.groupByKey(k).orElseThrow(() -> new RelizaException("no group " + k
				+ " on board " + board.getName() + "; groups are created in the board file or with group set"));
		if (g.status() == AgentBoardData.GroupStatus.CLOSED) {
			throw new RelizaException("group " + k + " is CLOSED; reopen it or register elsewhere");
		}
		return g;
	}

	/** After the group's last task (D5): one more than the highest orderIndex among its tasks, 0 for an empty group. */
	private int nextOrderInGroup(UUID boardUuid, UUID group) {
		return listByBoard(boardUuid, null).stream().filter(t -> group.equals(t.getGroup()))
				.mapToInt(AgentTaskData::getOrderIndex).max().stream().map(m -> m + 1).findFirst().orElse(0);
	}

	/**
	 * Move a task into a group, or out of every group with null (D10): refused on a finished task and
	 * into a CLOSED group; posts an INFO.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setGroup(UUID taskUuid, String groupKey, AgentActor by, WhoUpdated wu) throws RelizaException {
		AgentTaskData peek = getTaskData(taskUuid).orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		AgentBoardData board = agentBoardService.lockBoard(peek.getBoard());
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, "moved", StringUtils.isBlank(groupKey) ? "out of every group" : "to group " + groupKey.strip(),
				wu);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + keyOf(td) + " is " + td.getStatus() + "; its group is history");
		}
		AgentBoardData.TaskGroup g = StringUtils.isBlank(groupKey) ? null : groupToJoin(board, groupKey);
		td.setGroup(null == g ? null : g.uuid());
		AgentTaskData saved = saveData(td, wu);
		postBestEffort(saved, AgentBoardData.BoardEventKind.INFO, "Task " + taskLabel(saved)
				+ (null == g ? " ungrouped" : " moved to group " + g.key()) + " by " + by.name(), by, wu);
		return saved;
	}

	/** As below, with nobody named as the actor. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setTags(UUID taskUuid, List<CommonVariables.TagRecord> tags, WhoUpdated wu) throws RelizaException {
		return setTags(taskUuid, tags, null, wu);
	}

	/**
	 * Replace a task's tags (D10): refused on a finished task. No event: tags carry no meaning, except on a task
	 * awaiting the operator, where a person's retagging answers the seat's question like any action (RD4-17).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setTags(UUID taskUuid, List<CommonVariables.TagRecord> tags, AgentActor by, WhoUpdated wu)
			throws RelizaException {
		List<CommonVariables.TagRecord> checked = checkedTags(tags);
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, "retagged", checked.isEmpty() ? "no tags" : checked.stream()
				.map(t -> StringUtils.isBlank(t.value()) ? t.key() : t.key() + "=" + t.value())
				.collect(Collectors.joining(", ")), wu);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + keyOf(td) + " is " + td.getStatus() + "; its tags are history");
		}
		td.setTags(checked);
		return saveData(td, wu);
	}

	/**
	 * The keys of the groups this task's group waits on that still have an open task (D4): none for an
	 * ungrouped task. {@code boardTasks} is the board's tasks when the caller has them, else null.
	 */
	public List<String> waitingOnGroups(AgentTaskData td, AgentBoardData board, List<AgentTaskData> boardTasks) {
		if (null == td || null == td.getGroup() || null == board) return List.of();
		AgentBoardData.TaskGroup mine = board.groupByUuid(td.getGroup()).orElse(null);
		if (null == mine || mine.dependsOn().isEmpty()) return List.of();
		List<AgentTaskData> tasks = null != boardTasks ? boardTasks : listByBoard(board.getUuid(), null);
		List<String> out = new ArrayList<>();
		for (UUID upstream : mine.dependsOn()) {
			boolean open = tasks.stream().anyMatch(t -> upstream.equals(t.getGroup())
					&& t.getStatus() != TaskStatus.COMPLETED && t.getStatus() != TaskStatus.CANCELLED);
			if (open) board.groupByUuid(upstream).ifPresent(g -> out.add(g.key()));
		}
		return out;
	}

	/** How a refusal names a task: its key, else its uuid. */
	private static String keyOf(AgentTaskData td) {
		return null != td.getKey() ? td.getKey() : td.getUuid().toString();
	}

	/** True when no group this task's group depends on has an open task (D4); true for an ungrouped task. */
	public boolean groupDependenciesMet(AgentTaskData td, AgentBoardData board, List<AgentTaskData> boardTasks) {
		return waitingOnGroups(td, board, boardTasks).isEmpty();
	}

	/**
	 * A task's level, set or cleared by a person or the coordinator seat (RD2-1): 0 to
	 * {@link #MAX_LEVEL}, null clears it to the board default. Any status but the terminal ones.
	 * Stamps who and when, and posts an INFO.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setWorkLevel(UUID taskUuid, Integer level, AgentActor by, WhoUpdated wu) throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, "level set", null == level ? "cleared to the board's default" : "level " + level, wu);
		checkedWorkLevel(boardOf(td).getLadder(), level);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + taskUuid + " is " + td.getStatus() + "; its level is history");
		}
		td.setWorkLevel(level);
		td.setWorkLevelSetBy(by);
		td.setWorkLevelSetAt(ZonedDateTime.now());
		AgentTaskData saved = saveData(td, wu);
		postBestEffort(saved, AgentBoardData.BoardEventKind.INFO, "Level of task " + taskLabel(saved)
				+ (null == level ? " cleared" : " set to " + level) + " by " + by.name(), by, wu);
		return saved;
	}

	/**
	 * The coordinator seeds a task's budget at authorize and never changes one (task 6f1b348d): the
	 * coordinator seeds, people decide. Checked before the authorize writes, so a refused seed leaves
	 * the task as it was.
	 */
	public void checkBudgetSeed(UUID taskUuid, Long budgetMicros) throws RelizaException {
		AgentTaskData td = getTaskData(taskUuid).orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		if (null == budgetMicros) {
			throw new RelizaException("The coordinator can seed a task's budget but not clear it; ask the operator");
		}
		if (budgetMicros < 0) throw new RelizaException("A task budget cannot be negative");
		if (null != td.getBudgetMicros()) {
			throw new RelizaException("Task " + taskUuid + " already has a budget of " + usd(td.getBudgetMicros())
					+ "; raising it is an operator's decision");
		}
	}

	private static String usd(long micros) {
		return String.format(java.util.Locale.ROOT, "$%.2f", micros / 1_000_000.0);
	}

	private void postBestEffort(AgentTaskData td, AgentBoardData.BoardEventKind kind, String message,
			AgentActor actor, WhoUpdated wu) {
		try {
			agentBoardService.postEvent(td.getBoard(), kind, message, actor, wu);
		} catch (Exception e) {
			log.error("Failed to post board event for task {} on board {}", td.getUuid(), td.getBoard(), e);
		}
	}

	/**
	 * Ids of open review items, over the latest BOARD_REVIEW_ITEMS and BOARD_TEST_REPORT rounds, at or above
	 * the board's completion priority: the test that stops the board's own completion, not the
	 * blocking priority routing uses to send work back (operator-actions D5).
	 */
	List<String> completionBlockers(AgentTaskData td, AgentBoardData board) {
		List<String> out = new ArrayList<>();
		for (RearmSpecificationType spec : List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
				RearmSpecificationType.BOARD_TEST_REPORT)) {
			agentDocumentService.latestRoundRelease(td, spec).ifPresent(rd -> rd.getDocument().reviewItems()
					.blockingReviewItems(board.getCompletionPriority()).forEach(f -> out.add(f.id())));
		}
		return out;
	}

	/**
	 * Required roles without a pass, as a person's complete judges them (operator-actions D23,
	 * with the architect's two conditions). A role whose latest hop rejected counts as passed when:
	 * that rejection published a review item index with something blocking at the blocking priority,
	 * and the newest round of that index is a person's decision round reading PASSED. A rejection
	 * with nothing blocking stated no reason, so a person still needs the override and its note.
	 * Stale passes are not judged here (D24).
	 */
	List<String> missingRequiredRolesForPerson(AgentTaskData td, AgentBoardData board) {
		if (td.isInvestigation()) return investigationRolesWithoutPass(td);
		List<String> out = new ArrayList<>();
		for (AgentTaskRoleConfigData rc : agentBoardService.listRoleConfigs(td.getBoard())) {
			if (!rc.isActive() || rc.getNecessity() != AgentTaskRoleConfigData.RoleNecessity.REQUIRED) continue;
			SignOff last = td.lastSignOffForRole(rc.getUuid(), rc.getName());
			// A reopen to this role since its last hop makes that hop no pass at all.
			if (null != last && td.reopenedSince(last)) last = null;
			if (null != last && last.outcome() == SignOffOutcome.PASSED) continue;
			if (null != last && agentDocumentService.rejectionDecidedOver(td, board.getBlockingPriority(), last)) continue;
			out.add(rc.getName());
		}
		return out;
	}

	/**
	 * Active REQUIRED roles whose most recent sign-off on this task is absent or not PASSED.
	 *
	 * <p>Matched on the role config row rather than its name, so this cannot be answered wrongly
	 * by two roles that happen to share text. Reported by name, because a refusal a human reads
	 * should say "qa", not a uuid.
	 */
	public List<String> missingRequiredRoles(AgentTaskData td) {
		if (td.isInvestigation()) return investigationRolesWithoutPass(td);
		return agentBoardService.listRoleConfigs(td.getBoard()).stream()
				.filter(AgentTaskRoleConfigData::isActive)
				.filter(rc -> rc.getNecessity() == AgentTaskRoleConfigData.RoleNecessity.REQUIRED)
				.filter(rc -> {
					SignOff last = td.lastSignOffForRole(rc.getUuid(), rc.getName());
					return last == null || td.reopenedSince(last) || last.outcome() != SignOffOutcome.PASSED;
				})
				.map(AgentTaskRoleConfigData::getName)
				.collect(Collectors.toList());
	}

	/**
	 * The roles an investigation needs a pass from (task RD4-12): its investigating role, and its reviewer when it
	 * has one. The board's REQUIRED roles are a work task's pipeline and do not apply.
	 */
	List<String> investigationRolesWithoutPass(AgentTaskData td) {
		AgentTaskData.Investigation inv = td.getInvestigation();
		List<String> out = new ArrayList<>();
		SignOff made = td.lastSignOffForRole(inv.roleUuid(), inv.role());
		if (null == made || td.reopenedSince(made) || SignOffOutcome.PASSED != made.outcome()) out.add(inv.role());
		if (null != inv.review()) {
			SignOff reviewed = td.lastSignOffForRole(inv.reviewUuid(), inv.review());
			if (null == reviewed || td.reopenedSince(reviewed) || SignOffOutcome.PASSED != reviewed.outcome()) {
				out.add(inv.review());
			}
		}
		return out;
	}

	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData cancel(UUID taskUuid, String note, AgentActor actor, WhoUpdated wu) throws RelizaException {
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, actor, "cancelled", note, wu);
		if (td.getStatus() == TaskStatus.COMPLETED) {
			throw new RelizaException("Task " + taskUuid + " is already completed");
		}
		// Refused rather than recorded again: a second cancel changes nothing (RD3-16).
		if (td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + label(td) + " is already cancelled");
		}
		td.transitionStatus(TaskStatus.CANCELLED, StatusTrigger.CANCEL, actor, note);
		td.setAssignment(null);
		td.setHold(null);
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		returnIfInvestigation(saved, effects);
		if (!effects.isEmpty()) boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * The session that registered a task withdraws it while it waits on intake (task RD4-5): a duplicate, or a
	 * registration it no longer stands behind. A cancel whose status row reads "withdrawn by its registrant:
	 * reason", with an INFO on the board. Once the coordinator authorised the task it is the coordinator's,
	 * and the withdrawal is refused. The caller has checked the session's identity.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData withdraw(UUID taskUuid, UUID sessionUuid, String reason, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(reason)) {
			throw new RelizaException("A withdrawal needs a reason: say why the registration is withdrawn");
		}
		AgentTaskData td = lockedTask(taskUuid);
		if (null == sessionUuid || !sessionUuid.equals(td.getRegisteredBySession())) {
			throw new RelizaException("Task " + label(td) + " was not registered by session " + sessionUuid
					+ "; only its registrant withdraws it");
		}
		if (TaskStatus.CANCELLED == td.getStatus()) {
			throw new RelizaException("Task " + label(td) + " is already cancelled");
		}
		if (TaskStatus.PENDING_INTAKE != td.getStatus()) {
			throw new RelizaException("Task " + label(td) + " is " + td.getStatus()
					+ ": the coordinator owns this task now; return it or ask the seat");
		}
		String note = "withdrawn by its registrant: " + reason.strip();
		td.transitionStatus(TaskStatus.CANCELLED, StatusTrigger.CANCEL, AgentActor.ofSession(sessionUuid), note);
		td.setAssignment(null);
		td.setHold(null);
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		effects.info("Task " + label(saved) + " " + note);
		returnIfInvestigation(saved, effects);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,40}");

	/**
	 * Record that a delivery unit landed, or never will (task 18c5c293): a linked PR merged where this
	 * ReARM cannot see it, or on a board delivering without PRs, a push or release. Append-only; the
	 * newest declaration of a unit counts. A DELIVERING task then settles on what it has: completed
	 * once every unit is delivered, to the coordinator with an ALERT when one is abandoned.
	 *
	 * @param unit a linked PR's URL (matched as the board matches them) or, on a board delivering
	 *        without PRs, a branch or release reference
	 * @param commit the merged or pushed sha, 7 to 40 hex characters; required to say DELIVERED
	 * @param by who declares; the caller has checked they may
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData declareDelivery(UUID taskUuid, String unit, String commit, AgentTaskData.DeliveryOutcome outcome,
			String note, AgentActor by, WhoUpdated wu) throws RelizaException {
		AgentTaskData.DeliveryOutcome said = null == outcome ? AgentTaskData.DeliveryOutcome.DELIVERED : outcome;
		if (AgentTaskData.DeliveryOutcome.SUPERSEDED == said) {
			throw new RelizaException("A superseded PR is declared with its replacement: task supersedepr <task> --old <pr>"
					+ " --by <replacement pr>");
		}
		if (StringUtils.isBlank(unit)) {
			throw new RelizaException("Say which unit was delivered: a linked PR's URL, or a branch or release");
		}
		if (AgentTaskData.DeliveryOutcome.DELIVERED == said && (null == commit || !COMMIT_SHA.matcher(commit.strip()).matches())) {
			throw new RelizaException("A delivery names the commit that landed: the merged or pushed sha, 7 to 40 hex"
					+ " characters (--commit)");
		}
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, "declared", unit.strip()
				+ (AgentTaskData.DeliveryOutcome.DELIVERED == said ? " delivered at " + commit.strip().substring(0, 7)
						: " abandoned") + (StringUtils.isBlank(note) ? "" : ": " + note.strip()), wu);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + taskUuid + " is " + td.getStatus() + "; nothing waits on its delivery");
		}
		AgentBoardData.DeliveryPolicy policy = boardOf(td).getEffectiveDeliveryPolicy();
		String recorded = unit.strip();
		if (AgentBoardData.DeliveryMode.NONE != policy.mode()) {
			String key = AgentDeliveryService.matchKey(recorded);
			recorded = (null == td.getPrUrls() ? List.<String>of() : td.getPrUrls()).stream()
					.filter(u -> null != key && key.equals(AgentDeliveryService.matchKey(u))).findFirst()
					.orElseThrow(() -> new RelizaException("The unit " + unit.strip() + " is not a PR linked to this task"
							+ (null == td.getPrUrls() || td.getPrUrls().isEmpty() ? " (it links none)"
									: " (linked: " + String.join(", ", td.getPrUrls()) + ")")));
		}
		// An abandonment of a PR no CI reported records what its tracker says, so it and a later supersede read the
		// same source (RD3-20). It never refuses: the declaration is a person's or the seat's word.
		AgentTaskData.TrackerObservation observed = null;
		if (AgentTaskData.DeliveryOutcome.ABANDONED == said && AgentBoardData.DeliveryMode.NONE != policy.mode()) {
			String key = AgentDeliveryService.matchKey(recorded);
			boolean reported = agentDeliveryService.pullRequestsOf(td).stream()
					.anyMatch(pr -> key.equals(AgentDeliveryService.matchKey(pr.url())) && pr.registered());
			if (!reported) {
				PullRequestTrackerHook.Read read = trackerRead(td.getOrg(), recorded);
				if (read.ok()) observed = new AgentTaskData.TrackerObservation(read.state(), read.source(), ZonedDateTime.now());
			}
		}
		td.getDeliveries().add(new AgentTaskData.Delivery(recorded,
				null == commit || commit.isBlank() ? null : commit.strip(), said, by, ZonedDateTime.now(),
				StringUtils.isBlank(note) ? null : note.strip(), null, observed));
		BoardEffects effects = new BoardEffects(td.getBoard());
		effects.info("Task " + label(td) + ": " + recorded + " declared "
				+ (AgentTaskData.DeliveryOutcome.DELIVERED == said ? "delivered at " + commit.strip().substring(0, 7)
						: "abandoned") + " by " + (null == by ? "someone" : by.display())
				+ (StringUtils.isBlank(note) ? "" : ": " + note.strip()));
		agentDeliveryService.leaveDelivering(td, null == by ? AgentActor.system("delivery") : by, effects);
		AgentTaskData saved = saveData(td, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * Declare a linked PR superseded by another linked PR on the same repository (task RD3-13): recorded as an
	 * declaration of the old PR with outcome SUPERSEDED, naming the replacement, so delivery counts the
	 * replacement and treats the old PR as absent. The record keeps every PR the task named.
	 *
	 * <p>Accepted only when both are linked, differ, share a repository, the board delivers through PRs, and
	 * the old PR's row, read now, says closed and not merged: a merged PR is never superseded, and one whose
	 * CI never reported it closed cannot be told apart from one still open. A PR declared abandoned may be
	 * superseded, the newer declaration winning (architecture-2 §1); one declared delivered or superseded not. Who may is the caller's check (the
	 * session holding the task in a role that pushes code, or a person with BOARD_WRITE).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData supersedePullRequest(UUID taskUuid, String oldUrl, String byUrl, String note, AgentActor by,
			WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(oldUrl) || StringUtils.isBlank(byUrl)) {
			throw new RelizaException("Name the superseded PR (--old) and the PR that replaces it (--by)");
		}
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, by, "declared superseded", oldUrl.strip() + ", replaced by " + byUrl.strip()
				+ (StringUtils.isBlank(note) ? "" : ": " + note.strip()), wu);
		if (td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + label(td) + " is " + td.getStatus() + "; nothing waits on its delivery");
		}
		if (AgentBoardData.DeliveryMode.NONE == boardOf(td).getEffectiveDeliveryPolicy().mode()) {
			throw new RelizaException("Task " + label(td) + "'s board delivers without PRs; there is no PR to supersede");
		}
		String old = linkedPr(td, oldUrl, "superseded PR");
		String replacement = linkedPr(td, byUrl, "replacement");
		if (AgentDeliveryService.matchKey(old).equals(AgentDeliveryService.matchKey(replacement))) {
			throw new RelizaException("A PR cannot supersede itself: " + old);
		}
		if (!java.util.Objects.equals(AgentDeliveryService.repositoryOf(old), AgentDeliveryService.repositoryOf(replacement))) {
			throw new RelizaException("The replacement " + replacement + " is not on the same repository as " + old
					+ "; a PR is superseded by one on its own repository");
		}
		String oldKey = AgentDeliveryService.matchKey(old);
		AgentDeliveryService.TaskPullRequest row = agentDeliveryService.pullRequestsOf(td).stream()
				.filter(pr -> oldKey.equals(AgentDeliveryService.matchKey(pr.url()))).findFirst().orElse(null);
		// An abandonment may be corrected by a supersede (architecture-2 §1): on RD3-4 replaced PRs were declared
		// abandoned because no other verb existed. A delivered or already superseded PR's declaration stands.
		if (null != row && null != row.declaration() && AgentTaskData.DeliveryOutcome.ABANDONED != row.declaration().outcome()) {
			AgentTaskData.Delivery a = row.declaration();
			throw new RelizaException(old + " is already declared " + a.outcome().name().toLowerCase(java.util.Locale.ROOT)
					+ (AgentTaskData.DeliveryOutcome.SUPERSEDED == a.outcome() ? " by " + a.supersededBy() : "")
					+ "; its newest declaration stands");
		}
		// Closed unmerged, one of three ways (RD3-20): the board's own abandonment, which stands in for the tracker;
		// a CI report; or, for a PR no CI reported, the tracker read now and recorded. A merged PR never.
		boolean registered = null != row && row.registered();
		if (registered && PullRequestState.MERGED == row.state()) {
			throw new RelizaException(old + " is merged; a merged PR is never superseded");
		}
		boolean abandoned = null != row && null != row.declaration()
				&& AgentTaskData.DeliveryOutcome.ABANDONED == row.declaration().outcome();
		AgentTaskData.TrackerObservation observed = null;
		if (!abandoned && registered && PullRequestState.CLOSED != row.state()) {
			throw new RelizaException(old + " is " + String.valueOf(row.state()).toLowerCase(java.util.Locale.ROOT)
					+ "; close it without merging, or declare it abandoned, then supersede");
		}
		if (!abandoned && !registered) {
			PullRequestTrackerHook.Read read = trackerRead(td.getOrg(), old);
			if (!read.ok()) {
				throw new RelizaException(old + " has not been reported by its repository's CI, is not declared abandoned,"
						+ " and its tracker could not be read (" + read.failure() + "); declare it abandoned, then supersede");
			}
			if ("MERGED".equals(read.state())) {
				throw new RelizaException(old + " is merged on " + read.source() + "; a merged PR is never superseded");
			}
			if (!"CLOSED".equals(read.state())) {
				throw new RelizaException(old + " is open on " + read.source()
						+ "; close it unmerged, or declare it abandoned, then supersede");
			}
			observed = new AgentTaskData.TrackerObservation(read.state(), read.source(), ZonedDateTime.now());
		}
		td.getDeliveries().add(new AgentTaskData.Delivery(old, null, AgentTaskData.DeliveryOutcome.SUPERSEDED, by,
				ZonedDateTime.now(), StringUtils.isBlank(note) ? null : note.strip(), replacement, observed));
		BoardEffects effects = new BoardEffects(td.getBoard());
		effects.info("Task " + label(td) + ": " + old + " superseded by " + replacement + ", declared by "
				+ (null == by ? "someone" : by.display()) + (StringUtils.isBlank(note) ? "" : ": " + note.strip()));
		agentDeliveryService.leaveDelivering(td, null == by ? AgentActor.system("delivery") : by, effects);
		AgentTaskData saved = saveData(td, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/** The PR's tracker, read now (task RD3-20); a failure when this edition has no reader or the read fails. */
	private PullRequestTrackerHook.Read trackerRead(UUID org, String prUrl) {
		if (null == pullRequestTrackerHook) return PullRequestTrackerHook.Read.failed("this edition reads no tracker");
		try {
			return pullRequestTrackerHook.read(org, prUrl);
		} catch (Exception e) {
			log.error("Reading the tracker for PR {} in org {} failed", prUrl, org, e);
			return PullRequestTrackerHook.Read.failed("the read failed");
		}
	}

	/** The task's own spelling of a linked PR, or a refusal naming what it links. */
	private static String linkedPr(AgentTaskData td, String url, String what) throws RelizaException {
		String key = AgentDeliveryService.matchKey(url.strip());
		List<String> linked = null == td.getPrUrls() ? List.of() : td.getPrUrls();
		return linked.stream().filter(u -> null != key && key.equals(AgentDeliveryService.matchKey(u))).findFirst()
				.orElseThrow(() -> new RelizaException("The " + what + " " + url.strip() + " is not linked to task "
						+ td.label() + (linked.isEmpty() ? " (it links none)" : " (linked: " + String.join(", ", linked) + ")")
						+ ("replacement".equals(what) ? "; link it first" : "")));
	}

	/**
	 * Send a completed task back to a role, with a reason (operator-actions D4 as amended): its
	 * delivery could not land -- typically a PR that no longer merges after later merges -- and the
	 * role has to redo its part on the board, where whoever read that part re-verifies it.
	 *
	 * <p>Only COMPLETED: a cancel is final, and a cancelled task is registered again. The reopened
	 * role's earlier passes stop counting ({@link AgentTaskData#reopenedSince}); every other role's
	 * pass stands until the redone hop republishes an input it read, which D30 staleness then
	 * re-runs it on. A person's skipped required roles are required again. No cycle is counted: a
	 * reopen is a decision, not a loop. The budget is projected first, and a round that does not
	 * fit holds for an operator rather than queueing (gaps §1.23).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData reopen(UUID taskUuid, String roleName, String reason, AgentActor actor, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(reason)) throw new RelizaException("A reopen needs a reason: say why the delivery cannot land");
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, actor, "reopened", "to " + roleName + ": " + reason.strip(), wu);
		if (td.getStatus() == TaskStatus.CANCELLED) {
			throw new RelizaException("Task " + taskUuid + " is cancelled; a cancelled task is not reopened, register a new one");
		}
		if (td.getStatus() != TaskStatus.COMPLETED && td.getStatus() != TaskStatus.DELIVERING) {
			throw new RelizaException("Task " + taskUuid + " is " + td.getStatus()
					+ "; only a COMPLETED or DELIVERING task is reopened");
		}
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found for task " + taskUuid));
		if (board.isPaused()) {
			throw new RelizaException("Board " + board.getName() + " is paused (" + board.getPause().level()
					+ "); resume it before reopening a task");
		}
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), roleName)
				.orElseThrow(() -> new RelizaException("Role " + roleName + " is not configured on board " + board.getName()));
		if (!rc.isActive()) throw new RelizaException("Role " + roleName + " is inactive on board " + board.getName());

		List<String> skipped = null == td.getRequiredRolesSkipped() ? List.of() : List.copyOf(td.getRequiredRolesSkipped());
		td.getReopens().add(new AgentTaskData.Reopen(rc.getName(), rc.getUuid(), ZonedDateTime.now(), actor, reason.strip()));
		td.setCompletedAt(null);
		td.setRequiredRolesSkipped(null);
		td.setAssignment(null);
		td.setRole(rc.getName());
		td.setRoleUuid(rc.getUuid());
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.REOPEN, actor, reason.strip());
		String who = null != actor && StringUtils.isNotBlank(actor.name()) ? actor.name()
				: null != actor && null != actor.uuid() ? String.valueOf(actor.uuid()).substring(0, 8) : "someone";
		String info = "Task " + label(td) + " reopened to " + rc.getName() + " by " + who + ": " + reason.strip()
				+ (skipped.isEmpty() ? "" : " (required again: " + skipped + ")");
		String alert = null;
		if (AgentBudgetService.budgetApplies(td, board)) {
			AgentBudgetService.Projection p = budgetService.project(td, board, rc);
			if (!p.fits()) {
				String stop = "budget: reopening to " + rc.getName() + " does not fit (" + budgetService.refusal(p, rc) + ")";
				td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, AgentActor.system("routing"));
				td.setHold(new TaskHold(HoldLevel.OPERATOR, HoldKind.MANUAL, null, "stopped by " + stop,
						AgentActor.system("routing"), ZonedDateTime.now(), AgentTaskData.HoldStop.BUDGET));
				alert = "Task " + label(td) + " stopped by " + stop + " and needs a human";
			}
		}
		AgentTaskData saved = saveData(td, wu);
		// After the commit, board row after task row: the order the publish path depends on.
		BoardEffects effects = new BoardEffects(td.getBoard());
		effects.info(info);
		if (null != alert) effects.alert(alert);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/** Bind the tracker ref of a draft split child once its issue exists. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData bindExternalRef(UUID taskUuid, AgentBoardData board, String externalRef,
			String sourceUrl, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(externalRef)) throw new RelizaException("externalRef is required");
		// Same rendering as register: a late-bound reference must land under the key a
		// re-registration would find.
		externalRef = TrackerRef.parse(externalRef).canonical();
		validateRefAgainstSources(board, externalRef);
		AgentTaskData td = lockedTask(taskUuid);
		td.setExternalRef(externalRef);
		if (StringUtils.isNotBlank(sourceUrl)) td.setSourceUrl(sourceUrl);
		return saveData(td, wu);
	}

	// ---------- Worker operations ----------

	/**
	 * The roles a worker says it can take, as given: role names (matched case-insensitively,
	 * since an agent types them) or role config uuids. Blank entries are dropped. An empty
	 * declaration admits every role, which is the undeclared poll; a declaration of only blanks
	 * is not empty -- the caller did declare, and it admits nothing.
	 */
	record DeclaredRoles(List<String> entries) {
		static DeclaredRoles of(List<String> roles) {
			if (roles == null || roles.isEmpty()) return new DeclaredRoles(null);
			return new DeclaredRoles(roles.stream().filter(Objects::nonNull).map(String::strip)
					.filter(s -> !s.isEmpty()).toList());
		}

		boolean admits(AgentTaskRoleConfigData rc) {
			if (entries == null) return true;
			String uuid = null == rc.getUuid() ? null : rc.getUuid().toString();
			return entries.stream().anyMatch(e -> e.equalsIgnoreCase(rc.getName()) || e.equalsIgnoreCase(uuid));
		}

		String describe() {
			return entries == null ? "(any)" : String.join(", ", entries);
		}
	}

	/**
	 * Role-less poll across the org's boards (or one board): the
	 * lowest-ordered QUEUED task whose constraints admit this
	 * agent/session. Skips paused boards, tasks whose role WIP or the
	 * board's per-agent WIP is exhausted, and distinct-agent
	 * violations. Does not assign — follow with {@link #assign}.
	 */
	public Optional<WorkerAssignment> next(List<AgentBoardData> boards, UUID agentUuid, UUID sessionUuid) {
		return next(boards, agentUuid, sessionUuid, null);
	}

	/**
	 * As above, offering only tasks whose role is among {@code roles}: the roles this agent says
	 * it can take, each a role name on the board (case-insensitive) or a role config uuid. Null
	 * or empty means no declaration, and every role is considered. A declaration that matches no
	 * role, or matches roles with nothing open, offers nothing -- the agent asked not to be given
	 * anything else.
	 */
	public Optional<WorkerAssignment> next(List<AgentBoardData> boards, UUID agentUuid, UUID sessionUuid,
			List<String> roles) {
		return next(boards, agentUuid, sessionUuid, roles, true);
	}

	/**
	 * The poll. A candidate the budget no longer affords is not offered (board-mechanics D25), and
	 * with {@code park} it is parked afterwards through the exhaustion path, so it stops sitting in
	 * QUEUED misleading every poller. Without {@code park} nothing is projected at all: that is the
	 * STRICT check inside {@code assign}, which holds a task row lock a park would need, and a
	 * governance check must not change state as a side effect.
	 */
	private Optional<WorkerAssignment> next(List<AgentBoardData> boards, UUID agentUuid, UUID sessionUuid,
			List<String> roles, boolean park) {
		DeclaredRoles declared = DeclaredRoles.of(roles);
		List<AgentTaskData> overBudget = new ArrayList<>();
		Optional<WorkerAssignment> found = Optional.empty();
		// the boards this poll asked for work (task RD3-4): the staleness sweep reads who is staffing a role
		List<UUID> polled = new ArrayList<>();
		for (AgentBoardData board : boards) {
			if (board.isPaused() || board.getStatus() != AgentBoardData.BoardStatus.ACTIVE) continue;
			if (agentBoardService.isSeatHolder(board, sessionUuid)) continue;
			polled.add(board.getUuid());
			List<AgentTaskData> queued = listByBoard(board.getUuid(), TaskStatus.QUEUED.name());
			if (queued.isEmpty()) continue;
			List<AgentTaskData> assigned = listByBoard(board.getUuid(), TaskStatus.ASSIGNED.name());
			long agentWip = assigned.stream().filter(t -> t.getAssignment() != null
					&& agentUuid.equals(t.getAssignment().agent())).count();
			if (agentWip >= board.getPerAgentWipLimit()) continue;
			// Eligibility first, then preference. Everything in this loop decides whether an
			// agent MAY take a task; the ordering afterwards decides which of the tasks it may
			// take it is offered. Nothing below can override anything here, which is what keeps
			// affinity from quietly holding work for a favourite agent.
			// Per role: a model can be strong at architecture and weaker at coding, and a board
			// role may carry its own override. Worked out once per role, not per task.
			StrengthOf strengthOf = new StrengthOf(sessionUuid);
			List<Candidate> eligible = new ArrayList<>();
			AgentBudgetService.BoardSpend spend = null;
			// The board's tasks, read once per board and only when a queued task sits in a group that waits
			// on another (task-groups-and-tags.md D4): the group gate needs them, nothing else here does.
			List<AgentTaskData> boardTasks = board.getGroups() != null
					&& board.getGroups().stream().anyMatch(g -> !g.dependsOn().isEmpty())
					? listByBoard(board.getUuid(), null) : List.of();
			for (AgentTaskData td : queued) {
				if (!dependenciesMet(td)) continue;
				if (!groupDependenciesMet(td, board, boardTasks)) continue;
				AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), td.getRole()).orElse(null);
				if (rc == null || !rc.isActive()) continue;
				if (rc.getKind() == AgentTaskRoleConfigData.RoleKind.HUMAN) continue;
				if (!declared.admits(rc)) continue;
				if (rc.getWipLimit() != null && rc.getWipLimit() > 0) {
					long roleWip = assigned.stream().filter(t -> t.getAssignment() != null
							&& rc.getName().equals(t.getAssignment().role())).count();
					if (roleWip >= rc.getWipLimit()) continue;
				}
				if (rc.isRequireDistinctAgent() && agentUuid.equals(td.lastSignOffAgent())) continue;
				Double strength = strengthOf.forRole(rc);
				if (!admitsStrength(td, rc, strength)) continue;
				// what the work reads must exist at the stated maturity before it is offered
				if (!agentTaskInputService.satisfied(board, rc, td)) continue;
				if (park && AgentBudgetService.budgetApplies(td, board)) {
					// Read once per board per poll, and only when a candidate gets this far.
					if (null == spend) spend = budgetService.boardSpend(board);
					if (!budgetService.project(td, board, rc, spend).fits()) {
						overBudget.add(td);
						continue;
					}
				}
				eligible.add(new Candidate(td, rc, strength));
			}
			if (eligible.isEmpty()) continue;
			Candidate chosen = preferred(eligible, board, agentUuid, sessionUuid);
			AgentTaskData offered = chosen.role().isBlindReview() ? redactForBlindRole(chosen.task()) : chosen.task();
			found = Optional.of(new WorkerAssignment(offered, chosen.role().getName(),
					chosen.role().getUuid(), agentBoardService.servedPromptFor(board, chosen.role().getPrompt()),
					AgentBoardService.promptVersion(board, chosen.role().getPrompt()),
					agentTaskInputService.bindings(board, chosen.role(), chosen.task()),
					chosen.role().getHopBudgetMicros()));
			break;
		}
		if (park) {
			agentSessionService.recordBoardPolls(sessionUuid, polled, roles,
					found.map(f -> f.task().getBoard()).orElse(null), ZonedDateTime.now());
		}
		// After the loop and outside any lock: next is not transactional, and each park takes its
		// own. A park that fails leaves the task queued for the next poll to try again, and must
		// not cost this agent the task it was about to be offered.
		for (AgentTaskData td : overBudget) {
			try {
				routingService.parkOverBudget(td.getUuid(), boardOf(boards, td.getBoard()), WhoUpdated.getAutoWhoUpdated());
			} catch (Exception e) {
				log.error("Could not park over-budget task {}", td.getUuid(), e);
			}
		}
		return found;
	}

	private static AgentBoardData boardOf(List<AgentBoardData> boards, UUID boardUuid) {
		return boards.stream().filter(b -> b.getUuid().equals(boardUuid)).findFirst().orElseThrow();
	}

	/**
	 * Bind the task to a session: QUEUED -> ASSIGNED. Atomic under the
	 * task row lock; re-checks every constraint the poll applies. The
	 * assignment lives until sign-off, return, or the session closes.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public WorkerAssignment assign(UUID taskUuid, AgentBoardData board, UUID agentUuid,
			UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		return assign(taskUuid, board, agentUuid, sessionUuid, null, wu);
	}

	/**
	 * As above, for an agent that declared the roles it can take (see {@link #next(List, UUID,
	 * UUID, List)}). A task outside them is refused, and on a STRICT board priority is enforced
	 * among the declared roles: an architect-only agent is not held behind a coder task it could
	 * never be offered.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public WorkerAssignment assign(UUID taskUuid, AgentBoardData board, UUID agentUuid,
			UUID sessionUuid, List<String> roles, WhoUpdated wu) throws RelizaException {
		if (sessionUuid == null) throw new RelizaException("Assignment requires a session");
		if (board.isPaused()) {
			throw new RelizaException("Board " + board.getName() + " is paused ("
					+ board.getPause().level() + "): no new assignments");
		}
		if (agentBoardService.isSeatHolder(board, sessionUuid)) {
			throw new RelizaException("The coordinator seat session cannot take task assignments");
		}
		refuseOverBudget(taskUuid, board, wu);
		AgentTaskData td = lockedTask(taskUuid);
		requireStatus(td, taskUuid, TaskStatus.QUEUED);
		if (!dependenciesMet(td)) {
			throw new RelizaException("Task " + taskUuid + " has incomplete dependencies: "
					+ td.getDependsOn());
		}
		List<String> waiting = waitingOnGroups(td, agentBoardService.getBoardData(board.getUuid()).orElse(board), null);
		if (!waiting.isEmpty()) {
			throw new RelizaException("task " + keyOf(td) + " waits on group " + String.join(", ", waiting));
		}
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), td.getRole())
				.orElseThrow(() -> new RelizaException("Role " + td.getRole() + " is not configured on board " + board.getName()));
		if (rc.getKind() == AgentTaskRoleConfigData.RoleKind.HUMAN) {
			throw new RelizaException("Role " + rc.getName() + " is a HUMAN stage; agents cannot be assigned"
					+ " — an org admin signs off directly from the queue");
		}
		DeclaredRoles declared = DeclaredRoles.of(roles);
		if (!declared.admits(rc)) {
			throw new RelizaException("Task " + taskUuid + " is for role " + rc.getName()
					+ ", which is not among the roles this agent declared: " + declared.describe());
		}
		if (rc.isRequireDistinctAgent() && agentUuid.equals(td.lastSignOffAgent())) {
			throw new RelizaException("Role " + rc.getName()
					+ " requires a distinct agent from the last sign-off (separation of duties)");
		}
		// The poll only offers a task a session's model is fit for, but assign is callable
		// directly, and its contract is to re-check every constraint the poll applies. Without
		// this a role's strength floor held only for agents that happened to ask first.
		Double strength = new StrengthOf(sessionUuid).forRole(rc);
		if (!admitsStrength(td, rc, strength)) {
			Double required = requiredOf(td, rc);
			throw new RelizaException("Task " + taskUuid + " requires model strength "
					+ StrengthScale.format(required)
					+ (rc.getStrengthHeadroom() > 0 ? " (up to " + StrengthScale.format(required + rc.getStrengthHeadroom()) + ")" : "")
					+ " for role " + rc.getName() + "; this session's model rates "
					+ (null == strength ? "unrated" : StrengthScale.format(strength)) + " for it");
		}
		List<AgentTaskData> assigned = listByBoard(board.getUuid(), TaskStatus.ASSIGNED.name());
		long agentWip = assigned.stream().filter(t -> t.getAssignment() != null
				&& agentUuid.equals(t.getAssignment().agent())).count();
		if (agentWip >= board.getPerAgentWipLimit()) {
			throw new RelizaException("Agent " + agentUuid + " is at the board per-agent WIP limit ("
					+ board.getPerAgentWipLimit() + ")");
		}
		if (rc.getWipLimit() != null && rc.getWipLimit() > 0) {
			long roleWip = assigned.stream().filter(t -> t.getAssignment() != null
					&& rc.getName().equals(t.getAssignment().role())).count();
			if (roleWip >= rc.getWipLimit()) {
				throw new RelizaException("Role " + rc.getName() + " is at its WIP limit (" + rc.getWipLimit() + ")");
			}
		}
		if (board.getPriorityType() == AgentBoardData.PriorityType.STRICT) {
			// Priority is governance on this board: only the task the poll
			// would hand this agent may be assigned. Computed against the
			// same eligibility rules, so WIP/deps/distinct-agent all apply.
			UUID top = next(List.of(board), agentUuid, sessionUuid, roles, false)
					.map(wa -> wa.task().getUuid()).orElse(null);
			if (top != null && !top.equals(taskUuid)) {
				throw new RelizaException("Board " + board.getName() + " enforces STRICT priority:"
						+ " task " + top + " is ahead of " + taskUuid + " for this agent");
			}
		}
		List<AgentTaskInputService.InputVerdict> verdicts = agentTaskInputService.evaluate(board, rc, td);
		List<AgentTaskInputService.InputVerdict> unmet = verdicts.stream().filter(v -> !v.met()).toList();
		if (!unmet.isEmpty()) {
			throw new RelizaException("Task " + taskUuid + " is waiting on its inputs: "
					+ unmet.stream().map(AgentTaskInputService.InputVerdict::unmetReason)
							.collect(Collectors.joining("; ")));
		}
		String pv = AgentBoardService.promptVersion(board, rc.getPrompt());
		// the bindings are pinned: the agent is told which versions it works from, and a later
		// rejection of one of them can be traced to the work that consumed it
		List<ResolvedInput> bound = verdicts.stream().map(AgentTaskInputService.InputVerdict::resolved).toList();
		// The heads the hop starts from (task RD4-2): each linked PR in play, at its head and on its base, so the
		// sign-off can tell whether the hop's commits reached a PR and the task can say how far the base moved.
		td.setAssignment(new TaskAssignment(sessionUuid, agentUuid, rc.getName(), rc.getUuid(),
				ZonedDateTime.now(), pv, bound).withHeads(agentDeliveryService.inPlayHeads(td),
						agentDeliveryService.inPlayBases(td)));
		td.transitionStatus(TaskStatus.ASSIGNED, StatusTrigger.ASSIGN, AgentActor.ofSession(sessionUuid));
		td.addSession(sessionUuid);
		AgentTaskData saved = saveData(td, wu);
		// The session worked this board (D15): recorded once this commits, on the session's own lock.
		boardEffectsApplier.applyAfterCommit(new BoardEffects(board.getUuid()).sessionWorked(sessionUuid), wu);
		return new WorkerAssignment(rc.isBlindReview() ? redactForBlindRole(saved) : saved, rc.getName(), rc.getUuid(),
				agentBoardService.servedPromptFor(board, rc.getPrompt()), pv, bound,
				rc.getHopBudgetMicros());
	}

	/**
	 * The budget re-check at assignment (board-mechanics D25): usage that arrived after routing
	 * queued the round can still refuse it. Runs on an unlocked read, before this transaction takes
	 * the row lock, because the park has to commit although the assignment is refused, and it takes
	 * that same lock in its own transaction -- holding it here would deadlock it. A task that is
	 * not QUEUED is left to the locked checks, which say why properly, and so is one the park found
	 * affordable after all under its lock: usage may have completed the picture in between.
	 */
	private void refuseOverBudget(UUID taskUuid, AgentBoardData board, WhoUpdated wu) throws RelizaException {
		AgentTaskData td = getTaskData(taskUuid).orElse(null);
		if (null == td || td.getStatus() != TaskStatus.QUEUED || !AgentBudgetService.budgetApplies(td, board)) return;
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), td.getRole()).orElse(null);
		if (null == rc) return;
		AgentBudgetService.Projection p = budgetService.project(td, board, rc);
		if (p.fits()) return;
		AgentTaskData parked = routingService.parkOverBudget(taskUuid, board, wu);
		if (null == parked) return;
		throw new RelizaException(budgetService.refusal(p, rc) + "; the task is on hold for an operator");
	}

	/**
	 * Record "I did the work": ASSIGNED -> AWAITING_COORDINATOR with an
	 * appended sign-off — unless a human gate fires (role-level
	 * humanGate, or the per-task add-only flag), in which case the
	 * task parks ON_HOLD at OPERATOR level until a human verdict via
	 * {@link #humanReview}. The gate triggers only on an actual
	 * sign-off, so a task never routed through a gated role never
	 * pauses.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData signOff(UUID taskUuid, UUID sessionUuid, SignOffOutcome outcome,
			String note, WhoUpdated wu) throws RelizaException {
		return signOff(taskUuid, sessionUuid, outcome, note, List.of(), wu);
	}

	/**
	 * Close a hop, recording the documents it produced.
	 *
	 * <p>Two checks the plain path does not have. Every output the role DECLARES as required must
	 * be present, and every release offered as an output must actually be this session's document
	 * on this task. Both refuse rather than warn: the whole point of declaring outputs is that the
	 * next hop can rely on them, and a hop that signs off without its review items leaves a fixer with
	 * a paragraph to iterate over.
	 *
	 * <p>The refusal lands on the hop that can fix it — the agent is still assigned, still has its
	 * working tree, and can publish and sign off again.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData signOff(UUID taskUuid, UUID sessionUuid, SignOffOutcome outcome,
			String note, List<UUID> outputs, WhoUpdated wu) throws RelizaException {
		return signOff(taskUuid, sessionUuid, outcome, note, outputs, null, wu);
	}

	/**
	 * As above, with what the hop read (task RD2-34): every document published on the task since the
	 * assignment by someone other than this hop must be among {@code seenInputs}, or the sign-off is refused
	 * with no state change, naming each. Null skips the check, for callers that do not track what they read.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData signOff(UUID taskUuid, UUID sessionUuid, SignOffOutcome outcome,
			String note, List<UUID> outputs, List<UUID> seenInputs, WhoUpdated wu) throws RelizaException {
		return signOff(taskUuid, sessionUuid, outcome, note, outputs, seenInputs, null, wu);
	}

	/**
	 * As above, with the hop's statement that its round changes nothing to build (task RD4-13).
	 *
	 * <p>{@code noChange} matters on a pass that answers a review item about the signing role's own document:
	 * without it, a new round goes to the role that builds from it before the filer re-checks; with it,
	 * the round accepts the state as built and the task goes back to the filer. Whether a round changes
	 * what is built is the author's statement, so routing reads it rather than guessing from a diff.
	 * Recorded on the sign-off; refused on a rejection, which hands nothing over.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData signOff(UUID taskUuid, UUID sessionUuid, SignOffOutcome outcome,
			String note, List<UUID> outputs, List<UUID> seenInputs, Boolean noChange, WhoUpdated wu)
			throws RelizaException {
		return signOff(taskUuid, sessionUuid, outcome, note, outputs, seenInputs, noChange, null, wu);
	}

	/**
	 * As above, with the hop's statement that its round changed no code (task RD4-2).
	 *
	 * <p>A PASSED sign-off by a role holding CODE_PUSH is refused when the hop's linked PRs in play all sit at
	 * the heads recorded at its assignment: the hop's commits did not reach a PR (the RD3-5 side branch). A
	 * round that changed no code (a note-only round) says so with {@code noCode}, which passes and is recorded
	 * on the sign-off; refused on a rejection, which is not checked. A hop with no PR in play is not checked.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData signOff(UUID taskUuid, UUID sessionUuid, SignOffOutcome outcome,
			String note, List<UUID> outputs, List<UUID> seenInputs, Boolean noChange, Boolean noCode, WhoUpdated wu)
			throws RelizaException {
		if (outcome == null) throw new RelizaException("Sign-off requires an outcome");
		if (Boolean.TRUE.equals(noChange) && SignOffOutcome.PASSED != outcome) {
			throw new RelizaException("--no-change says a passed round changes nothing to build; a rejection"
					+ " hands nothing over, so sign off REJECTED without it");
		}
		if (Boolean.TRUE.equals(noCode) && SignOffOutcome.PASSED != outcome) {
			throw new RelizaException("--no-code says a passed round changed no code; a rejection is not checked"
					+ " for moved PRs, so sign off REJECTED without it");
		}
		AgentTaskData td = lockedTask(taskUuid);
		TaskAssignment a = requireAssignmentBySession(td, taskUuid, sessionUuid);
		assertPublishedSinceAssignmentSeen(td, a, sessionUuid, outputs, seenInputs);
		List<UUID> declared = validateOutputs(td, a, sessionUuid, outputs, outcome);
		if (SignOffOutcome.PASSED == outcome) assertPassLeavesNothingBlocking(td, declared);
		// Before the gate as well as before routing: a gated hop hands over at the verdict, but the
		// agent that can fix a failed check is the one signing off now (elements.md §7).
		agentDocumentService.assertBlockingElementChecksPass(td, declared);
		assertTestedHeads(td, declared, outcome);
		if (SignOffOutcome.PASSED == outcome && !Boolean.TRUE.equals(noCode)
				&& roleHolds(td, a.role(), AgentTaskRoleConfigData.AgentCapability.CODE_PUSH)) {
			Optional<String> unmoved = agentDeliveryService.noLinkedPrMoved(td, a);
			if (unmoved.isPresent()) throw new RelizaException(unmoved.get());
		}
		assertDocumentComponentsUnlocked(td);
		// Frozen here rather than resolved on read: the hop has ended, so its cost has ended too.
		// A later price correction must not restate what this sign-off says was spent.
		HopUsage usage = hopUsageWithAllowance(td, a, taskUuid, sessionUuid);
		// What a reviewer's hop reviewed, recorded while the assignment still says: a gated hop's
		// acceptance comes after the assignment is gone, and must promote the same documents (T-3).
		List<UUID> reviewed = reviewedDocuments(td, a);
		td.addSignOff(new SignOff(a.role(), a.roleUuid(), a.agent(), a.session(), a.assignedAt(),
				ZonedDateTime.now(), outcome, note, a.promptVersion(), null, usage, declared,
				reviewed.isEmpty() ? null : reviewed, null, Boolean.TRUE.equals(noChange) ? Boolean.TRUE : null, null,
				Boolean.TRUE.equals(noCode) ? Boolean.TRUE : null, a.baseHeads()));
		td.setAssignment(null);
		SignOff closing = td.getSignOffs().get(td.getSignOffs().size() - 1);
		BoardEffects effects = new BoardEffects(td.getBoard());
		alertIfOverAllowance(td, a.role(), usage, effects);
		effects.roleHistory(a.agent(), a.roleUuid());
		if (humanGateFires(td, a.role(), outcome)) {
			td.setHold(new TaskHold(HoldLevel.OPERATOR, HoldKind.HUMAN_GATE, a.role(),
					// A sentence the page prints as it is (RD2-23 architecture-2): role and outcome as words.
					a.role() + "'s sign-off (" + outcome.name().toLowerCase() + ") awaits human review",
					AgentActor.system("humanGate"), ZonedDateTime.now()));
			td.setRequireHumanReview(false);
			td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HUMAN_GATE, AgentActor.ofSession(sessionUuid));
			// Collected, not posted. This call used to go straight to postEvent, which takes the
			// board row while this method holds the task row -- the inversion that deadlocks
			// against a concurrent publish, which takes board then task.
			// On the feed only: placing the gate notifies the people who can act as AGENT_TASK_NEEDS_PERSON
			// (saveData), so this ALERT going out too was the same gate twice in the inbox (RD2-15).
			effects.alertOnFeed("Task " + taskLabel(td) + " awaits human review of its " + a.role()
					+ " sign-off");
		} else {
			// Handed over before routing, which binds the next role's inputs at their floors: a
			// draft would not satisfy an ASSEMBLED floor, and the next hop would find nothing to read.
			// A gated hop's outputs wait for the verdict (operator-actions D13, D14).
			agentDocumentService.promoteOutputs(declared, wu);
			// A reviewer's pass is the review of what it had pinned (D14, task 0192a587): those
			// documents become READY_TO_SHIP. A rejection reviewed them too, and found them wanting,
			// so it promotes nothing; a maker's pass only hands over its own outputs, above. The pass
			// is the one routing acts on: a PASSED whose own index leaves an item blocking is routed
			// as a rejection, and promotes nothing either (T-2).
			List<AgentDocumentService.RefusedPromotion> refused = List.of();
			if (SignOffOutcome.PASSED == outcome && !leavesBlocking(declared, boardOf(td).getBlockingPriority())) {
				AgentDocumentService.Promotions promoted = agentDocumentService.promoteReviewedRecording(reviewed, wu);
				refused = promoted.refused();
				notPromoted(refused, "the " + a.role() + " hop", effects);
				// Recorded on the hop, which is still the last sign-off: which review promoted a
				// document, and why a guard kept one back, are read from it (task fda2c9f1).
				closing = closing.withPromotions(promoted.made());
				td.getSignOffs().set(td.getSignOffs().size() - 1, closing);
			}
			td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.SIGNOFF, AgentActor.ofSession(sessionUuid));
			// The board decides what happens next: the coordinator is for intake, splitting,
			// ordering and exceptions, not for reading an index and picking the obvious role. A
			// refused promotion goes along, so a next role left without its input says why (T-1).
			AgentRoutingService.Routed routed = routingService.route(td, boardOf(td), closing, null, refused);
			td = routed.task();
			merge(effects, routed.effects());
		}
		AgentTaskData saved = saveData(td, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * Whether any of these releases is a review item round that leaves an item blocking at the board's
	 * blocking priority: the routing reads the index, not the verdict, and so does promotion.
	 */
	/**
	 * A PASSED sign-off whose own review item round leaves an item blocking on this board is a rejection
	 * (task de91c937): routing already treats it as one, and the recorded verdict must not say
	 * otherwise. Refused before anything is written, as the human gate refuses the same contradiction.
	 * A board with a blockingPriority still passes with items above it open.
	 */
	private void assertPassLeavesNothingBlocking(AgentTaskData td, List<UUID> outputs) throws RelizaException {
		Integer blockingPriority = boardOf(td).getBlockingPriority();
		for (UUID r : null == outputs ? List.<UUID>of() : outputs) {
			DocumentRef doc = sharedReleaseService.getReleaseData(r).map(ReleaseData::getDocument).orElse(null);
			if (null == doc || null == doc.reviewItems() || RearmSpecificationType.BOARD_QUESTIONS == doc.specification()) continue;
			List<BoardReviewItemIndex.BoardReviewItem> blocking = doc.reviewItems().blockingReviewItems(blockingPriority);
			if (blocking.isEmpty()) continue;
			String open = blocking.stream().map(f -> f.id() + (null == f.priority() ? "" : " at P" + f.priority()))
					.collect(Collectors.joining(", "));
			// Publish again first (T-2 of round 1): a CLI that clears its recorded outputs after a refused
			// sign-off would otherwise send a REJECTED with no round, which routes to the coordinator.
			throw new RelizaException("That is a rejection: your " + doc.specification() + " index has " + open
					+ " OPEN, which " + (blocking.size() == 1 ? "blocks" : "block") + " on this board. Publish the"
					+ " round again, which returns the same release, and sign off REJECTED with it in --outputs;"
					+ " or RESOLVE / WITHDRAW " + (blocking.size() == 1 ? "it" : "them") + " in a new round.");
		}
	}

	private boolean leavesBlocking(List<UUID> releases, Integer blockingPriority) {
		return !blockingIn(releases, blockingPriority).isEmpty();
	}

	/** The items still OPEN and blocking on the board in these releases' review item indexes. */
	private List<BoardReviewItemIndex.BoardReviewItem> blockingIn(List<UUID> releases, Integer blockingPriority) {
		List<BoardReviewItemIndex.BoardReviewItem> out = new ArrayList<>();
		if (null == releases) return out;
		for (UUID r : releases) {
			Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(r);
			if (rd.isEmpty() || null == rd.get().getDocument() || null == rd.get().getDocument().reviewItems()) continue;
			out.addAll(rd.get().getDocument().reviewItems().blockingReviewItems(blockingPriority));
		}
		return out;
	}

	/** The refusal of an acceptance that leaves items blocking: they have to be decided, or sent back. */
	private static String rejection(List<BoardReviewItemIndex.BoardReviewItem> blocking) {
		boolean one = blocking.size() == 1;
		return "That is a rejection: " + String.join(", ", blocking.stream().map(BoardReviewItemIndex.BoardReviewItem::id).toList())
				+ (one ? " is OPEN and blocks; accept it, dismiss it, or reject with it attached"
						: " are OPEN and block; accept them, dismiss them, or reject with them attached");
	}

	/**
	 * The documents published on the task since the hop was assigned, by anyone but this hop, that the hop does
	 * not say it read (task RD2-34). The baseline is what the hop started from: every document already on the
	 * task at its assignment. Its own publishes (and the check reports the board cut on them) never count.
	 */
	private void assertPublishedSinceAssignmentSeen(AgentTaskData td, TaskAssignment a, UUID sessionUuid,
			List<UUID> outputs, List<UUID> seenInputs) throws RelizaException {
		if (null == seenInputs || null == td.getReleases() || null == a.assignedAt()) return;
		Set<UUID> seen = new HashSet<>(seenInputs);
		if (null != outputs) seen.addAll(outputs);
		List<ReleaseData> rounds = new ArrayList<>();
		Set<UUID> own = new HashSet<>();
		for (UUID r : td.getReleases()) {
			ReleaseData rd = sharedReleaseService.getReleaseData(r).orElse(null);
			if (null == rd || null == rd.getDocument() || !td.getUuid().equals(rd.getDocument().task())) continue;
			if (ReleaseLifecycle.CANCELLED == rd.getLifecycle() || ReleaseLifecycle.REJECTED == rd.getLifecycle()) continue;
			if (sessionUuid.equals(rd.getDocument().session())) own.add(rd.getUuid());
			rounds.add(rd);
		}
		List<String> unseen = new ArrayList<>();
		for (ReleaseData rd : rounds) {
			DocumentRef d = rd.getDocument();
			if (seen.contains(rd.getUuid()) || own.contains(rd.getUuid())) continue;
			if (null == rd.getCreatedDate() || !rd.getCreatedDate().isAfter(a.assignedAt())) continue;
			if (null != d.elementChecks() && null != d.elementChecks().scope() && own.contains(d.elementChecks().scope().checked())) continue;
			unseen.add(d.specification() + (null == d.round() ? "" : " round " + d.round())
					+ (Boolean.TRUE.equals(d.advisory()) ? " (advisory)" : "") + " v" + rd.getVersion());
		}
		if (!unseen.isEmpty()) {
			throw new RelizaException("Task " + td.label() + " has documents published since your assignment that this"
					+ " sign-off does not acknowledge: " + String.join(", ", unseen)
					+ ". Run task show --session " + sessionUuid + ", read it, then sign off again.");
		}
	}

	/**
	 * The documents a hop reviewed: its pinned DOCUMENT inputs that another role produced, when its
	 * role is a reviewer (every declared output an index -- the definition routing uses). Empty for
	 * a maker, and for a role removed while the hop ran, since what it was cannot be told.
	 *
	 * <p>A re-running reviewer is pinned its own earlier rounds (a tester its previous BOARD_TEST_REPORT)
	 * and, after a person's answer, the board's answer round. Promoting those would record work as
	 * reviewed that nobody reviewed -- a report with a P2 still open went READY_TO_SHIP on its
	 * author's next pass (task 32b071f5). They stay handed over; a person at a gate or a different
	 * reviewer role can still promote them.
	 */
	private List<UUID> reviewedDocuments(AgentTaskData td, TaskAssignment a) {
		if (null == a.resolvedInputs() || a.resolvedInputs().isEmpty()) return List.of();
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(td.getBoard(), a.role()).orElse(null);
		if (null == rc || !AgentRoutingService.isReviewer(rc)) return List.of();
		Set<RearmSpecificationType> ownKinds = null == rc.getProducesOutputs() ? Set.of()
				: rc.getProducesOutputs().stream().filter(Objects::nonNull).map(ProducedOutput::specification)
						.filter(Objects::nonNull).collect(Collectors.toSet());
		return a.resolvedInputs().stream()
				.filter(ri -> null != ri && InputKind.DOCUMENT == ri.kind() && null != ri.release())
				.map(ResolvedInput::release)
				.distinct()
				.filter(r -> anotherRolesWork(sharedReleaseService.getReleaseData(r)
						.map(ReleaseData::getDocument).orElse(null), a.session(), ownKinds))
				.toList();
	}

	/**
	 * Whether a pinned document is another role's work, which a reviewer's pass may promote. Not
	 * when this hop's own session published it (the reviewer's earlier rounds), not when it is of a
	 * kind the signing role produces (the same role in another session, or after a reopen), and not
	 * when no session published it (the board's policy, answer and unwind rounds: nobody's work to
	 * review).
	 */
	static boolean anotherRolesWork(DocumentRef doc, UUID hopSession, Set<RearmSpecificationType> ownKinds) {
		if (null == doc) return false;
		if (null != hopSession && hopSession.equals(doc.session())) return false;
		if (null != doc.specification() && ownKinds.contains(doc.specification())) return false;
		return null != doc.session();
	}

	/**
	 * Check the releases a hop offers as its outputs, and that it produced what its role declares.
	 *
	 * <p>A hop whose outputs carry a BOARD_QUESTIONS round with an item blocking at the board's blocking
	 * priority is asking, not delivering (gaps §1.17): its role's required outputs are waived for
	 * that hop, and it must sign off REJECTED. Blocking, not merely open, because that is what
	 * routes: a question below the line is recorded and not sent anywhere, so it asks nothing and
	 * the hop still owes its document. A PASSED sign-off with only a question would count as a pass for a REQUIRED role
	 * the moment the frame unwinds, and a task could complete on a question. The hop that answers
	 * owes its outputs again.
	 *
	 * <p>An output a later version of its round replaced is taken as that newer version (task RD4-7), once:
	 * a hop that published the same path twice and offers both hands over the correction only, and the
	 * replaced version stays a draft. Then as the newest round of its series this hop published (task
	 * RD4-18): a round the hop replaced by publishing the next one is not an output, so a failing round 1
	 * the hop fixed in round 2 neither blocks the hand-over nor is handed over, and stays a draft.
	 *
	 * @param outcome the sign-off's outcome; null on a return, where nothing is mandatory
	 * @return the accepted output uuids, to be frozen onto the hop record
	 */
	private List<UUID> validateOutputs(AgentTaskData td, TaskAssignment a, UUID sessionUuid,
			List<UUID> outputs, SignOffOutcome outcome) throws RelizaException {
		List<UUID> offered = new ArrayList<>(new LinkedHashSet<>(null == outputs ? List.<UUID>of()
				: outputs.stream().map(agentDocumentService::newestVersionOf)
						.map(r -> agentDocumentService.newestRoundOfHop(td, sessionUuid, a.assignedAt(), r)).toList()));
		List<ReleaseData> resolved = new ArrayList<>();
		for (UUID r : offered) {
			ReleaseData rd = sharedReleaseService.getReleaseData(r)
					.orElseThrow(() -> new RelizaException("Output release not found: " + r));
			DocumentRef doc = rd.getDocument();
			if (null == doc) {
				throw new RelizaException("Release " + r + " is not a document release, "
						+ "so it cannot be an output of this hop");
			}
			// Ownership, both halves. A release published by another session, or against another
			// task, would let one hop take credit for another's work and would put a document in
			// front of the next hop that nobody in this hop actually wrote.
			if (!sessionUuid.equals(doc.session())) {
				throw new RelizaException("Release " + r + " was published by another session, "
						+ "so this hop cannot claim it as an output");
			}
			if (null != doc.task() && !td.getUuid().equals(doc.task())) {
				throw new RelizaException("Release " + r + " belongs to task " + doc.task()
						+ ", not to this one");
			}
			// Inside THIS hop's window. Session and task ownership are not enough: a session
			// re-assigned to the same task after a return holds both, so without this it could
			// offer its round-one document as round two's output and satisfy a required-output
			// rule having published nothing. The declared output would then be a document about
			// work that happened before the hop that claims it.
			if (null != a.assignedAt() && null != rd.getCreatedDate()
					&& rd.getCreatedDate().isBefore(a.assignedAt())) {
				throw new RelizaException("Release " + r + " was published before this hop began, "
						+ "so it is not an output of it");
			}
			resolved.add(rd);
		}
		if (null != outcome) {
			Integer blockingPriority = boardOf(td).getBlockingPriority();
			boolean asking = resolved.stream().anyMatch(rd -> asksQuestion(rd, blockingPriority));
			if (asking && SignOffOutcome.PASSED == outcome) {
				throw new RelizaException("This hop has open questions; sign off REJECTED so the board can"
						+ " route them, or resolve them and publish your output");
			}
			if (!asking) assertDeclaredOutputsPresent(td, a, resolved, outcome);
		}
		return offered;
	}

	/** A BOARD_QUESTIONS round with an item blocking at the blocking priority: a question that routes. */
	static boolean asksQuestion(ReleaseData rd, Integer blockingPriority) {
		DocumentRef doc = rd.getDocument();
		return null != doc && RearmSpecificationType.BOARD_QUESTIONS == doc.specification()
				&& null != doc.reviewItems() && !doc.reviewItems().blockingReviewItems(blockingPriority).isEmpty();
	}

	/**
	 * The PR heads a review or test round says it covered (task 3b97ccfd), against the task's
	 * linked PRs: every head names a linked PR, and a passing round of a task with linked PRs names
	 * a head for each, so the board knows what a pass was of and can notice a push after it. A
	 * rejection, and a task without PRs, need none.
	 */
	private void assertTestedHeads(AgentTaskData td, List<UUID> outputs, SignOffOutcome outcome)
			throws RelizaException {
		Set<String> linked = AgentDeliveryService.matchKeys(td);
		for (UUID r : null == outputs ? List.<UUID>of() : outputs) {
			DocumentRef doc = sharedReleaseService.getReleaseData(r).map(ReleaseData::getDocument).orElse(null);
			BoardReviewItemIndex index = null == doc ? null : doc.reviewItems();
			if (null == index || (index.kind() != RearmSpecificationType.BOARD_TEST_REPORT
					&& index.kind() != RearmSpecificationType.BOARD_REVIEW_ITEMS)) continue;
			List<BoardReviewItemIndex.TestedHead> tested = null == index.tested() ? List.of() : index.tested();
			Set<String> named = new LinkedHashSet<>();
			for (BoardReviewItemIndex.TestedHead t : tested) {
				String key = AgentDeliveryService.matchKey(t.pr());
				if (!linked.contains(key)) {
					throw new RelizaException("tested names " + t.pr() + ", which is not linked to this task"
							+ (linked.isEmpty() ? " (it links no PR)" : " (linked: " + String.join(", ", td.getPrUrls()) + ")"));
				}
				named.add(key);
			}
			// Tested heads are the tester's (task 5ec48b02): a design reviewer passing a task whose coder
			// already linked a PR did not test that PR. Any index may name heads, and every one it
			// names must be linked (above); only a BOARD_TEST_REPORT pass must name them all.
			if (index.kind() != RearmSpecificationType.BOARD_TEST_REPORT) continue;
			if (SignOffOutcome.PASSED != outcome || BoardReviewItemIndex.BoardReviewVerdict.PASSED != index.verdict()) continue;
			for (String url : null == td.getPrUrls() ? List.<String>of() : td.getPrUrls()) {
				if (!named.contains(AgentDeliveryService.matchKey(url))) {
					throw new RelizaException("A pass says what it tested: say the head you tested for " + url
							+ " (tested: [{pr, head}]) in the " + index.kind() + " index");
				}
			}
		}
	}

	/**
	 * Every output the role declares as required must be among the hop's outputs.
	 *
	 * <p>A HUMAN role is exempt: a person reviewing in the UI does not publish a review item document
	 * through the CLI, and requiring one would make the gate unusable. That exemption is why the
	 * check reads the role config rather than assuming every hop produces the same things.
	 */
	private void assertDeclaredOutputsPresent(AgentTaskData td, TaskAssignment a,
			List<ReleaseData> resolved, SignOffOutcome outcome) throws RelizaException {
		if (null == a.agent()) return; // human hop: see the note above
		// An investigation delivers its report and nothing else (task RD4-12): the investigating role's pass carries
		// it, whatever else the role produces on a work task, and a review hop owes nothing but its verdict.
		if (td.isInvestigation()) {
			if (SignOffOutcome.PASSED != outcome || !td.getInvestigation().investigatedBy(a.roleUuid(), a.role())) return;
			boolean report = resolved.stream().anyMatch(rd -> null != rd.getDocument()
					&& RearmSpecificationType.BOARD_INVESTIGATION_REPORT == rd.getDocument().specification());
			if (!report) {
				throw new RelizaException("Investigation " + td.label() + " completes with its report: publish an "
						+ RearmSpecificationType.BOARD_INVESTIGATION_REPORT + " with `rearm agent doc publish` and pass it as an"
						+ " output");
			}
			return;
		}
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(td.getBoard(), a.role()).orElse(null);
		if (null == rc || null == rc.getProducesOutputs() || rc.getProducesOutputs().isEmpty()) return;
		for (ProducedOutput po : rc.getProducesOutputs()) {
			if (!po.required()) continue;
			// A report is an investigation's deliverable, never a work task's (task RD4-12).
			if (RearmSpecificationType.BOARD_INVESTIGATION_REPORT == po.specification()) continue;
			boolean present = resolved.stream().anyMatch(rd ->
					null != rd.getDocument() && rd.getDocument().specification() == po.specification());
			if (!present) {
				throw new RelizaException("Role " + a.role() + " must publish a " + po.specification()
						+ " document before signing off. Publish it with `rearm agent doc publish` "
						+ "and pass it as an output.");
			}
		}
	}

	/**
	 * Refuse the sign-off when any of the board's document components is locked.
	 *
	 * <p>D5's second clause: a lock raised on the documents repository stops work being SIGNED OFF,
	 * not merely released. A lock that only bit at the next publish would let hops keep closing
	 * over a repository somebody has deliberately frozen.
	 *
	 * <p>Scoped to components whose vcs IS the board's documents repository. A specification
	 * component composed into the target but living elsewhere is somebody else's document and is
	 * not what "lock the documents repo" meant.
	 */
	private void assertDocumentComponentsUnlocked(AgentTaskData td) throws RelizaException {
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard()).orElse(null);
		if (null == board || null == board.getTarget() || null == board.getDocumentsRepo()) return;
		for (UUID comp : agentTaskInputService.documentComponentsOfRepo(board)) {
			UUID baseBranch = branchService.getBaseBranchOfComponent(comp)
					.map(io.reliza.model.Branch::getUuid).orElse(null);
			componentLockService.assertUnlocked(comp, baseBranch, LockedOperation.TASK_SIGN_OFF);
		}
	}

	private boolean humanGateFires(AgentTaskData td, String role, SignOffOutcome outcome) {
		if (td.isRequireHumanReview()) return true;
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(td.getBoard(), role).orElse(null);
		if (rc == null) return false;
		return switch (rc.getHumanGate()) {
			case ON_ANY_SIGNOFF -> true;
			case ON_PASS -> outcome == SignOffOutcome.PASSED;
			case NONE -> false;
		};
	}


	/** A board, its tasks, and enough of each to see what everyone else is doing. */
	public record BoardSnapshot(AgentBoardData board, List<TaskSnapshot> tasks) {}

	// ---------- blind roles (task 0192a587, gaps §1.13) ----------

	/**
	 * Whether this caller reads the task as the blind role it is assigned to: the key that opened
	 * the session currently holding the task, in a role with {@code blindReview}. Keyed by the
	 * caller's key because the task reads take no session; a blind reviewer reading through its
	 * own key is the reader this is for.
	 */
	public boolean readsBlind(AgentTaskData td, UUID callerKey) {
		if (null == callerKey || null == td || null == td.getAssignment()) return false;
		AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(td.getBoard(), td.getAssignment().role())
				.orElse(null);
		if (null == rc || !rc.isBlindReview()) return false;
		return agentSessionService.getSessionData(td.getAssignment().session())
				.map(AgentSessionData::getApiKey)
				.filter(callerKey::equals)
				.isPresent();
	}

	/** The task as this caller may read it: redacted for its blind role, whole for everyone else. */
	public AgentTaskData forReader(AgentTaskData td, UUID callerKey) {
		return readsBlind(td, callerKey) ? redactForBlindRole(td) : td;
	}

	/**
	 * The earlier hops' account of the work removed: each sign-off's note, session and agent, and
	 * each return's description, session and agent, read as null. The outcome, the role, the time,
	 * the outputs and the documents stay -- the reviewer reads the work, not the worker's account
	 * of it. In place, on a copy freshly read for this caller; never saved.
	 */
	public static AgentTaskData redactForBlindRole(AgentTaskData td) {
		if (null == td) return null;
		List<SignOff> signOffs = new ArrayList<>();
		for (SignOff so : td.getSignOffs()) {
			signOffs.add(new SignOff(so.role(), so.roleUuid(), null, null, so.assignedAt(), so.signedOffAt(),
					so.outcome(), null, so.promptVersion(), so.reviewedBy(), so.usage(), so.outputs()));
		}
		td.setSignOffs(signOffs);
		List<TaskReturn> returns = new ArrayList<>();
		for (TaskReturn tr : td.getReturns()) {
			returns.add(new TaskReturn(tr.role(), tr.roleUuid(), null, null, tr.reason(), null, tr.returnedAt(),
					tr.usage(), tr.outputs()));
		}
		td.setReturns(returns);
		return td;
	}

	/**
	 * @param holder who holds it now, or null
	 * @param dependsOn what it waits on, each with its own status, so a reader can tell whether
	 *        the wait is over without another call per dependency
	 * @param latestDocuments newest release per specification type
	 * @param waitingOn top of the question stack, or null
	 */
	public record TaskSnapshot(AgentTaskData task, AgentActor holder, ZonedDateTime heldSince,
			List<TaskDependency> dependsOn, List<UUID> latestDocuments,
			AgentTaskData.QuestionFrame waitingOn, Long spentMicros, Long budgetMicros) {}

	public record TaskDependency(UUID task, String externalRef, String title, TaskStatus status) {}

	/**
	 * Everything happening on one board, in one read.
	 *
	 * <p>Assembled from what is already loaded rather than per task: the alternative is an agent
	 * making one call per task to find out whether the thing it depends on has moved, which is
	 * the behaviour this exists to remove.
	 */
	public BoardSnapshot snapshot(AgentBoardData board) {
		// Spend is read from the board's usage rows once for the whole snapshot, not per task.
		AgentBudgetService.BoardSpend spend = budgetService.boardSpend(board);
		List<AgentTaskData> tasks = spend.tasks();
		Map<UUID, AgentTaskData> byUuid = new LinkedHashMap<>();
		tasks.forEach(t -> byUuid.put(t.getUuid(), t));
		List<TaskSnapshot> out = new ArrayList<>();
		for (AgentTaskData td : tasks) {
			List<TaskDependency> deps = new ArrayList<>();
			if (null != td.getDependsOn()) {
				for (UUID d : td.getDependsOn()) {
					AgentTaskData dep = byUuid.get(d);
					if (null != dep) {
						deps.add(new TaskDependency(d, dep.getExternalRef(), dep.getTitle(), dep.getStatus()));
					} else {
						// A dependency on another board, or one archived away. Naming it with an
						// unknown status is more useful than dropping it, which would read as
						// "nothing to wait for".
						deps.add(new TaskDependency(d, null, null, null));
					}
				}
			}
			out.add(new TaskSnapshot(td,
					null != td.getAssignment() ? AgentActor.ofSession(td.getAssignment().session()) : null,
					null != td.getAssignment() ? td.getAssignment().assignedAt() : null,
					deps,
					latestDocumentReleases(td),
					td.getQuestionStack().isEmpty() ? null
							: td.getQuestionStack().get(td.getQuestionStack().size() - 1),
					spend.spentOn(td),
					td.getBudgetMicros()));
		}
		return new BoardSnapshot(board, out);
	}

	/** Newest release per specification type on a task. */
	private List<UUID> latestDocumentReleases(AgentTaskData td) {
		if (null == td.getReleases()) return List.of();
		Map<RearmSpecificationType, UUID> newest = new LinkedHashMap<>();
		for (UUID r : td.getReleases()) {
			sharedReleaseService.getReleaseData(r).ifPresent(rd -> {
				// A replaced version is never the newest of its round (task RD4-7).
				if (null != rd.getDocument() && !rd.getDocument().superseded()) {
					newest.put(rd.getDocument().specification(), rd.getUuid());
				}
			});
		}
		return List.copyOf(newest.values());
	}

	/**
	 * Require a stronger (or weaker) model for one task than its role asks for.
	 *
	 * <p>Recorded on the task with who and when rather than as a status transition: the status did
	 * not change, and a row whose from and to are equal would split one queue wait into two
	 * intervals in every cycle-time view.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData setRequiredStrength(UUID taskUuid, Double requiredStrength,
			AgentActor actor, WhoUpdated wu) throws RelizaException {
		StrengthScale.validate("requiredStrength", requiredStrength);
		AgentTaskData td = lockedTask(taskUuid);
		answeredByActing(td, actor, "strength set", null == requiredStrength ? "cleared to the role's"
				: StrengthScale.format(requiredStrength), wu);
		td.setRequiredStrength(requiredStrength);
		td.setStrengthSetBy(actor);
		td.setStrengthSetAt(ZonedDateTime.now());
		return saveData(td, wu);
	}

	/**
	 * One task an agent may take, with the role it would take it as and how strong the session's
	 * model is for that role.
	 */
	private record Candidate(AgentTaskData task, AgentTaskRoleConfigData role, Double strength) {}

	/**
	 * A session's strength per role, worked out once per role within one poll. The session row is
	 * read once; each role then costs a catalogue lookup at most.
	 */
	private final class StrengthOf {
		private final AgentSessionData session;
		private final Map<UUID, Double> byRole = new java.util.HashMap<>();
		private boolean baseKnown;
		private Double base;

		StrengthOf(UUID sessionUuid) {
			this.session = null == sessionUuid ? null : agentSessionService.getSessionData(sessionUuid).orElse(null);
		}

		Double forRole(AgentTaskRoleConfigData rc) {
			if (null == rc || null == rc.getUuid()) {
				if (!baseKnown) {
					base = modelOntologyService.effectiveStrength(session, null);
					baseKnown = true;
				}
				return base;
			}
			return byRole.computeIfAbsent(rc.getUuid(), k -> modelOntologyService.effectiveStrength(session, rc));
		}
	}

	/**
	 * Which of the tasks an agent may take it is offered.
	 *
	 * <p>Preference, never permission. In order: a question about something this agent wrote,
	 * because whoever produced the document is who can answer about it most cheaply; then the
	 * task's level; then an answer coming back to the session that asked; then a role this agent
	 * has done on this board before; then the coordinator's order; then, only between tasks the
	 * coordinator ranked equal, how closely the model fits the task's strength requirement, so the
	 * cheapest model that suffices gets the work; then the role's place in the pipeline; then the
	 * oldest task.
	 *
	 * <p>Task order comes before both role order and strength fit. The other way round, something
	 * other than the coordinator decided which of its tasks came first: a hotfix waiting on qa lost
	 * to a refactor waiting on a coder -- by pipeline position, or because the refactor's floor
	 * happened to sit closer to the polling model -- and a STRICT board refused the hotfix outright.
	 */
	private Candidate preferred(List<Candidate> eligible, AgentBoardData board, UUID agentUuid,
			UUID sessionUuid) {
		Set<UUID> agentRoles = roleHistoryService.rolesFor(board.getUuid(), agentUuid);
		return eligible.stream().min(Comparator
				.comparing((Candidate c) -> askedAboutMyWork(c.task(), agentUuid) ? 0 : 1)
				// The level the board reads (RD2-1): an unset task takes the board default; none sorts last.
				.thenComparingInt(c -> {
					Integer level = effectiveWorkLevel(c.task(), board);
					return null != level ? level : Integer.MAX_VALUE;
				})
				.thenComparing(c -> answerForMySession(c.task(), sessionUuid) || reportForMySession(c.task(), sessionUuid) ? 0 : 1)
				.thenComparing(c -> agentRoles.contains(c.role().getUuid()) ? 0 : 1)
				.thenComparingInt(c -> c.task().getOrderIndex())
				.thenComparingLong(c -> StrengthScale.distanceAbove(c.strength(), requiredOf(c.task(), c.role())))
				.thenComparingInt(c -> c.role().getOrderIndex())
				// Stated rather than left to the repository's sort and to min() keeping the first
				// of two equal elements: a tie the coordinator did not break goes to the task that
				// has waited longest.
				.thenComparing(c -> c.task().getCreatedDate(), Comparator.nullsLast(Comparator.naturalOrder())))
				.orElse(eligible.get(0));
	}

	/** A question about a document this agent produced. */
	private boolean askedAboutMyWork(AgentTaskData td, UUID agentUuid) {
		if (td.getQuestionStack().isEmpty() || null == agentUuid) return false;
		return agentUuid.equals(td.getQuestionStack().get(td.getQuestionStack().size() - 1).askingAgent());
	}

	/** An answer coming back to the session that asked, which still holds the context. */
	private boolean answerForMySession(AgentTaskData td, UUID sessionUuid) {
		if (td.getQuestionStack().isEmpty() || null == sessionUuid) return false;
		return sessionUuid.equals(td.getQuestionStack().get(td.getQuestionStack().size() - 1).askingSession());
	}

	/**
	 * A report coming back to the session that commissioned it (task RD4-12), which still holds the context: a
	 * report returned since the task was last assigned, commissioned by this session.
	 */
	private boolean reportForMySession(AgentTaskData td, UUID sessionUuid) {
		if (null == sessionUuid || td.getReportsReturned().isEmpty()) return false;
		ZonedDateTime lastAssigned = null;
		for (AgentTaskData.StatusChange c : null == td.getStatusHistory() ? List.<AgentTaskData.StatusChange>of()
				: td.getStatusHistory()) {
			if (StatusTrigger.ASSIGN == c.trigger()) lastAssigned = c.at();
		}
		final ZonedDateTime since = lastAssigned;
		return td.getReportsReturned().stream().anyMatch(r -> sessionUuid.equals(r.session())
				&& (null == since || null == r.at() || r.at().isAfter(since)));
	}

	/** What a task needs from a model: its own requirement, else its role's. */
	private static Double requiredOf(AgentTaskData td, AgentTaskRoleConfigData rc) {
		return null != td.getRequiredStrength() ? td.getRequiredStrength() : rc.getRequiredStrength();
	}

	/**
	 * Whether this session's model is strong enough for the role, and not needlessly stronger.
	 *
	 * <p>The task's requirement wins over the role's, which is how a coordinator says "this one is
	 * harder than the role usually is". The role's headroom applies either way.
	 */
	private boolean admitsStrength(AgentTaskData td, AgentTaskRoleConfigData rc, Double strength) {
		return StrengthScale.admits(strength, requiredOf(td, rc), rc.getStrengthHeadroom());
	}

	/**
	 * Save a task whose hop has ended, letting the board decide what happens next.
	 *
	 * <p>One place, so every path that ends a hop -- agent sign-off, human verdict, human sign-off,
	 * hold lift -- routes the same way. A path that saved without routing would park its tasks
	 * in a state the coordinator has been told not to touch.
	 */
	/**
	 * A human answers the questions a task is waiting on (D2, D6).
	 *
	 * <p>The answer becomes a round of the BOARD_QUESTIONS index rather than a note, so it reaches the
	 * asking agent as a pinned input instead of prose it would have to go and find, and so the
	 * no-progress rule sees the open set change rather than the same ids twice.
	 *
	 * <p><b>This method owns the only copy of the task written in this transaction</b> (D14). The
	 * round is cut against that copy and its release added to it here; the publish path
	 * deliberately links nothing, because linking would load and save a second copy that the save
	 * at the end of this method would overwrite, dropping the answer release from the task while
	 * the asker's pin still pointed at it.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData answer(UUID taskUuid, List<AgentDocumentService.Answer> answers,
			AgentActor by, boolean liftHold, WhoUpdated wu) throws RelizaException {
		if (null == by) throw new RelizaException("An answer requires an actor");
		AgentTaskData td = lockedTask(taskUuid);
		if (td.getQuestionStack().isEmpty()) {
			throw new RelizaException("Nothing is waiting on an answer on task " + taskUuid);
		}
		// Not while an agent is mid-hop on it. Answering pops the frame, re-queues the asker and
		// clears the assignment -- under the agent that is working the answering role right now,
		// whose sign-off would then fail because it no longer holds the task. A QUEUED answerer
		// has not started and may be pre-empted; an ASSIGNED one is owed the chance to finish.
		if (TaskStatus.ASSIGNED == td.getStatus()) {
			throw new RelizaException("Task " + taskUuid + " is being worked by the "
					+ td.getRole() + " role right now; answering would cancel that hop mid-flight."
					+ " Wait for it to sign off, or put the task on hold first.");
		}
		// A hop its holder parked for the operator (task RD4-5) is still that hop: answering would pop the
		// frame and route past the session that holds it. The person's words go on the release instead.
		if (td.holdParksAHop()) {
			throw new RelizaException("Task " + label(td) + " is " + td.getHold().reason() + ", parked by the "
					+ td.getRole() + " hop working it; lift the hold with your answer as the note.");
		}
		// A task the coordinator seat parked (RD4-17): the answer to the open questions is the answer to the seat's
		// question as well. The hold is released to where it was parked from, and the answer then routes as it does.
		// liftHold does not keep it: routing the answer moves the task on, so the seat's question is answered
		// on the record whatever the flag says.
		answeredByActing(td, by, "answered " + answers.stream().map(AgentDocumentService.Answer::id)
				.collect(Collectors.joining(", ")), answers.stream().map(a -> a.id()
						+ (null == a.status() || BoardReviewItemStatus.RESOLVED == a.status() ? "" : " " + a.status())
						+ (StringUtils.isBlank(a.resolution()) ? "" : ": " + a.resolution().strip()))
				.collect(Collectors.joining("; ")), wu);
		BoardReviewItemIndex previous = agentDocumentService.latestIndexes(td)
				.get(RearmSpecificationType.BOARD_QUESTIONS);
		// The component exists already: a question cannot have been asked without a BOARD_QUESTIONS
		// round, and that round created it under the board lock. So the resolve inside the cut is
		// a lookup, which is why this runs under the task lock without inverting the lock order.
		ReleaseData round = agentDocumentService.publishAnswerRound(td, previous, answers, wu);
		td.addRelease(round.getUuid());
		if (liftHold && TaskStatus.ON_HOLD == td.getStatus()) {
			td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.LIFT_HOLD, by);
			td.setHold(null);
		}
		BoardEffects effects = new BoardEffects(td.getBoard());
		AgentRoutingService.Routed routed = routingService.routeAnswered(td, boardOf(td),
				round.getUuid());
		merge(effects, routed.effects());
		AgentTaskData saved = saveData(routed.task(), wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/** The newest sign-off of a role, which at a gate is the hop the gate is holding. */
	private SignOff lastSignOffOfRole(AgentTaskData td, String role) {
		for (int i = td.getSignOffs().size() - 1; i >= 0; i--) {
			SignOff so = td.getSignOffs().get(i);
			if (null != role && role.equals(so.role())) return so;
		}
		return null;
	}

	/**
	 * The index a person's decisions at a gate belong to: the review item index the gated hop
	 * published, or BOARD_REVIEW_ITEMS when it published none -- a person reviewing a design at a gate
	 * is reviewing, whatever the hop produced.
	 */
	private RearmSpecificationType gatedReviewItemsSpec(SignOff gated) {
		if (null != gated && null != gated.outputs()) {
			for (UUID r : gated.outputs()) {
				Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(r);
				if (rd.isEmpty() || null == rd.get().getDocument()) continue;
				RearmSpecificationType spec = rd.get().getDocument().specification();
				if (RearmSpecificationType.BOARD_REVIEW_ITEMS == spec || RearmSpecificationType.BOARD_TEST_REPORT == spec) {
					return spec;
				}
			}
		}
		return RearmSpecificationType.BOARD_REVIEW_ITEMS;
	}

	/**
	 * A person decides review items on a task: accepts, dismisses, re-prioritises or files them, as one
	 * round of the task's BOARD_REVIEW_ITEMS or BOARD_TEST_REPORT index (operator-actions D6-D9).
	 *
	 * <p>Shaped like {@link #answer}: this method owns the only copy of the task written in the
	 * transaction, adds the round to it, routes and saves once. A retried submission finds the
	 * round the first one cut and changes nothing, since the first one has already routed.
	 *
	 * <p>Routes when the round leaves something blocking that was not blocking before (D9): back
	 * to the producer. Decisions that only take blocking items away route too once nothing blocks
	 * in any review or test index (D9 as amended, gaps §1.8): the task moves on as the board would
	 * have at a clean close, rather than sending a producer a round with nothing to fix. While
	 * something else still blocks, the task stays where those items sent it. On hold, nothing
	 * routes: the hold is the person's brake, and its release reads these decisions.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData decideReviewItems(UUID taskUuid, RearmSpecificationType spec,
			List<AgentDocumentService.BoardReviewItemDecision> decisions, BoardReviewItemIndex.About about, AgentActor by,
			WhoUpdated wu) throws RelizaException {
		if (null == by || AgentActor.ActorKind.USER != by.kind()) {
			throw new RelizaException("Review item decisions are a person's");
		}
		AgentTaskData td = lockedTask(taskUuid);
		AgentBoardData board = boardOf(td);
		// Outside a gate a filed review item is a normal one (task cac71351): a person filing a defect on
		// a task in flight wants it to block.
		AgentDocumentService.DecisionPlan plan = agentDocumentService.planDecisionRound(td, spec, decisions,
				about, by, board.getBlockingPriority(), false);
		if (null != plan.landed()) return td;
		// A task the seat parked (RD4-17): the decisions answer its question; the hold is lifted to where it was
		// parked from, and the decisions then apply as they do there.
		answeredByActing(td, by, "review items decided", spec + ": " + decisions.stream()
				.map(d -> AgentDocumentService.BoardReviewItemDecisionAction.FILE == d.action() ? "filed " + d.title()
						: d.reviewItemId() + " " + d.action())
				.collect(Collectors.joining(", ")), wu);
		switch (td.getStatus()) {
			case ASSIGNED -> throw new RelizaException("Task " + taskUuid + " is being worked by the "
					+ td.getRole() + " role right now, and its next round would be built without your"
					+ " decisions. Wait for it to sign off, or put the task on hold first.");
			case COMPLETED, CANCELLED, DELIVERING -> throw new RelizaException("Task " + taskUuid + " is "
					+ td.getStatus() + "; its review items are settled");
			case PENDING_INTAKE -> throw new RelizaException("Task " + taskUuid
					+ " has not been authorized yet, so it has no review items to decide");
			case ON_HOLD -> {
				if (plan.addsBlocking()) {
					TaskHold h = td.getHold();
					throw new RelizaException(null != h && HoldKind.HUMAN_GATE == h.kind()
							? "Task " + taskUuid + " awaits your verdict on its " + h.gateRole()
									+ " sign-off. To send work back over these review items, reject the hop"
									+ " at the gate with them attached."
							: "Task " + taskUuid + " is on hold, and these decisions leave something"
									+ " blocking that would send it back to work. Lift the hold first,"
									+ " or leave out what adds a blocking item.");
				}
			}
			default -> { }
		}
		ReleaseData round = agentDocumentService.cutDecisionRound(td, plan, wu);
		td.addRelease(round.getUuid());
		BoardEffects effects = new BoardEffects(td.getBoard());
		AgentTaskData out = td;
		if (plan.addsBlocking()) {
			AgentRoutingService.Routed routed = routingService.routeDecided(td, board, round);
			merge(effects, routed.effects());
			out = routed.task();
		} else if ((td.getStatus() == TaskStatus.QUEUED || td.getStatus() == TaskStatus.AWAITING_COORDINATOR)
				&& !agentDocumentService.anyLatestRoundBlocks(td, board.getBlockingPriority())) {
			AgentRoutingService.Routed routed = routingService.routeCleared(td, board, round, by);
			merge(effects, routed.effects());
			out = routed.task();
		}
		AgentTaskData saved = saveData(out, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/** Task-scoped STRICT_LATEST pin of the questions round, added once. */
	private void pinQuestions(AgentTaskData td) {
		List<RequiredInput> required = new ArrayList<>(
				null != td.getRequiredInputs() ? td.getRequiredInputs() : List.of());
		RequiredInput pin = new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.BOARD_QUESTIONS,
				InputScope.TASK, null, null, InputResolution.STRICT_LATEST);
		if (required.stream().noneMatch(ri -> ri.mergeKey().equals(pin.mergeKey()))) {
			required.add(pin);
			td.setRequiredInputs(required);
		}
	}

	/**
	 * Release a hold, and record what the releaser said in the place that can read it.
	 *
	 * <p>One entry point for "an operator lifted a hold and typed something", because what the
	 * words MEAN depends on the hold: on a QUESTION hold they are the answer to every id still
	 * open, and become a round the asking agent reads as an input; on any other hold there is
	 * nothing open to answer, so they are a note on the board feed.
	 *
	 * <p>Here rather than in the fetcher so the rule is testable and so the CLI and any later
	 * caller get it for free -- a second caller re-deciding this would be the whole point of D12
	 * lost, since the difference between a note and a human-authored answer to every open
	 * question is not something two places should be deciding separately.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData liftHoldOrAnswer(UUID taskUuid, HoldLevel actorLevel, AgentActor actor,
			String note, WhoUpdated wu) throws RelizaException {
		return liftHoldOrAnswer(taskUuid, actorLevel, actor, note, null, wu);
	}

	/**
	 * As above, a release optionally to a named role (task 4c566d0d). Words on a QUESTION hold are
	 * an answer, which goes back to whoever asked; naming a role as well would say two different
	 * things, so it is refused rather than one of them silently winning.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData liftHoldOrAnswer(UUID taskUuid, HoldLevel actorLevel, AgentActor actor,
			String note, String role, WhoUpdated wu) throws RelizaException {
		AgentTaskData peek = getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		TaskHold h = peek.getHold();
		boolean answering = null != h && HoldKind.QUESTION == h.kind()
				&& !peek.getQuestionStack().isEmpty() && StringUtils.isNotBlank(note);
		if (answering && StringUtils.isNotBlank(role)) {
			throw new RelizaException("Task " + taskUuid + " is held on a question: words here answer it and go"
					+ " back to whoever asked. Answer without a role, or lift it without words to name one.");
		}
		if (!answering) return liftHold(taskUuid, actorLevel, actor, note, role, wu);
		BoardReviewItemIndex previous = agentDocumentService.latestIndexes(peek)
				.get(RearmSpecificationType.BOARD_QUESTIONS);
		if (null == previous || previous.openReviewItems().isEmpty()) {
			return liftHold(taskUuid, actorLevel, actor, note, wu);
		}
		List<AgentDocumentService.Answer> all = previous.openReviewItems().stream()
				.map(f -> new AgentDocumentService.Answer(f.id(), BoardReviewItemStatus.RESOLVED, note))
				.toList();
		return answer(taskUuid, all, actor, true, wu);
	}

	/**
	 * The words a human said when releasing a hold, kept on the board feed rather than dropped,
	 * and what the release did: a stop overridden once, which release of that stop kind it was,
	 * and where the task went.
	 *
	 * @param released the loop stop the release routed past, or null
	 */
	private void postReleaseNote(AgentTaskData td, String note, AgentActor actor, AgentTaskData.HoldStop released,
			WhoUpdated wu) {
		String where = TaskStatus.QUEUED == td.getStatus() && null != td.getRole() ? "routed to " + td.getRole()
				: "now " + td.getStatus().toString().replace('_', ' ');
		String kind = null == released ? null : AgentRoutingService.StopReason.valueOf(released.name()).toString();
		int n = null == released ? 0 : td.stopLiftsOf(released);
		String count = null == released ? "" : n == 1 ? " (1 of 1 for " + kind + ")" : " (release " + n + " for " + kind + ")";
		String message = "Hold lifted on task " + td.label() + (null == actor ? "" : " by " + actor.display()) + count
				+ (StringUtils.isNotBlank(note) ? ": " + note : "")
				+ " — " + (null != released ? "stop overridden once, " : "") + where
				+ (null != released ? "; the next " + kind.replace(' ', '-') + " stop on this task is the operator's" : "");
		try {
			agentBoardService.postEvent(td.getBoard(), AgentBoardData.BoardEventKind.INFO, message,
					null == actor ? AgentActor.system("release") : actor, wu);
		} catch (Exception e) {
			log.error("Failed to post hold-lift note for task {} on board {}", td.getUuid(),
					td.getBoard(), e);
		}
	}

	private AgentTaskData routeAfterHop(AgentTaskData td, SignOff closing, WhoUpdated wu)
			throws RelizaException {
		return routeAfterHop(td, closing, null, wu);
	}

	private AgentTaskData routeAfterHop(AgentTaskData td, SignOff closing,
			AgentRoutingService.RouteOverride override, WhoUpdated wu) throws RelizaException {
		return routeAfterHop(td, closing, override, new BoardEffects(td.getBoard()), wu);
	}

	/** As above, with effects the caller has already collected, applied with routing's in one commit. */
	private AgentTaskData routeAfterHop(AgentTaskData td, SignOff closing,
			AgentRoutingService.RouteOverride override, BoardEffects effects, WhoUpdated wu) throws RelizaException {
		return routeAfterHop(td, closing, override, effects, List.of(), wu);
	}

	/** As above, with the promotions a guard refused in this hop, for routing to name (T-1). */
	private AgentTaskData routeAfterHop(AgentTaskData td, SignOff closing, AgentRoutingService.RouteOverride override,
			BoardEffects effects, List<AgentDocumentService.RefusedPromotion> refused, WhoUpdated wu)
			throws RelizaException {
		AgentRoutingService.Routed routed = routingService.route(td, boardOf(td), closing, override, refused);
		merge(effects, routed.effects());
		AgentTaskData saved = saveData(routed.task(), wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	private SignOff lastSignOff(AgentTaskData td) {
		return td.getSignOffs().isEmpty() ? null : td.getSignOffs().get(td.getSignOffs().size() - 1);
	}

	/** The board a task belongs to, read for routing. */
	private AgentBoardData boardOf(AgentTaskData td) throws RelizaException {
		return agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
	}

	/** Fold one set of board effects into another, so one commit applies them together. */
	/**
	 * Fold the router's effects into the ones this hop collected.
	 *
	 * <p>The copying lives on {@link BoardEffects#absorb}, beside the fields it has to keep up
	 * with. Written out here it went stale exactly as that arrangement invites -- four fields of
	 * six, so a policy stop recorded the stop and never cut the round that closes what it stopped
	 * on, and no test saw it because none asserted the round existed.
	 */
	private void merge(BoardEffects into, BoardEffects from) {
		into.absorb(from);
	}

	private String taskLabel(AgentTaskData td) {
		return td.label();
	}

	/** Hand the task back to the coordinator: ASSIGNED -> AWAITING_COORDINATOR with a return record. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData returnTask(UUID taskUuid, UUID sessionUuid, TaskReturnReason reason,
			String description, WhoUpdated wu) throws RelizaException {
		return returnTask(taskUuid, sessionUuid, reason, description, List.of(), wu);
	}

	/**
	 * Hand a task back, recording any documents the hop produced.
	 *
	 * <p>Nothing is REQUIRED on a return — an agent that returns because the task was unclear has
	 * nothing to publish, and demanding a document would mean it could neither finish nor hand
	 * back. But partial review items from an aborted review are worth having as data, so they are
	 * accepted and checked for ownership exactly as a sign-off's are.
	 *
	 * <p>Not lock-checked either: handing work back is never a write, and a lock that trapped a
	 * task with its assignment held would be worse than one that merely stops sign-offs.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData returnTask(UUID taskUuid, UUID sessionUuid, TaskReturnReason reason,
			String description, List<UUID> outputs, WhoUpdated wu) throws RelizaException {
		if (reason == null) throw new RelizaException("Return requires a reason");
		if (reason == TaskReturnReason.OTHER && StringUtils.isBlank(description)) {
			throw new RelizaException("Return reason OTHER requires a description");
		}
		AgentTaskData td = lockedTask(taskUuid);
		TaskAssignment a = requireAssignmentBySession(td, taskUuid, sessionUuid);
		List<UUID> declared = validateOutputs(td, a, sessionUuid, outputs, null);
		HopUsage usage = hopUsageWithAllowance(td, a, taskUuid, sessionUuid);
		td.addReturn(new TaskReturn(a.role(), a.roleUuid(), a.agent(), a.session(), reason, description,
				ZonedDateTime.now(), usage, declared));
		td.setAssignment(null);
		BoardEffects effects = new BoardEffects(td.getBoard());
		// A hop that returns to wait on the investigations it commissioned (task RD4-12) waits for them, not for the
		// coordinator: queued for its role again, depending on them, so the board offers it back once each report is
		// pinned -- as an answered question comes back to the asker.
		List<AgentTaskData> waitingOn = TaskReturnReason.BLOCKED_ON_DEPENDENCY == reason ? openInvestigationsFrom(td)
				: List.of();
		if (!waitingOn.isEmpty()) {
			List<UUID> deps = new ArrayList<>(null == td.getDependsOn() ? List.of() : td.getDependsOn());
			waitingOn.stream().map(AgentTaskData::getUuid).filter(u -> !deps.contains(u)).forEach(deps::add);
			td.setDependsOn(deps);
			String keys = String.join(", ", waitingOn.stream().map(AgentTaskData::keyOrUuid).toList());
			td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.RETURN, AgentActor.ofSession(sessionUuid),
					"waiting on investigation " + keys + "; back to " + a.role() + " with the report");
			effects.info("Task " + taskLabel(td) + " waits on investigation " + keys + "; queued for " + a.role()
					+ " again once the report is in");
		} else {
			td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.RETURN, AgentActor.ofSession(sessionUuid));
			returnEvent(effects, td, a.role(), reason, description);
		}
		// A returned hop spent what it spent, so it is flagged like a sign-off.
		alertIfOverAllowance(td, a.role(), usage, effects);
		AgentTaskData saved = saveData(td, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		if (waitingOn.isEmpty()) agentBoardNotifier.returned(saved, saved.getReturns().get(saved.getReturns().size() - 1));
		return saved;
	}

	/**
	 * One board event per return (gaps §1.9): a return is the one hop outcome that always needs a
	 * decision, and the feed is how a person sees it on a board without a coordinator -- and what
	 * notifications will read. ALERT when someone has to decide something; INFO when the board's
	 * own routing offered the wrong role, or the holding session simply closed.
	 */
	private void returnEvent(BoardEffects effects, AgentTaskData td, String role, TaskReturnReason reason,
			String description) {
		if (reason == TaskReturnReason.SESSION_CLOSED) {
			effects.info("Task " + taskLabel(td) + " unassigned"
					+ (StringUtils.isBlank(description) ? "" : ": " + description));
			return;
		}
		// On a board with a blind review role the description stays on the task, where the blind
		// read redacts it; the board feed, which every key on the board reads, carries only the
		// reason (task 0192a587, round 2, T-4).
		boolean withDescription = StringUtils.isNotBlank(description) && !boardHasBlindRole(td.getBoard());
		String message = "Task " + taskLabel(td) + " returned by " + role + " (" + reason + ")"
				+ (withDescription ? ": " + StringUtils.abbreviate(description.strip(), 200) : "");
		if (reason == TaskReturnReason.ROLE_MISMATCH) effects.info(message);
		else effects.alert(message);
	}

	/** Whether an active role on the board reviews blind, so what the feed says may reach a blind reader. */
	private boolean boardHasBlindRole(UUID boardUuid) {
		return agentBoardService.listRoleConfigs(boardUuid).stream()
				.anyMatch(rc -> rc.isActive() && rc.isBlindReview());
	}

	/**
	 * The hop's usage, stamped with the role's allowance as it stands when the hop ends (hop
	 * allowance design §3.3). Stamped rather than looked up later, so a change to the role does not
	 * restate whether an earlier hop went over.
	 */
	private HopUsage hopUsageWithAllowance(AgentTaskData td, TaskAssignment a, UUID taskUuid, UUID sessionUuid) {
		Long allowance = agentBoardService.getRoleConfig(td.getBoard(), a.role())
				.map(AgentTaskRoleConfigData::getHopBudgetMicros).orElse(null);
		return usageService.hopSnapshot(taskUuid, sessionUuid, a.assignedAt()).withAllowance(allowance);
	}

	/**
	 * One board ALERT when a hop ends over its allowance. Never a refusal: the money is spent, and
	 * refusing the sign-off would strand the documents the hop produced.
	 */
	void alertIfOverAllowance(AgentTaskData td, String role, HopUsage usage, BoardEffects effects) {
		Long over = AgentBudgetService.overAllowanceMicros(usage);
		if (null == over || over <= 0) return;
		effects.alert("hop of " + role + " on task " + taskLabel(td) + " spent " + usage.derivedCostMicros()
				+ " micros against an allowance of " + usage.allowanceMicros());
	}

	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData linkPr(UUID taskUuid, String prUrl, WhoUpdated wu) throws RelizaException {
		return linkPr(taskUuid, prUrl, null, wu);
	}

	/**
	 * Link a delivering PR. On a task the coordinator seat parked for the operator (task RD4-19) the link is accepted
	 * as on any other task, and it is preparation for the person's decision, not the decision: it is not routed
	 * through {@link #answeredByActing}, never lifts the hold, and is never recorded as the answer. It is recorded
	 * on the hold instead ("PR linked by whom, when"), since it changes the delivery set the person is deciding on,
	 * and one INFO says so after the commit. A link on any other task, a hop's own hold (RD4-5) included, and a PR
	 * the task already links, record nothing new.
	 *
	 * @param linkedBy who links it, as the hold records it: the linking key's agent; blank reads "an API key"
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData linkPr(UUID taskUuid, String prUrl, String linkedBy, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(prUrl)) throw new RelizaException("prUrl is required");
		AgentTaskData td = lockedTask(taskUuid);
		if (td.isInvestigation()) {
			throw new RelizaException("Task " + td.label() + " is an investigation: it delivers a report, not code, and"
					+ " links no PRs");
		}
		boolean added = null == td.getPrUrls() || !td.getPrUrls().contains(prUrl);
		td.addPrUrl(prUrl);
		recordLinkedHead(td, prUrl);
		BoardEffects effects = null;
		if (added && td.seatParkedForOperator()) {
			String by = StringUtils.isBlank(linkedBy) ? "an API key" : linkedBy.strip();
			td.setHold(td.getHold().withLinked(new AgentTaskData.HoldLink(prUrl, by, ZonedDateTime.now())));
			effects = new BoardEffects(td.getBoard());
			effects.info("Task " + label(td) + " is parked, awaiting the operator: PR " + prUrl + " linked by "
					+ by + "; the delivery set the person is deciding on has changed");
		}
		AgentTaskData saved = saveData(td, wu);
		if (null != effects) boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	/**
	 * A PR linked during a hop while it is in play records its head at link time and the base it starts from, as
	 * those in play at the assignment did (task RD4-2, design round 2). The sign-off head check counts it as moved
	 * when its head moves past the recorded one or when it was opened during the hop; the task says how far its
	 * base moved since the round. A PR not in play when linked records nothing.
	 */
	private void recordLinkedHead(AgentTaskData td, String prUrl) {
		TaskAssignment a = td.getAssignment();
		if (TaskStatus.ASSIGNED != td.getStatus() || null == a || null == a.prHeads()) return;
		String key = AgentDeliveryService.matchKey(prUrl);
		if (null == key || a.prHeads().containsKey(key)
				|| (null != a.linkedHeads() && a.linkedHeads().containsKey(key))) return;
		agentDeliveryService.linkedInPlay(td.getOrg(), prUrl)
				.ifPresent(pr -> td.setAssignment(a.withLinked(pr.key(), pr.head(), pr.base())));
	}

	/** Whether the board's role declares the capability. */
	private boolean roleHolds(AgentTaskData td, String role, AgentTaskRoleConfigData.AgentCapability capability) {
		return agentBoardService.getRoleConfig(td.getBoard(), role)
				.map(rc -> null != rc.getRequiredCapabilities() && rc.getRequiredCapabilities().contains(capability))
				.orElse(false);
	}

	/**
	 * Link a release this task produced -- the release counterpart of {@link #linkPr}. What a
	 * downstream role waits on when its requirement is the output of this task rather than of
	 * the component at large, and the provenance that says which work produced which release.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData linkRelease(UUID taskUuid, UUID releaseUuid, WhoUpdated wu) throws RelizaException {
		if (releaseUuid == null) throw new RelizaException("releaseUuid is required");
		AgentTaskData td = lockedTask(taskUuid);
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("Release not found: " + releaseUuid));
		if (!td.getOrg().equals(rd.getOrg())) throw new RelizaException("Release belongs to another organization");
		td.addRelease(releaseUuid);
		return saveData(td, wu);
	}

	private static UUID parseChildComponent(Object raw) throws RelizaException {
		if (raw == null) return null;
		try {
			return UUID.fromString(raw.toString());
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Invalid producesComponent on split child: " + raw);
		}
	}

	/** A task's output component must be an active component of the task's own org. */
	private void validateProduces(UUID producesComponent, UUID orgUuid) throws RelizaException {
		ComponentData cd = getComponentService.getComponentData(producesComponent)
				.orElseThrow(() -> new RelizaException("Component not found: " + producesComponent));
		if (!orgUuid.equals(cd.getOrg())) throw new RelizaException("Component belongs to another organization");
		if (StatusEnum.ARCHIVED == cd.getStatus()) throw new RelizaException("Component is archived");
	}

	// ---------- A person releases an assignment (task RD3-4) ----------

	/**
	 * A person takes an ASSIGNED task back from its holder and queues it again for the same role (task
	 * RD3-4): the stale-hop remedy, which the staleness sweep only names. The session stays open. The
	 * hop's usage is kept on the release like a return keeps it, the status row carries who and why,
	 * and the released session's later sign-off, publish or question on the task is refused with those
	 * facts. The caller has checked BOARD_WRITE.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentTaskData unassign(UUID taskUuid, String reason, AgentActor by, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(reason)) throw new RelizaException("Say why the task is unassigned");
		AgentTaskData td = lockedTask(taskUuid);
		if (td.getStatus() != TaskStatus.ASSIGNED || null == td.getAssignment()) {
			throw new RelizaException("Task " + taskLabel(td) + " is " + td.getStatus()
					+ "; only an ASSIGNED task can be unassigned");
		}
		TaskAssignment a = td.getAssignment();
		HopUsage usage = hopUsageWithAllowance(td, a, taskUuid, a.session());
		String why = reason.strip();
		List<AgentTaskData.Unassignment> releases = new ArrayList<>(td.getUnassignments());
		releases.add(new AgentTaskData.Unassignment(a.role(), a.roleUuid(), a.agent(), a.session(),
				a.assignedAt(), ZonedDateTime.now(), by, why, usage));
		td.setUnassignments(releases);
		td.setAssignment(null);
		String note = "unassigned by " + (null == by ? "a person" : by.display()) + ": " + why;
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.UNASSIGN, by, note);
		AgentTaskData saved = saveData(td, wu);
		BoardEffects effects = new BoardEffects(saved.getBoard());
		effects.info("Task " + taskLabel(saved) + " " + note + "; queued again for " + a.role());
		alertIfOverAllowance(saved, a.role(), usage, effects);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return saved;
	}

	// ---------- Session-close release path ----------

	/**
	 * Release every assignment held by a closing session back to QUEUED for the role it was
	 * assigned in (task 6e7fe6fe): the work is unfinished, not a decision for the coordinator, and
	 * the next session of that role can take it. A SESSION_CLOSED return keeps the hop's usage; the
	 * status change carries the note saying which session closed and why. Never CANCELLED. Called
	 * from every session-close path (agent close, idle sweep, operator force-close).
	 */
	public void unassignForSession(UUID sessionUuid, String closeNote, WhoUpdated wu) {
		if (sessionUuid == null) return;
		// One set of effects per board, applied once each after the loop: a session holding two
		// tasks on one board posts two lines in one board write, not two writes.
		Map<UUID, BoardEffects> byBoard = new LinkedHashMap<>();
		for (AgentTask t : repository.findByAssignmentSession(sessionUuid.toString())) {
			try {
				AgentTaskData td = lockedTask(t.getUuid());
				TaskAssignment a = td.getAssignment();
				if (a == null || !sessionUuid.equals(a.session())) continue;
				// The hop's usage too, like any other return: a session that reports and then
				// closes has spent what it reported, and a release with no snapshot left those rows
				// outside every hop.
				HopUsage usage = hopUsageWithAllowance(td, a, t.getUuid(), sessionUuid);
				td.addReturn(new TaskReturn(a.role(), a.roleUuid(), a.agent(), a.session(),
						TaskReturnReason.SESSION_CLOSED, closeNote, ZonedDateTime.now(), usage));
				td.setAssignment(null);
				// A hop parked for the operator (task RD4-5) stays parked: the question still waits on a
				// person, whose release then queues the task for the role, the answer on its record.
				if (TaskStatus.ON_HOLD != td.getStatus()) {
					td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.SESSION_CLOSED, AgentActor.ofSession(sessionUuid),
							closeNote);
				}
				saveData(td, wu);
				// Only once the release is saved: a task that failed to release posts nothing.
				BoardEffects effects = byBoard.computeIfAbsent(td.getBoard(), BoardEffects::new);
				returnEvent(effects, td, a.role(), TaskReturnReason.SESSION_CLOSED, closeNote);
				alertIfOverAllowance(td, a.role(), usage, effects);
			} catch (Exception e) {
				log.error("Failed to release assignment on task {} for closing session {}",
						t.getUuid(), sessionUuid, e);
			}
		}
		byBoard.values().forEach(effects -> boardEffectsApplier.applyAfterCommit(effects, wu));
	}

	/** Whether a session holds any task assignment (task 6e7fe6fe: such a session gets twice the idle window). */
	public boolean hasAssignmentsForSession(UUID sessionUuid) {
		if (sessionUuid == null) return false;
		return repository.findByAssignmentSession(sessionUuid.toString()).stream()
				.map(AgentTaskData::dataFromRecord)
				.anyMatch(td -> null != td.getAssignment() && sessionUuid.equals(td.getAssignment().session()));
	}

	/** The idle warning (task 6e7fe6fe) as an ALERT on every board where the session holds a task, naming them. */
	public void idleWarningOnHoldingBoards(UUID sessionUuid, String message, WhoUpdated wu) {
		if (sessionUuid == null) return;
		Map<UUID, List<String>> tasksByBoard = new LinkedHashMap<>();
		for (AgentTask t : repository.findByAssignmentSession(sessionUuid.toString())) {
			AgentTaskData td = AgentTaskData.dataFromRecord(t);
			if (null == td.getAssignment() || !sessionUuid.equals(td.getAssignment().session())) continue;
			tasksByBoard.computeIfAbsent(td.getBoard(), b -> new ArrayList<>()).add(taskLabel(td));
		}
		for (Map.Entry<UUID, List<String>> e : tasksByBoard.entrySet()) {
			try {
				agentBoardService.postEvent(e.getKey(), AgentBoardData.BoardEventKind.ALERT,
						message + " (it holds " + String.join(", ", e.getValue()) + ")", AgentActor.system("idle-sweep"), wu);
			} catch (Exception ex) {
				log.error("Failed to post the idle warning on board {} for session {}", e.getKey(), sessionUuid, ex);
			}
		}
	}

	// ---------- Reads ----------

	public Optional<AgentTaskData> getTaskData(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid).map(AgentTaskData::dataFromRecord);
	}

	/** At most this many tasks in one by-uuid read (task cc14f4cb). */
	public static final int TASKS_BY_UUID_MAX = 100;

	/**
	 * Tasks by uuid in one read, in the order asked (task cc14f4cb): an unknown uuid is absent, a
	 * repeated one appears once. Refused past {@link #TASKS_BY_UUID_MAX}. The caller filters by
	 * what its reader may see.
	 */
	public List<AgentTaskData> getTasksData(List<UUID> uuids) throws RelizaException {
		if (null == uuids || uuids.isEmpty()) return List.of();
		List<UUID> asked = uuids.stream().filter(Objects::nonNull).distinct().toList();
		if (asked.size() > TASKS_BY_UUID_MAX) {
			throw new RelizaException("At most " + TASKS_BY_UUID_MAX + " tasks in one read; " + asked.size()
					+ " were asked for. Read a board's tasks by status with task list instead.");
		}
		Map<UUID, AgentTaskData> found = new HashMap<>();
		for (AgentTask t : repository.findAllById(asked)) {
			AgentTaskData td = AgentTaskData.dataFromRecord(t);
			found.put(td.getUuid(), td);
		}
		return asked.stream().map(found::get).filter(Objects::nonNull).toList();
	}

	public List<AgentTaskData> listByBoard(UUID boardUuid, String status) {
		if (boardUuid == null) return List.of();
		Iterable<AgentTask> rows = StringUtils.isBlank(status)
				? repository.findByBoard(boardUuid.toString())
				: repository.findByBoardAndStatus(boardUuid.toString(), status);
		return StreamSupport.stream(rows.spliterator(), false)
				.map(AgentTaskData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * A board's tasks that changed at or after {@code changedSince}, oldest change first, so a
	 * follower takes the last one's updatedAt as its next cursor (task 9540d3b6). The board feed
	 * carries what needs a person and what routing said, not the forward hand-overs, authorizes and
	 * assignments that are most movement; this read is how a follower sees those. Null
	 * {@code changedSince} is the plain list, unordered as before.
	 *
	 * <p>Filtered in Java over the board's rows, which are read whole already and number in the
	 * hundreds; last_updated_date is a column, so a SQL filter is one query method away if a board
	 * ever outgrows that.
	 */
	public List<AgentTaskData> listByBoard(UUID boardUuid, String status, ZonedDateTime changedSince) {
		List<AgentTaskData> all = listByBoard(boardUuid, status);
		if (null == changedSince) return all;
		return all.stream()
				.filter(td -> null != td.getUpdatedDate() && !td.getUpdatedDate().isBefore(changedSince))
				.sorted(Comparator.comparing(AgentTaskData::getUpdatedDate))
				.toList();
	}

	public List<AgentTaskData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(AgentTaskData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/** Child uuids not yet COMPLETED (CANCELLED children do not block parent completion). */
	public List<UUID> incompleteChildren(AgentTaskData td) {
		if (td.getChildTasks() == null || td.getChildTasks().isEmpty()) return List.of();
		return td.getChildTasks().stream()
				.filter(c -> getTaskData(c)
						.map(cd -> cd.getStatus() != TaskStatus.COMPLETED && cd.getStatus() != TaskStatus.CANCELLED)
						.orElse(false))
				.collect(Collectors.toList());
	}

	// ---------- Internals ----------

	/** Package-private for the budget park, which re-locks the task in its own transaction. */
	AgentTaskData lockedTask(UUID taskUuid) throws RelizaException {
		AgentTask t = repository.findByIdWriteLocked(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		return AgentTaskData.dataFromRecord(t);
	}

	private void requireStatus(AgentTaskData td, UUID taskUuid, TaskStatus... allowed) throws RelizaException {
		for (TaskStatus s : allowed) {
			if (td.getStatus() == s) return;
		}
		throw new RelizaException("Task " + taskUuid + " is " + td.getStatus()
				+ "; operation requires " + List.of(allowed));
	}

	private TaskAssignment requireAssignmentBySession(AgentTaskData td, UUID taskUuid, UUID sessionUuid)
			throws RelizaException {
		// a released session hears that it was released, before any status check could say something vaguer
		Optional<AgentTaskData.Unassignment> released = td.unassignedFor(sessionUuid);
		if (released.isPresent()) throw new RelizaException(released.get().refusal(td.keyOrUuid()));
		// the holder of a parked hop (task RD4-5) hears what it is waiting on, not a bare status
		if (td.holdParksAHop() && null != td.getAssignment() && td.getAssignment().session().equals(sessionUuid)) {
			throw new RelizaException("Task " + label(td) + " is parked, " + td.getHold().reason()
					+ "; the hop resumes when a person lifts the hold with the answer");
		}
		requireStatus(td, taskUuid, TaskStatus.ASSIGNED);
		TaskAssignment a = td.getAssignment();
		if (a == null) throw new RelizaException("Task " + taskUuid + " carries no assignment");
		if (sessionUuid == null || !sessionUuid.equals(a.session())) {
			throw new RelizaException("Task " + taskUuid + " is assigned to a different session");
		}
		return a;
	}

	public AgentTaskData saveData(AgentTaskData td, WhoUpdated wu) {
		AgentTask t = td.getUuid() != null
				? repository.findById(td.getUuid()).orElseGet(AgentTask::new)
				: new AgentTask();
		Map<String, Object> recordData = Utils.dataToRecord(td);
		Optional<AgentTask> existing = repository.findById(t.getUuid());
		// Read before the record is replaced below: the entity is the same managed instance.
		TaskHold holdBefore = existing.map(e -> AgentTaskData.dataFromRecord(e).getHold()).orElse(null);
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.AGENT_TASKS, t);
			t.setRevision(t.getRevision() + 1);
			t.setLastUpdatedDate(ZonedDateTime.now());
		}
		t.setRecordData(recordData);
		t = (AgentTask) WhoUpdated.injectWhoUpdatedData(t, wu);
		AgentTaskData saved = AgentTaskData.dataFromRecord(repository.save(t));
		// Every hold placement goes through here, so this is the one place a hold that only a
		// person can release is noticed -- a gate, an operator hold, a question nobody can answer
		// (task 82880ea6). Written once this commits; a rolled-back hold notifies nobody.
		if (AgentBoardNotifier.placedForAPerson(holdBefore, saved.getHold())) {
			agentBoardNotifier.needsPerson(saved);
		}
		return saved;
	}
}
