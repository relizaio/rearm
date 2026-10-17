/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.UpdateReleaseStrength;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentLockService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The release writes CE's {@link OssReleaseService} owns, under a component or branch lock: a
 * create is refused even with an explicit version (which skips version assignment, the check that
 * already held), a content update is refused, and the lifecycle can still move -- a lock stops work
 * being built on an unresolved problem, it does not trap a release in place. Mirrors the release
 * half of Pro's BuildIntegrityTest, whose rest covers Pro's policy locks.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseWriteLockEnforcementTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ComponentLockService componentLockService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private record Rig(Organization org, Component component, Branch branch) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent("lock_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		return new Rig(org, component, branch);
	}

	private UUID release(Rig r, Branch branch, String version) throws RelizaException {
		return ossReleaseService.createRelease(ReleaseDto.builder().component(r.component().getUuid())
				.org(r.org().getUuid()).branch(branch.getUuid()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
	}

	@Test
	public void aComponentLockRefusesACreateWithAnExplicitVersion() throws RelizaException {
		Rig r = rig();
		release(r, r.branch(), "1.0.0");
		componentLockService.lockComponent(r.component().getUuid(), "incident 4021",
				UnlockLevel.ADMIN, AttestationRequirement.NONE, WU);
		ActionRefusedException e = assertThrows(ActionRefusedException.class, () -> release(r, r.branch(), "1.0.1"),
				"a locked component takes no new releases, version or not");
		assertTrue(e.getMessage().contains("incident 4021"), e.getMessage());
		assertTrue(e.getMessage().contains("create a release"), e.getMessage());
	}

	@Test
	public void aLockedReleaseRefusesContentButNotItsLifecycle() throws RelizaException {
		Rig r = rig();
		UUID releaseUuid = release(r, r.branch(), "1.0.0");
		componentLockService.lockComponent(r.component().getUuid(), "freeze",
				UnlockLevel.ADMIN, AttestationRequirement.NONE, WU);
		ActionRefusedException e = assertThrows(ActionRefusedException.class,
				() -> ossReleaseService.updateRelease(ReleaseDto.builder().uuid(releaseUuid).notes("changed").build(),
						UpdateReleaseStrength.FULL, WU));
		assertTrue(e.getMessage().contains("change release content"), e.getMessage());
		ossReleaseService.updateReleaseLifecycle(releaseUuid, ReleaseLifecycle.REJECTED, WU);
		assertEquals(ReleaseLifecycle.REJECTED,
				sharedReleaseService.getReleaseData(releaseUuid).orElseThrow().getLifecycle());
	}

	@Test
	public void aBranchLockLeavesTheOtherBranchesWritable() throws RelizaException {
		Rig r = rig();
		Branch feature = branchService.createBranch("feature-" + UUID.randomUUID().toString().substring(0, 8),
				r.component().getUuid(), BranchType.FEATURE, WU);
		componentLockService.lockBranch(r.branch().getUuid(), "unrecognized commit",
				UnlockLevel.HUMAN, AttestationRequirement.NONE, WU);
		assertThrows(ActionRefusedException.class, () -> release(r, r.branch(), "1.0.0"));
		assertTrue(sharedReleaseService.getReleaseData(release(r, feature, "1.0.0-feature")).isPresent(),
				"the lock is on one branch, not on the component");
	}
}
