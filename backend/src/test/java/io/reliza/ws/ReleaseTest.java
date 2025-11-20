/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;


import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.Organization;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Unit test for Release-related functionality.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class ReleaseTest 
{
	@Autowired
    private ComponentService componentService;

	@Autowired
    private BranchService branchService;
	
	@Autowired
    private ReleaseService releaseService;
	
	@Autowired
    private SharedReleaseService sharedReleaseService;
	
	@Autowired
    private OssReleaseService ossReleaseService;
	
	@Autowired
	private TestInitializer testInitializer;
	
	
	@Test
	public void testCreateReleaseWOReqFieldsThrowsIllegalState() throws RelizaException {
		ReleaseDto releaseDto = ReleaseDto.builder()
											
											.build();
		Assertions.assertThrows(IllegalStateException.class,
				() -> ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated()));
	}
	
	@Test
	public void testCreateProductReleaseProper() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProductForRelease", org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch fs = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		ReleaseDto releaseDto = ReleaseDto.builder()
				.version("0.0.1-junit-test")
				.branch(fs.getUuid())
				.org(org.getUuid())
				.build();
		Release r = ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		Release rSaved = sharedReleaseService.getRelease(r.getUuid()).get();
		Assertions.assertEquals(r.getUuid(), rSaved.getUuid());
	}
	
	@Test
	public void testCreateProjectReleaseProper() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component proj = componentService.createComponent("testProjectForRelease", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBranch = branchService.getBaseBranchOfComponent(proj.getUuid()).get();
		ReleaseDto releaseDto = ReleaseDto.builder()
				.version("0.0.1-junit-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release r = ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		Release rSaved = sharedReleaseService.getRelease(r.getUuid()).get();
		Assertions.assertEquals(r.getUuid(), rSaved.getUuid());
	}
	
	@Test
	public void testSearchReleaseDataByVersion() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component proj = componentService.createComponent("testProjectForReleaseSearch2", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBranch = branchService.getBaseBranchOfComponent(proj.getUuid()).get();
		ReleaseDto releaseDto = ReleaseDto.builder()
				.version("0.0.3-junit-version-search-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		List<ReleaseData> rdList = releaseService.listReleaseDataByVersion("0.0.3-junit-version-search-test", org.getUuid());
		Assertions.assertEquals(1, rdList.size());
		Assertions.assertEquals("0.0.3-junit-version-search-test", rdList.get(0).getVersion());
	}
	
	@Test
	public void testSearchReleaseDataByVersionEmptyQuery() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component proj = componentService.createComponent("testProjectForReleaseSearch4", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBranch = branchService.getBaseBranchOfComponent(proj.getUuid()).get();
		ReleaseDto releaseDto = ReleaseDto.builder()
				.version("0.0.4-junit-version-search-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		List<ReleaseData> rdList = releaseService.listReleaseDataByVersion("", org.getUuid());
		Assertions.assertEquals(0, rdList.size());
	}

	
	@Test
	public void locateReleasesByIds() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component proj = componentService.createComponent("testProjectForListRlzByIds", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBranch = branchService.getBaseBranchOfComponent(proj.getUuid()).get();
		ReleaseDto releaseDtoProj1 = ReleaseDto.builder()
				.version("0.0.1-listrlz-junit-test")
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj1 = ossReleaseService.createRelease(releaseDtoProj1, WhoUpdated.getTestWhoUpdated());
		ReleaseDto releaseDtoProj2 = ReleaseDto.builder()
				.version("0.0.2-listrlz-junit-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj2 = ossReleaseService.createRelease(releaseDtoProj2, WhoUpdated.getTestWhoUpdated());
		ReleaseDto releaseDtoProj3 = ReleaseDto.builder()
				.version("0.0.3-listrlz-junit-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj3 = ossReleaseService.createRelease(releaseDtoProj3, WhoUpdated.getTestWhoUpdated());
		var foundRlzs = sharedReleaseService.getReleaseDataList(List.of(rProj1.getUuid(), rProj2.getUuid()), org.getUuid());
		Assertions.assertEquals(2,  foundRlzs.size());
	}
	
	@Test
	public void retrieveReleasesBetweenReleasesByDates() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component proj = componentService.createComponent("testProjectRlzComparison", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch baseBranch = branchService.getBaseBranchOfComponent(proj.getUuid()).get();
		ReleaseDto releaseDtoProj1 = ReleaseDto.builder()
				.version("0.0.1-listrlz-junit-test")
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj1 = ossReleaseService.createRelease(releaseDtoProj1, WhoUpdated.getTestWhoUpdated());
		ReleaseDto releaseDtoProj2 = ReleaseDto.builder()
				.version("0.0.2-listrlz-junit-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj2 = ossReleaseService.createRelease(releaseDtoProj2, WhoUpdated.getTestWhoUpdated());
		ReleaseDto releaseDtoProj3 = ReleaseDto.builder()
				.version("0.0.3-listrlz-junit-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj3 = ossReleaseService.createRelease(releaseDtoProj3, WhoUpdated.getTestWhoUpdated());
		ReleaseDto releaseDtoProj4 = ReleaseDto.builder()
				.version("0.0.4-listrlz-junit-test")
				
				.branch(baseBranch.getUuid())
				.org(org.getUuid())
				.build();
		Release rProj4 = ossReleaseService.createRelease(releaseDtoProj4, WhoUpdated.getTestWhoUpdated());		
		var rlzBetweenReleases = sharedReleaseService.listAllReleasesBetweenReleases(rProj1.getUuid(), rProj4.getUuid());
		Assertions.assertEquals(4,  rlzBetweenReleases.size());
	}
}
