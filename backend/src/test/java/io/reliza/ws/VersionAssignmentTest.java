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

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.Organization;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
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
}
