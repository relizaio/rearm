/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;


import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Organization;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

@ExtendWith(SpringExtension.class)
@SpringBootTest
public class BranchTest 
{
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private OrganizationService organizationService;
	
	@Autowired
    private ComponentService ComponentService;
	
	@Autowired
    private ReleaseService releaseService;
	@Autowired
    private SharedReleaseService sharedReleaseService;
	
	
	private Organization obtainOrganization() {
		return organizationService.getOrganization(UserService.USER_ORG).get();
	}
	
	@Test
	public void testCreateBranchWithoutReqFieldsThrowsIllegalState() throws RelizaException {
		Assertions.assertThrows(InvalidDataAccessApiUsageException.class,
				() -> branchService.createBranch(null, null, BranchType.REGULAR, null));
	}
	
	@Test
	public void testCreateBranchProper() throws RelizaException {
		Organization org = obtainOrganization();
		Component p = ComponentService.createComponent("testProjectForBranch", org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		Branch b = branchService.createBranch("testBranch", p.getUuid(), BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		Branch bSaved = branchService.getBranch(b.getUuid()).get();
		Assertions.assertEquals(b.getUuid(), bSaved.getUuid());
	}
	
	@Test
	public void testListBranchesOfComponent() throws RelizaException {
		Organization org = obtainOrganization();
		Component p = ComponentService.createComponent("testProjectForBranchList", org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Branch b1 = branchService.createBranch("testBranch1", p.getUuid(), BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Branch b2 = branchService.createBranch("testBranch2", p.getUuid(), BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Branch b3 = branchService.createBranch("testBranch3", p.getUuid(), BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		
		Collection<BranchData> bDataList = branchService.listBranchDataOfComponent(p.getUuid(), null);
		
		Assertions.assertEquals(4,  bDataList.size());
	}
	
	@Test
	@Disabled // TODO - switch to dependencies field test
	public void addProjectToBranchAsDependency() throws RelizaException {
		Organization org = obtainOrganization();
		Component projTarget = ComponentService.createComponent("test project branch add proj target", org.getUuid(), ComponentType.COMPONENT, 
				WhoUpdated.getTestWhoUpdated());

		Component projSource = ComponentService.createComponent("test project branch add proj source", org.getUuid(), ComponentType.COMPONENT, 
				WhoUpdated.getTestWhoUpdated());
		
		Branch brSource = branchService.createBranch("test branch add proj", 
												projSource.getUuid(), BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		
		Set<UUID> components = new LinkedHashSet<>();
		components.add(brSource.getUuid());
		Branch brTarget = branchService.getBaseBranchOfComponent(projTarget.getUuid()).get();
		// branchService.addComponentsToFeatureSet(brTarget, components, WhoUpdated.getTestWhoUpdated());
		
		// reload target branch and verify that it has same project with branch
		BranchData bdLoaded = branchService.getBranchData(brTarget.getUuid()).get();
		// Set<UUID> loadedComponents = bdLoaded.getComponents();
		// UUID branchUuid = loadedComponents.iterator().next();
		Assertions.assertEquals(brSource.getUuid(), bdLoaded.getComponent());
	}
	
	@Test
	public void testRetrieveLatestBranchRelease() throws RelizaException {
		Organization org = obtainOrganization();
		Component prod = ComponentService.createComponent("testProductForLatestRlz", org.getUuid(), 
				ComponentType.PRODUCT, "semver", null, WhoUpdated.getTestWhoUpdated());
		// get base feature set of product
		Branch fs = branchService.getBaseBranchOfComponent(prod.getUuid()).get();
		BranchData fsData = BranchData.branchDataFromDbRecord(fs);
		Assertions.assertEquals("semver", fsData.getVersionSchema());
		// create releases for feature set

		var releaseDtoBuilder = ReleaseDto.builder()
				.component(prod.getUuid())
				.branch(fs.getUuid())
				.org(org.getUuid())
				.lifecycle(ReleaseLifecycle.ASSEMBLED);
		Release r1 = releaseService.createRelease(releaseDtoBuilder.version("0.0.0").build(), WhoUpdated.getTestWhoUpdated());
		Release r2 = releaseService.createRelease(releaseDtoBuilder.version("0.0.1+testRlz").build(), WhoUpdated.getTestWhoUpdated());
		Release r3 = releaseService.createRelease(releaseDtoBuilder.version("0.0.7+test.Rlz").lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WhoUpdated.getTestWhoUpdated());
		Release r4 = releaseService.createRelease(releaseDtoBuilder.version("0.0.8+test.Rlz").lifecycle(ReleaseLifecycle.DRAFT).build(), WhoUpdated.getTestWhoUpdated());
		Release r5 = releaseService.createRelease(releaseDtoBuilder.version("2020.01").build(), WhoUpdated.getTestWhoUpdated());

		ReleaseData latestRd = sharedReleaseService.getReleaseDataOfBranch(org.getUuid(), fs.getUuid(), ReleaseLifecycle.ASSEMBLED).get();
		Assertions.assertEquals("0.0.7+test.Rlz", latestRd.getVersion());
	}
	
	@Test
	@Disabled //WIP - using hard-coded values currently, but need to re-do with pre-creating all components
	public void findBranchByChildProjectBranch() {
		List<BranchData> bdList = branchService.findBranchDataByChildComponentBranch(UUID.fromString("4cba762e-c08d-4d7a-9c01-6bdbefcf6282"), UUID.fromString("ce77c5be-7641-446c-bc40-0bc51e53e866"),
				UUID.fromString("d18c04f2-f0b6-4dc0-b419-d2ed4deeee58"));
		Assertions.assertEquals(UUID.fromString("28c6b0d0-5396-4f17-b3bb-072f52cc3500"), bdList.get(0).getUuid());
	}
	
	
}
