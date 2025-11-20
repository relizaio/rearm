/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;


import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Organization;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.BranchService;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.service.ComponentService;

@ExtendWith(SpringExtension.class)
@SpringBootTest
public class FeatureSetTest 
{
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private ComponentService componentService;

	@Autowired
	private TestInitializer testInitializer;
	
	@Test
	public void testCreateFeatureSetProper() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProductForFeaturSet", org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch fs = branchService.createBranch("testFeatureSet", prod.getUuid(),
																BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		Branch fsSaved = branchService.getBranch(fs.getUuid()).get();
		Assertions.assertEquals(fs.getUuid(), fsSaved.getUuid());
	}
	
	@Test
	public void testListFeatureSetsOfProduct() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProductForFeaturSetList", org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Branch fs1 = branchService.createBranch("testFeatureSet1", prod.getUuid(),
				BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Branch fs2 = branchService.createBranch("testFeatureSet2", prod.getUuid(),
				BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Branch fs3 = branchService.createBranch("testFeatureSet3", prod.getUuid(),
				BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		
		Collection<BranchData> fsDataList = branchService.listBranchDataOfComponent(prod.getUuid(), null);
		
		Assertions.assertEquals(4,  fsDataList.size()); // expect 4, because of base feature set added
	}
	
	
	@Test
	@Disabled // TODO switch to dependencies field
	public void addComponentToFeatureSet() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component prod = componentService.createComponent("testProductForFeaturSet ws ", org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch fs = branchService.createBranch("testFeatureSet ws", prod.getUuid(),
				BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		Component proj = componentService.createComponent("test project ws feature set", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		
		Branch br = branchService.createBranch("test branch ws feature set", 
															proj.getUuid(), BranchType.REGULAR, WhoUpdated.getTestWhoUpdated());
		
		Set<UUID> components = Set.of(br.getUuid());
		// branchService.addComponentsToFeatureSet(fs, components, WhoUpdated.getTestWhoUpdated());
		
		// reload feature set and verify that it has same project with branch
		BranchData fsdLoaded = branchService.getBranchData(fs.getUuid()).get();
		// Set<UUID> loadedComponents = fsdLoaded.getComponents();
		// UUID branchUuid = loadedComponents.iterator().next();
		Assertions.assertEquals(br.getUuid(), fsdLoaded.getComponent());
	}
}
