/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentSessionUsageRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Stale work on a board (task RD3-4): a role nobody staffs, a hop with no progress, a delivery that does
 * not land, a coordinator seat that does not answer. Only boards with a staleness block are read. Each
 * breach, keyed by rule and subject, posts one ALERT, and again only once it has stood for the board's
 * repeatMinutes or after it cleared and came back. No task changes state here: a person releases a
 * stalled assignment by hand.
 */
@Slf4j
@Service
public class BoardStalenessService {

	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentDeliveryService agentDeliveryService;
	@Autowired private AgentSessionUsageRepository usageRepository;

	public static final String ROLE_UNSTAFFED = "roleUnstaffed";
	public static final String HOP_NO_PROGRESS = "hopNoProgress";
	public static final String DELIVERY_STUCK = "deliveryStuck";
	public static final String SEAT_SILENT = "seatSilent";
	public static final String INVESTIGATION_OVERDUE = "investigationOverdue";

	/** One standing breach: its key (rule and subject) and the ALERT it posts. */
	public record Breach(String key, String message) {}

	/** @return how many ALERTs were posted */
	public int sweep(ZonedDateTime now) {
		int posted = 0;
		for (AgentBoardData board : agentBoardService.listWatchingStaleness()) {
			try {
				posted += sweepBoard(board, now);
			} catch (Exception e) {
				log.error("Staleness sweep failed on board {}", board.getUuid(), e);
			}
		}
		return posted;
	}

	int sweepBoard(AgentBoardData board, ZonedDateTime now) {
		AgentBoardData.Staleness st = board.getStaleness();
		if (null == st || !st.anyRule() || board.getStatus() != AgentBoardData.BoardStatus.ACTIVE) return 0;
		List<Breach> breaches = breaches(board, st, now);
		Map<String, Long> before = null == board.getStalenessAlerted() ? Map.of() : board.getStalenessAlerted();
		Map<String, Long> after = new LinkedHashMap<>();
		long nowEpoch = now.toEpochSecond();
		long repeat = st.effectiveRepeatMinutes() * 60L;
		int posted = 0;
		for (Breach b : breaches) {
			Long last = before.get(b.key());
			if (null != last && nowEpoch - last < repeat) {
				after.put(b.key(), last);
				continue;
			}
			try {
				agentBoardService.postEvent(board.getUuid(), AgentBoardData.BoardEventKind.ALERT, b.message(),
						AgentActor.system("staleness"), WhoUpdated.getAutoWhoUpdated());
				after.put(b.key(), nowEpoch);
				posted++;
			} catch (Exception e) {
				log.error("Could not post the staleness ALERT {} on board {}", b.key(), board.getUuid(), e);
				if (null != last) after.put(b.key(), last);
			}
		}
		// a breach that cleared leaves the map, so its return alerts at once
		if (!after.equals(before)) agentBoardService.stampStalenessAlerted(board.getUuid(), after);
		return posted;
	}

	/** Every breach standing on the board now, rule by rule. */
	public List<Breach> breaches(AgentBoardData board, AgentBoardData.Staleness st, ZonedDateTime now) {
		List<AgentTaskData> tasks = agentTaskService.listByBoard(board.getUuid(), null);
		List<Breach> out = new ArrayList<>();
		if (null != st.roleUnstaffedMinutes()) out.addAll(unstaffed(board, tasks, st.roleUnstaffedMinutes(), now));
		if (null != st.hopNoProgressMinutes()) out.addAll(noProgress(tasks, st.hopNoProgressMinutes(), now));
		if (null != st.deliveryStuckMinutes()) out.addAll(stuckDeliveries(tasks, st.deliveryStuckMinutes(), now));
		if (null != st.seatSilentMinutes()) out.addAll(silentSeat(board, tasks, st.seatSilentMinutes(), now));
		if (null != st.investigationOverdueMinutes()) out.addAll(overdue(tasks, st.investigationOverdueMinutes(), now));
		return out;
	}

