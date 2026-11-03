/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentLockService;
import io.reliza.service.ComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared fixtures of the auto-integrate dry-run tests (TPF-119): ReleaseService.evaluateAutoIntegrate,
 * autoIntegrateFeatureSetOnDemandExplained and the preview / explained fetchers.
 *
 * <p>EVERY dependency release is created deferred ({@code createRelease(dto, wu, false, true)}): a
 * non-deferred ASSEMBLED create queues the real after-commit auto-integrate worker, which reads the
 * dependents when it runs and could integrate the feature set on its own. With no worker ever queued,
 * every product release in these classes is made by the test's own call, so counts are exact.
 */
abstract class AutoIntegrateDryRunTestBase {

	@Autowired protected BranchService branchService;
	@Autowired protected ComponentService componentService;
	@Autowired protected SharedReleaseService sharedReleaseService;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected ReleaseService releaseService;
	@Autowired protected VersionAssignmentService versionAssignmentService;
	@Autowired protected ComponentLockService componentLockService;
	@Autowired protected TestInitializer testInitializer;

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	/** A component (or product) with its base branch. */
	protected record Comp(Component component, String name, Branch branch, String branchName) {
		UUID uuid() { return component.getUuid(); }
		UUID branchUuid() { return branch.getUuid(); }
	}

	protected Comp component(Organization org, String prefix) throws RelizaException {
		String name = prefix + "_" + UUID.randomUUID();
		Component c = componentService.createComponent(name, org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch base = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return new Comp(c, name, base, branchService.getBranchData(base.getUuid()).orElseThrow().getName());
	}

	protected Component product(Organization org) throws RelizaException {
		return componentService.createComponent("dryRunProduct_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
	}

	protected UUID featureSet(Component product, String name) throws RelizaException {
		return branchService.createBranch(name, product.getUuid(), BranchType.FEATURE, WU).getUuid();
	}

	/** A deferred release: never queues the after-commit auto-integrate worker. */
	protected ReleaseData release(Organization org, Comp c, String version, ReleaseLifecycle lifecycle)
			throws RelizaException {
		Release r = ossReleaseService.createRelease(ReleaseDto.builder().component(c.uuid()).branch(c.branchUuid())
				.org(org.getUuid()).status(ReleaseStatus.ACTIVE).lifecycle(lifecycle).version(version).build(),
				WU, false, true);
		return sharedReleaseService.getReleaseData(r.getUuid()).orElseThrow();
	}

	protected ReleaseData assembled(Organization org, Comp c, String version) throws RelizaException {
		return release(org, c, version, ReleaseLifecycle.ASSEMBLED);
	}

	protected static ChildComponent dep(Comp c, StatusEnum status) {
		return ChildComponent.builder().uuid(c.uuid()).branch(c.branchUuid()).status(status).build();
	}

	/** Sets the feature set's dependencies and auto-integrate state; returns the re-read feature set. */
	protected BranchData configure(UUID fs, AutoIntegrateState state, ChildComponent... deps) throws RelizaException {
		BranchData seed = branchService.getBranchData(fs).orElseThrow();
		branchService.updateBranch(BranchDto.builder().uuid(seed.getUuid()).name(seed.getName())
				.versionSchema(seed.getVersionSchema()).type(seed.getType()).dependencies(List.of(deps))
				.autoIntegrate(state).build(), WU);
		return branchService.getBranchData(fs).orElseThrow();
	}

	protected BranchData read(UUID fs) {
		return branchService.getBranchData(fs).orElseThrow();
	}

	protected int count(UUID fs) {
		return sharedReleaseService.listReleaseDataOfBranch(fs).size();
	}

	protected Optional<UUID> latestAssignment(UUID fs) {
		return versionAssignmentService.getLatestVersionAssignmentOfBranch(fs, 10).map(VersionAssignment::getUuid);
	}

	protected String peek(UUID fs) {
		return versionAssignmentService.getCurrentNextVersion(fs, VersionTypeEnum.DEV);
	}

	/** What "nothing written" compares: product releases, latest assignment, peek, dependency release counts. */
	protected record Snapshot(int count, Optional<UUID> latestAssignment, String peek, List<Integer> depCounts) {}

	protected Snapshot snapshot(UUID fs, Comp... deps) {
		List<Integer> depCounts = new ArrayList<>();
		for (Comp c : deps) depCounts.add(sharedReleaseService.listReleaseDataOfBranch(c.branchUuid()).size());
		return new Snapshot(count(fs), latestAssignment(fs), peek(fs), depCounts);
	}

	protected static AutoIntegrateDependencyRow row(AutoIntegrateEvaluation ev, Comp c) {
		return ev.dependencies().stream().filter(r -> r.component().uuid().equals(c.uuid())).findFirst()
				.orElseThrow(() -> new AssertionError("no row for component " + c.uuid()));
	}

	protected static List<UUID> rowComponents(AutoIntegrateEvaluation ev) {
		return ev.dependencies().stream().map(r -> r.component().uuid()).toList();
	}

	protected List<UUID> parentsOf(UUID productRelease) {
		return sharedReleaseService.getReleaseData(productRelease).orElseThrow().getParentReleases().stream()
				.map(p -> p.getRelease()).toList();
	}

	protected void assertRowRelease(AutoIntegrateDependencyRow r, ReleaseData expected) {
		assertEquals(expected.getUuid(), r.release().uuid());
		assertEquals(expected.getVersion(), r.release().version());
		assertEquals(expected.getLifecycle(), r.release().lifecycle());
	}
}
