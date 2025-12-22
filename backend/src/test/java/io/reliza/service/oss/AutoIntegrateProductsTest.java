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
	
	/**
	 * Test 4: Pinned release - should skip auto-integrate
	 * When a dependency is pinned to a specific release, auto-integrate should not trigger
	 */
	@Test
	public void testAutoIntegrateProducts_PinnedRelease_ShouldSkip() throws RelizaException {
		// Arrange
		Organization org = testInitializer.obtainOrganization();
		
		// Create component
		Component component = componentService.createComponent(
			"testComponent_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch componentBranch = branchService.createBranch(
			"main", 
			component.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create first release (this will be the pinned release)
		ReleaseDto release1Dto = ReleaseDto.builder()
			.component(component.getUuid())
			.branch(componentBranch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		Release release1 = ossReleaseService.createRelease(release1Dto, WhoUpdated.getTestWhoUpdated());
		
		// Create product with feature set that PINS the first release
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
		
		// Add dependency with PINNED release
		BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
		ChildComponent pinnedDep = ChildComponent.builder()
			.uuid(component.getUuid())
			.branch(componentBranch.getUuid())
			.status(StatusEnum.REQUIRED)
			.release(release1.getUuid()) // PINNED to v1.0.0
			.build();
		
		BranchDto branchDto = BranchDto.builder()
			.uuid(featureSetData.getUuid())
			.name(featureSetData.getName())
			.versionSchema(featureSetData.getVersionSchema())
			.type(featureSetData.getType())
			.dependencies(List.of(pinnedDep))
			.autoIntegrate(AutoIntegrateState.ENABLED)
			.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		
		// Create second release (this should NOT trigger auto-integrate because dependency is pinned)
		ReleaseDto release2Dto = ReleaseDto.builder()
			.component(component.getUuid())
			.branch(componentBranch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("2.0.0")
			.build();
		Release release2 = ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
		
		// Get initial product release count
		List<ReleaseData> productReleasesBefore = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		int initialCount = productReleasesBefore.size();
		
		// Act - trigger auto-integrate with v2.0.0
		ReleaseData release2Data = sharedReleaseService.getReleaseData(release2.getUuid()).get();
		ossReleaseService.autoIntegrateProducts(release2Data);
		
		// Assert - verify NO new product release was created (pinned dependency should skip)
		List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		
		assertEquals(initialCount, productReleasesAfter.size(), 
			"Auto-integrate should NOT create a product release when dependency is pinned to a specific release");
	}
	
	/**
	 * Test 5: Duplicate prevention - should skip when release already in product
	 * When a release is already in a product for the feature set, auto-integrate should not create duplicate
	 */
	@Test
	public void testAutoIntegrateProducts_DuplicatePrevention() throws RelizaException {
		// Arrange
		Organization org = testInitializer.obtainOrganization();
		
		// Create two components
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
		
		// Create releases for both components
		ReleaseDto release1Dto = ReleaseDto.builder()
			.component(component1.getUuid())
			.branch(component1Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		Release release1 = ossReleaseService.createRelease(release1Dto, WhoUpdated.getTestWhoUpdated());
		
		ReleaseDto release2Dto = ReleaseDto.builder()
			.component(component2.getUuid())
			.branch(component2Branch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		Release release2 = ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
		
		// Create product with feature set
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
		
		// Add dependencies
		BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
		ChildComponent dep1 = ChildComponent.builder()
			.uuid(component1.getUuid())
			.branch(component1Branch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		
		ChildComponent dep2 = ChildComponent.builder()
			.uuid(component2.getUuid())
			.branch(component2Branch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		
		BranchDto branchDto = BranchDto.builder()
			.uuid(featureSetData.getUuid())
			.name(featureSetData.getName())
			.versionSchema(featureSetData.getVersionSchema())
			.type(featureSetData.getType())
			.dependencies(List.of(dep1, dep2))
			.autoIntegrate(AutoIntegrateState.ENABLED)
			.build();
		branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
		
		// Act 1 - trigger auto-integrate for component1 (should create product release)
		ReleaseData release1Data = sharedReleaseService.getReleaseData(release1.getUuid()).get();
		ossReleaseService.autoIntegrateProducts(release1Data);
		
		List<ReleaseData> productReleasesAfterFirst = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		int countAfterFirst = productReleasesAfterFirst.size();
		
		// Act 2 - trigger auto-integrate AGAIN for the SAME release (should NOT create duplicate)
		ossReleaseService.autoIntegrateProducts(release1Data);
		
		// Assert - verify NO duplicate was created
		List<ReleaseData> productReleasesAfterSecond = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		int countAfterSecond = productReleasesAfterSecond.size();
		
		assertEquals(countAfterFirst, countAfterSecond, 
			"Auto-integrate should NOT create duplicate product release when called twice with same release");
		assertEquals(1, countAfterSecond, 
			"Should have exactly 1 product release (no duplicates)");
	}
	
	/**
	 * Test 6: Multiple feature sets - should create product for each
	 * When multiple feature sets depend on a component, auto-integrate should create product for each
	 */
	@Test
	public void testAutoIntegrateProducts_MultipleFeatureSets() throws RelizaException {
		// Arrange
		Organization org = testInitializer.obtainOrganization();
		
		// Create component
		Component component = componentService.createComponent(
			"testComponent_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch componentBranch = branchService.createBranch(
			"main", 
			component.getUuid(), 
			BranchType.BASE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Create release
		ReleaseDto releaseDto = ReleaseDto.builder()
			.component(component.getUuid())
			.branch(componentBranch.getUuid())
			.org(org.getUuid())
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version("1.0.0")
			.build();
		Release release = ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
		
		// Create TWO products with feature sets that both depend on the component
		Component product1 = componentService.createComponent(
			"testProduct1_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.PRODUCT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch featureSet1 = branchService.createBranch(
			"featureSet1", 
			product1.getUuid(), 
			BranchType.FEATURE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Component product2 = componentService.createComponent(
			"testProduct2_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.PRODUCT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch featureSet2 = branchService.createBranch(
			"featureSet2", 
			product2.getUuid(), 
			BranchType.FEATURE, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		// Add dependency to both feature sets
		BranchData featureSetData1 = branchService.getBranchData(featureSet1.getUuid()).get();
		BranchData featureSetData2 = branchService.getBranchData(featureSet2.getUuid()).get();
		
		ChildComponent dep = ChildComponent.builder()
			.uuid(component.getUuid())
			.branch(componentBranch.getUuid())
			.status(StatusEnum.REQUIRED)
			.build();
		
		BranchDto branchDto1 = BranchDto.builder()
			.uuid(featureSetData1.getUuid())
			.name(featureSetData1.getName())
			.versionSchema(featureSetData1.getVersionSchema())
			.type(featureSetData1.getType())
			.dependencies(List.of(dep))
			.autoIntegrate(AutoIntegrateState.ENABLED)
			.build();
		branchService.updateBranch(branchDto1, WhoUpdated.getTestWhoUpdated());
		
		BranchDto branchDto2 = BranchDto.builder()
			.uuid(featureSetData2.getUuid())
			.name(featureSetData2.getName())
			.versionSchema(featureSetData2.getVersionSchema())
			.type(featureSetData2.getType())
			.dependencies(List.of(dep))
			.autoIntegrate(AutoIntegrateState.ENABLED)
			.build();
		branchService.updateBranch(branchDto2, WhoUpdated.getTestWhoUpdated());
		
		// Get initial counts
		int count1Before = sharedReleaseService.listReleaseDataOfBranch(featureSet1.getUuid()).size();
		int count2Before = sharedReleaseService.listReleaseDataOfBranch(featureSet2.getUuid()).size();
		
		// Act - trigger auto-integrate
		ReleaseData releaseData = sharedReleaseService.getReleaseData(release.getUuid()).get();
		ossReleaseService.autoIntegrateProducts(releaseData);
		
		// Assert - verify product releases created for BOTH feature sets
		int count1After = sharedReleaseService.listReleaseDataOfBranch(featureSet1.getUuid()).size();
		int count2After = sharedReleaseService.listReleaseDataOfBranch(featureSet2.getUuid()).size();
		
		assertTrue(count1After > count1Before, 
			"Auto-integrate should create product release for feature set 1");
		assertTrue(count2After > count2Before, 
			"Auto-integrate should create product release for feature set 2");
	}
	
	/**
	 * Test 7: BASE branch priority - feature branch release should use BASE branch release instead
 * 
 * Scenario:
 * - Component has TWO branches: main (BASE) and feature (FEATURE)
 * - Feature set depends on BOTH branches of the same component
 * - Release v1.0.0 created on main (BASE)
 * - Release v2.0.0 created on feature branch
 * - When feature branch release triggers auto-integrate, it should use BASE branch release instead
 * 
 * This tests the determineReleaseToUse logic that prioritizes BASE branch over feature branches.
 */
@Test
public void testAutoIntegrateProducts_BaseBranchPriority() throws RelizaException {
    // Arrange
    Organization org = testInitializer.obtainOrganization();
    
    // Create component with TWO branches
    Component component = componentService.createComponent(
        "testComponent_" + UUID.randomUUID(), 
        org.getUuid(), 
        ComponentType.COMPONENT, 
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Create BASE branch (main)
    Branch baseBranch = branchService.createBranch(
        "main", 
        component.getUuid(), 
        BranchType.BASE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Create FEATURE branch
    Branch featureBranch = branchService.createBranch(
        "feature", 
        component.getUuid(), 
        BranchType.FEATURE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Create release v1.0.0 on BASE branch
    ReleaseDto baseReleaseDto = ReleaseDto.builder()
        .component(component.getUuid())
        .branch(baseBranch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.0.0")
        .build();
    Release baseRelease = ossReleaseService.createRelease(baseReleaseDto, WhoUpdated.getTestWhoUpdated());
    
    // Create release v2.0.0 on FEATURE branch
    ReleaseDto featureReleaseDto = ReleaseDto.builder()
        .component(component.getUuid())
        .branch(featureBranch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("2.0.0")
        .build();
    Release featureRelease = ossReleaseService.createRelease(featureReleaseDto, WhoUpdated.getTestWhoUpdated());
    
    // Create product with feature set that depends on BOTH branches
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
    
    // Add BOTH branches as dependencies (BASE + FEATURE)
    BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
    ChildComponent baseDep = ChildComponent.builder()
        .uuid(component.getUuid())
        .branch(baseBranch.getUuid())
        .status(StatusEnum.REQUIRED)
        .build();
    
    ChildComponent featureDep = ChildComponent.builder()
        .uuid(component.getUuid())
        .branch(featureBranch.getUuid())
        .status(StatusEnum.REQUIRED)
        .build();
    
    BranchDto branchDto = BranchDto.builder()
        .uuid(featureSetData.getUuid())
        .name(featureSetData.getName())
        .versionSchema(featureSetData.getVersionSchema())
        .type(featureSetData.getType())
        .dependencies(List.of(baseDep, featureDep))
        .autoIntegrate(AutoIntegrateState.ENABLED)
        .build();
    branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
    
    // Get initial product release count
    List<ReleaseData> productReleasesBefore = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
    int countBefore = productReleasesBefore.size();
    System.out.println("DEBUG: Product releases before: " + countBefore);
    
    // Act - trigger auto-integrate with FEATURE branch release v2.0.0
    // This should use BASE branch release v1.0.0 instead due to priority logic
    ReleaseData featureReleaseData = sharedReleaseService.getReleaseData(featureRelease.getUuid()).get();
    ossReleaseService.autoIntegrateProducts(featureReleaseData);
    
    // Assert - verify product release was created with BASE branch release (v1.0.0), NOT feature release (v2.0.0)
    List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
    int countAfter = productReleasesAfter.size();
    System.out.println("DEBUG: Product releases after: " + countAfter);
    
    assertTrue(countAfter > countBefore, 
        "Auto-integrate should create a product release");
    
    // Find the newest product release
    productReleasesAfter.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
    ReleaseData newestProductRelease = productReleasesAfter.get(0);
    
    System.out.println("DEBUG: Newest product release version: " + newestProductRelease.getVersion());
    System.out.println("DEBUG: Newest product release has " + newestProductRelease.getParentReleases().size() + " parent releases");
    
    // Verify the product contains BASE branch release (v1.0.0), NOT feature branch release (v2.0.0)
    boolean hasBaseBranchRelease = newestProductRelease.getParentReleases().stream()
        .anyMatch(pr -> {
            Optional<ReleaseData> parentRd = sharedReleaseService.getReleaseData(pr.getRelease());
            if (parentRd.isPresent()) {
                ReleaseData prd = parentRd.get();
                System.out.println("  - Parent release: component=" + prd.getComponent() + 
                    ", branch=" + prd.getBranch() + ", version=" + prd.getVersion());
                return prd.getComponent().equals(component.getUuid()) && 
                       prd.getBranch().equals(baseBranch.getUuid()) &&
                       "1.0.0".equals(prd.getVersion());
            }
            return false;
        });
    
    boolean hasFeatureBranchRelease = newestProductRelease.getParentReleases().stream()
        .anyMatch(pr -> {
            Optional<ReleaseData> parentRd = sharedReleaseService.getReleaseData(pr.getRelease());
            return parentRd.isPresent() && 
                   parentRd.get().getComponent().equals(component.getUuid()) &&
                   parentRd.get().getBranch().equals(featureBranch.getUuid()) &&
                   "2.0.0".equals(parentRd.get().getVersion());
        });
    
    assertTrue(hasBaseBranchRelease, 
        "Product release should contain BASE branch release v1.0.0 (determineReleaseToUse should prioritize BASE)");
    assertFalse(hasFeatureBranchRelease, 
        "Product release should NOT contain feature branch release v2.0.0 (BASE branch takes priority)");
}
}
