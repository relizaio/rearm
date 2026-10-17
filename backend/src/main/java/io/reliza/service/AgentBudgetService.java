/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData.UsageTotals;
import io.reliza.model.AgentSessionUsage;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ModelOntologyData;
import io.reliza.repositories.AgentSessionUsageRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether the next round is affordable, and what to say when it is not.
 *
 * <h2>Why a projection rather than a limit</h2>
 *
 * Checking spend against a budget after the fact stops nothing: by the time the number crosses the
 * line the money is gone. So the question asked before queueing a round is whether the round that
 * is about to start still fits, estimated from what rounds of that role have cost on this board.
 * The overshoot is then bounded by how wrong the estimate is, not by the size of a whole hop.
 *
 * <h2>Why spend is read from the usage rows</h2>
 *
 * Usage arrives after the work: an agent reports what it spent when it reports, which for the CLI
 * is typically at session close -- after the sign-off that ends the hop. The hop snapshots on a
 * sign-off or return are frozen when the hop ends, so a report that lands later is not in them.
 * Spend is therefore the derived cost of the usage rows attributed to the task, whenever they
 * arrived, and the snapshots stay what they are: the record of the hop as it was known when it
 * closed. Until gaps §1.22 refreshes them, a late-reported hop reads lower in its snapshot than it
 * does here.
 *
 * <h2>Why the closing hop may still be invisible</h2>
 *
 * At the moment routing decides, the hop that just finished can have no rows at all. Waiting for
 * them inside the sign-off transaction would hold a lock on an external event. Instead a hop with
 * no row since it was assigned is counted at its estimate as pending, and the projection runs again
 * at the poll and at assignment (board-mechanics D25). A late report both adds its cost and clears
 * the hop from pending, so the two cannot disagree: they read the same rows.
 *
 * <h2>The coordinator's share</h2>
 *
 * The coordinator seat's rows carry the board and no task. They count in board spend as they are.
 * Per task, each coordinator delta is apportioned to the tasks the seat moved (D16,
 * {@link CoordinatorShareService}) and frozen on {@code coordinatorEstimateMicros}, which task spend
 * adds to the task's own rows. Board spend never adds the shares -- they are the same money as the
 * seat's rows -- so the sum of task spends is not the board's spend and is not meant to be. Every
 * projected round also carries §5.3's overhead, the board's average coordinator share per
 * transition, since the round will cost some coordinating too.
 */
@Slf4j
@Service
public class AgentBudgetService {

	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentSessionUsageRepository usageRepository;
	@Autowired private ModelPricingService pricingService;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired @org.springframework.context.annotation.Lazy private AgentSessionUsageService agentSessionUsageService;

	/**
	 * What a projection concluded, so a refusal can say the numbers rather than just "no".
	 *
	 * @param costComplete false when some usage row had no price, so spend is a lower bound
	 */
	public record Projection(long spent, long pending, long estimate, Long taskBudget,
			Long boardBudget, boolean fits, String refusedBy, boolean costComplete, long overhead) {

		public Projection(long spent, long pending, long estimate, Long taskBudget, Long boardBudget,
				boolean fits, String refusedBy) {
			this(spent, pending, estimate, taskBudget, boardBudget, fits, refusedBy, true, 0);
		}

		public Projection(long spent, long pending, long estimate, Long taskBudget, Long boardBudget,
				boolean fits, String refusedBy, boolean costComplete) {
			this(spent, pending, estimate, taskBudget, boardBudget, fits, refusedBy, costComplete, 0);
		}

		/** The next round as §5.3 counts it: the role's estimate plus the coordinator overhead. */
		public long next() {
			return estimate + overhead;
		}

		public long wouldSpend() {
			return spent + pending + next();
		}
	}

	/**
	 * A board's spend, read once: its tasks, and its usage rows derived to cost once, grouped by
	 * task. A poll projects every queued candidate against the same one (architecture §3.5), so the
	 * rows are read and priced once per board per poll rather than once per candidate.
	 */
	public final class BoardSpend {
		private final List<AgentTaskData> tasks;
		private final Map<UUID, List<AgentSessionUsage>> rowsByTask;
		private final Map<UUID, Long> costByTask;
		private final Set<UUID> unpricedTasks;
		private final long boardCost;
		private final boolean costComplete;
		private final Map<UUID, Long> pendingByTask = new HashMap<>();
		private List<AgentTaskRoleConfigData> roles;
		private final UUID boardUuid;

