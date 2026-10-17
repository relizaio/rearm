/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.common.Utils;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves what a task must be able to read before it starts, and to which version.
 *
 * <p>A requirement is not only a gate but a binding: when it is met, the resolution names the
 * release the agent is to work from, which is pinned on the assignment. That is what lets a later
 * rejection of that version be traced to the work built on it, rather than inferred from
 * timestamps.
 *
 * <p>Documents are looked up through the board: its document component for the specification
 * (board-documents.md §2.3), or, for a board from before it recorded them, the one its target node
 * carries that no other board owns.
 */
@Slf4j
@Service
public class AgentTaskInputService {

	@Autowired private BranchService branchService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private BoardDocumentComponentService boardDocumentComponentService;

	/** The pre-shipment ladder. Anything shipped or later counts as fully mature. */
	static int maturity(ReleaseLifecycle lc) {
		if (lc == null) return -1;
		return switch (lc) {
			case CANCELLED, REJECTED -> -1;
			case PENDING -> 0;
			case DRAFT -> 1;
			case ASSEMBLED -> 2;
			default -> 3; // READY_TO_SHIP and every post-shipment lifecycle
		};
	}

	/**
	 * Role requirements unioned with the task's own. Add-only: where both name the same input,
	 * the higher threshold wins, so a task can demand more than its role and never less.
	 */
	public List<RequiredInput> effectiveRequirements(AgentTaskRoleConfigData rc, AgentTaskData td) {
		Map<String, RequiredInput> merged = new LinkedHashMap<>();
		List<RequiredInput> all = new ArrayList<>();
		// An investigation (task RD4-12) reads what it was commissioned with, never its role's work-task inputs:
		// a tester asked for a report has no test plan on the investigation to wait for.
		boolean investigation = null != td && td.isInvestigation();
		if (!investigation && rc != null && rc.getRequiredInputs() != null) all.addAll(rc.getRequiredInputs());
		if (td != null && td.getRequiredInputs() != null) all.addAll(td.getRequiredInputs());
		for (RequiredInput ri : all) {
			if (ri == null || ri.kind() == null) continue;
			merged.merge(ri.mergeKey(), ri,
					(a, b) -> maturity(b.minLifecycle()) > maturity(a.minLifecycle()) ? b : a);
		}
		return List.copyOf(merged.values());
	}

	/** One requirement's verdict: the binding when met, the reason when not. */
	public record InputVerdict(RequiredInput requirement, ResolvedInput resolved, String unmetReason) {
		public boolean met() { return resolved != null; }
	}

	/** Verdicts for every effective requirement of a task, in declaration order. */
	public List<InputVerdict> evaluate(AgentBoardData board, AgentTaskRoleConfigData rc, AgentTaskData td) {
		List<InputVerdict> out = new ArrayList<>();
		for (RequiredInput ri : effectiveRequirements(rc, td)) {
			out.add(evaluateOne(board, td, ri));
		}
		return out;
	}

	/** True when nothing is outstanding -- the eligibility question at poll and assign. */
	public boolean satisfied(AgentBoardData board, AgentTaskRoleConfigData rc, AgentTaskData td) {
		return evaluate(board, rc, td).stream().allMatch(InputVerdict::met);
	}

	/** The bindings to pin on an assignment: what the agent is to work from. */
	public List<ResolvedInput> bindings(AgentBoardData board, AgentTaskRoleConfigData rc, AgentTaskData td) {
		return evaluate(board, rc, td).stream().filter(InputVerdict::met)
				.map(InputVerdict::resolved).toList();
	}

	private InputVerdict evaluateOne(AgentBoardData board, AgentTaskData td, RequiredInput ri) {
		if (null != ri.release()) return evaluatePinned(td, ri);
		InputResolution mode = ri.resolution() != null ? ri.resolution()
				: (board.getDefaultInputResolution() != null ? board.getDefaultInputResolution()
						: InputResolution.LATEST_PASSING);
		if (ri.kind() == InputKind.DOCUMENT) return evaluateDocument(board, td, ri, mode);
		return evaluateRelease(board, td, ri, mode);
	}

