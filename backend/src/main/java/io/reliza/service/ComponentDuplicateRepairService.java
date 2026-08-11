/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.oss.OssReleaseService;

/**
 * Feature-gated repair for DUPLICATE component registrations: several
 * non-archived COMPONENT-type components of one org bound to the same
 * (vcs, repoPath) AND carrying the same name. Name equality is required even
 * though resolution is name-blind -- the incident loop derives the identical
 * name on every duplicate, while a name mismatch signals possibly-intentional
 * distinct components, which are never auto-merged (and deliberately not
 * reported: operator direction, 2026-07-30 -- only the repairable count is
 * logged, one line per org).
 *
 * <p>How they arise: the resolve and create sides of VCS-based resolution used
 * to canonicalize URIs differently, and create-on-missing callers treated every
 * resolution failure -- including "multiple found" -- as absence. A first-build
 * race seeds two; each CI run then minted another (30+ observed in prod, one
 * per run). The resolution fixes stop NEW duplicates; this sweep repairs the
 * accumulated estate, which otherwise keeps every affected CI red with
 * "Multiple components found ... Please use component UUID instead."
 *
 * <p>Repair contract (per duplicate group): the OLDEST component is the leader
 * -- it is the one the org's history accreted around. For every other
 * component: when its release versions do not collide with versions already
 * under the leader, its releases are FOLDED under the leader (moved to the
 * leader's same-named branch, auto-created when absent), then the duplicate's
 * branches and the duplicate itself are archived. On any version collision the
 * duplicate is archived WITHOUT folding -- its releases stay attached to the
 * archived component, preserved and recoverable, rather than risking two
 * different releases claiming one version slot under the leader.
 *
 * <p>Release moves save with considerTriggers=false: a repair must not fire
 * lifecycle triggers, notifications or auto-integrate.
 *
 * <p>Gated by {@code relizaprops.enforceUniqueComponents} (helm:
 * {@code enforceUniqueComponents}, default false). When enabled the sweep runs
 * once per boot, asynchronously, after the application is ready. It is also
 * exposed per-org through the {@code repairDuplicateComponents} mutation (org
 * ADMIN) so operators can repair a single org on demand -- and so e2e tests can
 * drive it without a server restart.
 */
@Service
public class ComponentDuplicateRepairService {

	private static final Logger log = LoggerFactory.getLogger(ComponentDuplicateRepairService.class);

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private ReleaseService releaseService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private OrganizationService organizationService;

	// Self-proxy so per-group @Transactional applies on internal calls.
	@Lazy @Autowired private ComponentDuplicateRepairService self;

	@Value("${relizaprops.enforceUniqueComponents:false}")
	private boolean enforceUniqueComponents;

	public record RepairSummary(
			int groupsExamined,
			int releasesFolded,
			int componentsArchived,
			int branchesArchived,
			int conflictGroups) {
		public static RepairSummary empty() { return new RepairSummary(0, 0, 0, 0, 0); }
		public RepairSummary plus(RepairSummary o) {
			return new RepairSummary(
					groupsExamined + o.groupsExamined,
					releasesFolded + o.releasesFolded,
					componentsArchived + o.componentsArchived,
					branchesArchived + o.branchesArchived,
					conflictGroups + o.conflictGroups);
		}
	}

	/** Duplicate census for one org: same-name group count + excess (repairable) components. */
	public record DuplicateCount(int groups, int excessComponents) {}