		private BoardSpend(UUID boardUuid, List<AgentTaskData> tasks,
				Map<UUID, List<AgentSessionUsage>> rowsByTask, Map<UUID, Long> costByTask,
				Set<UUID> unpricedTasks, long boardCost, boolean costComplete) {
			this.boardUuid = boardUuid;
			this.tasks = tasks;
			this.rowsByTask = rowsByTask;
			this.costByTask = costByTask;
			this.unpricedTasks = unpricedTasks;
			this.boardCost = boardCost;
			this.costComplete = costComplete;
		}

		/** Every row charged to the board, the coordinator seat's included. */
		public long boardCost() { return boardCost; }

		public List<AgentTaskData> tasks() { return tasks; }

		/** The task's rows plus its apportioned coordinator estimate. */
		public long spentOn(AgentTaskData td) {
			long rows = costByTask.getOrDefault(td.getUuid(), 0L);
			return null != td.getCoordinatorEstimateMicros() ? rows + td.getCoordinatorEstimateMicros() : rows;
		}

		boolean costCompleteFor(AgentTaskData td) {
			return !unpricedTasks.contains(td.getUuid());
		}

		List<AgentSessionUsage> rowsOf(UUID task) {
			return rowsByTask.getOrDefault(task, List.of());
		}

		/** Pending over the board's open tasks, with {@code current} read from its fresh copy. */
		long pendingExcept(UUID current) {
			long total = 0;
			for (AgentTaskData t : tasks) {
				if (t.getUuid().equals(current) || isTerminal(t)) continue;
				total += pendingByTask.computeIfAbsent(t.getUuid(), k -> pendingOn(t, this));
			}
			return total;
		}

		/**
		 * The role a hop ran as: by its row where the hop recorded one, else by name for hops
		 * recorded before roles had rows (pre-#562). Inactive roles included -- a hop that ran as a
		 * role since deactivated still spent what that role costs.
		 */
		Optional<AgentTaskRoleConfigData> role(UUID roleUuid, String roleName) {
			if (null == roles) roles = agentBoardService.listRoleConfigs(boardUuid);
			if (null != roleUuid) {
				Optional<AgentTaskRoleConfigData> byRow = roles.stream()
						.filter(rc -> roleUuid.equals(rc.getUuid())).findFirst();
				if (byRow.isPresent()) return byRow;
			}
			return roles.stream().filter(rc -> null != roleName && roleName.equals(rc.getName())).findFirst();
		}
	}

	private static boolean isTerminal(AgentTaskData td) {
		return td.getStatus() == TaskStatus.COMPLETED || td.getStatus() == TaskStatus.CANCELLED;
	}

	/** Whether any budget applies to this task, so a board without one pays for no reads. */
	public static boolean budgetApplies(AgentTaskData td, AgentBoardData board) {
		return null != td.getBudgetMicros() || null != board.getBudgetMicros();
	}

	/** Read and price the board's rows once. */
	public BoardSpend boardSpend(AgentBoardData board) {
		List<AgentTaskData> tasks = agentTaskService.listByBoard(board.getUuid(), null);
		Map<UUID, List<AgentSessionUsage>> rowsByTask = new HashMap<>();
		Map<UUID, Long> costByTask = new HashMap<>();
		Set<UUID> unpricedTasks = new HashSet<>();
		Map<UUID, ModelOntologyData> models = new HashMap<>();
		long boardCost = 0;
		boolean complete = true;
		for (AgentSessionUsage row : usageRepository.findByBoard(board.getUuid())) {
			// A row without a price counts 0 and says so: a lower bound, not a fabricated figure.
			Long cost = costOf(row, models);
			if (null == cost) complete = false;
			else boardCost += cost;
			if (null != row.getTask()) {
				rowsByTask.computeIfAbsent(row.getTask(), k -> new ArrayList<>()).add(row);
				costByTask.merge(row.getTask(), null == cost ? 0L : cost, Long::sum);
				if (null == cost) unpricedTasks.add(row.getTask());
			}
		}
		return new BoardSpend(board.getUuid(), tasks, rowsByTask, costByTask, unpricedTasks, boardCost,
				complete);
	}

	/** True when the next round of this role still fits every budget that applies. */
	public boolean nextRoundFits(AgentTaskData td, AgentBoardData board, AgentTaskRoleConfigData rc) {
		if (!budgetApplies(td, board)) return true;
		return project(td, board, rc).fits();
	}