	/**
	 * One release pinned by uuid (task RD4-12): met by that release while it is live in the task's organization
	 * and at the floor, if the requirement names one. A cancelled or rejected release is nobody's input.
	 */
	private InputVerdict evaluatePinned(AgentTaskData td, RequiredInput ri) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(ri.release());
		if (ord.isEmpty() || (null != td && null != td.getOrg() && !td.getOrg().equals(ord.get().getOrg()))) {
			return new InputVerdict(ri, null, "pinned release " + ri.release() + " does not exist");
		}
		ReleaseData rd = ord.get();
		int need = Math.max(maturity(ri.minLifecycle()), maturity(ReleaseLifecycle.PENDING));
		if (maturity(rd.getLifecycle()) < need) {
			return new InputVerdict(ri, null, "pinned release " + rd.getVersion() + " is " + rd.getLifecycle());
		}
		DocumentRef doc = rd.getDocument();
		return new InputVerdict(ri, new ResolvedInput(null != doc ? InputKind.DOCUMENT : InputKind.RELEASE,
				null != doc ? doc.specification() : ri.specification(), rd.getComponent(), rd.getUuid(),
				rd.getVersion(), rd.getLifecycle()), null);
	}

	private InputVerdict evaluateDocument(AgentBoardData board, AgentTaskData td, RequiredInput ri,
			InputResolution mode) {
		if (ri.specification() == null) {
			return new InputVerdict(ri, null, "document requirement names no specification type");
		}
		// TASK scope resolves among THIS task's own document releases, and nothing else.
		//
		// Neither pre-existing path could do this. Component scope (below) resolves against the
		// board target's document components, and a task-scoped type keeps one component per
		// target holding every task's rounds -- so it would bind to whichever task published most
		// recently. The RELEASE-kind task branch walks task.releases with no specification filter,
		// so it would happily hand back the task's code release, or a test report when review items
		// were asked for. Either mistake gives a fixer someone else's work and looks like it
		// worked.
		if (ri.scope() == InputScope.TASK) return evaluateTaskDocument(td, ri, mode);
		if (board.getTarget() == null) {
			return new InputVerdict(ri, null, "board has no target node to resolve documents against");
		}
		// The board's own component for the specification, never "every component on the target
		// that carries it" (board-documents.md D4): with that, a second board on the same target, or
		// sharing the documents repository, would hand its documents to this board's roles.
		List<UUID> candidates = boardDocumentComponentService.candidatesOf(board, ri.specification());
		if (candidates.isEmpty()) {
			return new InputVerdict(ri, null, "board " + board.getName() + " has no "
					+ ri.specification() + " document yet");
		}
		for (UUID comp : candidates) {
			Optional<ResolvedInput> hit = resolveComponentRelease(comp, ri, mode, InputKind.DOCUMENT);
			if (hit.isPresent()) return new InputVerdict(ri, hit.get(), null);
		}
		return new InputVerdict(ri, null, ri.specification() + " is below " + ri.minLifecycle());
	}

	/**
	 * A document requirement at TASK scope: the newest release of this task carrying this
	 * specification, subject to the lifecycle floor.
	 *
	 * <p>Walks {@code task.releases} newest first and keeps only releases whose {@code document}
	 * names both this specification and this task. The task filter is not redundant with walking
	 * the task's own list: a task-scoped document component is shared across the board, so a
	 * release reachable from this task could still belong to another one if it were ever linked
	 * by mistake, and binding a fixer to another task's review items is exactly the failure this
	 * branch exists to prevent.
	 *
	 * <p>{@code LATEST_PASSING} binds the newest release that clears the floor; {@code
	 * STRICT_LATEST} requires the newest matching release itself to clear it, so a fresh draft
	 * round un-satisfies the requirement rather than silently resolving to an older baselined one.
	 */
	private InputVerdict evaluateTaskDocument(AgentTaskData td, RequiredInput ri, InputResolution mode) {
		List<UUID> linked = td.getReleases();
		if (null == linked || linked.isEmpty()) {
			return new InputVerdict(ri, null, "this task has published no " + ri.specification() + " yet");
		}
		boolean sawAny = false;
		for (int i = linked.size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(linked.get(i));
			if (ord.isEmpty()) continue;
			ReleaseData rd = ord.get();
			DocumentRef doc = rd.getDocument();
			if (null == doc || doc.specification() != ri.specification()) continue;
			if (!td.getUuid().equals(doc.task())) continue;
			// A replaced version is never an input: its round's newer version is (task RD4-7).
			if (doc.superseded()) continue;
			sawAny = true;
			if (maturity(rd.getLifecycle()) >= maturity(ri.minLifecycle())) {
				return new InputVerdict(ri, new ResolvedInput(InputKind.DOCUMENT, ri.specification(),
						rd.getComponent(), rd.getUuid(), rd.getVersion(), rd.getLifecycle()), null);
			}
			if (mode == InputResolution.STRICT_LATEST) {
				// The newest round is below the floor, and STRICT_LATEST means the newest is the
				// only one that counts. Stopping here is the point: falling through to an older
				// round would report the requirement met by a document the reviewer has already
				// superseded.
				return new InputVerdict(ri, null, "the newest " + ri.specification()
						+ " round of this task is below " + ri.minLifecycle());
			}
		}
		return new InputVerdict(ri, null, sawAny
				? "no " + ri.specification() + " round of this task reaches " + ri.minLifecycle()
				: "this task has published no " + ri.specification() + " yet");
	}

	private InputVerdict evaluateRelease(AgentBoardData board, AgentTaskData td, RequiredInput ri,
			InputResolution mode) {
		if (ri.scope() == InputScope.TASK) {
			List<UUID> linked = td.getReleases();
			if (linked == null || linked.isEmpty()) {
				return new InputVerdict(ri, null, "this task has linked no release yet");
			}
			for (int i = linked.size() - 1; i >= 0; i--) {
				Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(linked.get(i));
				if (ord.isEmpty()) continue;
				ReleaseData rd = ord.get();
				// Document releases are appended to task.releases so TASK-scope DOCUMENT
				// requirements can find them, which means this branch now sees them too. A
				// requirement for the task's BUILT release must not resolve to its review items
				// round: the caller asked "is the work built", and a review document is not an
				// answer to that. Skipped rather than counted, so the verdict stays honest.
				if (null != rd.getDocument()) continue;
				if (maturity(rd.getLifecycle()) >= maturity(ri.minLifecycle())) {
					return new InputVerdict(ri, new ResolvedInput(InputKind.RELEASE, null,
							rd.getComponent(), rd.getUuid(), rd.getVersion(), rd.getLifecycle()), null);
				}
			}
			return new InputVerdict(ri, null, "no release of this task reaches " + ri.minLifecycle());
		}
		UUID comp = ri.component() != null ? ri.component() : board.getTarget();
		if (comp == null) return new InputVerdict(ri, null, "release requirement names no component");
		return resolveComponentRelease(comp, ri, mode, InputKind.RELEASE)
				.map(r -> new InputVerdict(ri, r, null))
				.orElseGet(() -> new InputVerdict(ri, null,
						"no release of " + componentName(comp) + " reaches " + ri.minLifecycle()));
	}

	/**
	 * LATEST_PASSING takes the newest release at or above the threshold, so a fresh draft never
	 * un-satisfies a baselined version; STRICT_LATEST demands the component's own latest clear it.
	 */
	private Optional<ResolvedInput> resolveComponentRelease(UUID componentUuid, RequiredInput ri,
			InputResolution mode, InputKind kind) {
		Optional<Branch> base = branchService.getBaseBranchOfComponent(componentUuid);
		if (base.isEmpty()) return Optional.empty();
		List<ReleaseData> releases = sharedReleaseService.listReleaseDataOfBranch(base.get().getUuid(), true);
		if (releases == null || releases.isEmpty()) return Optional.empty();
		int need = maturity(ri.minLifecycle());
		if (mode == InputResolution.STRICT_LATEST) {
			ReleaseData latest = releases.get(0);
			return maturity(latest.getLifecycle()) >= need ? Optional.of(bind(kind, ri, latest)) : Optional.empty();
		}
		return releases.stream().filter(rd -> maturity(rd.getLifecycle()) >= need).findFirst()
				.map(rd -> bind(kind, ri, rd));
	}

	private ResolvedInput bind(InputKind kind, RequiredInput ri, ReleaseData rd) {
		return new ResolvedInput(kind, ri.specification(), rd.getComponent(), rd.getUuid(),
				rd.getVersion(), rd.getLifecycle());
	}

	/**
	 * The board's document components that live in the board's documents repository.
	 *
	 * <p>Used by the sign-off lock check. The components are the board's own (board-documents.md
	 * §2.3); the repository filter keeps the check to that repository, so a lock on some other
	 * repository does not stop this board's hops from closing.
	 */
	public List<UUID> documentComponentsOfRepo(AgentBoardData board) {
		List<UUID> out = new ArrayList<>();
		if (null == board.getDocumentsRepo()) return out;
		for (UUID comp : boardDocumentComponentService.componentsOf(board)) {
			Optional<ComponentData> ocd = getComponentService.getComponentData(comp);
			// A uuid comparison, because both sides now name the same repository ROW. This used to
			// canonicalise two strings and compare them, which is how the check came to be inert:
			// the two sides were canonicalised differently and matched nothing, so a lock on the
			// documents repository silently never fired.
			if (ocd.isPresent() && board.getDocumentsRepo().equals(ocd.get().getVcs())) out.add(comp);
		}
		return out;
	}


	/**
	 * Whether a live assignment's pinned versions still hold. The binding says which release the
	 * agent was told to work from; this asks whether that release has since moved below what the
	 * requirement asks for -- a rejected document, a lifecycle walked back.
	 *
	 * <p>Computed on read rather than stored: a stamped verdict goes stale the moment the input
	 * is approved again, and a board view full of stale warnings is worse than none. Nothing is
	 * interrupted either way -- an agent cannot be stopped mid-task, so the point of noticing is
	 * that the coordinator can reject the resulting release or re-queue the work when it lands.
	 */
	public List<String> regressedBindings(AgentTaskData td) {
		List<String> out = new ArrayList<>();
		if (td == null || td.getAssignment() == null) return out;
		List<ResolvedInput> pinned = td.getAssignment().resolvedInputs();
		if (pinned == null || pinned.isEmpty()) return out;
		for (ResolvedInput ri : pinned) {
			if (ri == null || ri.release() == null) continue;
			Optional<ReleaseData> now = sharedReleaseService.getReleaseData(ri.release());
			if (now.isEmpty()) {
				out.add(describe(ri) + " no longer exists");
				continue;
			}
			ReleaseLifecycle current = now.get().getLifecycle();
			if (maturity(current) < maturity(ri.lifecycle())) {
				out.add(describe(ri) + " has fallen from " + ri.lifecycle() + " to " + current);
			}
		}
		return out;
	}

	private static String describe(ResolvedInput ri) {
		String what = ri.specification() != null ? ri.specification().name() : "release";
		return what + " " + (ri.version() == null ? "" : ri.version()).trim();
	}

	private String componentName(UUID componentUuid) {
		return getComponentService.getComponentData(componentUuid)
				.map(ComponentData::getName).orElse(componentUuid.toString());
	}
}
