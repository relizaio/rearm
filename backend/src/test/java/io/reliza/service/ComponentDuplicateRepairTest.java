/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.VcsRepository;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.ComponentDuplicateRepairService.RepairSummary;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Exercises the duplicate-component repair sweep
 * ({@link ComponentDuplicateRepairService}) against real rows on the local test
 * database: fold-under-oldest-leader, branch mapping (same-named merge +
 * auto-create), version-conflict archival without folding, intra-group version
 * competition, idempotency, and org scoping.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ComponentDuplicateRepairTest {

	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private ComponentDuplicateRepairService repairService;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String REPO_PATH = "svc/app";

	// ---- fixtures ----

	private record Fx(Organization org, UUID vcsUuid) {}

	private Fx fx() {
		Organization org = testInitializer.obtainOrganization();
		String slug = "it-duprepair-" + UUID.randomUUID().toString().substring(0, 8);
		VcsRepository vcs = vcsRepositoryService.getVcsRepositoryByUri(
				org.getUuid(), "github.com/it-dup/" + slug, null, null, true, WU).get();
		return new Fx(org, vcs.getUuid());
	}

	/**
	 * Component on the fixture VCS + shared repoPath + the SHARED name -- the
	 * repair identity now requires name equality (the incident loop derives the
	 * identical name on every duplicate), so group members must share it.
	 */
	private UUID comp(Fx fx, String name) throws RelizaException {
		return compWithPath(fx, name, REPO_PATH);
	}

	private UUID compWithPath(Fx fx, String name, String repoPath) throws RelizaException {
		CreateComponentDto dto = CreateComponentDto.builder()
				.organization(fx.org().getUuid())
				.name(name)
				.type(ComponentType.COMPONENT)
				.vcs(fx.vcsUuid())
				.repoPath(repoPath)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build();
		UUID uuid = componentService.createComponent(dto, WU).getUuid();
		// created_date is the leader tiebreaker -- keep creation instants distinct.
		try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
		return uuid;
	}

	private UUID release(Fx fx, UUID component, String branchName, String version) throws RelizaException {
		Branch branch = branchService.findBranchByName(component, branchName, true, WU).get();
		ReleaseDto dto = ReleaseDto.builder()
				.component(component)
				.branch(branch.getUuid())
				.org(fx.org().getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version(version)
				.build();
		return ossReleaseService.createRelease(dto, WU).getUuid();
	}

	private Set<String> versionsOf(UUID component) {
		return releaseService.listReleasesByComponent(component).stream()
				.map(r -> ReleaseData.dataFromRecord(r).getVersion())
				.collect(Collectors.toSet());
	}

	private StatusEnum componentStatus(UUID uuid) {
		Component c = getComponentService.getComponent(uuid).orElseThrow();
		return ComponentData.dataFromRecord(c).getStatus();
	}

	private ReleaseData releaseData(UUID uuid) {
		return sharedReleaseService.getReleaseData(uuid).orElseThrow();
	}

	// ---- scenarios ----

	@Test
	public void noDuplicatesIsNoop() throws Exception {
		Fx fx = fx();
		comp(fx, "solo");
		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(0, s.groupsExamined());
		assertEquals(0, s.componentsArchived());
	}

	@Test
	public void disjointVersionsFoldUnderOldestLeader() throws Exception {
		Fx fx = fx();
		UUID leader = comp(fx, "app");
		UUID dupA = comp(fx, "app");
		UUID dupB = comp(fx, "app");

		UUID leaderRel = release(fx, leader, "main", "1.0.0");
		UUID dupMainRel = release(fx, dupA, "main", "2.0.0");
		UUID dupFeatRel = release(fx, dupA, "feat", "3.0.0");
		// dupB carries no releases at all.

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);

		assertEquals(1, s.groupsExamined());
		assertEquals(2, s.releasesFolded());
		assertEquals(2, s.componentsArchived());
		assertEquals(0, s.conflictGroups());

		// Leader owns every release; duplicates own none.
		assertEquals(Set.of("1.0.0", "2.0.0", "3.0.0"), versionsOf(leader));
		assertEquals(Set.of(), versionsOf(dupA));
		assertEquals(Set.of(), versionsOf(dupB));

		// Same-named branch merged: the moved release sits on the LEADER's main.
		UUID leaderMain = releaseData(leaderRel).getBranch();
		assertEquals(leaderMain, releaseData(dupMainRel).getBranch());
		assertEquals(leader, releaseData(dupMainRel).getComponent());

		// Unique-named branch auto-created on the leader and adopted the release.
		ReleaseData featData = releaseData(dupFeatRel);
		assertEquals(leader, featData.getComponent());
		BranchData leaderFeat = branchService.getBranchData(featData.getBranch()).orElseThrow();
		assertEquals("feat", leaderFeat.getName());
		assertEquals(leader, leaderFeat.getComponent());
		assertNotEquals(StatusEnum.ARCHIVED, leaderFeat.getStatus());

		// Duplicates and their branches archived; leader stays live.
		assertEquals(StatusEnum.ARCHIVED, componentStatus(dupA));
		assertEquals(StatusEnum.ARCHIVED, componentStatus(dupB));
		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(leader));
		for (Branch b : branchService.listBranchesOfComponent(dupA, null)) {
			assertEquals(StatusEnum.ARCHIVED, BranchData.branchDataFromDbRecord(b).getStatus(),
					"duplicate branch must be archived: " + b.getUuid());
		}
	}

	@Test
	public void versionConflictArchivesWithoutFolding() throws Exception {
		Fx fx = fx();
		UUID leader = comp(fx, "app");
		UUID dup = comp(fx, "app");

		release(fx, leader, "main", "1.0.0");
		UUID dupConflicting = release(fx, dup, "main", "1.0.0");
		UUID dupExtra = release(fx, dup, "main", "2.0.0");

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);

		assertEquals(1, s.groupsExamined());
		assertEquals(0, s.releasesFolded());
		assertEquals(1, s.conflictGroups());
		assertEquals(1, s.componentsArchived());

		// Nothing moved: the conflicted duplicate keeps BOTH its releases,
		// preserved on the archived component rather than merged.
		assertEquals(Set.of("1.0.0"), versionsOf(leader));
		assertEquals(Set.of("1.0.0", "2.0.0"), versionsOf(dup));
		assertEquals(dup, releaseData(dupConflicting).getComponent());
		assertEquals(dup, releaseData(dupExtra).getComponent());
		assertEquals(StatusEnum.ARCHIVED, componentStatus(dup));
	}

	@Test
	public void secondDuplicateConflictsAfterFirstFolds() throws Exception {
		Fx fx = fx();
		UUID leader = comp(fx, "app");   // oldest; no releases of its own
		UUID dupA = comp(fx, "app");
		UUID dupB = comp(fx, "app");

		release(fx, dupA, "main", "5.0.0");
		UUID dupBRel = release(fx, dupB, "main", "5.0.0");

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);

		// dupA folds 5.0.0 into the leader; dupB's 5.0.0 now collides with the
		// freshly folded slot and must NOT fold -- the version set grows as the
		// group repair progresses, never allowing one version to land twice.
		assertEquals(1, s.releasesFolded());
		assertEquals(1, s.conflictGroups());
		assertEquals(2, s.componentsArchived());
		assertEquals(Set.of("5.0.0"), versionsOf(leader));
		assertEquals(dupB, releaseData(dupBRel).getComponent());
	}

	@Test
	public void secondRunIsIdempotentNoop() throws Exception {
		Fx fx = fx();
		UUID leader = comp(fx, "app");
		UUID dup = comp(fx, "app");
		release(fx, leader, "main", "1.0.0");
		release(fx, dup, "main", "2.0.0");

		RepairSummary first = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(1, first.componentsArchived());

		RepairSummary second = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(0, second.groupsExamined());
		assertEquals(0, second.componentsArchived());
		assertEquals(Set.of("1.0.0", "2.0.0"), versionsOf(leader));
	}

	@Test
	public void repairIsScopedToTheRequestedOrg() throws Exception {
		Fx fxA = fx();
		Fx fxB = fx();
		comp(fxA, "a-app");
		comp(fxA, "a-app");
		UUID bSolo = comp(fxB, "b-solo");

		RepairSummary s = repairService.repairOrganization(fxA.org().getUuid(), WU);
		assertEquals(1, s.groupsExamined());
		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(bSolo));

		RepairSummary sB = repairService.repairOrganization(fxB.org().getUuid(), WU);
		assertEquals(0, sB.groupsExamined());
	}

	/** Duplicate groups are keyed by (vcs, repoPath) -- distinct paths never merge. */
	@Test
	public void distinctRepoPathsAreNotDuplicates() throws Exception {
		Fx fx = fx();
		comp(fx, "path-a");
		CreateComponentDto other = CreateComponentDto.builder()
				.organization(fx.org().getUuid())
				.name("path-b")
				.type(ComponentType.COMPONENT)
				.vcs(fx.vcsUuid())
				.repoPath("svc/other")
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build();
		componentService.createComponent(other, WU);

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(0, s.groupsExamined());
	}

	/**
	 * ROOT components can be registered with repoPath '.' (the CI default), ''
	 * or null (UI-created) -- resolution's FIND_COMPONENT_BY_VCS_AND_PATH
	 * equates all three, so a '.'-form + null-form pair is AMBIGUOUS to CI. The
	 * sweep must group by the SAME identity, or precisely the estate it exists
	 * to fix stays invisible (found empirically pre-fix: resolution AMBIGUOUS
	 * while repairOrganization saw two singleton groups and did nothing).
	 */
	@Test
	public void mixedRootRepoPathFormsAreOneDuplicateGroup() throws Exception {
		Fx fx = fx();
		UUID ciForm = compWithPath(fx, "rootapp", ".");
		UUID uiForm = compWithPath(fx, "rootapp", null);

		// Resolution's view first: this pair keeps CI red.
		var before = componentService.resolveComponentResolutionByVcsUriAndPath(
				fx.org().getUuid(), "github.com/it-dup/" + vcsSlug(fx), null);
		assertEquals(ComponentService.ComponentResolutionStatus.AMBIGUOUS, before.status());

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(1, s.groupsExamined());
		assertEquals(1, s.componentsArchived());

		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(ciForm));
		assertEquals(StatusEnum.ARCHIVED, componentStatus(uiForm));

		// And CI recovers, whichever root form it passes.
		for (String repoPath : new String[] { null, "", "." }) {
			var after = componentService.resolveComponentResolutionByVcsUriAndPath(
					fx.org().getUuid(), "github.com/it-dup/" + vcsSlug(fx), repoPath);
			assertEquals(ComponentService.ComponentResolutionStatus.FOUND, after.status(),
					"root form must resolve post-repair: " + repoPath);
			assertEquals(ciForm, after.componentId());
		}
	}

	/** The whole point, end to end: after repair, VCS-based resolution works again. */
	@Test
	public void resolutionRecoversAfterRepair() throws Exception {
		Fx fx = fx();
		UUID leader = comp(fx, "app");
		comp(fx, "app");

		var before = componentService.resolveComponentResolutionByVcsUriAndPath(
				fx.org().getUuid(), "github.com/it-dup/" + vcsSlug(fx), REPO_PATH);
		assertEquals(ComponentService.ComponentResolutionStatus.AMBIGUOUS, before.status());

		repairService.repairOrganization(fx.org().getUuid(), WU);

		var after = componentService.resolveComponentResolutionByVcsUriAndPath(
				fx.org().getUuid(), "github.com/it-dup/" + vcsSlug(fx), REPO_PATH);
		assertEquals(ComponentService.ComponentResolutionStatus.FOUND, after.status());
		assertEquals(leader, after.componentId());
	}

	/**
	 * Same (vcs, repoPath) but DIFFERENT names: possibly intentional distinct
	 * components -- never auto-merged. Resolution stays ambiguous (name-blind),
	 * which the sweep reports at error; repairing it is a manual decision.
	 */
	@Test
	public void differentNamesAreNeverRepaired() throws Exception {
		Fx fx = fx();
		UUID a = comp(fx, "frontend");
		UUID b = comp(fx, "backend");

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(0, s.groupsExamined());
		assertEquals(0, s.componentsArchived());
		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(a));
		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(b));

		// The deliberate leftover: resolution is still ambiguous for this pair.
		var res = componentService.resolveComponentResolutionByVcsUriAndPath(
				fx.org().getUuid(), "github.com/it-dup/" + vcsSlug(fx), REPO_PATH);
		assertEquals(ComponentService.ComponentResolutionStatus.AMBIGUOUS, res.status());
	}

	/** Mixed group {app, app, other}: the same-named pair repairs, 'other' is untouched. */
	@Test
	public void mixedNamesRepairOnlyTheSameNamedSubset() throws Exception {
		Fx fx = fx();
		UUID appLeader = comp(fx, "app");
		UUID appDup = comp(fx, "app");
		UUID other = comp(fx, "other");

		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(1, s.groupsExamined());
		assertEquals(1, s.componentsArchived());
		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(appLeader));
		assertEquals(StatusEnum.ARCHIVED, componentStatus(appDup));
		assertNotEquals(StatusEnum.ARCHIVED, componentStatus(other));
	}

	/**
	 * The startup census counts with EXACTLY the sweep identity: same-name pairs
	 * count, different-name pairs and PRODUCT pairs do not -- and after a repair
	 * the census reads zero.
	 */
	@Test
	public void censusCountsExactlyWhatTheSweepWouldRepair() throws Exception {
		Fx fx = fx();
		comp(fx, "app");
		comp(fx, "app");            // same-name pair -> 1 group, 1 excess
		comp(fx, "frontend");
		comp(fx, "backend");        // different names -> not counted

		var count = repairService.countDuplicates(fx.org().getUuid());
		assertEquals(1, count.groups());
		assertEquals(1, count.excessComponents());

		repairService.repairOrganization(fx.org().getUuid(), WU);
		var after = repairService.countDuplicates(fx.org().getUuid());
		assertEquals(0, after.groups());
		assertEquals(0, after.excessComponents());
	}

	/** PRODUCT-type components are outside the sweep entirely. */
	@Test
	public void productTypeComponentsAreIgnored() throws Exception {
		Fx fx = fx();
		for (int i = 0; i < 2; i++) {
			CreateComponentDto dto = CreateComponentDto.builder()
					.organization(fx.org().getUuid())
					.name("bundle")
					.type(ComponentType.PRODUCT)
					.vcs(fx.vcsUuid())
					.repoPath(REPO_PATH)
					.versionSchema("semver")
					.featureBranchVersioning("Branch.Micro")
					.build();
			componentService.createComponent(dto, WU);
		}
		RepairSummary s = repairService.repairOrganization(fx.org().getUuid(), WU);
		assertEquals(0, s.groupsExamined());
	}

	private String vcsSlug(Fx fx) {
		return vcsRepositoryService.getVcsRepositoryData(fx.vcsUuid()).orElseThrow()
				.getUri().replace("github.com/it-dup/", "");
	}

}