	/** Project one task, reading the board's spend for it. For assign, authorize and routing. */
	public Projection project(AgentTaskData td, AgentBoardData board, AgentTaskRoleConfigData rc) {
		return project(td, board, rc, boardSpend(board));
	}

	/**
	 * Project against a board spend already read. {@code td} may be newer than its copy in
	 * {@code spend} -- routing projects the task it is about to save -- so the task's own pending
	 * is read from {@code td} and only the other tasks' from the board read.
	 */
	public Projection project(AgentTaskData td, AgentBoardData board, AgentTaskRoleConfigData rc,
			BoardSpend spend) {
		long spent = spend.spentOn(td);
		long pending = pendingOn(td, spend);
		long estimate = estimateFor(td, rc, spend.tasks());
		long overhead = board.getCoordinatorOverheadMicros();
		boolean complete = spend.costCompleteFor(td);

		Long taskBudget = td.getBudgetMicros();
		if (null != taskBudget && spent + pending + estimate + overhead > taskBudget) {
			return new Projection(spent, pending, estimate, taskBudget, board.getBudgetMicros(),
					false, "task", complete, overhead);
		}
		Long boardBudget = board.getBudgetMicros();
		if (null != boardBudget) {
			long boardSpent = spend.boardCost();
			long boardPending = spend.pendingExcept(td.getUuid()) + (isTerminal(td) ? 0 : pending);
			if (boardSpent + boardPending + estimate + overhead > boardBudget) {
				return new Projection(boardSpent, boardPending, estimate, taskBudget, boardBudget,
						false, "board", spend.costComplete, overhead);
			}
		}
		return new Projection(spent, pending, estimate, taskBudget, boardBudget, true, null, complete, overhead);
	}

	/** A refusal that names the estimate and the remaining room, because "over budget" is not actionable. */
	public String refusal(Projection p, AgentTaskRoleConfigData rc) {
		Long limit = "board".equals(p.refusedBy()) ? p.boardBudget() : p.taskBudget();
		return "the next " + rc.getName() + " round is estimated at " + p.estimate()
				+ " micros" + (p.overhead() > 0 ? " plus " + p.overhead() + " micros of coordinator overhead" : "")
				+ "; the " + p.refusedBy() + " has spent " + p.spent()
				+ (p.pending() > 0 ? " plus " + p.pending() + " not yet reported" : "")
				+ " of " + limit + ", so the round does not fit"
				+ (p.costComplete() ? "" : " (cost incomplete: some usage has no price, so spend is a lower bound)");
	}

	/** What the task has spent: its usage rows, whenever they arrived, plus its coordinator share. */
	public long spentOnTask(AgentTaskData td, BoardSpend spend) {
		return spend.spentOn(td);
	}

	/**
	 * The same figure for one task, reading only its own rows (task 02bfab7c): what a task read
	 * serves as {@code spentMicros}, without pricing the whole board once per task in a list.
	 * Priced by {@link #costOf} as {@link #boardSpend} prices them, so the two cannot drift.
	 */
	public long spentOnTask(AgentTaskData td) {
		Map<UUID, ModelOntologyData> models = new HashMap<>();
		long rows = 0;
		for (AgentSessionUsage row : usageRepository.findByTask(td.getUuid())) {
			Long cost = costOf(row, models);
			if (null != cost) rows += cost;
		}
		return null != td.getCoordinatorEstimateMicros() ? rows + td.getCoordinatorEstimateMicros() : rows;
	}

	// ---------- the Usage tab's breakdown (task RD2-8) ----------

	/** A role's share of the board's spend in a window, and how many of its hops that spend fell in. */
	public record RoleSpend(String role, UsageTotals tokens, long costMicros, int closedHops, int openHops) {}

	/** One session's share of the board's spend in a window, with the role it worked as. */
	public record SessionSpend(UUID session, UUID agent, String role, UsageTotals tokens, long costMicros) {}

	/**
	 * The board's spend in a window, broken down so the parts add up: every row counted once, in
	 * the role of the hop it fell in (closed or still open), in the coordinator seat's line when it
	 * names no task, or unattributed when it names a task no hop of its session owns.
	 * {@code totalMicros} is the same rows priced the same way as the period rollup's total.
	 */
	public record SpendBreakdown(long totalMicros, boolean costComplete, List<RoleSpend> byRole,
			List<SessionSpend> bySession, long coordinatorEstimateMicros, long unattributedMicros,
			ZonedDateTime from, ZonedDateTime to) {}

