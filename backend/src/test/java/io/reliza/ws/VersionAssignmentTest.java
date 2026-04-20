/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;


import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.OrganizationService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.versioning.VersionApi.ActionEnum;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.versioning.VersionType;

/**
 * Unit test for Release-related functionality.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class VersionAssignmentTest 
{
	
	@Autowired
    private ComponentService componentService;
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private VersionAssignmentService versionAssignmetService;
	
	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private TestInitializer testInitializer;
	
	@Test
	public void testObtainVersionAssignment1Semver() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProjectForVersionAssignmentSemver", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, 
				WhoUpdated.getTestWhoUpdated());
		Branch baseBr = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		BranchDto branchDto1 = BranchDto.builder()
									.uuid(baseBr.getUuid())
									.versionSchema("1.3.patch")
									.build();
		branchService.updateBranch(branchDto1, WhoUpdated.getTestWhoUpdated());
		Optional<VersionAssignment> ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("1.3.0", ova.get().getVersion());
		ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("1.3.1", ova.get().getVersion());
		BranchDto branchDto2 = BranchDto.builder()
				.uuid(baseBr.getUuid())
				.versionSchema("1.minor.patch")
				.build();
		branchService.updateBranch(branchDto2, WhoUpdated.getTestWhoUpdated());
		ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), ActionEnum.BUMP_MINOR, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("1.4.0", ova.get().getVersion());
	}
	
	@Test
	public void testObtainVersionAssignment2Calver() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProjectForVersionAssignmentCalver", org.getUuid(), ComponentType.COMPONENT, 
				VersionType.CALVER_RELIZA_2020.getSchema(), "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBr = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		BranchDto branchDto = BranchDto.builder()
				.uuid(baseBr.getUuid())
				.versionSchema("2019.12.Calvermodifier.Minor.Micro+Metadata")
				.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		Optional<VersionAssignment> ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("2019.12.Snapshot.0.0", ova.get().getVersion());
		ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), ActionEnum.BUMP_PATCH, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("2019.12.Snapshot.0.1", ova.get().getVersion());
	}
	
	@Test
	public void dbPreventsDuplicateVersions() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProjectForVersionAssignmentRecovery", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBr = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		BranchDto branchDto = BranchDto.builder()
				.uuid(baseBr.getUuid())
				.versionSchema("1.3.patch")
				.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		Optional<VersionAssignment> ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("1.3.0", ova.get().getVersion());
		Assertions.assertThrows(DataIntegrityViolationException.class,
				() -> versionAssignmetService.createNewVersionAssignment(baseBr.getUuid(), "1.3.0", null));
	}	

	@Test
	public void testNextVersion() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProjectForNextVersion", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBr = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		BranchDto branchDto = BranchDto.builder()
				.uuid(baseBr.getUuid())
				.versionSchema("semver")
				.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		Optional<VersionAssignment> ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.0", ova.get().getVersion());
		
		Optional<VersionAssignment> ova1 = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.1", ova1.get().getVersion());

		versionAssignmetService.setNextVesion(baseBr.getUuid(), "3.2.1");
		Optional<VersionAssignment> ova2 = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("3.2.1", ova2.get().getVersion());
		
	}
	@Test
	public void testNextCalverVersion() throws Exception {
		ZonedDateTime date = ZonedDateTime.now(ZoneId.of("UTC"));
		String CURRENT_MONTH_SINGLE = String.valueOf(date.getMonthValue());
		String CURRENT_MONTH = StringUtils.leftPad(CURRENT_MONTH_SINGLE, 2, "0");
		
		String CURRENT_YEAR_LONG = String.valueOf(date.getYear());
		String CURRENT_YEAR_SHORT = CURRENT_YEAR_LONG.substring(2);
		
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProjectForNextVersion", org.getUuid(), ComponentType.COMPONENT, VersionType.CALVER_UBUNTU.getSchema(), 
				"Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBr = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		BranchDto branchDto = BranchDto.builder()
				.uuid(baseBr.getUuid())
				.versionSchema(VersionType.CALVER_UBUNTU.getSchema())
				.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		Optional<VersionAssignment> ova = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals(CURRENT_YEAR_SHORT+"." + CURRENT_MONTH + ".0", ova.get().getVersion());
		
		Optional<VersionAssignment> ova1 = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals(CURRENT_YEAR_SHORT+"." + CURRENT_MONTH + ".1", ova1.get().getVersion());

		versionAssignmetService.setNextVesion(baseBr.getUuid(), CURRENT_YEAR_SHORT+"." + CURRENT_MONTH + ".12");
		Optional<VersionAssignment> ova2 = versionAssignmetService.getSetNewVersion(baseBr.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals(CURRENT_YEAR_SHORT+"." + CURRENT_MONTH + ".12", ova2.get().getVersion());
		
	}

	// ==========================================================================
	// Branch prefix mode tests
	// ==========================================================================

	/**
	 * Helper: creates a non-base branch of type FEATURE with semver schema.
	 */
	private Branch createSemverFeatureBranch(String name, Component comp) throws RelizaException {
		Branch br = branchService.createBranch(name, comp.getUuid(), BranchType.FEATURE,
				WhoUpdated.getTestWhoUpdated());
		branchService.updateBranch(
				BranchDto.builder().uuid(br.getUuid()).versionSchema("semver").build(),
				WhoUpdated.getTestWhoUpdated());
		return br;
	}

	/**
	 * Default org/component (no branch-prefix-mode set) should behave as APPEND:
	 * feature branches get a namespace suffix derived from branch name.
	 */
	@Test
	public void testBranchSuffixModeDefaultAppend() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component comp = componentService.createComponent("testBranchPrefixDefault_" + UUID_SHORT(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "semver", null,
				WhoUpdated.getTestWhoUpdated());
		Branch foo = createSemverFeatureBranch("foo", comp);
		Optional<VersionAssignment> v1 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.0-foo", v1.get().getVersion());
		Optional<VersionAssignment> v2 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.1-foo", v2.get().getVersion());
	}

	/**
	 * Component with NO_APPEND from the start: feature branches do not get namespace suffix.
	 */
	@Test
	public void testBranchSuffixModeNoAppendFromStart() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component comp = componentService.createComponent("testBranchPrefixNoAppend_" + UUID_SHORT(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "semver", null,
				WhoUpdated.getTestWhoUpdated());
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.branchSuffixMode(BranchSuffixMode.NO_APPEND)
				.build(), WhoUpdated.getTestWhoUpdated());
		Branch foo = createSemverFeatureBranch("foo", comp);
		Optional<VersionAssignment> v1 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.0", v1.get().getVersion());
		Optional<VersionAssignment> v2 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.1", v2.get().getVersion());
	}

	/**
	 * Regression test for the bug: when a branch has produced prefixed versions
	 * under APPEND mode (e.g. 0.0.0-foo), switching the component to NO_APPEND
	 * must clear the inherited branch-prefix modifier on subsequent versions.
	 * Before fix: new version would be 0.0.2-foo (modifier leaked from latestOva).
	 * After fix: new version is 0.0.2 (clean).
	 */
	@Test
	public void testBranchSuffixModeNoAppendClearsLeakedModifier() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component comp = componentService.createComponent("testBranchPrefixLeak_" + UUID_SHORT(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "semver", null,
				WhoUpdated.getTestWhoUpdated());
		Branch foo = createSemverFeatureBranch("foo", comp);
		// Produce two versions under default APPEND mode
		Optional<VersionAssignment> v1 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.0-foo", v1.get().getVersion());
		Optional<VersionAssignment> v2 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.1-foo", v2.get().getVersion());
		// Switch component to NO_APPEND
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.branchSuffixMode(BranchSuffixMode.NO_APPEND)
				.build(), WhoUpdated.getTestWhoUpdated());
		// Next version on same branch must not carry "-foo" modifier
		Optional<VersionAssignment> v3 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.2", v3.get().getVersion(),
				"Expected clean 0.0.2 without leaked -foo modifier; was: " + v3.get().getVersion());
		Optional<VersionAssignment> v4 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.3", v4.get().getVersion());
	}

	/**
	 * Component override beats organization setting: org=NO_APPEND, component=APPEND → APPEND wins.
	 */
	@Test
	public void testBranchSuffixModeComponentOverridesOrg() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings orgPatch = new OrganizationData.Settings();
		orgPatch.setBranchSuffixMode(BranchSuffixMode.NO_APPEND);
		organizationService.updateSettings(org.getUuid(), orgPatch, WhoUpdated.getTestWhoUpdated());

		Component comp = componentService.createComponent("testBranchPrefixCompOverride_" + UUID_SHORT(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "semver", null,
				WhoUpdated.getTestWhoUpdated());
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.branchSuffixMode(BranchSuffixMode.APPEND)
				.build(), WhoUpdated.getTestWhoUpdated());

		Branch foo = createSemverFeatureBranch("foo", comp);
		Optional<VersionAssignment> v1 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.0-foo", v1.get().getVersion());
	}

	/**
	 * Component INHERIT (null) defers to organization setting.
	 */
	@Test
	public void testBranchSuffixModeComponentInheritsOrg() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings orgPatch = new OrganizationData.Settings();
		orgPatch.setBranchSuffixMode(BranchSuffixMode.NO_APPEND);
		organizationService.updateSettings(org.getUuid(), orgPatch, WhoUpdated.getTestWhoUpdated());

		Component comp = componentService.createComponent("testBranchPrefixInherit_" + UUID_SHORT(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "semver", null,
				WhoUpdated.getTestWhoUpdated());
		// Explicitly set INHERIT on the component — should be stored as null and defer to org
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.branchSuffixMode(BranchSuffixMode.INHERIT)
				.build(), WhoUpdated.getTestWhoUpdated());

		Branch foo = createSemverFeatureBranch("foo", comp);
		Optional<VersionAssignment> v1 = versionAssignmetService.getSetNewVersion(foo.getUuid(), null, null, null, VersionTypeEnum.DEV);
		Assertions.assertEquals("0.0.0", v1.get().getVersion(), "Component INHERIT should pick up org NO_APPEND");
	}

	/**
	 * Organization-level INHERIT must be rejected by the service validation.
	 */
	@Test
	public void testBranchSuffixModeOrgRejectsInherit() {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings orgPatch = new OrganizationData.Settings();
		orgPatch.setBranchSuffixMode(BranchSuffixMode.INHERIT);
		Assertions.assertThrows(IllegalArgumentException.class, () ->
			organizationService.updateSettings(org.getUuid(), orgPatch, WhoUpdated.getTestWhoUpdated())
		);
	}

	private static String UUID_SHORT() {
		return java.util.UUID.randomUUID().toString().substring(0, 8);
	}
}
