/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The organization a release is created under, and its references validated against, is its
 * branch's (TPF-5, security review F-1). createRelease used to take the dto's org as given, so a
 * caller passing another organization than the branch's -- a global admin on addReleaseManual, or
 * instance product generation over a plan naming another organization's feature set -- had the
 * parents checked against, and the release recorded under, that other organization. The check runs
 * before the lock check, the version lookup, the reference validation and every write, for every
 * caller; the manual lane is pinned in ReleaseDatafetcherParentOrgTest.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseCreateOrgBranchMismatchTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private VersionAssignmentService versionAssignmentService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private record Rig(UUID org, UUID component, UUID branch) {}

	private Rig rigIn(UUID org, ComponentType type) throws RelizaException {
		Component c = componentService.createComponent("orgbranch_" + UUID.randomUUID(), org, type, "semver",
				"Branch.Micro", null, WU);
		return new Rig(org, c.getUuid(), branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid());
	}

	/** A dto that names the rig's component and branch; the org is set by each case. */
	private static ReleaseDto.ReleaseDtoBuilder onBranchOf(Rig r, String version) {
		return ReleaseDto.builder().component(r.component()).branch(r.branch()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED);
	}

	@Test
	public void aDtoOfAnotherOrganizationOnTheBranchIsRefusedAndWritesNothing() throws RelizaException {
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		Rig featureSet = rigIn(orgA, ComponentType.PRODUCT);
		Rig bComponent = rigIn(orgB, ComponentType.COMPONENT);
		UUID secret = ossReleaseService.createRelease(onBranchOf(bComponent, "1.0.0").org(orgB).build(), WU).getUuid();
		int secretEvents = sharedReleaseService.getReleaseData(secret).orElseThrow().getUpdateEvents().size();

		// The component is the feature set's own, so this takes the matched-component arm of the
		// branch resolution; the manual lane (no component in the input) is the other arm, pinned in
		// ReleaseDatafetcherParentOrgTest.
		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
				onBranchOf(featureSet, "1.0.0").org(orgB)
					.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(secret, null))).build(), WU));
		assertEquals("Organization and branch mismatch for org = " + orgB + " and branch = " + featureSet.branch(),
				re.getMessage());
		assertTrue(sharedReleaseService.listReleaseDataOfBranch(featureSet.branch()).isEmpty(),
				"no release may be created on the feature set");
		assertTrue(versionAssignmentService.getLatestVersionAssignmentOfBranch(featureSet.branch(), 10).isEmpty(),
				"no version may be assigned on the feature set");
		ReleaseData secretAfter = sharedReleaseService.getReleaseData(secret).orElseThrow();
		assertEquals(secretEvents, secretAfter.getUpdateEvents().size(), "org B's release is untouched");
		assertTrue(secretAfter.getParentReleases().isEmpty());
	}

	@Test
	public void aDtoWithNoOrganizationTakesTheBranchs() throws RelizaException {
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		Rig featureSet = rigIn(orgA, ComponentType.PRODUCT);
		UUID created = ossReleaseService.createRelease(onBranchOf(featureSet, "1.0.0").build(), WU).getUuid();
		assertEquals(orgA, sharedReleaseService.getReleaseData(created).orElseThrow().getOrg());
	}
}
