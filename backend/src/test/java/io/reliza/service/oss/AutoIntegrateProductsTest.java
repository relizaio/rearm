/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

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
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Integration tests for autoIntegrateProducts functionality.
 * 
 * These tests verify the core auto-integrate behavior using actual Spring context and database.
 * Tests focus on key scenarios without requiring Mockito.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateProductsTest {

	@Autowired
	private BranchService branchService;
	
	@Autowired
	private ComponentService componentService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private OssReleaseService ossReleaseService;
	
	@Autowired
	private ReleaseService releaseService;
	
	@Autowired
	private TestInitializer testInitializer;
	
	/**
	 * Test 1: Basic auto-integrate flow
	 * Verifies that when a new release is created for a component, auto-integrate creates
	 * a new product release for feature sets that depend on it.
	 */
	@Test
	public void testAutoIntegrateProducts_BasicFlow() throws RelizaException {
		// Arrange - create real entities
		Organization org = testInitializer.obtainOrganization();
		
		// Create component1 - this will be the triggering component
		Component component1 = componentService.createComponent(
			"testComponent1_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch component1Branch = branchService.createBranch(
			"main", 
			component1.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create first release for component1
		ReleaseDto release1v1Dto = ReleaseDto.builder()
			.component(component1.getUuid())
			.branch(component1Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		ossReleaseService.createRelease(release1v1Dto, WhoUpdated.getTestWhoUpdated());
		
		// Create component2 with a release (to satisfy getCurrentProductParentRelease)
		Component component2 = componentService.createComponent(
			"testComponent2_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch component2Branch = branchService.createBranch(
			"main", 
			component2.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		ReleaseDto release2Dto = ReleaseDto.builder()
			.component(component2.getUuid())
			.branch(component2Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
		
		// Create a product (for the feature set)
		Component product = componentService.createComponent(
			"testProduct_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.PRODUCT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a feature set branch
		Branch featureSetBranch = branchService.createBranch(
			"testFeatureSet", 
			product.getUuid(), 
			BranchType.FEATURE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Add BOTH components as dependencies and enable auto-integrate
		BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
		ChildComponent dependency1 = ChildComponent.builder()
			.uuid(component1.getUuid())
			.branch(component1Branch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		ChildComponent dependency2 = ChildComponent.builder()
			.uuid(component2.getUuid())
			.branch(component2Branch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		featureSetData.setDependencies(List.of(dependency1, dependency2));
		featureSetData.setAutoIntegrate(AutoIntegrateState.ENABLED);
		
		BranchDto branchDto = BranchDto.builder()
			.uuid(featureSetData.getUuid())
			.name(featureSetData.getName())
			.versionSchema(featureSetData.getVersionSchema())
			.marketingVersionSchema(featureSetData.getMarketingVersionSchema())
			.vcs(featureSetData.getVcs())
			.vcsBranch(featureSetData.getVcsBranch())
			.metadata(featureSetData.getMetadata())
			.dependencies(featureSetData.getDependencies())
			.autoIntegrate(featureSetData.getAutoIntegrate())
			.type(featureSetData.getType())
			.pullRequestData(featureSetData.getPullRequestData())
			.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		
		// Trigger initial auto-integrate to create first product release
		releaseService.autoIntegrateFeatureSetOnDemand(featureSetData);
		
		// Get product release count after first auto-integrate
		List<ReleaseData> productReleasesAfterFirst = sharedReleaseService.listReleaseDataOfBranch(
			featureSetBranch.getUuid());
		int countAfterFirst = productReleasesAfterFirst.size();
		System.out.println("DEBUG: Product releases after first auto-integrate: " + countAfterFirst);
		
		// Act - Create a NEW release for component1 (v2.0.0) - this should trigger a new product release
		ReleaseDto release1v2Dto = ReleaseDto.builder()
			.component(component1.getUuid())
			.branch(component1Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("2.0.0")
			.build();
		Release newRelease = ossReleaseService.createRelease(release1v2Dto, WhoUpdated.getTestWhoUpdated());
		
		System.out.println("DEBUG: Created new release v2.0.0 for component1");
		
		// Trigger auto-integrate again with the new release
		BranchData featureSetForAutoIntegrate = branchService.getBranchData(featureSetBranch.getUuid()).get();
		releaseService.autoIntegrateFeatureSetOnDemand(featureSetForAutoIntegrate);
		
		// Assert - verify a NEW product release was created
		List<ReleaseData> productReleasesAfterSecond = sharedReleaseService.listReleaseDataOfBranch(
			featureSetBranch.getUuid());
		int countAfterSecond = productReleasesAfterSecond.size();
		
		System.out.println("DEBUG: Product releases after second auto-integrate: " + countAfterSecond);
		System.out.println("DEBUG: Expected: " + (countAfterFirst + 1) + ", Actual: " + countAfterSecond);
		
		// Verify that a new product release was created
		assertTrue(countAfterSecond > countAfterFirst, 
			"Auto-integrate should create a NEW product release when component gets a new release. " +
			"Before: " + countAfterFirst + ", After: " + countAfterSecond);
	}
	
	/**
	 * Test 1b: Test actual autoIntegrateProducts method (async version)
	 * Verifies that autoIntegrateProducts correctly triggers auto-integrate when a release is created.
	 * With TestAsyncConfig, the async method runs synchronously for testing.
	 */
	@Test
	public void testAutoIntegrateProducts_AsyncMethod() throws RelizaException {
		// Arrange - create real entities
		Organization org = testInitializer.obtainOrganization();
		
		// Create component1 with initial release
		Component component1 = componentService.createComponent(
			"testComponent1_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch component1Branch = branchService.createBranch(
			"main", 
			component1.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		ReleaseDto release1v1Dto = ReleaseDto.builder()
			.component(component1.getUuid())
			.branch(component1Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		ossReleaseService.createRelease(release1v1Dto, WhoUpdated.getTestWhoUpdated());
		
		// Create component2 with release
		Component component2 = componentService.createComponent(
			"testComponent2_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch component2Branch = branchService.createBranch(
			"main", 
			component2.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		ReleaseDto release2Dto = ReleaseDto.builder()
			.component(component2.getUuid())
			.branch(component2Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
		
		// Create product and feature set
		Component product = componentService.createComponent(
			"testProduct_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.PRODUCT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch featureSetBranch = branchService.createBranch(
			"testFeatureSet", 
			product.getUuid(), 
			BranchType.FEATURE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Add dependencies and enable auto-integrate
		BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
		ChildComponent dependency1 = ChildComponent.builder()
			.uuid(component1.getUuid())
			.branch(component1Branch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		ChildComponent dependency2 = ChildComponent.builder()
			.uuid(component2.getUuid())
			.branch(component2Branch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		featureSetData.setDependencies(List.of(dependency1, dependency2));
		featureSetData.setAutoIntegrate(AutoIntegrateState.ENABLED);
		
		BranchDto branchDto = BranchDto.builder()
			.uuid(featureSetData.getUuid())
			.name(featureSetData.getName())
			.versionSchema(featureSetData.getVersionSchema())
			.marketingVersionSchema(featureSetData.getMarketingVersionSchema())
			.vcs(featureSetData.getVcs())
			.vcsBranch(featureSetData.getVcsBranch())
			.metadata(featureSetData.getMetadata())
			.dependencies(featureSetData.getDependencies())
			.autoIntegrate(featureSetData.getAutoIntegrate())
			.type(featureSetData.getType())
			.pullRequestData(featureSetData.getPullRequestData())
			.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		
		// Create initial product release
		releaseService.autoIntegrateFeatureSetOnDemand(featureSetData);
		
		int countBefore = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).size();
		System.out.println("DEBUG: Product releases before new component release: " + countBefore);
		
		// Act - Create a NEW release for component1 and call autoIntegrateProducts
		ReleaseDto release1v2Dto = ReleaseDto.builder()
			.component(component1.getUuid())
			.branch(component1Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("2.0.0")
			.build();
		Release newRelease = ossReleaseService.createRelease(release1v2Dto, WhoUpdated.getTestWhoUpdated());
		
		// Verify the new release was created
		ReleaseData newReleaseData = sharedReleaseService.getReleaseData(newRelease.getUuid()).get();
		System.out.println("DEBUG: Created new release - UUID: " + newReleaseData.getUuid() + 
			", component: " + newReleaseData.getComponent() + 
			", version: " + newReleaseData.getVersion());
		
		// Verify there are now 2 releases for component1
		List<ReleaseData> component1Releases = sharedReleaseService.listReleaseDataOfBranch(component1Branch.getUuid());
		System.out.println("DEBUG: Component1 now has " + component1Releases.size() + " releases:");
		for (ReleaseData rd : component1Releases) {
			System.out.println("  - " + rd.getVersion() + " (UUID: " + rd.getUuid() + ")");
		}
		
		// Call autoIntegrateProducts directly (with TestAsyncConfig, it runs synchronously)
		ossReleaseService.autoIntegrateProducts(newReleaseData);
		
		// Assert - verify a NEW product release was created with correct components
		List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		int countAfter = productReleasesAfter.size();
		System.out.println("DEBUG: Product releases after autoIntegrateProducts: " + countAfter);
		
		assertTrue(countAfter > countBefore, 
			"autoIntegrateProducts should create a NEW product release. Before: " + countBefore + ", After: " + countAfter);
		
		// Find the newest product release (the one created after the v2.0.0 release)
		// Sort by created date to get the actual newest one
		productReleasesAfter.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
		ReleaseData newestProductRelease = productReleasesAfter.get(0);
		System.out.println("DEBUG: Newest product release version: " + newestProductRelease.getVersion());
		System.out.println("DEBUG: Newest product release created: " + newestProductRelease.getCreatedDate());
		System.out.println("DEBUG: Newest product release has " + newestProductRelease.getParentReleases().size() + " parent releases");
		
		// Verify the new product release contains the correct component releases
		assertNotNull(newestProductRelease.getParentReleases(), "Product release should have parent releases");
		assertEquals(2, newestProductRelease.getParentReleases().size(), 
			"Product release should have 2 parent releases (component1 v2.0.0 + component2 v1.0.0)");
		
		// Verify it contains the NEW release (v2.0.0) for component1
		boolean hasComponent1v2 = newestProductRelease.getParentReleases().stream()
			.anyMatch(pr -> {
				Optional<ReleaseData> parentRd = sharedReleaseService.getReleaseData(pr.getRelease());
				if (parentRd.isPresent()) {
					ReleaseData prd = parentRd.get();
					System.out.println("  - Parent release: component=" + prd.getComponent() + 
						", version=" + prd.getVersion());
					return prd.getComponent().equals(component1.getUuid()) && 
						   "2.0.0".equals(prd.getVersion());
				}
				return false;
			});
		
		assertTrue(hasComponent1v2, 
			"New product release should contain component1 v2.0.0 (the triggering release)");
		
		// Verify it contains component2 v1.0.0
		boolean hasComponent2v1 = newestProductRelease.getParentReleases().stream()
			.anyMatch(pr -> {
				Optional<ReleaseData> parentRd = sharedReleaseService.getReleaseData(pr.getRelease());
				return parentRd.isPresent() && 
					   parentRd.get().getComponent().equals(component2.getUuid()) &&
					   "1.0.0".equals(parentRd.get().getVersion());
			});
		
		assertTrue(hasComponent2v1, 
			"New product release should contain component2 v1.0.0");
	}
	
	/**
	 * Test 2: IGNORED status - should skip auto-integrate
	 * When a dependency has IGNORED status, auto-integrate should not trigger
	 */
	@Test
	public void testAutoIntegrateProducts_IgnoredStatus_ShouldSkip() throws RelizaException {
		// Arrange - create real entities
		Organization org = testInitializer.obtainOrganization();
		
		// Create a component
		Component component = componentService.createComponent(
			"testComponent_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a branch for the component
		Branch componentBranch = branchService.createBranch(
			"main", 
			component.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a product (for the feature set)
		Component product = componentService.createComponent(
			"testProduct_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.PRODUCT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a feature set branch
		Branch featureSetBranch = branchService.createBranch(
			"testFeatureSet", 
			product.getUuid(), 
			BranchType.FEATURE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Add component as dependency with IGNORED status and enable auto-integrate
		BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
		ChildComponent dependency = ChildComponent.builder()
			.uuid(component.getUuid())
			.branch(componentBranch.getUuid())
			.status(StatusEnum.IGNORED)  // IGNORED status
			.build();
		featureSetData.setDependencies(List.of(dependency));
		featureSetData.setAutoIntegrate(AutoIntegrateState.ENABLED);
		
		BranchDto branchDto = BranchDto.builder()
			.uuid(featureSetData.getUuid())
			.name(featureSetData.getName())
			.versionSchema(featureSetData.getVersionSchema())
			.marketingVersionSchema(featureSetData.getMarketingVersionSchema())
			.vcs(featureSetData.getVcs())
			.vcsBranch(featureSetData.getVcsBranch())
			.metadata(featureSetData.getMetadata())
			.dependencies(featureSetData.getDependencies())
			.autoIntegrate(featureSetData.getAutoIntegrate())
			.type(featureSetData.getType())
			.pullRequestData(featureSetData.getPullRequestData())
			.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		
		// Create a release for the component
		ReleaseDto releaseDto = ReleaseDto.builder()
			.component(component.getUuid())
			.branch(componentBranch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		Release release = ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		
		// Get initial product release count
		List<ReleaseData> productReleasesBefore = sharedReleaseService.listReleaseDataOfBranch(
			featureSetBranch.getUuid());
		int initialCount = productReleasesBefore.size();
		
		// Act - trigger auto-integrate
		ReleaseData releaseData = sharedReleaseService.getReleaseData(release.getUuid()).get();
		ossReleaseService.autoIntegrateProducts(releaseData);
		
		// Give async operation time to complete
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		// Assert - verify NO product release was created (IGNORED should skip)
		List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(
			featureSetBranch.getUuid());
		
		assertEquals(initialCount, productReleasesAfter.size(), 
			"Auto-integrate should NOT create a product release for IGNORED dependencies");
	}
	
	/**
	 * Test 3: AutoIntegrate disabled - should skip
	 * When a feature set has autoIntegrate=DISABLED, no product release should be created
	 */
	@Test
	public void testAutoIntegrateProducts_AutoIntegrateDisabled_ShouldSkip() throws RelizaException {
		// Arrange - create real entities
		Organization org = testInitializer.obtainOrganization();
		
		// Create a component
		Component component = componentService.createComponent(
			"testComponent_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a branch for the component
		Branch componentBranch = branchService.createBranch(
			"main", 
			component.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a product (for the feature set)
		Component product = componentService.createComponent(
			"testProduct_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.PRODUCT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create a feature set branch
		Branch featureSetBranch = branchService.createBranch(
			"testFeatureSet", 
			product.getUuid(), 
			BranchType.FEATURE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Add component as dependency but keep auto-integrate DISABLED
		BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
		ChildComponent dependency = ChildComponent.builder()
			.uuid(component.getUuid())
			.branch(componentBranch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		featureSetData.setDependencies(List.of(dependency));
		featureSetData.setAutoIntegrate(AutoIntegrateState.DISABLED);  // DISABLED
		
		BranchDto branchDto = BranchDto.builder()
			.uuid(featureSetData.getUuid())
			.name(featureSetData.getName())
			.versionSchema(featureSetData.getVersionSchema())
			.marketingVersionSchema(featureSetData.getMarketingVersionSchema())
			.vcs(featureSetData.getVcs())
			.vcsBranch(featureSetData.getVcsBranch())
			.metadata(featureSetData.getMetadata())
			.dependencies(featureSetData.getDependencies())
			.autoIntegrate(featureSetData.getAutoIntegrate())
			.type(featureSetData.getType())
			.pullRequestData(featureSetData.getPullRequestData())
			.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		
		// Create a release for the component
		ReleaseDto releaseDto = ReleaseDto.builder()
			.component(component.getUuid())
			.branch(componentBranch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		Release release = ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		
		// Get initial product release count
		List<ReleaseData> productReleasesBefore = sharedReleaseService.listReleaseDataOfBranch(
			featureSetBranch.getUuid());
		int initialCount = productReleasesBefore.size();
		
		// Act - trigger auto-integrate
		ReleaseData releaseData = sharedReleaseService.getReleaseData(release.getUuid()).get();
		ossReleaseService.autoIntegrateProducts(releaseData);
		
		// Give async operation time to complete
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		// Assert - verify NO product release was created (DISABLED should skip)
		List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(
			featureSetBranch.getUuid());
		
		assertEquals(initialCount, productReleasesAfter.size(), 
			"Auto-integrate should NOT create a product release when DISABLED");
	}
}