	/** One hop of a task, closed or open: which session worked it, as which role, from when. */
	private record Hop(String role, UUID session, ZonedDateTime start, boolean open, String id) {}

	/** The role the coordinator seat's rows are shown under. */
	public static final String COORDINATOR_ROLE = "coordinator";

	/**
	 * The breakdown behind the Usage tab (task RD2-8). A row belongs to the hop by its session on its
	 * task that started last at or before the row was reported -- the rule the hop snapshots use: a
	 * hop runs from its assignment to the same session's next one. A null window is the board's life.
	 */
	public SpendBreakdown breakdown(AgentBoardData board, ZonedDateTime from, ZonedDateTime to) {
		ZonedDateTime f = null != from ? from : ZonedDateTime.parse("1970-01-01T00:00:00Z");
		ZonedDateTime t = null != to ? to : ZonedDateTime.now().plusMinutes(1);
		List<AgentSessionUsage> rows = usageRepository.findForBoardPeriod(board.getUuid(), f, t);
		Map<UUID, List<Hop>> hopsByTask = new HashMap<>();
		for (AgentTaskData td : agentTaskService.listByBoard(board.getUuid(), null)) hopsByTask.put(td.getUuid(), hopsOf(td));

		Map<UUID, ModelOntologyData> models = new HashMap<>();
		Map<String, List<AgentSessionUsage>> rowsByRole = new java.util.LinkedHashMap<>();
		Map<String, Long> costByRole = new HashMap<>();
		Map<String, Set<String>> closedByRole = new HashMap<>();
		Map<String, Set<String>> openByRole = new HashMap<>();
		Map<UUID, List<AgentSessionUsage>> rowsBySession = new java.util.LinkedHashMap<>();
		Map<UUID, Long> costBySession = new HashMap<>();
		Map<UUID, String> roleOfSession = new HashMap<>();
		long total = 0;
		long coordinator = 0;
		long unattributed = 0;
		boolean complete = true;
		for (AgentSessionUsage row : rows) {
			Long priced = costOf(row, models);
			if (null == priced) complete = false;
			long cost = null == priced ? 0 : priced;
			total += cost;
			String role = null;
			if (null == row.getTask()) {
				coordinator += cost;
				role = COORDINATOR_ROLE;
			} else {
				Hop hop = hopOf(hopsByTask.getOrDefault(row.getTask(), List.of()), row);
				if (null == hop) {
					unattributed += cost;
				} else {
					role = null != hop.role() ? hop.role() : "(unnamed role)";
					rowsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(row);
					costByRole.merge(role, cost, Long::sum);
					(hop.open() ? openByRole : closedByRole).computeIfAbsent(role, k -> new HashSet<>()).add(hop.id());
				}
			}
			if (null != row.getSession()) {
				rowsBySession.computeIfAbsent(row.getSession(), k -> new ArrayList<>()).add(row);
				costBySession.merge(row.getSession(), cost, Long::sum);
				if (null != role) roleOfSession.putIfAbsent(row.getSession(), role);
			}
		}
		List<RoleSpend> byRole = new ArrayList<>();
		for (Map.Entry<String, List<AgentSessionUsage>> e : rowsByRole.entrySet()) {
			String role = e.getKey();
			byRole.add(new RoleSpend(role, agentSessionUsageService.summarise(e.getValue()), costByRole.getOrDefault(role, 0L),
					closedByRole.getOrDefault(role, Set.of()).size(), openByRole.getOrDefault(role, Set.of()).size()));
		}
		byRole.sort(java.util.Comparator.comparingLong(RoleSpend::costMicros).reversed().thenComparing(RoleSpend::role));
		List<SessionSpend> bySession = new ArrayList<>();
		for (Map.Entry<UUID, List<AgentSessionUsage>> e : rowsBySession.entrySet()) {
			List<AgentSessionUsage> sr = e.getValue();
			bySession.add(new SessionSpend(e.getKey(), sr.get(0).getAgent(), roleOfSession.get(e.getKey()),
					agentSessionUsageService.summarise(sr), costBySession.getOrDefault(e.getKey(), 0L)));
		}
		bySession.sort(java.util.Comparator.comparingLong(SessionSpend::costMicros).reversed()
				.thenComparing(s -> -s.tokens().inputTokens() - s.tokens().outputTokens()));
		return new SpendBreakdown(total, complete, byRole, bySession, coordinator, unattributed, f, t);
	}

