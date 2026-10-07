/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.TestedHead;
import io.reliza.model.PullRequestData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * COMPLETED means delivered (task 9af9d722): a task whose required roles have all passed waits in
 * DELIVERING until its linked PRs are merged.
 *
 * <p>The board used to complete a task at its last pass, and that released its dependents, while
 * its PR was still open or could no longer merge at all. ReARM already knows each PR's state: CI
 * registers PRs through the pull-request upsert. This service reads those rows for a task's
 * {@code prUrls} and decides.
 *
 * <p>Entering: every completion path asks {@link #settle} before writing COMPLETED. Leaving: the PR
 * upsert calls {@link #onPullRequestChangedAfterCommit}, and {@link #sweep} runs every few minutes
 * for the events CI never sent. A task with no linked PR completes as before.
 */
@Slf4j
@Service
public class AgentDeliveryService {

	/** Itself, so REQUIRES_NEW goes through the proxy; see {@link BoardEffectsApplier#self}. */
	@Autowired @Lazy private AgentDeliveryService self;

	@Autowired private AgentTaskRepository agentTaskRepository;
	@Autowired @Lazy private AgentTaskService agentTaskService;
	@Autowired @Lazy private PullRequestService pullRequestService;
	@Autowired @Lazy private BoardEffectsApplier boardEffectsApplier;
	@Autowired @Lazy private AgentBoardService agentBoardService;
	@Autowired @Lazy private SharedReleaseService sharedReleaseService;
	@Autowired @Lazy private GetSourceCodeEntryService getSourceCodeEntryService;

	@PersistenceContext
	private EntityManager entityManager;

	public enum Delivery {
		/** No PR, or every linked PR merged. */
		DELIVERED,
		/** A linked PR is open, or CI has not reported it yet -- never read as merged. */
		WAITING,
		/** A linked PR was closed without merging. */
		BLOCKED,
		/**
		 * A linked PR declared superseded by its replacement (task RD3-13): neither pending nor blocked, as if
		 * it were not linked; the replacement is the unit that counts.
		 */
		SUPERSEDED
	}

	/**
	 * One linked PR as ReARM knows it.
	 *
	 * @param registered  false when no PR row has this URL: the repository's CI is not reporting it
	 * @param headSce     the row's newest commit (the last of its commits); null when unregistered
	 * @param declaration the newest declaration for the PR (task 18c5c293); null when none
	 */
	public record TaskPullRequest(String url, PullRequestState state, String targetBranch,
			ZonedDateTime mergedDate, boolean registered, UUID headSce, AgentTaskData.Delivery declaration) {

		public TaskPullRequest(String url, PullRequestState state, String targetBranch, ZonedDateTime mergedDate,
				boolean registered) {
			this(url, state, targetBranch, mergedDate, registered, null, null);
		}

		/**
		 * The unit rule (task 18c5c293): the newest declaration for the PR settles it, delivered or
		 * abandoned; without one, its row here -- merged delivers, closed blocks, open or unregistered
		 * waits.
		 */
		public Delivery unit() {
			if (null != declaration) {
				return switch (declaration.outcome()) {
					case ABANDONED -> Delivery.BLOCKED;
					case SUPERSEDED -> Delivery.SUPERSEDED;
					default -> Delivery.DELIVERED;
				};
			}
			if (state == PullRequestState.MERGED) return Delivery.DELIVERED;
			if (state == PullRequestState.CLOSED) return Delivery.BLOCKED;
			return Delivery.WAITING;
		}
	}

	/**
	 * @param taskDeclaration on a board delivering without PRs, the newest declaration of the task's
	 *        delivery; null otherwise
	 */
	public record Verdict(Delivery delivery, List<TaskPullRequest> pullRequests, AgentBoardData.DeliveryPolicy policy,
			AgentTaskData.Delivery taskDeclaration) {

		public Verdict(Delivery delivery, List<TaskPullRequest> pullRequests) {
			this(delivery, pullRequests, AgentBoardData.DeliveryPolicy.DEFAULT, null);
		}

		List<TaskPullRequest> units(Delivery d) {
			return pullRequests.stream().filter(pr -> pr.unit() == d).toList();
		}

		/** Whether a declaration, rather than a PR row, is what stopped the delivery. */
		boolean abandoned() {
			if (null != taskDeclaration) return AgentTaskData.DeliveryOutcome.ABANDONED == taskDeclaration.outcome();
			return units(Delivery.BLOCKED).stream().anyMatch(pr -> null != pr.declaration());
		}
	}

	/**
	 * What a completion did: the status it set and what the board should be told.
	 *
	 * @param info an INFO line, or null
	 * @param alert an ALERT line, or null
	 */
	public record Settled(TaskStatus status, String info, String alert) {}

	/**
	 * A PR URL as a key: scheme and host lower-cased, and a trailing slash, a {@code .git} suffix, a
	 * query and a fragment dropped. The path keeps its case, which some hosts give meaning to.
	 */
	static String normalise(String url) {
		if (StringUtils.isBlank(url)) return null;
		String trimmed = url.strip();
		try {
			URI u = URI.create(trimmed);
			if (null == u.getScheme() || null == u.getHost()) return trimmed;
			String path = null == u.getPath() ? "" : u.getPath();
			while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
			if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
			return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
					+ (u.getPort() > 0 ? ":" + u.getPort() : "") + path;
		} catch (IllegalArgumentException e) {
			return trimmed;
		}
	}

	/** How a linked URL and a PR endpoint are matched: normalised, then case-insensitively. */
	static String matchKey(String url) {
		String n = normalise(url);
		return null == n ? null : n.toLowerCase(Locale.ROOT);
	}

	/**
	 * The task's linked PRs as ReARM knows them, in link order.
	 *
	 * <p>Reads only the org's PR rows whose endpoint matches one of the linked URLs: the database
	 * filters by the normalised endpoint and this matches exactly on what it returns. Loading the
	 * whole PR table per task made a board read of 40 tasks take seconds on an org with 10,000 PRs
	 * (9af9d722 T-1).
	 */
	public List<TaskPullRequest> pullRequestsOf(AgentTaskData td) {
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) return List.of();
		return pullRequestsOf(td, pullRequestsByKey(td.getOrg(), matchKeys(td)));
	}

	/** The match keys of a task's linked URLs. */
	public static java.util.Set<String> matchKeys(AgentTaskData td) {
		java.util.Set<String> keys = new java.util.LinkedHashSet<>();
		for (String url : null == td.getPrUrls() ? List.<String>of() : td.getPrUrls()) {
			String k = matchKey(url);
			if (null != k) keys.add(k);
		}
		return keys;
	}

	/**
	 * The org's PR rows for these match keys, in one query: what a GraphQL read batches every task's
	 * keys into, so a board of 40 tasks is one read of the PR table rather than 40.
	 */
	public Map<String, PullRequestData> pullRequestsByKey(UUID org, java.util.Collection<String> keys) {
		Map<String, PullRequestData> byKey = new HashMap<>();
		for (PullRequestData pr : pullRequestService.listByOrgAndEndpointKeys(org, keys)) {
			if (null == pr.getEndpoint()) continue;
			String key = matchKey(pr.getEndpoint().toString());
			// Two rows for one URL (a PR re-registered under another identity): the newest wins.
			PullRequestData had = byKey.get(key);
			if (null == had || newer(pr, had)) byKey.put(key, pr);
		}
		return byKey;
	}

	/** A task's linked PRs, in link order, from rows already read, each with its newest declaration. */
	public static List<TaskPullRequest> pullRequestsOf(AgentTaskData td, Map<String, PullRequestData> byUrl) {
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) return List.of();
		List<TaskPullRequest> out = new ArrayList<>();
		for (String url : td.getPrUrls()) {
			PullRequestData pr = byUrl.get(matchKey(url));
			AgentTaskData.Delivery declared = declarationFor(td, url);
			out.add(null == pr ? new TaskPullRequest(url, null, null, null, false, null, declared)
					: new TaskPullRequest(url, pr.getState(), pr.getTargetBranchName(), pr.getMergedDate(), true,
							null == pr.getCommits() || pr.getCommits().isEmpty() ? null
									: pr.getCommits().get(pr.getCommits().size() - 1), declared));
		}
		return out;
	}

	/** The newest declaration naming this PR, or null. */
	static AgentTaskData.Delivery declarationFor(AgentTaskData td, String url) {
		String key = matchKey(url);
		List<AgentTaskData.Delivery> all = null == td.getDeliveries() ? List.of() : td.getDeliveries();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (null != key && key.equals(matchKey(all.get(i).unit()))) return all.get(i);
		}
		return null;
	}

	/** How the task's board proves delivery; the default when the board cannot be read. */
	public AgentBoardData.DeliveryPolicy policyOf(AgentTaskData td) {
		return agentBoardService.getBoardData(td.getBoard()).map(AgentBoardData::getEffectiveDeliveryPolicy)
				.orElse(AgentBoardData.DeliveryPolicy.DEFAULT);
	}

	private static boolean newer(PullRequestData a, PullRequestData b) {
		return null != a.getCreatedDate() && (null == b.getCreatedDate() || a.getCreatedDate().isAfter(b.getCreatedDate()));
	}

	/**
	 * Whether the task's delivery is done, waiting, or blocked (task 18c5c293): by its linked PRs'
	 * units on a board that delivers through PRs; on one that does not, delivered at once, or with
	 * declare, by the newest declaration of the task.
	 */
	public Verdict deliveryOf(AgentTaskData td) {
		AgentBoardData.DeliveryPolicy policy = policyOf(td);
		if (AgentBoardData.DeliveryMode.NONE == policy.mode()) {
			if (!Boolean.TRUE.equals(policy.awaitDeclaration())) return new Verdict(Delivery.DELIVERED, List.of(), policy, null);
			List<AgentTaskData.Delivery> all = null == td.getDeliveries() ? List.of() : td.getDeliveries();
			AgentTaskData.Delivery newest = all.isEmpty() ? null : all.get(all.size() - 1);
			Delivery d = null == newest ? Delivery.WAITING
					: AgentTaskData.DeliveryOutcome.ABANDONED == newest.outcome() ? Delivery.BLOCKED : Delivery.DELIVERED;
			return new Verdict(d, List.of(), policy, newest);
		}
		List<TaskPullRequest> prs = pullRequestsOf(td);
		return new Verdict(delivery(prs), prs, policy, null);
	}

	/** The rule, on its own: any blocked unit blocks; any waiting one waits; else delivered. A superseded unit
	 * counts as absent (task RD3-13). */
	static Delivery delivery(List<TaskPullRequest> prs) {
		if (prs.stream().anyMatch(pr -> pr.unit() == Delivery.BLOCKED)) return Delivery.BLOCKED;
		if (prs.stream().anyMatch(pr -> pr.unit() == Delivery.WAITING)) return Delivery.WAITING;
		return Delivery.DELIVERED;
	}

	/**
	 * Complete a task whose work is done, as far as its PRs allow: COMPLETED with {@code trigger}
	 * when delivered, DELIVERING while a PR is open, or back to the coordinator when a PR was closed
	 * without merging. Called under the task's lock by every completion path; the caller saves and
	 * posts the returned lines.
	 */
	public Settled settle(AgentTaskData td, StatusTrigger trigger, AgentActor actor, String note) {
		Verdict v = deliveryOf(td);
		String label = label(td);
		switch (v.delivery()) {
			case WAITING -> {
				td.transitionStatus(TaskStatus.DELIVERING, StatusTrigger.DELIVER_WAIT, actor, note);
				td.setAssignment(null);
				// The heads it waits at, so a later push is told from these (task 3b97ccfd).
				td.setDeliveringHeads(headsOf(td));
				return new Settled(TaskStatus.DELIVERING, waitingInfo(label, td.getUuid(), v), null);
			}
			case BLOCKED -> {
				// A pass routed to the coordinator is already there: the alert says why, and a row from
				// AWAITING_COORDINATOR to itself would say nothing (RD3-16).
				if (TaskStatus.AWAITING_COORDINATOR != td.getStatus()) {
					td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, blockedTrigger(v), actor, note);
				}
				td.setAssignment(null);
				return new Settled(TaskStatus.AWAITING_COORDINATOR, null, closedAlert(label, v));
			}
			default -> {
				td.transitionStatus(TaskStatus.COMPLETED, trigger, actor, note);
				td.setCompletedAt(ZonedDateTime.now());
				td.setAssignment(null);
				return new Settled(TaskStatus.COMPLETED, null, null);
			}
		}
	}

	/**
	 * Why completing the task would not land (RD3-16): the blocked-delivery message -- a PR closed
	 * without merging, or a unit declared abandoned -- when the verdict is BLOCKED; else empty. Read
	 * before any write, so a refused complete leaves the task, its history and the board as they were.
	 */
	public Optional<String> blockedDelivery(AgentTaskData td) {
		Verdict v = deliveryOf(td);
		return Delivery.BLOCKED == v.delivery() ? Optional.of(closedAlert(label(td), v)) : Optional.empty();
	}

	/**
	 * What a DELIVERING task waits for, per unit, and how to settle it (task 18c5c293): a PR that is
	 * unregistered here says so, since on a board whose code registers elsewhere every one is.
	 */
	static String waitingInfo(String label, UUID task, Verdict v) {
		// Runnable as printed (T-2 of round 1): the task's own uuid, and the session the verb requires.
		String declare = "rearm agent task declare-delivery " + task + " --session <seat-session>";
		// A person declares on the task page (RD2-10); the command stays for an agent, runnable as printed.
		if (AgentBoardData.DeliveryMode.NONE == v.policy().mode()) {
			return "Task " + label + " passed; waiting for its delivery to be declared: on the task page (Declare delivery),"
					+ " or with " + declare + " --unit <branch or release> --commit <sha>";
		}
		List<TaskPullRequest> waiting = v.units(Delivery.WAITING);
		boolean declared = AgentBoardData.DeliveryMode.DECLARED == v.policy().mode();
		List<String> parts = waiting.stream().map(pr -> pr.url() + " " + (pr.registered()
				? String.valueOf(pr.state()).toLowerCase(Locale.ROOT) : "unregistered on this instance")).toList();
		String head = "Task " + label + " passed; waiting for " + waiting.size()
				+ (declared ? " PR(s) to be declared as merged: " : " PR(s) to merge: ") + String.join(", ", parts);
		if (declared) {
			return head + ". Declare each once merged: on the task page (Declare merge), or with " + declare
					+ " --unit <pr> --commit <merge sha>";
		}
		if (waiting.stream().anyMatch(pr -> !pr.registered())) {
			return head + ". An unregistered PR's CI does not report here: declare it on the task page (Declare merge), or"
					+ " with " + declare + " --unit <pr> --commit <merge sha>, or set delivery.mode: DECLARED on the board";
		}
		return head;
	}

	/** Closed without merging, or declared abandoned: the trigger says which. */
	static StatusTrigger blockedTrigger(Verdict v) {
		return v.abandoned() ? StatusTrigger.DELIVERY_ABANDONED : StatusTrigger.PR_CLOSED;
	}

	/** The way out of a blocked delivery: redo the work, or supersede the PR by its replacement (RD3-13). */
	static final String REOPEN_REMEDY = "Reopen it to the role that must redo the work";
	static final String SUPERSEDE_REMEDY = ", or have the role that pushes code link the PR that replaces it and declare"
			+ " this one superseded with task supersedepr";

	/**
	 * The ALERT, and the refusal a complete gives (RD3-16). Linking a replacement alone leaves the blocked PR
	 * blocking, so the remedy names supersedepr (RD3-13 run 1 T-1) whenever a PR blocks -- closed unmerged or
	 * declared abandoned, both of which may be superseded (architecture-2 §1). A task-level declaration on a
	 * board without PRs has no PR to supersede.
	 */
	private static String closedAlert(String label, Verdict v) {
		boolean supersedable = !v.units(Delivery.BLOCKED).isEmpty();
		String remedy = ". " + REOPEN_REMEDY + (supersedable ? SUPERSEDE_REMEDY : "") + ".";
		if (!v.abandoned()) {
			return "Task " + label + " passed, but its PR(s) were closed without merging: "
					+ String.join(", ", v.units(Delivery.BLOCKED).stream().map(TaskPullRequest::url).toList())
					+ remedy;
		}
		List<String> why = new ArrayList<>();
		if (null != v.taskDeclaration()) {
			why.add("its delivery was declared abandoned" + noteOf(v.taskDeclaration()));
		}
		for (TaskPullRequest pr : v.units(Delivery.BLOCKED)) {
			why.add(null != pr.declaration() ? pr.url() + " declared abandoned" + noteOf(pr.declaration())
					: pr.url() + " closed without merging");
		}
		return "Task " + label + " passed, but its delivery will not land: " + String.join("; ", why) + remedy;
	}

	private static String label(AgentTaskData td) {
		return td.label();
	}

	private static String noteOf(AgentTaskData.Delivery d) {
		return StringUtils.isBlank(d.note()) ? "" : " (" + d.note().strip() + ")";
	}

	/**
	 * Settle a DELIVERING task on what it now has (task 18c5c293): COMPLETED when delivered, to the
	 * coordinator with an ALERT when blocked, unchanged while waiting. Under the task's lock; the
	 * caller saves and posts the effects. Shared by the PR upsert, the sweep and a declaration.
	 *
	 * @return whether the task left DELIVERING
	 */
	public boolean leaveDelivering(AgentTaskData td, AgentActor actor, BoardEffects effects) {
		if (td.getStatus() != TaskStatus.DELIVERING) return false;
		Verdict v = deliveryOf(td);
		switch (v.delivery()) {
			case WAITING -> {
				return false;
			}
			case BLOCKED -> {
				td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, blockedTrigger(v), actor);
				effects.alert(closedAlert(label(td), v));
			}
			default -> {
				td.transitionStatus(TaskStatus.COMPLETED, StatusTrigger.DELIVERED, actor);
				td.setCompletedAt(ZonedDateTime.now());
				effects.info("Task " + label(td) + " delivered: "
						// A superseded unit is not evidence of delivery: only a delivered declaration says "declared"
						// (RD3-13 architecture-2 §2).
						+ (null != v.taskDeclaration() || v.pullRequests().stream().anyMatch(pr -> null != pr.declaration()
								&& AgentTaskData.DeliveryOutcome.DELIVERED == pr.declaration().outcome())
								? "its delivery is declared" : "its PR(s) merged") + ", so it is completed");
			}
		}
		return true;
	}

	// ---------- tested heads (task 3b97ccfd) ----------

	/** The PR heads a task's newest passing review or test round covered, and the role that passed it. */
	public record Tested(UUID round, String role, List<TestedHead> heads) {
		/** The head named for a linked URL, matched as the task's links are. */
		public Optional<TestedHead> headFor(String url) {
			String key = matchKey(url);
			return heads.stream().filter(h -> null != key && key.equals(matchKey(h.pr()))).findFirst();
		}
	}

	/**
	 * The newest BOARD_TEST_REPORT round of the task that passed and names tested heads: what the board
	 * holds the PRs to (task 5ec48b02). A BOARD_REVIEW_ITEMS round may name the heads its reviewer
	 * looked at, but that is informative only: reviewing a design is not testing a PR. The role is
	 * the one whose sign-off offered that round; null when none did.
	 */
	public Optional<Tested> testedOf(AgentTaskData td) {
		List<UUID> linked = null == td.getReleases() ? List.of() : td.getReleases();
		for (int i = linked.size() - 1; i >= 0; i--) {
			UUID r = linked.get(i);
			DocumentRef doc = sharedReleaseService.getReleaseData(r).map(ReleaseData::getDocument).orElse(null);
			BoardReviewItemIndex index = null == doc ? null : doc.reviewItems();
			if (null == index || !td.getUuid().equals(doc.task())) continue;
			// A replaced version's verdict was withdrawn by the version that replaced it (task RD4-7).
			if (doc.superseded()) continue;
			if (index.kind() != RearmSpecificationType.BOARD_TEST_REPORT) continue;
			if (BoardReviewItemIndex.BoardReviewVerdict.PASSED != index.verdict() || null == index.tested() || index.tested().isEmpty()) continue;
			String role = null;
			for (int j = td.getSignOffs().size() - 1; j >= 0 && null == role; j--) {
				SignOff so = td.getSignOffs().get(j);
				if (null != so.outputs() && so.outputs().contains(r)) role = so.role();
			}
			return Optional.of(new Tested(r, role, index.tested()));
		}
		return Optional.empty();
	}

	/** The newest commit ReARM knows on each linked PR it has a row for, by match key. */
	Map<String, String> headsOf(AgentTaskData td) {
		Map<String, String> out = new LinkedHashMap<>();
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) return out;
		Map<String, PullRequestData> rows = pullRequestsByKey(td.getOrg(), matchKeys(td));
		for (String key : matchKeys(td)) {
			String head = headOf(rows.get(key));
			if (null != head) out.put(key, head);
		}
		return out;
	}

	/** A PR row's head commit sha: the last of its commits, which the upsert keeps at the tip. */
	private String headOf(PullRequestData pr) {
		if (null == pr || null == pr.getCommits() || pr.getCommits().isEmpty()) return null;
		UUID sce = pr.getCommits().get(pr.getCommits().size() - 1);
		return getSourceCodeEntryService.getSourceCodeEntryData(sce).map(SourceCodeEntryData::getCommit)
				.filter(StringUtils::isNotBlank).orElse(null);
	}

	private static String shortSha(String sha) {
		return null == sha ? "?" : sha.substring(0, Math.min(7, sha.length()));
	}

	/**
	 * A DELIVERING task whose PR has moved since it was last looked at (task 3b97ccfd). Past the
	 * head a passing review or test named: back to the role that passed it, with an ALERT, so the
	 * new head is tested before anyone merges. With no head named for that PR (a round from before
	 * tested existed): the ALERT only. Each new head is looked at once, so the sweep does not
	 * repeat it.
	 *
	 * @return true when the task was reopened, and has left DELIVERING
	 */
	private boolean movedPastTested(AgentTaskData td, BoardEffects effects, WhoUpdated wu) {
		Map<String, PullRequestData> rows = pullRequestsByKey(td.getOrg(), matchKeys(td));
		Optional<Tested> tested = testedOf(td);
		Map<String, String> seen = new LinkedHashMap<>(null == td.getDeliveringHeads() ? Map.of() : td.getDeliveringHeads());
		Set<String> superseded = supersededKeys(td);
		for (String url : td.getPrUrls()) {
			String key = matchKey(url);
			// A superseded PR is not a unit any more (task RD3-13): whatever moves on it reopens nothing.
			if (null != key && superseded.contains(key)) continue;
			PullRequestData row = rows.get(key);
			String head = headOf(row);
			if (null == key || null == head || head.equalsIgnoreCase(seen.get(key))) continue;
			String last = seen.put(key, head);
			td.setDeliveringHeads(seen);
			// A merge may be reported with the merge commit as the PR's newest: what merged is what
			// passed when the head just before it was the tested one.
			boolean mergeCommit = PullRequestState.MERGED == row.getState() && null != last;
			TestedHead th = tested.flatMap(t -> t.headFor(url)).orElse(null);
			if (null != th) {
				if (th.matches(head) || (mergeCommit && th.matches(last))) continue;
				String role = tested.get().role();
				String why = "PR " + url + " moved past the tested head " + shortSha(th.head()) + " → " + shortSha(head)
						+ " after " + (null == role ? "the" : role + "'s") + " pass";
				if (null != role) {
					try {
						agentTaskService.reopen(td.getUuid(), role, why, AgentActor.system("delivery"), wu);
						effects.alert("Task " + label(td) + ": " + why + "; reopened to the " + role
								+ " so the new head is tested before anyone merges");
						return true;
					} catch (RelizaException e) {
						effects.alert("Task " + label(td) + ": " + why + "; it could not be reopened to the " + role
								+ " (" + e.getMessage() + "). Test the new head before merging.");
						continue;
					}
				}
				effects.alert("Task " + label(td) + ": " + why + ". Test the new head before merging.");
			} else if (null != last && !mergeCommit) {
				effects.alert("Task " + label(td) + ": PR " + url + " moved " + shortSha(last) + " → " + shortSha(head)
						+ " while delivering, and no passing review or test names the head it covered."
						+ " Test the new head before merging.");
			}
		}
		return false;
	}

	// ---------- registered PRs in play (task RD4-2) ----------

	/**
	 * The linked PRs in play and their heads now, by match key (task RD4-2). A linked PR is in play when this
	 * organization has a pull-request row for it, which CI writes when it reports here; its head is null while
	 * the row has no commit. A PR not in play is absent, and every rule that reads this leaves it as it was.
	 */
	public Map<String, String> inPlayHeads(AgentTaskData td) {
		Map<String, String> out = new LinkedHashMap<>();
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) return out;
		Map<String, PullRequestData> rows = pullRequestsByKey(td.getOrg(), matchKeys(td));
		for (String key : matchKeys(td)) {
			if (rows.containsKey(key)) out.put(key, headOf(rows.get(key)));
		}
		return out;
	}

	/** The base each in-play linked PR starts from, by match key: the newest entry on its target branch (task RD4-2). */
	public Map<String, AgentTaskData.BaseHead> inPlayBases(AgentTaskData td) {
		Map<String, AgentTaskData.BaseHead> out = new LinkedHashMap<>();
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) return out;
		Map<String, PullRequestData> rows = pullRequestsByKey(td.getOrg(), matchKeys(td));
		for (String key : matchKeys(td)) {
			AgentTaskData.BaseHead base = baseOf(rows.get(key));
			if (null != base) out.put(key, base);
		}
		return out;
	}

	/**
	 * The newest commit ReARM knows on a PR row's target branch: the newest source code entry on the PR's
	 * target repository and branch, which CI writes when it reports a build there. Null when the row names no
	 * target; a base with no commit when the branch has no entry yet.
	 */
	AgentTaskData.BaseHead baseOf(PullRequestData row) {
		if (null == row || null == row.getTargetVcsRepository() || StringUtils.isBlank(row.getTargetBranchName())) return null;
		UUID vcs = row.getTargetVcsRepository();
		String branch = row.getTargetBranchName();
		return getSourceCodeEntryService.newestOnBranch(vcs, branch)
				.map(sce -> new AgentTaskData.BaseHead(vcs, branch, SourceCodeEntryData.dataFromRecord(sce).getCommit(),
						sce.getCreatedDate()))
				.orElseGet(() -> new AgentTaskData.BaseHead(vcs, branch, null, null));
	}

	/**
	 * How many commits ReARM recorded on the base branch after {@code base}: "base moved: N commits since your
	 * round" (task RD4-2). Null when unknown: no base was recorded, or the branch has no entry at all, which is
	 * never read as zero. Visibility only: nothing here knows whether the PR still merges.
	 */
	public Integer baseMovedBy(AgentTaskData.BaseHead base) {
		if (null == base) return null;
		long n = getSourceCodeEntryService.countOnBranchAfter(base.vcs(), base.branch(), base.at());
		if (null == base.at() && n == 0) return null;
		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	/**
	 * The base the task's newest round started from for a linked PR: the hop in progress when it recorded
	 * bases, else the newest sign-off that recorded one for this PR. Null when no round did.
	 */
	public static AgentTaskData.BaseHead roundBase(AgentTaskData td, String url) {
		String key = matchKey(url);
		if (null == key) return null;
		AgentTaskData.TaskAssignment a = td.getAssignment();
		if (null != a && null != a.baseHeads()) return a.baseHeads().get(key);
		List<SignOff> signOffs = null == td.getSignOffs() ? List.of() : td.getSignOffs();
		for (int i = signOffs.size() - 1; i >= 0; i--) {
			Map<String, AgentTaskData.BaseHead> bases = signOffs.get(i).baseHeads();
			if (null != bases && bases.containsKey(key)) return bases.get(key);
		}
		return null;
	}

	/** The design's words for the sign-off head check (task RD4-2), which the templates and the CLI repeat. */
	public static final String NO_PR_MOVED = "no linked PR moved since your assignment; push your commits to the PR"
			+ " branches, or sign off with --no-code for a round that changed no code";

	/** The design's words (round 2) for a PR the hop linked that was opened before it and has not moved. */
	public static final String LINKED_PR_PREDATES = "the PR you linked predates your assignment and has not moved";

	/**
	 * Why a PASSED sign-off by a role that pushes code is refused (task RD4-2): the hop recorded its in-play PRs'
	 * heads at assignment, and none of them has moved. A PR the hop linked while it was in play records its head
	 * at link time (design round 2); it counts as moved when its head differs from that one, or when it was
	 * opened after the assignment, since a fresh PR is the hop's own work. One opened before the hop and linked
	 * without a push does not count, and the refusal names it. A PR that came into play during the hop by any
	 * other way -- CI reporting it for the first time, or a link made before CI knew it -- counts as moved: a
	 * first report is a build of a push. Not checked: an assignment recorded before the heads, a hop with no PR
	 * in play, and a PR whose row has no head yet or that was declared superseded. Empty when the sign-off may
	 * pass.
	 */
	public Optional<String> noLinkedPrMoved(AgentTaskData td, AgentTaskData.TaskAssignment a) {
		if (null == a || null == a.prHeads()) return Optional.empty();
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) return Optional.empty();
		Set<String> superseded = supersededKeys(td);
		Map<String, String> linked = null == a.linkedHeads() ? Map.of() : a.linkedHeads();
		Map<String, PullRequestData> rows = pullRequestsByKey(td.getOrg(), matchKeys(td));
		List<String> still = new ArrayList<>();
		List<String> stale = new ArrayList<>();
		for (String key : matchKeys(td)) {
			PullRequestData row = rows.get(key);
			if (null == row || superseded.contains(key)) continue;
			String head = headOf(row);
			if (null == head) continue;
			if (a.prHeads().containsKey(key)) {
				String was = a.prHeads().get(key);
				if (null == was || !was.equalsIgnoreCase(head)) return Optional.empty();
				still.add(urlOf(td, key) + " at " + shortSha(head));
			} else if (linked.containsKey(key)) {
				String was = linked.get(key);
				if (null == was || !was.equalsIgnoreCase(head) || openedAfter(row, a.assignedAt())) return Optional.empty();
				stale.add(urlOf(td, key) + " at " + shortSha(head));
			} else {
				return Optional.empty();
			}
		}
		if (still.isEmpty() && stale.isEmpty()) return Optional.empty();
		StringBuilder why = new StringBuilder(NO_PR_MOVED.substring(0, 1).toUpperCase(Locale.ROOT))
				.append(NO_PR_MOVED.substring(1));
		if (!still.isEmpty()) why.append(" (").append(String.join(", ", still)).append(")");
		why.append(".");
		if (!stale.isEmpty()) {
			why.append(" ").append(LINKED_PR_PREDATES.substring(0, 1).toUpperCase(Locale.ROOT))
					.append(LINKED_PR_PREDATES.substring(1)).append(": ").append(String.join(", ", stale)).append(".");
		}
		return Optional.of(why.toString());
	}

	/**
	 * Whether the PR was opened after {@code instant}: its SCM creation time when CI sent one, else the time this
	 * instance first recorded the PR, which is never earlier than the PR itself.
	 */
	static boolean openedAfter(PullRequestData row, ZonedDateTime instant) {
		if (null == row || null == instant) return false;
		ZonedDateTime opened = null != row.getPrCreatedDate() ? row.getPrCreatedDate() : row.getCreatedDate();
		return null != opened && opened.isAfter(instant);
	}

	/**
	 * A linked PR in play as the hop links it (task RD4-2): its head now (null while the row has no commit) and
	 * the base it starts from. Empty when the organization has no row for it, which is not in play.
	 */
	public Optional<LinkedPr> linkedInPlay(UUID org, String url) {
		String key = matchKey(url);
		if (null == key) return Optional.empty();
		PullRequestData row = pullRequestsByKey(org, List.of(key)).get(key);
		if (null == row) return Optional.empty();
		return Optional.of(new LinkedPr(key, headOf(row), baseOf(row)));
	}

	/** A PR in play as a hop links it: its match key, its head (null without a commit) and its base (task RD4-2). */
	public record LinkedPr(String key, String head, AgentTaskData.BaseHead base) {}

	/** The task's own spelling of a linked PR, by its match key. */
	private static String urlOf(AgentTaskData td, String key) {
		for (String u : null == td.getPrUrls() ? List.<String>of() : td.getPrUrls()) {
			if (key.equals(matchKey(u))) return u;
		}
		return key;
	}

	// ---------- superseded PRs (task RD3-13) ----------

	/**
	 * A PR's repository, as a key: the normalised URL without its last two path segments (the "pull/692"
	 * or "merge_requests/12" part), so two PRs of one repository compare equal. Null for a blank URL.
	 */
	static String repositoryOf(String url) {
		String n = normalise(url);
		if (null == n) return null;
		String path = n;
		for (int i = 0; i < 2 && path.lastIndexOf('/') > 0; i++) path = path.substring(0, path.lastIndexOf('/'));
		return path;
	}

	/** The match keys of the task's PRs whose newest declaration is SUPERSEDED. */
	static Set<String> supersededKeys(AgentTaskData td) {
		Map<String, AgentTaskData.DeliveryOutcome> newest = new HashMap<>();
		for (AgentTaskData.Delivery d : null == td.getDeliveries() ? List.<AgentTaskData.Delivery>of() : td.getDeliveries()) {
			String key = matchKey(d.unit());
			if (null != key) newest.put(key, d.outcome());
		}
		Set<String> out = new HashSet<>();
		newest.forEach((k, o) -> { if (AgentTaskData.DeliveryOutcome.SUPERSEDED == o) out.add(k); });
		return out;
	}

	/**
	 * A superseded PR that has merged after all is a conflict (architecture §3.2): the unit stays superseded,
	 * and the board is told once, naming both PRs. @return whether an ALERT was added
	 */
	boolean alertSupersededMerged(AgentTaskData td, BoardEffects effects) {
		if (null == td.getSupersededMergedAlerted()) td.setSupersededMergedAlerted(new ArrayList<>());
		boolean added = false;
		for (TaskPullRequest pr : pullRequestsOf(td)) {
			if (Delivery.SUPERSEDED != pr.unit() || PullRequestState.MERGED != pr.state()) continue;
			String key = normalise(pr.url());
			if (td.getSupersededMergedAlerted().contains(key)) continue;
			td.getSupersededMergedAlerted().add(key);
			effects.alert("Task " + label(td) + ": " + pr.url() + " was declared superseded by "
					+ pr.declaration().supersededBy() + " but has merged after all. It stays superseded; check which"
					+ " change landed, and reopen the task if the replacement's work is now wrong.");
			added = true;
		}
		return added;
	}

	// ---------- leaving DELIVERING ----------

	/**
	 * Re-check the org's DELIVERING tasks linked to this PR once the upsert commits; inline when
	 * there is no transaction. Never throws into the upsert, which CI is waiting on.
	 */
	public void onPullRequestChangedAfterCommit(UUID orgUuid, String endpoint, WhoUpdated wu) {
		if (null == orgUuid || StringUtils.isBlank(endpoint)) return;
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			onPullRequestChanged(orgUuid, endpoint, wu);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				onPullRequestChanged(orgUuid, endpoint, wu);
			}
		});
	}

	public void onPullRequestChanged(UUID orgUuid, String endpoint, WhoUpdated wu) {
		try {
			String key = normalise(endpoint);
			for (AgentTask t : agentTaskRepository.findByOrgAndStatus(orgUuid.toString(), TaskStatus.DELIVERING.name())) {
				AgentTaskData td = AgentTaskData.dataFromRecord(t);
				if (null != td.getPrUrls() && td.getPrUrls().stream().anyMatch(u -> key.equals(normalise(u)))) {
					settleDelivering(td.getUuid(), wu);
				}
			}
		} catch (Exception e) {
			log.error("Re-checking delivery after the PR {} changed in org {} failed; the sweep will retry",
					endpoint, orgUuid, e);
		}
	}

	/** Every DELIVERING task, for the PR events CI did not send. Idempotent. */
	public int sweep(WhoUpdated wu) {
		int moved = 0;
		for (AgentTask t : agentTaskRepository.findByStatus(TaskStatus.DELIVERING.name())) {
			if (settleDelivering(t.getUuid(), wu)) moved++;
		}
		return moved;
	}

	/** One task, in its own transaction; logged, never thrown. @return whether it left DELIVERING */
	private boolean settleDelivering(UUID taskUuid, WhoUpdated wu) {
		try {
			return self.settleDeliveringOnce(taskUuid, wu);
		} catch (Exception e) {
			log.error("Settling the delivery of task {} failed", taskUuid, e);
			return false;
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public boolean settleDeliveringOnce(UUID taskUuid, WhoUpdated wu) throws RelizaException {
		AgentTask locked = agentTaskRepository.findByIdWriteLocked(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		// The lock statement does not discard a copy already in the persistence context.
		entityManager.refresh(locked);
		AgentTaskData td = AgentTaskData.dataFromRecord(locked);
		if (td.getStatus() != TaskStatus.DELIVERING) return false;
		BoardEffects effects = new BoardEffects(td.getBoard());
		Map<String, String> headsBefore = null == td.getDeliveringHeads() ? Map.of() : Map.copyOf(td.getDeliveringHeads());
		// Before any evidence counts, declared or merged: a PR that moved past the head that passed is
		// not a delivery of what passed (task 3b97ccfd).
		if (movedPastTested(td, effects, wu)) {
			boardEffectsApplier.applyAfterCommit(effects, wu);
			return true;
		}
		boolean conflict = alertSupersededMerged(td, effects);
		if (!leaveDelivering(td, AgentActor.system("delivery"), effects)) {
			if (conflict || !headsBefore.equals(td.getDeliveringHeads())) {
				// Still waiting, at a newer head: remembered, and any ALERT about it posted.
				agentTaskService.saveData(td, wu);
				boardEffectsApplier.applyAfterCommit(effects, wu);
			}
			return false;
		}
		agentTaskService.saveData(td, wu);
		boardEffectsApplier.applyAfterCommit(effects, wu);
		return true;
	}
}
