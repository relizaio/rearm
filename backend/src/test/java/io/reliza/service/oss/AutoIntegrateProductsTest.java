/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
import io.reliza.model.FlowControl;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
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
import io.reliza.service.VersionAssignmentService;
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

	@Autowired
	private VersionAssignmentService versionAssignmentService;
	
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
		
		Branch component1Branch = branchService.getBaseBranchOfComponent(component1.getUuid()).orElseThrow();
		
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
		
		Branch component2Branch = branchService.getBaseBranchOfComponent(component2.getUuid()).orElseThrow();
		
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
		
		Branch component1Branch = branchService.getBaseBranchOfComponent(component1.getUuid()).orElseThrow();
		
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
		
		Branch component2Branch = branchService.getBaseBranchOfComponent(component2.getUuid()).orElseThrow();
		
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
		
		// Trigger auto-integrate for the new release. createRelease's own
		// after-commit dispatch may already hold the processing claim (the
		// atomic lease that prevents double integration), in which case this
		// direct call is a no-op and the bounded-executor worker does the
		// work — so await the outcome instead of asserting immediately.
		triggerAutoIntegrateSynchronously(newReleaseData);

		List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		for (int i = 0; i < 40 && productReleasesAfter.size() <= countBefore; i++) {
			try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
			productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		}
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
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		
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
		triggerAutoIntegrateSynchronously(releaseData);
		
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
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		
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
		triggerAutoIntegrateSynchronously(releaseData);
		
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
		
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		
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
		triggerAutoIntegrateSynchronously(release2Data);
		
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
		
		Branch component1Branch = branchService.getBaseBranchOfComponent(component1.getUuid()).orElseThrow();
		
		Component component2 = componentService.createComponent(
			"testComponent2_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch component2Branch = branchService.getBaseBranchOfComponent(component2.getUuid()).orElseThrow();
		
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
		triggerAutoIntegrateSynchronously(release1Data);
		
		List<ReleaseData> productReleasesAfterFirst = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
		int countAfterFirst = productReleasesAfterFirst.size();
		
		// Act 2 - trigger auto-integrate AGAIN for the SAME release (should NOT create duplicate)
		triggerAutoIntegrateSynchronously(release1Data);
		
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
		
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		
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
		triggerAutoIntegrateSynchronously(releaseData);
		
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
    Branch baseBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
    
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
    triggerAutoIntegrateSynchronously(featureReleaseData);
    
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

/**
 * Test 8: Pattern-based auto-integrate - component matches pattern
 * 
 * Scenario:
 * - Create components: "myapp-api", "myapp-ui"
 * - Create feature set with pattern "^myapp-.*" (no explicit dependencies)
 * - Create release on "myapp-api"
 * - Auto-integrate should trigger via pattern matching
 * 
 * This tests the pattern-based discovery path in autoIntegrateProducts.
 */
@Test
public void testAutoIntegrateProducts_PatternMatching() throws RelizaException {
    // Arrange
    Organization org = testInitializer.obtainOrganization();
    
    // Create components matching pattern with unique names
    // Note: createComponent automatically creates a BASE branch named "main"
	// Use a unique test ID to avoid pattern collisions with other tests
    String testId = UUID.randomUUID().toString().substring(0, 8);
    
    Component comp1 = componentService.createComponent(
        "test8-api-" + testId, 
        org.getUuid(), 
        ComponentType.COMPONENT, 
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Component comp2 = componentService.createComponent(
        "test8-ui-" + testId, 
        org.getUuid(), 
        ComponentType.COMPONENT,
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Get the auto-created BASE branches
    Branch comp1Branch = branchService.getBaseBranchOfComponent(comp1.getUuid()).get();
    Branch comp2Branch = branchService.getBaseBranchOfComponent(comp2.getUuid()).get();
    
    // Create product with feature set using PATTERN (not explicit dependency)
    Component product = componentService.createComponent(
        "test8-product-" + testId, 
        org.getUuid(), 
        ComponentType.PRODUCT,
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Branch featureSet = branchService.createBranch(
        "main", 
        product.getUuid(), 
        BranchType.FEATURE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Add dependency PATTERN (not explicit dependency)
    // Note: Not specifying targetBranchName so it defaults to BASE branch type
    BranchData featureSetData = branchService.getBranchData(featureSet.getUuid()).get();
    BranchData.DependencyPattern pattern = BranchData.DependencyPattern.builder()
        .uuid(UUID.randomUUID())
        .pattern("^test8-.*")
        .defaultStatus(StatusEnum.REQUIRED)
        .build();
    
    BranchDto branchDto = BranchDto.builder()
        .uuid(featureSetData.getUuid())
        .name(featureSetData.getName())
        .versionSchema(featureSetData.getVersionSchema())
        .type(featureSetData.getType())
        .dependencyPatterns(List.of(pattern))
        .dependencies(new LinkedList<>())  // Empty explicit dependencies
        .autoIntegrate(AutoIntegrateState.ENABLED)
        .build();
    branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
    
    // Create releases for both components (pattern matched both, so both need releases)
    ReleaseDto release2Dto = ReleaseDto.builder()
        .component(comp2.getUuid())
        .branch(comp2Branch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.0.0")
        .build();
    Release release2 = ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
    
    // Get initial product release count
    int countBefore = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).size();
    System.out.println("DEBUG: Product releases before: " + countBefore);
    
    // Act - create release on comp1, which should trigger auto-integrate via pattern matching
    // Note: createRelease with ASSEMBLED lifecycle automatically triggers autoIntegrateProducts
    ReleaseDto release1Dto = ReleaseDto.builder()
        .component(comp1.getUuid())
        .branch(comp1Branch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("2.0.0")
        .build();
    Release release1 = ossReleaseService.createRelease(release1Dto, WhoUpdated.getTestWhoUpdated());
    
    // Give async operation time to complete (auto-integrate is triggered automatically)
    try {
        Thread.sleep(3000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    
    // Assert - verify product release created via pattern matching
    List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid());
    int countAfter = productReleasesAfter.size();
    System.out.println("DEBUG: Product releases after: " + countAfter);
    
    assertTrue(countAfter > countBefore, 
        "Pattern-based auto-integrate should create product release");
    
    // Verify the product contains the triggering release
    productReleasesAfter.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
    ReleaseData newestProduct = productReleasesAfter.get(0);
    
    boolean hasComp1 = newestProduct.getParentReleases().stream()
        .anyMatch(pr -> {
            Optional<ReleaseData> prd = sharedReleaseService.getReleaseData(pr.getRelease());
            return prd.isPresent() && prd.get().getComponent().equals(comp1.getUuid());
        });
    
    assertTrue(hasComp1, "Product should contain comp1 release (matched by pattern)");
}

/**
 * Test 9: Pattern with override IGNORED - should skip component
 * 
 * Scenario:
 * - Create components: "myapp-api", "myapp-test"
 * - Create feature set with pattern "^myapp-.*"
 * - Add override: myapp-test → IGNORED
 * - Create release on "myapp-test"
 * - Auto-integrate should NOT trigger (excluded by override)
 */
@Test
public void testAutoIntegrateProducts_PatternWithOverrideIgnored() throws RelizaException {
    // Arrange
    Organization org = testInitializer.obtainOrganization();
    
    Component comp1 = componentService.createComponent(
        "myapp-api-" + UUID.randomUUID().toString().substring(0, 8), 
        org.getUuid(), 
        ComponentType.COMPONENT, 
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Component comp2 = componentService.createComponent(
        "myapp-test-" + UUID.randomUUID().toString().substring(0, 8), 
        org.getUuid(), 
        ComponentType.COMPONENT,
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Get the auto-created BASE branches
    Branch comp1Branch = branchService.getBaseBranchOfComponent(comp1.getUuid()).get();
    Branch comp2Branch = branchService.getBaseBranchOfComponent(comp2.getUuid()).get();
    
    Component product = componentService.createComponent(
        "myapp-product", 
        org.getUuid(), 
        ComponentType.PRODUCT,
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Branch featureSet = branchService.createBranch(
        "main", 
        product.getUuid(), 
        BranchType.FEATURE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Add pattern to match myapp-.* components
    // Add manual dependency to IGNORE myapp-test (overrides pattern match)
    BranchData featureSetData = branchService.getBranchData(featureSet.getUuid()).get();
    BranchData.DependencyPattern pattern = BranchData.DependencyPattern.builder()
        .uuid(UUID.randomUUID())
        .pattern("^myapp-.*")
        .defaultStatus(StatusEnum.REQUIRED)
        .build();
    
    // Manual dependency with IGNORED status overrides the pattern-matched entry
    BranchData.ChildComponent manualIgnored = BranchData.ChildComponent.builder()
        .uuid(comp2.getUuid())
        .branch(comp2Branch.getUuid())
        .status(StatusEnum.IGNORED)
        .build();
    
    BranchDto branchDto = BranchDto.builder()
        .uuid(featureSetData.getUuid())
        .name(featureSetData.getName())
        .versionSchema(featureSetData.getVersionSchema())
        .type(featureSetData.getType())
        .dependencyPatterns(List.of(pattern))
        .dependencies(List.of(manualIgnored))
        .autoIntegrate(AutoIntegrateState.ENABLED)
        .build();
    branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
    
    int countBefore = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).size();
    
    // Act - create release on myapp-test (which is IGNORED)
    ReleaseDto release2Dto = ReleaseDto.builder()
        .component(comp2.getUuid())
        .branch(comp2Branch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.0.0")
        .build();
    Release release2 = ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
    
    ReleaseData release2Data = sharedReleaseService.getReleaseData(release2.getUuid()).get();
    triggerAutoIntegrateSynchronously(release2Data);
    
    // Give async operation time to complete
    try {
        Thread.sleep(2000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    
    // Assert - NO product release should be created (component is IGNORED)
    int countAfter = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).size();
    
    assertEquals(countBefore, countAfter, 
        "Auto-integrate should NOT create product release for IGNORED component");
}

/**
 * Test 10: Pattern + manual dependencies work together
 * 
 * Scenario:
 * - Create components: "myapp-api" (matches pattern), "custom-lib" (manual)
 * - Create feature set with pattern "^myapp-.*" + manual dependency on "custom-lib"
 * - Create release on "myapp-api"
 * - Product should include both myapp-api (pattern) and custom-lib (manual)
 */
@Test
public void testAutoIntegrateProducts_PatternAndManualDependencies() throws RelizaException {
    // Arrange
    Organization org = testInitializer.obtainOrganization();
    
    Component comp1 = componentService.createComponent(
        "myapp-api-" + UUID.randomUUID().toString().substring(0, 8), 
        org.getUuid(), 
        ComponentType.COMPONENT, 
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Component comp2 = componentService.createComponent(
        "custom-lib-" + UUID.randomUUID().toString().substring(0, 8), 
        org.getUuid(), 
        ComponentType.COMPONENT,
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Get the auto-created BASE branches
    Branch comp1Branch = branchService.getBaseBranchOfComponent(comp1.getUuid()).get();
    Branch comp2Branch = branchService.getBaseBranchOfComponent(comp2.getUuid()).get();
    
    // Create releases for both components
    ReleaseDto release1Dto = ReleaseDto.builder()
        .component(comp1.getUuid())
        .branch(comp1Branch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.0.0")
        .build();
    Release release1 = ossReleaseService.createRelease(release1Dto, WhoUpdated.getTestWhoUpdated());
    
    ReleaseDto release2Dto = ReleaseDto.builder()
        .component(comp2.getUuid())
        .branch(comp2Branch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("2.0.0")
        .build();
    Release release2 = ossReleaseService.createRelease(release2Dto, WhoUpdated.getTestWhoUpdated());
    
    Component product = componentService.createComponent(
        "myapp-product", 
        org.getUuid(), 
        ComponentType.PRODUCT,
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Branch featureSet = branchService.createBranch(
        "main", 
        product.getUuid(), 
        BranchType.FEATURE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Add pattern + manual dependency
    BranchData featureSetData = branchService.getBranchData(featureSet.getUuid()).get();
    BranchData.DependencyPattern pattern = BranchData.DependencyPattern.builder()
        .uuid(UUID.randomUUID())
        .pattern("^myapp-.*")
        .defaultStatus(StatusEnum.REQUIRED)
        .build();
    
    ChildComponent manualDep = ChildComponent.builder()
        .uuid(comp2.getUuid())
        .branch(comp2Branch.getUuid())
        .status(StatusEnum.REQUIRED)
        .build();
    
    BranchDto branchDto = BranchDto.builder()
        .uuid(featureSetData.getUuid())
        .name(featureSetData.getName())
        .versionSchema(featureSetData.getVersionSchema())
        .type(featureSetData.getType())
        .dependencyPatterns(List.of(pattern))
        .dependencies(List.of(manualDep))
        .autoIntegrate(AutoIntegrateState.ENABLED)
        .build();
    branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
    
    int countBefore = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).size();
    
    // Act - create new release on myapp-api
    ReleaseDto release3Dto = ReleaseDto.builder()
        .component(comp1.getUuid())
        .branch(comp1Branch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.1.0")
        .build();
    Release release3 = ossReleaseService.createRelease(release3Dto, WhoUpdated.getTestWhoUpdated());
    
    ReleaseData release3Data = sharedReleaseService.getReleaseData(release3.getUuid()).get();
    triggerAutoIntegrateSynchronously(release3Data);
    
    // Give async operation time to complete
    try {
        Thread.sleep(2000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    
    // Assert - product should contain BOTH pattern-matched and manual dependencies
    List<ReleaseData> productReleasesAfter = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid());
    int countAfter = productReleasesAfter.size();
    
    assertTrue(countAfter > countBefore, 
        "Auto-integrate should create product release");
    
    productReleasesAfter.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
    ReleaseData newestProduct = productReleasesAfter.get(0);
    
    boolean hasComp1 = newestProduct.getParentReleases().stream()
        .anyMatch(pr -> {
            Optional<ReleaseData> prd = sharedReleaseService.getReleaseData(pr.getRelease());
            return prd.isPresent() && prd.get().getComponent().equals(comp1.getUuid());
        });
    
    boolean hasComp2 = newestProduct.getParentReleases().stream()
        .anyMatch(pr -> {
            Optional<ReleaseData> prd = sharedReleaseService.getReleaseData(pr.getRelease());
            return prd.isPresent() && prd.get().getComponent().equals(comp2.getUuid());
        });
    
    assertTrue(hasComp1, "Product should contain myapp-api (pattern-matched)");
    assertTrue(hasComp2, "Product should contain custom-lib (manual dependency)");
}

/**
 * Test 11: Pattern-based auto-integrate with no branch specified should only trigger for BASE branch releases
 * 
 * Scenario:
 * - Create component "lib" with 2 branches: main (BASE), bugfix (FEATURE)
 * - Create product with feature set using pattern "^lib-.*" (no explicit branch chosen)
 * - Pattern defaults to BASE branch
 * - Create release on main branch -> should trigger auto-integrate
 * - Create release on bugfix branch -> should NOT trigger auto-integrate
 * 
 * This tests that pattern-based auto-integrate respects the branch constraint.
 */
@Test
public void testAutoIntegrateProducts_PatternMatchingOnlyBaseBranch() throws RelizaException {
    // Arrange
    Organization org = testInitializer.obtainOrganization();
    String testId = UUID.randomUUID().toString().substring(0, 8);
    
    // Create component with unique name matching pattern
    Component lib = componentService.createComponent(
        "lib-" + testId, 
        org.getUuid(), 
        ComponentType.COMPONENT, 
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Get the automatically created BASE branch (main)
    Branch mainBranch = branchService.getBaseBranchOfComponent(lib.getUuid()).get();
    
    // Create a FEATURE branch (bugfix)
    Branch bugfixBranch = branchService.createBranch(
        "bugfix", 
        lib.getUuid(), 
        BranchType.FEATURE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Create product with feature set using PATTERN (defaults to BASE branch)
    Component product = componentService.createComponent(
        "test11-product-" + testId, 
        org.getUuid(), 
        ComponentType.PRODUCT, 
        "semver", 
        "Branch.Micro", 
        null, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    Branch featureSet = branchService.createBranch(
        "test11-fs-" + testId, 
        product.getUuid(), 
        BranchType.FEATURE, 
        WhoUpdated.getTestWhoUpdated()
    );
    
    // Add dependency PATTERN (no targetBranchName, so defaults to BASE branch)
    BranchData featureSetData = branchService.getBranchData(featureSet.getUuid()).get();
    BranchData.DependencyPattern pattern = BranchData.DependencyPattern.builder()
        .uuid(UUID.randomUUID())
        .pattern("^lib-" + testId + "$")
        .defaultStatus(StatusEnum.REQUIRED)
        .build();
    
    BranchDto branchDto = BranchDto.builder()
        .uuid(featureSetData.getUuid())
        .name(featureSetData.getName())
        .versionSchema(featureSetData.getVersionSchema())
        .type(featureSetData.getType())
        .dependencyPatterns(List.of(pattern))
        .dependencies(new LinkedList<>())
        .autoIntegrate(AutoIntegrateState.ENABLED)
        .build();
    branchService.updateBranch(branchDto, WhoUpdated.getTestWhoUpdated());
    
    // Act 1: Create release on main (BASE) branch - should trigger auto-integrate
    ReleaseDto mainReleaseDto = ReleaseDto.builder()
        .component(lib.getUuid())
        .branch(mainBranch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.0.0")
        .build();
    Release mainRelease = ossReleaseService.createRelease(mainReleaseDto, WhoUpdated.getTestWhoUpdated());
    
    // Wait for async processing
    try {
        Thread.sleep(1000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    
    int countAfterMainRelease = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).size();
    System.out.println("DEBUG Test11: Product releases after main branch release: " + countAfterMainRelease);
    
    // Assert 1: Product release should be created for BASE branch release
    assertTrue(countAfterMainRelease > 0, 
        "Pattern-based auto-integrate SHOULD create product release for BASE branch release");
    
    // Act 2: Create release on bugfix (FEATURE) branch - should NOT trigger auto-integrate
    ReleaseDto bugfixReleaseDto = ReleaseDto.builder()
        .component(lib.getUuid())
        .branch(bugfixBranch.getUuid())
        .org(org.getUuid())
        .status(ReleaseStatus.ACTIVE)
        .lifecycle(ReleaseLifecycle.ASSEMBLED)
        .version("1.0.1-bugfix")
        .build();
    Release bugfixRelease = ossReleaseService.createRelease(bugfixReleaseDto, WhoUpdated.getTestWhoUpdated());
    
    // Wait for async processing
    try {
        Thread.sleep(1000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    
    int countAfterBugfixRelease = sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).size();
    System.out.println("DEBUG Test11: Product releases after bugfix branch release: " + countAfterBugfixRelease);
    
    // Assert 2: NO new product release should be created for non-BASE branch release
    assertEquals(countAfterMainRelease, countAfterBugfixRelease,
        "Pattern-based auto-integrate should NOT create product release for non-BASE branch release. " +
        "Pattern defaults to BASE branch, so bugfix branch releases should be ignored.");
}

/**
 * Direct trigger with the create-time worker awaited first. createRelease queues an
 * after-commit auto-integrate hop on a REAL background executor (see TestAsyncConfig);
 * a direct autoIntegrateProducts() call racing that in-flight worker loses its claim to
 * the worker's lease and returns without integrating -- the assertion right after then
 * fails intermittently (the CI coin-flip on testAutoIntegrateProducts_BaseBranchPriority).
 * Await the release's queue entry draining before triggering explicitly.
 */
private void triggerAutoIntegrateSynchronously(ReleaseData rd) {
    awaitAutoIntegrateDrained(rd);
    ossReleaseService.autoIntegrateProducts(rd);
}

/**
 * Blocks until this release's auto-integrate queue entry clears, i.e. the create-time
 * background worker is done with it.
 *
 * <p>Fails rather than returning on timeout: proceeding anyway lets the caller's direct
 * trigger lose its claim to the still-running worker's lease and silently do nothing, which
 * shows up later as an unrelated count assertion failing for a reason the message does not
 * mention.
 */
private void awaitAutoIntegrateDrained(ReleaseData rd) {
    long deadline = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < deadline) {
        FlowControl fc = sharedReleaseService.getRelease(rd.getUuid())
            .orElseThrow().getFlowControl();
        if (fc == null || fc.autoIntegrateRequestedAt() == null) return;
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    fail("auto-integrate queue entry for release " + rd.getUuid()
        + " did not drain within 15s; the test's own trigger would race the background worker");
}

/**
 * Two component releases that are both dependencies of ONE feature set, integrated
 * concurrently, must still produce exactly ONE product release.
 *
 * <p>Drives {@code integrateFeatureSetTx} directly from two barrier-synchronised threads
 * rather than relying on the create-time workers, because the race needs executor backlog to
 * line up and is otherwise seen only rarely under full-suite load.
 */
@Test
public void testAutoIntegrateProducts_ConcurrentIntegrationCreatesOneProduct() throws Exception {
    Organization org = testInitializer.obtainOrganization();

    Component component1 = componentService.createComponent("dupRaceC1_" + UUID.randomUUID(),
        org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
        WhoUpdated.getTestWhoUpdated());
    Branch component1Branch = branchService.getBaseBranchOfComponent(component1.getUuid()).orElseThrow();

    Component component2 = componentService.createComponent("dupRaceC2_" + UUID.randomUUID(),
        org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
        WhoUpdated.getTestWhoUpdated());
    Branch component2Branch = branchService.getBaseBranchOfComponent(component2.getUuid()).orElseThrow();

    // Created BEFORE the feature set exists, so the create-time workers find no feature set
    // depending on them and no-op -- leaving this test in control of when integration runs.
    Release release1 = ossReleaseService.createRelease(ReleaseDto.builder()
        .component(component1.getUuid()).branch(component1Branch.getUuid()).org(org.getUuid())
        .status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
        .build(), WhoUpdated.getTestWhoUpdated());
    Release release2 = ossReleaseService.createRelease(ReleaseDto.builder()
        .component(component2.getUuid()).branch(component2Branch.getUuid()).org(org.getUuid())
        .status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
        .build(), WhoUpdated.getTestWhoUpdated());

    Component product = componentService.createComponent("dupRaceProd_" + UUID.randomUUID(),
        org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
        WhoUpdated.getTestWhoUpdated());
    Branch featureSetBranch = branchService.createBranch("dupRaceFeatureSet",
        product.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());

    BranchData featureSetData = branchService.getBranchData(featureSetBranch.getUuid()).get();
    branchService.updateBranch(BranchDto.builder()
        .uuid(featureSetData.getUuid())
        .name(featureSetData.getName())
        .versionSchema(featureSetData.getVersionSchema())
        .type(featureSetData.getType())
        .dependencies(List.of(
            ChildComponent.builder().uuid(component1.getUuid())
                .branch(component1Branch.getUuid()).status(StatusEnum.REQUIRED).build(),
            ChildComponent.builder().uuid(component2.getUuid())
                .branch(component2Branch.getUuid()).status(StatusEnum.REQUIRED).build()))
        .autoIntegrate(AutoIntegrateState.ENABLED)
        .build(), WhoUpdated.getTestWhoUpdated());

    ReleaseData release1Data = sharedReleaseService.getReleaseData(release1.getUuid()).get();
    ReleaseData release2Data = sharedReleaseService.getReleaseData(release2.getUuid()).get();
    awaitAutoIntegrateDrained(release1Data);
    awaitAutoIntegrateDrained(release2Data);

    // Without this the race below can be vacuous: if a create-time worker already integrated,
    // both threads would correctly skip and the test would pass with or without the fix.
    assertEquals(0, sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).size(),
        "precondition: no product release should exist before the concurrent integration");

    BranchData fsData = branchService.getBranchData(featureSetBranch.getUuid()).get();
    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
        List<Future<?>> results = new ArrayList<>();
        for (ReleaseData trigger : List.of(release1Data, release2Data)) {
            results.add(pool.submit(() -> {
                startGate.await();
                ossReleaseService.integrateFeatureSetTx(fsData, trigger);
                return null;
            }));
        }
        startGate.countDown();
        // Bare get(): ExecutionException already carries the task's cause with its stack, and
        // catching inside the task would swallow Errors (an AssertionError among them).
        for (Future<?> f : results) {
            f.get(60, TimeUnit.SECONDS);
        }
    } finally {
        pool.shutdownNow();
    }

    assertEquals(1, sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).size(),
        "two concurrent integrations of the same feature set must produce exactly ONE product "
        + "release; 2 means the duplicate check and the create are not serialized");
}

/**
 * Wider version of the two-thread case: N dependency releases integrated at once.
 *
 * <p>Guards the failure mode the lock could plausibly INTRODUCE, which the narrow test cannot
 * see -- serializing makes the losers skip, and a skip is only correct when the skipping
 * release is itself already carried by the winner's product. If the guard were keyed on
 * anything coarser than the triggering release, this fix would trade duplicate products for
 * silently dropped dependency updates, which is the worse bug. So it asserts both directions:
 * exactly one product, AND every one of the N releases present in it.
 */
@Test
public void testAutoIntegrateProducts_ManyConcurrentIntegrationsLoseNothing() throws Exception {
    // Bounded by the surefire connection-pool cap in pom.xml: each concurrent integrator pins a
    // connection while blocked on the feature-set lock, and the winner needs one more for its
    // nested REQUIRES_NEW version mint. Raising this means raising that cap.
    final int deps = 6;
    Organization org = testInitializer.obtainOrganization();

    List<ChildComponent> dependencies = new ArrayList<>();
    List<ReleaseData> triggers = new ArrayList<>();
    Set<UUID> expectedParents = new HashSet<>();
    for (int i = 0; i < deps; i++) {
        Component c = componentService.createComponent("stressC" + i + "_" + UUID.randomUUID(),
            org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
            WhoUpdated.getTestWhoUpdated());
        Branch cBranch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
        Release r = ossReleaseService.createRelease(ReleaseDto.builder()
            .component(c.getUuid()).branch(cBranch.getUuid()).org(org.getUuid())
            .status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
            .build(), WhoUpdated.getTestWhoUpdated());
        dependencies.add(ChildComponent.builder().uuid(c.getUuid()).branch(cBranch.getUuid())
            .status(StatusEnum.REQUIRED).build());
        triggers.add(sharedReleaseService.getReleaseData(r.getUuid()).get());
        expectedParents.add(r.getUuid());
    }

    Component product = componentService.createComponent("stressProd_" + UUID.randomUUID(),
        org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
        WhoUpdated.getTestWhoUpdated());
    Branch featureSetBranch = branchService.createBranch("stressFeatureSet", product.getUuid(),
        BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
    BranchData fsSeed = branchService.getBranchData(featureSetBranch.getUuid()).get();
    branchService.updateBranch(BranchDto.builder()
        .uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
        .type(fsSeed.getType()).dependencies(dependencies)
        .autoIntegrate(AutoIntegrateState.ENABLED).build(), WhoUpdated.getTestWhoUpdated());

    for (ReleaseData t : triggers) {
        awaitAutoIntegrateDrained(t);
    }
    assertEquals(0, sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).size(),
        "precondition: no product release should exist before the concurrent integration");

    BranchData fsData = branchService.getBranchData(featureSetBranch.getUuid()).get();
    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(deps);
    try {
        List<Future<?>> results = new ArrayList<>();
        for (ReleaseData trigger : triggers) {
            results.add(pool.submit(() -> {
                startGate.await();
                ossReleaseService.integrateFeatureSetTx(fsData, trigger);
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> f : results) {
            f.get(120, TimeUnit.SECONDS);
        }
    } finally {
        pool.shutdownNow();
    }

    List<ReleaseData> products = sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid());
    assertEquals(1, products.size(),
        deps + " concurrent integrations of one feature set must produce exactly ONE product release");

    Set<UUID> actualParents = products.get(0).getParentReleases().stream()
        .map(ParentRelease::getRelease).collect(Collectors.toSet());
    assertTrue(actualParents.containsAll(expectedParents),
        "the single product release must carry every dependency release; missing "
        + expectedParents.stream().filter(u -> !actualParents.contains(u)).toList()
        + " means serialization dropped an integration instead of merging it");
}

	/**
	 * A feature set whose explicit dependency is a component of ANOTHER organization. Its latest
	 * release would become a parent of the product release, which createRelease refuses -- but only
	 * after createProductRelease had minted the version, and the empty result then armed the
	 * auto-integrate retry, which fails the same way on every attempt. The parent check now runs
	 * before the mint and the feature set is skipped: no version, no product release, no retry.
	 */
	@Test
	public void testAutoIntegrateProducts_CrossOrgDependencyIsSkippedWithoutMintOrRetry() throws Exception {
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();
		Organization org = testInitializer.obtainOrganization();
		Organization otherOrg = testInitializer.obtainOrganization();
		Component own = componentService.createComponent("crossOrgOwn_" + UUID.randomUUID(), org.getUuid(),
			ComponentType.COMPONENT, "semver", "Branch.Micro", null, wu);
		Branch ownBranch = branchService.getBaseBranchOfComponent(own.getUuid()).orElseThrow();
		Component foreign = componentService.createComponent("crossOrgForeign_" + UUID.randomUUID(),
			otherOrg.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, wu);
		Branch foreignBranch = branchService.getBaseBranchOfComponent(foreign.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(foreign.getUuid())
			.branch(foreignBranch.getUuid()).org(otherOrg.getUuid()).status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0").build(), wu);

		Component product = componentService.createComponent("crossOrgProduct_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, wu);
		Branch featureSetBranch = branchService.createBranch("crossOrgFeatureSet", product.getUuid(),
			BranchType.FEATURE, wu);
		BranchData fsSeed = branchService.getBranchData(featureSetBranch.getUuid()).get();
		branchService.updateBranch(BranchDto.builder()
			.uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
			.type(fsSeed.getType())
			.dependencies(List.of(
				ChildComponent.builder().uuid(own.getUuid()).branch(ownBranch.getUuid())
					.status(StatusEnum.REQUIRED).build(),
				ChildComponent.builder().uuid(foreign.getUuid()).branch(foreignBranch.getUuid())
					.status(StatusEnum.REQUIRED).build()))
			.autoIntegrate(AutoIntegrateState.ENABLED).build(), wu);

		// The trigger: an ASSEMBLED release of the feature set's own-org dependency. Its create
		// queues the real after-commit auto-integrate worker; wait for it to settle.
		Release trigger = ossReleaseService.createRelease(ReleaseDto.builder().component(own.getUuid())
			.branch(ownBranch.getUuid()).org(org.getUuid()).status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0").build(), wu);
		FlowControl fc = null;
		long deadline = System.currentTimeMillis() + 30000;
		while (System.currentTimeMillis() < deadline) {
			fc = sharedReleaseService.getRelease(trigger.getUuid()).orElseThrow().getFlowControl();
			if (fc == null || fc.autoIntegrateRequestedAt() == null || fc.autoIntegrateFailureCount() != null) break;
			Thread.sleep(100);
		}

		assertTrue(sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).isEmpty(),
			"no product release may be created over another organization's release");
		assertTrue(versionAssignmentService.getLatestVersionAssignmentOfBranch(featureSetBranch.getUuid(), 10).isEmpty(),
			"the refusal must come before the version is minted");
		assertTrue(fc == null || (fc.autoIntegrateRequestedAt() == null && fc.autoIntegrateFailureCount() == null),
			"a cross-org dependency is permanent: the release must not stay queued with a retry armed, got " + fc);
	}
}