	/** A task's hops: every sign-off and return that names its session, and the assignment still open. */
	private static List<Hop> hopsOf(AgentTaskData td) {
		List<Hop> out = new ArrayList<>();
		int n = 0;
		for (SignOff so : td.getSignOffs()) {
			n++;
			if (null != so.session()) out.add(new Hop(so.role(), so.session(), so.assignedAt(), false, td.getUuid() + "/s" + n));
		}
		n = 0;
		for (TaskReturn tr : td.getReturns()) {
			n++;
			if (null != tr.session()) {
				out.add(new Hop(tr.role(), tr.session(), assignedAt(td, tr.session(), tr.returnedAt()), false,
						td.getUuid() + "/r" + n));
			}
		}
		if (td.getStatus() == TaskStatus.ASSIGNED && null != td.getAssignment() && null != td.getAssignment().session()) {
			out.add(new Hop(td.getAssignment().role(), td.getAssignment().session(), td.getAssignment().assignedAt(), true,
					td.getUuid() + "/open"));
		}
		return out;
	}

	/** The hop of the row's session that started last at or before the row; null when none did. */
	private static Hop hopOf(List<Hop> hops, AgentSessionUsage row) {
		Hop best = null;
		for (Hop h : hops) {
			if (!h.session().equals(row.getSession())) continue;
			if (null != h.start() && h.start().isAfter(row.getReportedAt())) continue;
			if (null == best || (null != h.start() && (null == best.start() || h.start().isAfter(best.start())))) best = h;
		}
		return best;
	}

	/** One row's cost in micros, null when its model has no applicable price. */
	private Long costOf(AgentSessionUsage row, Map<UUID, ModelOntologyData> models) {
		ModelOntologyData model = models.computeIfAbsent(row.getModel(),
				k -> modelOntologyService.getModelOntologyData(k).orElse(null));
		return pricingService.derive(model, row).costMicros();
	}

	/** Every row charged to the board, for the soft alert and the over-budget pause. */
	public long spentOnBoard(AgentBoardData board) {
		return boardSpend(board).boardCost();
	}

	/**
	 * Hops that closed with no usage row since they were assigned, each at the estimate of its
	 * role (answer to Q-3): a first hop with no history is pending at the role's allowance, and at 0
	 * only when the board has said nothing about cost.
	 *
	 * <p>A hop with no row spent something -- an agent worked -- so treating it as zero would let a
	 * task start another round on money it has already spent. Returns count too: a hop handed back
	 * after an hour of work spent that hour. A person's verdict names no session and is never
	 * pending.
	 */
	long pendingOn(AgentTaskData td, BoardSpend spend) {
		List<AgentSessionUsage> rows = spend.rowsOf(td.getUuid());
		long pending = 0;
		for (SignOff so : td.getSignOffs()) {
			if (null == so.session() || reportedSince(rows, so.session(), so.assignedAt())) continue;
			pending += pendingAt(td, so.roleUuid(), so.role(), spend);
		}
		for (TaskReturn tr : td.getReturns()) {
			if (null == tr.session()
					|| reportedSince(rows, tr.session(), assignedAt(td, tr.session(), tr.returnedAt()))) {
				continue;
			}
			pending += pendingAt(td, tr.roleUuid(), tr.role(), spend);
		}
		return pending;
	}

	private long pendingAt(AgentTaskData td, UUID roleUuid, String roleName, BoardSpend spend) {
		return spend.role(roleUuid, roleName).map(rc -> estimateFor(td, rc, spend.tasks())).orElse(0L);
	}

	/** Any row from this session on the task since the hop opened; no upper bound (Q-1). */
	private static boolean reportedSince(List<AgentSessionUsage> rows, UUID session,
			java.time.ZonedDateTime from) {
		return rows.stream().anyMatch(r -> session.equals(r.getSession())
				&& (null == from || !r.getReportedAt().isBefore(from)));
	}