	/**
	 * Startup pass across every organization, async so a large estate never
	 * blocks boot. The CENSUS always runs: every boot counts existing
	 * duplicates by the exact sweep identity (COMPONENT type, vcs + normalized
	 * repoPath + NAME) and reports any findings at error, so an affected
	 * instance is visible without any flag. The SWEEP itself stays gated by
	 * enforceUniqueComponents so plain upgrades never mutate data.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		CompletableFuture.runAsync(() -> {
			try {
				int orgsWithDups = 0, totalGroups = 0, totalExcess = 0;
				RepairSummary total = RepairSummary.empty();
				for (OrganizationData od : organizationService.listAllOrganizationData()) {
					// Archived orgs run no CI; their estate cannot page anyone and
					// must not generate boot noise. The org-scoped ADMIN mutation can
					// still target one explicitly if ever needed.
					if (od.getStatus() == StatusEnum.ARCHIVED) continue;
					try {
						DuplicateCount count = countDuplicates(od.getUuid());
						if (count.groups() > 0) {
							orgsWithDups++;
							totalGroups += count.groups();
							totalExcess += count.excessComponents();
							// One line per org: the count of components an actual repair
							// would act on (fold or archive), nothing else.
							log.error("[DUP-COMPONENT-REPAIR] org {}: {} component(s) in scope for repair "
									+ "across {} duplicate group(s)",
									od.getUuid(), count.excessComponents(), count.groups());
						}
						if (enforceUniqueComponents) {
							total = total.plus(repairOrganization(od.getUuid(), WhoUpdated.getAutoWhoUpdated()));
						}
					} catch (Exception e) {
						log.error("Duplicate-component startup pass failed for org {}", od.getUuid(), e);
					}
				}
				if (enforceUniqueComponents) {
					log.error("[DUP-COMPONENT-REPAIR] startup sweep complete: groups={}, releasesFolded={}, "
							+ "componentsArchived={}, branchesArchived={}, conflictGroups={}",
							total.groupsExamined(), total.releasesFolded(), total.componentsArchived(),
							total.branchesArchived(), total.conflictGroups());
				} else if (totalGroups > 0) {
					log.error("[DUP-COMPONENT-REPAIR] startup census: {} duplicate group(s) / {} excess "
							+ "component(s) across {} org(s); enforceUniqueComponents=false so NOTHING was "
							+ "repaired -- enable the flag or run repairDuplicateComponents per org",
							totalGroups, totalExcess, orgsWithDups);
				} else {
					// Positive confirmation (operator request): a clean boot prints
					// exactly one line, so "checked and clean" is distinguishable
					// from "census never ran".
					log.error("[DUP-COMPONENT-REPAIR] startup census clean: no duplicate components in scope for repair");
				}
			} catch (Exception e) {
				log.error("Duplicate-component startup pass failed", e);
			}
		});
	}

	/**
	 * Count same-name duplicate groups for one org, using EXACTLY the sweep's
	 * grouping (shared code path) -- the census can never drift from what the
	 * sweep would repair. Read-only.
	 */
	public DuplicateCount countDuplicates(UUID orgUuid) {
		int groups = 0, excess = 0;
		for (List<Component> group : sameNameDuplicateGroups(orgUuid).values()) {
			groups++;
			excess += group.size() - 1;
		}
		return new DuplicateCount(groups, excess);
	}

	/**
	 * Repair one organization: group its live VCS-bound components by
	 * (vcs, repoPath) and repair every group holding more than one.
	 */
	public RepairSummary repairOrganization(UUID orgUuid, WhoUpdated wu) {
		RepairSummary summary = RepairSummary.empty();
		for (Map.Entry<String, List<Component>> group : sameNameDuplicateGroups(orgUuid).entrySet()) {
			try {
				summary = summary.plus(self.repairGroup(orgUuid, group.getValue(), wu));
			} catch (Exception e) {
				// Per-group isolation: one failed group must not abort the org.
				log.error("Duplicate-component repair failed for org {} group {}", orgUuid, group.getKey(), e);
			}
		}
		if (summary.groupsExamined() > 0) {
			log.error("[DUP-COMPONENT-REPAIR] org {}: groups={}, releasesFolded={}, componentsArchived={}, "
					+ "branchesArchived={}, conflictGroups={}",
					orgUuid, summary.groupsExamined(), summary.releasesFolded(), summary.componentsArchived(),
					summary.branchesArchived(), summary.conflictGroups());
		}
		return summary;
	}

	/**
	 * Repair identity: (vcs, repoPath, NAME), COMPONENT type only (the query
	 * filters type). repoPath normalizes null / '' / '.' into one root bucket,
	 * mirroring FIND_COMPONENT_BY_VCS_AND_PATH (a UI-created component stores
	 * null where CI stores '.'). NAME is deliberately REQUIRED to match even
	 * though resolution is name-blind: the incident loop derives the identical
	 * name on every duplicate registration (same URI, same path -> same derived
	 * name, verified empirically), while a name MISMATCH on one (vcs, repoPath)
	 * signals possibly-intentional distinct components -- those are never
	 * auto-merged (resolution for them stays ambiguous; surfacing that is left
	 * to the resolution error itself, per operator direction).
	 *
	 * <p>Returns only groups with 2+ members. Shared by the startup census, the
	 * startup sweep and the mutation, so counting and repairing can never
	 * disagree about what a duplicate is.
	 */
	private Map<String, List<Component>> sameNameDuplicateGroups(UUID orgUuid) {
		Map<String, List<Component>> groups = new LinkedHashMap<>();
		for (Component c : componentService.listLiveVcsComponentsOfOrg(orgUuid)) {
			ComponentData cd = ComponentData.dataFromRecord(c);
			if (cd.getVcs() == null) continue;
			String vcsPathKey = cd.getVcs() + "|" + normalizeRepoPath(cd.getRepoPath());
			groups.computeIfAbsent(vcsPathKey + "|" + cd.getName(), k -> new ArrayList<>()).add(c);
		}
		groups.values().removeIf(g -> g.size() < 2);
		return groups;
	}