	/**
	 * An investigation (task RD4-12) neither completed nor cancelled this many minutes after its deadline. Alert only:
	 * the commissioner, or a person, decides whether to wait, re-brief or cancel it.
	 */
	private List<Breach> overdue(List<AgentTaskData> tasks, int graceMinutes, ZonedDateTime now) {
		List<Breach> out = new ArrayList<>();
		for (AgentTaskData td : tasks) {
			if (!td.isInvestigation() || null == td.getInvestigation().deadline()) continue;
			if (TaskStatus.COMPLETED == td.getStatus() || TaskStatus.CANCELLED == td.getStatus()) continue;
			ZonedDateTime deadline = td.getInvestigation().deadline();
			if (now.isBefore(deadline.plusMinutes(graceMinutes))) continue;
			long late = minutesBetween(deadline, now);
			out.add(new Breach(INVESTIGATION_OVERDUE + ":" + td.getUuid(), td.label() + " investigation overdue: due "
					+ deadline.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES) + ", " + ago(late) + " ago, and "
					+ td.getStatus().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
					+ (null == td.getRole() ? "" : " (" + td.getRole() + ")")));
		}
		return out;
	}

	/** When the task entered the status it is in: its newest status row into it, else its creation. */
	static ZonedDateTime inStatusSince(AgentTaskData td) {
		List<AgentTaskData.StatusChange> h = td.getStatusHistory();
		if (null != h) {
			for (int i = h.size() - 1; i >= 0; i--) {
				if (h.get(i).to() == td.getStatus()) return h.get(i).at();
			}
		}
		return td.getCreatedDate();
	}

	private static long minutesBetween(ZonedDateTime from, ZonedDateTime to) {
		return Duration.between(from, to).toMinutes();
	}

	private static String ago(long minutes) {
		return minutes < 120 ? minutes + " min" : (minutes / 60) + " h";
	}

	/** A role with queued work older than the threshold, and no session of the role polling within the same span. */
	private List<Breach> unstaffed(AgentBoardData board, List<AgentTaskData> tasks, int threshold, ZonedDateTime now) {
		Map<String, List<AgentTaskData>> queuedByRole = new LinkedHashMap<>();
		for (AgentTaskData td : tasks) {
			if (td.getStatus() != TaskStatus.QUEUED || null == td.getRole()) continue;
			ZonedDateTime since = inStatusSince(td);
			if (null == since || minutesBetween(since, now) < threshold) continue;
			queuedByRole.computeIfAbsent(td.getRole(), r -> new ArrayList<>()).add(td);
		}
		if (queuedByRole.isEmpty()) return List.of();
		List<List<String>> polls = agentSessionService.rolesPolledSince(board.getUuid(), now.minusMinutes(threshold));
		List<Breach> out = new ArrayList<>();
		for (Map.Entry<String, List<AgentTaskData>> e : queuedByRole.entrySet()) {
			AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), e.getKey()).orElse(null);
			// a person's role is not staffed by polling agents
			if (null != rc && rc.getKind() == AgentTaskRoleConfigData.RoleKind.HUMAN) continue;
			if (staffed(e.getKey(), null == rc ? null : rc.getUuid(), polls)) continue;
			long oldest = e.getValue().stream().mapToLong(td -> minutesBetween(inStatusSince(td), now)).max().orElse(0);
			int n = e.getValue().size();
			out.add(new Breach(ROLE_UNSTAFFED + ":" + e.getKey(), e.getKey() + " unstaffed: " + n + " task" + (n == 1 ? "" : "s")
					+ " queued for up to " + ago(oldest) + ", and no " + e.getKey() + " session has polled this board in the last "
					+ ago(threshold)));
		}
		return out;
	}

	/** Whether any poll covered the role: a poll that declared no roles takes every role. */
	static boolean staffed(String role, UUID roleUuid, List<List<String>> polls) {
		for (List<String> declared : polls) {
			if (null == declared || declared.isEmpty()) return true;
			for (String d : declared) {
				if (null == d) continue;
				if (d.strip().equalsIgnoreCase(role) || (null != roleUuid && d.strip().equalsIgnoreCase(roleUuid.toString()))) return true;
			}
		}
		return false;
	}

	/** An assignment older than the threshold with nothing from its session since: no document, no usage report. */
	private List<Breach> noProgress(List<AgentTaskData> tasks, int threshold, ZonedDateTime now) {
		List<Breach> out = new ArrayList<>();
		for (AgentTaskData td : tasks) {
			AgentTaskData.TaskAssignment a = td.getAssignment();
			if (td.getStatus() != TaskStatus.ASSIGNED || null == a || null == a.assignedAt()) continue;
			long held = minutesBetween(a.assignedAt(), now);
			if (held < threshold || progressed(td, a)) continue;
			out.add(new Breach(HOP_NO_PROGRESS + ":" + td.getUuid() + ":" + a.session(), td.label() + " stalled: assigned to "
					+ a.role() + " (session " + a.session().toString().substring(0, 8) + ") for " + ago(held)
					+ " with no progress reported; release it with task unassign if the agent is gone"));
		}
		return out;
	}

	/** Whether the holder did anything the board can see since its assignment: a document (a question is one) or a usage report. */
	boolean progressed(AgentTaskData td, AgentTaskData.TaskAssignment a) {
		for (ReleaseData rd : agentDocumentService.documentsOfTask(td)) {
			if (null != rd.getDocument() && a.session().equals(rd.getDocument().session())
					&& null != rd.getCreatedDate() && !rd.getCreatedDate().isBefore(a.assignedAt())) {
				return true;
			}
		}
		return !usageRepository.findForHop(td.getUuid(), a.session(), a.assignedAt(), null).isEmpty();
	}

	/**
	 * A task DELIVERING longer than the threshold: what has not landed. On a board that delivers through PRs the
	 * rule reads each PR's state from its row, so it speaks only of PRs in play (task RD4-2): a PR whose CI
	 * reports elsewhere has no row here, and a task waiting only on such PRs stays silent, since this ReARM
	 * cannot tell a slow merge from one it never hears of. A board delivering without PRs is read as before.
	 */
	private List<Breach> stuckDeliveries(List<AgentTaskData> tasks, int threshold, ZonedDateTime now) {
		List<Breach> out = new ArrayList<>();
		for (AgentTaskData td : tasks) {
			if (td.getStatus() != TaskStatus.DELIVERING) continue;
			ZonedDateTime since = inStatusSince(td);
			if (null == since) continue;
			long waited = minutesBetween(since, now);
			if (waited < threshold) continue;
			AgentDeliveryService.Verdict v = agentDeliveryService.deliveryOf(td);
			boolean throughPrs = AgentBoardData.DeliveryMode.NONE != v.policy().mode();
			String pending = null == v.pullRequests() ? "" : v.pullRequests().stream()
					.filter(pr -> pr.registered() && pr.unit() == AgentDeliveryService.Delivery.WAITING)
					.map(pr -> pr.url() + " " + String.valueOf(pr.state()).toLowerCase(java.util.Locale.ROOT))
					.collect(Collectors.joining(", "));
			if (throughPrs && pending.isEmpty()) continue;
			out.add(new Breach(DELIVERY_STUCK + ":" + td.getUuid(), td.label() + " delivery stuck: "
					+ (pending.isEmpty() ? "nothing delivered" : pending) + " for " + ago(waited)));
		}
		return out;
	}

	/** Work waiting on the coordinator longer than the threshold while an open session holds the seat. */
	private List<Breach> silentSeat(AgentBoardData board, List<AgentTaskData> tasks, int threshold, ZonedDateTime now) {
		AgentBoardData.CoordinatorSeat seat = board.getCoordinatorSeat();
		if (null == seat || null == seat.session()) return List.of();
		boolean open = agentSessionService.getSessionData(seat.session())
				.map(s -> s.getStatus() == AgentSessionData.SessionStatus.OPEN).orElse(false);
		if (!open) return List.of();
		Set<TaskStatus> waiting = Set.of(TaskStatus.PENDING_INTAKE, TaskStatus.AWAITING_COORDINATOR);
		List<Breach> out = new ArrayList<>();
		for (AgentTaskData td : tasks) {
			if (!waiting.contains(td.getStatus())) continue;
			ZonedDateTime since = inStatusSince(td);
			if (null == since) continue;
			long waited = minutesBetween(since, now);
			if (waited < threshold) continue;
			out.add(new Breach(SEAT_SILENT + ":" + td.getUuid(), td.label() + " waiting on the coordinator: "
					+ td.getStatus() + " for " + ago(waited) + " while the seat is held (session "
					+ seat.session().toString().substring(0, 8) + ")"));
		}
		return out;
	}
}