	/**
	 * When a returned hop was assigned. A return records no assignment time, so it is the last
	 * ASSIGN transition by that session before the return; null, meaning any row counts, when the
	 * history does not say.
	 */
	static java.time.ZonedDateTime assignedAt(AgentTaskData td, UUID session,
			java.time.ZonedDateTime returnedAt) {
		java.time.ZonedDateTime found = null;
		for (StatusChange sc : td.getStatusHistory()) {
			if (sc.trigger() != StatusTrigger.ASSIGN || null == sc.actor()
					|| !session.equals(sc.actor().uuid())) continue;
			if (null != returnedAt && sc.at().isAfter(returnedAt)) continue;
			if (null == found || sc.at().isAfter(found)) found = sc.at();
		}
		return found;
	}

	/**
	 * How far a hop went over its role's allowance: null when either the allowance or the cost is
	 * unknown, 0 when it stayed within it. Computed rather than stored, so when a late report
	 * refreshes a hop's usage the flag follows without anything else changing.
	 */
	public static Long overAllowanceMicros(HopUsage usage) {
		if (null == usage || null == usage.allowanceMicros() || null == usage.derivedCostMicros()) return null;
		return Math.max(0, usage.derivedCostMicros() - usage.allowanceMicros());
	}

	/**
	 * What a round of this role costs, best available answer first.
	 *
	 * <p>This task's own history for the role, then the board's, then the role's allowance, then
	 * nothing -- which allows the round. The task comes first because its own hops are the most
	 * specific evidence of its size (D13's "last round on this task"; gaps §1.25). With the board
	 * first the task step could never answer, since the board average already holds the task's hops. A board with no history and no allowance has said nothing
	 * about cost, and refusing work on the strength of an estimate nobody supplied would make
	 * budgets a default rather than a decision.
	 *
	 * <p>Board-mechanics §5.3 asks for the board rung per (role, model). The hop snapshots carry no
	 * per-model split, so the board rung is per role only.
	 */
	long estimateFor(AgentTaskData td, AgentTaskRoleConfigData rc, List<AgentTaskData> boardTasks) {
		long fromTask = averageOnTask(td, rc.getUuid());
		if (fromTask > 0) return fromTask;
		long fromBoard = averageOnBoard(boardTasks, rc.getUuid());
		if (fromBoard > 0) return fromBoard;
		return null != rc.getHopBudgetMicros() ? rc.getHopBudgetMicros() : 0;
	}

	/** Average measured cost of this role's sign-offs across the board's tasks; 0 with no history. */
	private long averageOnBoard(List<AgentTaskData> boardTasks, UUID roleUuid) {
		long sum = 0;
		int n = 0;
		for (AgentTaskData td : boardTasks) {
			for (SignOff so : td.getSignOffs()) {
				if (null == roleUuid || !roleUuid.equals(so.roleUuid())) continue;
				HopUsage usage = so.usage();
				if (null == usage || null == usage.derivedCostMicros() || usage.derivedCostMicros() == 0) continue;
				sum += usage.derivedCostMicros();
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	private long averageOnTask(AgentTaskData td, UUID roleUuid) {
		long sum = 0;
		int n = 0;
		for (SignOff so : td.getSignOffs()) {
			if (null == roleUuid || !roleUuid.equals(so.roleUuid())) continue;
			HopUsage usage = so.usage();
			if (null == usage || null == usage.derivedCostMicros() || usage.derivedCostMicros() == 0) continue;
			sum += usage.derivedCostMicros();
			n++;
		}
		return n == 0 ? 0 : sum / n;
	}

	/**
	 * Whether a board has reached its soft-alert line and has not said so yet: the one condition
	 * both routing and a late usage refresh post the alert on, so it is posted once per crossing.
	 */
	static boolean softAlertDue(AgentBoardData board, long spent) {
		if (null == board.getBudgetMicros() || board.isSoftAlertPosted()) return false;
		return spent >= board.getBudgetMicros() * board.effectiveSoftAlertPercent() / 100;
	}

	static String softAlertMessage(AgentBoardData board, long spent) {
		return "Board has spent " + spent + " of " + board.getBudgetMicros()
				+ " micros (" + board.effectiveSoftAlertPercent() + "% line crossed)";
	}

	/** Whether spend just crossed the board's soft-alert line, so one alert is posted. */
	public boolean crossedSoftAlert(AgentBoardData board, long before, long after) {
		if (null == board.getBudgetMicros()) return false;
		long line = board.getBudgetMicros() * board.effectiveSoftAlertPercent() / 100;
		return before < line && after >= line;
	}
}