	/** Root-identity normalization, mirroring FIND_COMPONENT_BY_VCS_AND_PATH. */
	private static String normalizeRepoPath(String repoPath) {
		return (repoPath == null || repoPath.isEmpty() || ".".equals(repoPath)) ? "" : repoPath;
	}

	/**
	 * Repair a single duplicate group atomically: either the whole group's
	 * folds + archivals commit, or none do (the group is retried on the next
	 * startup / manual run).
	 */
	@Transactional
	public RepairSummary repairGroup(UUID orgUuid, List<Component> components, WhoUpdated wu) throws RelizaException {
		List<Component> ordered = new ArrayList<>(components);
		ordered.sort((a, b) -> a.getCreatedDate().compareTo(b.getCreatedDate()));
		Component leader = ordered.get(0);

		// Version slots already occupied under the leader; grows as folds land so
		// a version can never be claimed twice within one group repair.
		Set<String> leaderVersions = new HashSet<>();
		for (Release r : releaseService.listReleasesByComponent(leader.getUuid())) {
			ReleaseData rd = ReleaseData.dataFromRecord(r);
			if (rd.getVersion() != null) leaderVersions.add(rd.getVersion());
		}

		int releasesFolded = 0;
		int componentsArchived = 0;
		int branchesArchived = 0;
		int conflictGroups = 0;

		for (Component dup : ordered.subList(1, ordered.size())) {
			List<Release> dupReleases = releaseService.listReleasesByComponent(dup.getUuid());

			boolean conflict = dupReleases.stream()
					.map(r -> ReleaseData.dataFromRecord(r).getVersion())
					.anyMatch(v -> v != null && leaderVersions.contains(v));

			if (conflict) {
				conflictGroups++;
				log.error("[DUP-COMPONENT-REPAIR] org {}: component {} conflicts with leader {} on at least one "
						+ "release version -- archiving WITHOUT folding ({} release(s) stay on the archived component)",
						orgUuid, dup.getUuid(), leader.getUuid(), dupReleases.size());
			} else {
				for (Release r : dupReleases) {
					moveReleaseToLeader(r, leader.getUuid(), wu);
					ReleaseData rd = ReleaseData.dataFromRecord(r);
					if (rd.getVersion() != null) leaderVersions.add(rd.getVersion());
					releasesFolded++;
				}
			}

			// archiveComponent cascades to ALL branches (including BASE, which the
			// standalone archiveBranch guard rightly refuses for live components).
			long liveBranches = branchService.listBranchesOfComponent(dup.getUuid(), null).stream()
					.map(BranchData::branchDataFromDbRecord)
					.filter(bd -> bd.getStatus() != StatusEnum.ARCHIVED)
					.count();
			componentService.archiveComponent(dup.getUuid(), wu);
			branchesArchived += (int) liveBranches;
			componentsArchived++;
			log.error("[DUP-COMPONENT-REPAIR] org {}: archived duplicate component {} (leader {}), folded={}",
					orgUuid, dup.getUuid(), leader.getUuid(), !conflict);
		}

		return new RepairSummary(1, releasesFolded, componentsArchived, branchesArchived, conflictGroups);
	}

	/**
	 * Move one release under the leader, into the leader's branch of the same
	 * name (auto-created when the leader has no such branch). Saved with
	 * considerTriggers=false -- a repair must not fire triggers, notifications
	 * or auto-integrate.
	 */
	private void moveReleaseToLeader(Release r, UUID leaderUuid, WhoUpdated wu) throws RelizaException {
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		String branchName = branchService.getBranchData(rd.getBranch())
				.map(BranchData::getName)
				.orElseThrow(() -> new RelizaException(
						"Branch " + rd.getBranch() + " of release " + rd.getUuid() + " not found"));
		Optional<Branch> leaderBranch = branchService.findBranchByName(leaderUuid, branchName, true, wu);
		if (leaderBranch.isEmpty()) {
			throw new RelizaException("Could not resolve or create branch '" + branchName
					+ "' on leader component " + leaderUuid);
		}
		rd.repointToComponentBranch(leaderUuid, leaderBranch.get().getUuid());
		ossReleaseService.saveRelease(r, rd, wu, false);
	}
}
