/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZonedDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Deliverable;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.Variant;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.repositories.VariantRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Comprehensive test for DTrack cleanup query (findOrphanedDtrackProjects).
 * 
 * Tests the bug fix where the JSONB ? operator in Path 3 was causing:
 * "Mixing of ? parameters and other forms like ?1 is not supported"
 * 
 * Validates all three artifact paths:
 * - Path 1: Direct release artifacts
 * - Path 2a/2b: SCE artifacts (via sourceCodeEntry field or commits array)
 * - Path 3: Deliverable artifacts (via variants → deliverables)
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class DtrackCleanupQueryTest {

	@Autowired
	private BranchService branchService;
	
	@Autowired
	private ComponentService componentService;
	
	@Autowired
	private OssReleaseService ossReleaseService;
	
	@Autowired
	private ArtifactService artifactService;
	
	@Autowired
	private ArtifactRepository artifactRepository;
	
	@Autowired
	private ReleaseRepository releaseRepository;
	
	@Autowired
	private SourceCodeEntryRepository sourceCodeEntryRepository;
	
	@Autowired
	private DeliverableRepository deliverableRepository;
	
	@Autowired
	private VariantRepository variantRepository;
	
	@Autowired
	private TestInitializer testInitializer;
	
	@Autowired
	private ObjectMapper objectMapper;
	
	/**
	 * Test: Path 1 - Direct release artifacts
	 * Validates basic orphaned detection for artifacts linked directly to releases.
	 */
	@Test
	public void testPath1_DirectReleaseArtifacts() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		
		Component component = componentService.createComponent(
			"testPath1_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch activeBranch = branchService.createBranch("main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		Branch archivedBranch = branchService.createBranch("develop", component.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		
		// Create releases
		Release activeRelease = createRelease(component.getUuid(), activeBranch.getUuid(), org.getUuid(), "1.0.0");
		Release archivedRelease = createRelease(component.getUuid(), archivedBranch.getUuid(), org.getUuid(), "develop.1");
		
		// Create artifacts with DTrack projects
		Artifact activeArtifact = createArtifactWithDtrackProject(org.getUuid(), "active-artifact", "active-project-123");
		Artifact orphanedArtifact = createArtifactWithDtrackProject(org.getUuid(), "orphaned-artifact", "orphaned-project-456");
		
		// Link artifacts to releases
		linkArtifactToRelease(activeRelease, activeArtifact.getUuid());
		linkArtifactToRelease(archivedRelease, orphanedArtifact.getUuid());
		
		// Archive develop branch
		branchService.archiveBranch(archivedBranch.getUuid(), WhoUpdated.getTestWhoUpdated());
		
		// Act
		List<String> orphanedProjects = artifactService.findOrphanedDtrackProjects(org.getUuid());
		
		// Assert
		assertFalse(orphanedProjects.contains("active-project-123"), 
			"Active artifact should NOT be orphaned");
		assertTrue(orphanedProjects.contains("orphaned-project-456"), 
			"Archived artifact SHOULD be orphaned");
	}
	
	/**
	 * Test: Path 2a - SCE artifacts via sourceCodeEntry field
	 */
	@Test
	public void testPath2a_SCEArtifacts_SourceCodeEntryField() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		
		Component component = componentService.createComponent(
			"testPath2a_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch activeBranch = branchService.createBranch("main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		Branch archivedBranch = branchService.createBranch("develop", component.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		
		// Create SCEs with artifacts
		Artifact activeSceArtifact = createArtifactWithDtrackProject(org.getUuid(), "active-sce-artifact", "active-sce-project-111");
		SourceCodeEntry activeSce = createSourceCodeEntry(org.getUuid(), "commit-active", activeSceArtifact.getUuid());
		
		Artifact archivedSceArtifact = createArtifactWithDtrackProject(org.getUuid(), "archived-sce-artifact", "archived-sce-project-222");
		SourceCodeEntry archivedSce = createSourceCodeEntry(org.getUuid(), "commit-archived", archivedSceArtifact.getUuid());
		
		// Create releases pointing to SCEs
		createReleaseWithSCE(component.getUuid(), activeBranch.getUuid(), org.getUuid(), "1.0.0", activeSce.getUuid());
		createReleaseWithSCE(component.getUuid(), archivedBranch.getUuid(), org.getUuid(), "develop.1", archivedSce.getUuid());
		
		branchService.archiveBranch(archivedBranch.getUuid(), WhoUpdated.getTestWhoUpdated());
		
		// Act
		List<String> orphanedProjects = artifactService.findOrphanedDtrackProjects(org.getUuid());
		
		// Assert
		assertFalse(orphanedProjects.contains("active-sce-project-111"), 
			"Path 2a: Active SCE artifact should NOT be orphaned");
		assertTrue(orphanedProjects.contains("archived-sce-project-222"), 
			"Path 2a: Archived SCE artifact SHOULD be orphaned");
	}
	
	/**
	 * Test: Path 2b - SCE artifacts via commits array
	 */
	@Test
	public void testPath2b_SCEArtifacts_CommitsArray() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		
		Component component = componentService.createComponent(
			"testPath2b_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch activeBranch = branchService.createBranch("main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		Branch archivedBranch = branchService.createBranch("develop", component.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		
		// Create SCEs with artifacts
		Artifact activeSceArtifact = createArtifactWithDtrackProject(org.getUuid(), "active-commits-artifact", "active-commits-project-333");
		SourceCodeEntry activeSce = createSourceCodeEntry(org.getUuid(), "commit-active-2", activeSceArtifact.getUuid());
		
		Artifact archivedSceArtifact = createArtifactWithDtrackProject(org.getUuid(), "archived-commits-artifact", "archived-commits-project-444");
		SourceCodeEntry archivedSce = createSourceCodeEntry(org.getUuid(), "commit-archived-2", archivedSceArtifact.getUuid());
		
		// Create releases with SCEs in commits array
		createReleaseWithCommits(component.getUuid(), activeBranch.getUuid(), org.getUuid(), "1.0.0", activeSce.getUuid());
		createReleaseWithCommits(component.getUuid(), archivedBranch.getUuid(), org.getUuid(), "develop.1", archivedSce.getUuid());
		
		branchService.archiveBranch(archivedBranch.getUuid(), WhoUpdated.getTestWhoUpdated());
		
		// Act
		List<String> orphanedProjects = artifactService.findOrphanedDtrackProjects(org.getUuid());
		
		// Assert
		assertFalse(orphanedProjects.contains("active-commits-project-333"), 
			"Path 2b: Active commits SCE artifact should NOT be orphaned");
		assertTrue(orphanedProjects.contains("archived-commits-project-444"), 
			"Path 2b: Archived commits SCE artifact SHOULD be orphaned");
	}
	
	/**
	 * Test: Path 3 - Deliverable artifacts
	 */
	@Test
	public void testPath3_DeliverableArtifacts() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		
		Component component = componentService.createComponent(
			"testPath3_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch activeBranch = branchService.createBranch("main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		Branch archivedBranch = branchService.createBranch("develop", component.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		
		// Create releases
		Release activeRelease = createRelease(component.getUuid(), activeBranch.getUuid(), org.getUuid(), "1.0.0");
		Release archivedRelease = createRelease(component.getUuid(), archivedBranch.getUuid(), org.getUuid(), "develop.1");
		
		// Create deliverables with artifacts
		Artifact activeDelArtifact = createArtifactWithDtrackProject(org.getUuid(), "active-del-artifact", "active-del-project-555");
		Deliverable activeDeliverable = createDeliverable(org.getUuid(), "active-deliverable", activeDelArtifact.getUuid());
		createVariant(org.getUuid(), activeRelease.getUuid(), activeDeliverable.getUuid());
		
		Artifact archivedDelArtifact = createArtifactWithDtrackProject(org.getUuid(), "archived-del-artifact", "archived-del-project-666");
		Deliverable archivedDeliverable = createDeliverable(org.getUuid(), "archived-deliverable", archivedDelArtifact.getUuid());
		createVariant(org.getUuid(), archivedRelease.getUuid(), archivedDeliverable.getUuid());
		
		branchService.archiveBranch(archivedBranch.getUuid(), WhoUpdated.getTestWhoUpdated());
		
		// Act
		List<String> orphanedProjects = artifactService.findOrphanedDtrackProjects(org.getUuid());
		
		// Assert
		assertFalse(orphanedProjects.contains("active-del-project-555"), 
			"Path 3: Active deliverable artifact should NOT be orphaned");
		assertTrue(orphanedProjects.contains("archived-del-project-666"), 
			"Path 3: Archived deliverable artifact SHOULD be orphaned");
	}
	
	/**
	 * Test: Duplicate DTrack project ID across active and archived branches
	 * Verifies deduplication logic - if ANY active branch uses a project, it's not orphaned.
	 */
	@Test
	public void testDuplicateProjectId_ActiveBranchTakesPrecedence() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		
		Component component = componentService.createComponent(
			"testDuplicate_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch activeBranch = branchService.createBranch("main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		Branch archivedBranch = branchService.createBranch("develop", component.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		
		Release activeRelease = createRelease(component.getUuid(), activeBranch.getUuid(), org.getUuid(), "1.0.0");
		Release archivedRelease = createRelease(component.getUuid(), archivedBranch.getUuid(), org.getUuid(), "develop.1");
		
		// CRITICAL: Both artifacts share the SAME DTrack project ID
		String sharedProjectId = "shared-project-789";
		Artifact mainArtifact = createArtifactWithDtrackProject(org.getUuid(), "main-artifact-shared", sharedProjectId);
		Artifact developArtifact = createArtifactWithDtrackProject(org.getUuid(), "develop-artifact-shared", sharedProjectId);
		
		linkArtifactToRelease(activeRelease, mainArtifact.getUuid());
		linkArtifactToRelease(archivedRelease, developArtifact.getUuid());
		
		branchService.archiveBranch(archivedBranch.getUuid(), WhoUpdated.getTestWhoUpdated());
		
		// Act
		List<String> orphanedProjects = artifactService.findOrphanedDtrackProjects(org.getUuid());
		
		// Assert - shared project should NOT be orphaned
		assertFalse(orphanedProjects.contains(sharedProjectId), 
			"Shared DTrack project should NOT be orphaned when active branch still uses it");
	}
	
	/**
	 * Test: Artifacts marked with dtrackProjectDeleted=true should be excluded
	 * Verifies that already-deleted DTrack projects are not re-identified as orphaned.
	 * This prevents the cleanup job from repeatedly trying to delete the same projects.
	 */
	@Test
	public void testDeletedProjectsExcluded_NoReprocessing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		
		Component component = componentService.createComponent(
			"testDeletedExcluded_" + UUID.randomUUID(), 
			org.getUuid(), 
			ComponentType.COMPONENT, 
			"semver", 
			"Branch.Micro", 
			null, 
			WhoUpdated.getTestWhoUpdated()
		);
		
		Branch archivedBranch = branchService.createBranch("archived-branch", component.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		Release archivedRelease = createRelease(component.getUuid(), archivedBranch.getUuid(), org.getUuid(), "1.0.0");
		
		// Create artifact with DTrack project
		Artifact orphanedArtifact = createArtifactWithDtrackProject(org.getUuid(), "orphaned-artifact", "orphaned-project-777");
		linkArtifactToRelease(archivedRelease, orphanedArtifact.getUuid());
		
		// Archive the branch to make the artifact orphaned
		branchService.archiveBranch(archivedBranch.getUuid(), WhoUpdated.getTestWhoUpdated());
		
		// First query - should find the orphaned project
		List<String> orphanedProjectsBeforeMarking = artifactService.findOrphanedDtrackProjects(org.getUuid());
		assertTrue(orphanedProjectsBeforeMarking.contains("orphaned-project-777"), 
			"Orphaned project should be found before marking as deleted");
		
		// Mark the artifact as having a deleted DTrack project (simulating cleanup job)
		Map<String, Object> recordData = orphanedArtifact.getRecordData();
		@SuppressWarnings("unchecked")
		Map<String, Object> metrics = (Map<String, Object>) recordData.get("metrics");
		metrics.put("dtrackProjectDeleted", true);
		recordData.put("metrics", metrics);
		orphanedArtifact.setRecordData(recordData);
		orphanedArtifact.setLastUpdatedDate(ZonedDateTime.now());
		artifactRepository.save(orphanedArtifact);
		
		// Second query - should NOT find the project anymore
		List<String> orphanedProjectsAfterMarking = artifactService.findOrphanedDtrackProjects(org.getUuid());
		assertFalse(orphanedProjectsAfterMarking.contains("orphaned-project-777"), 
			"Deleted project should NOT be found after marking as deleted - prevents reprocessing");
	}
	
	// ==================== Helper Methods ====================
	
	private Release createRelease(UUID componentUuid, UUID branchUuid, UUID orgUuid, String version) throws Exception {
		ReleaseDto releaseDto = ReleaseDto.builder()
			.component(componentUuid)
			.branch(branchUuid)
			.org(orgUuid)
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version(version)
			.build();
		return ossReleaseService.createRelease(releaseDto, WhoUpdated.getTestWhoUpdated());
	}
	
	private Artifact createArtifactWithDtrackProject(UUID orgUuid, String displayIdentifier, String dtrackProjectId) throws Exception {
		Artifact artifact = new Artifact();
		artifact.setUuid(UUID.randomUUID());
		artifact.setCreatedDate(ZonedDateTime.now());
		artifact.setLastUpdatedDate(ZonedDateTime.now());
		artifact.setSchemaVersion(0);
		
		ArtifactData artifactData = new ArtifactData();
		artifactData.setOrg(orgUuid);
		artifactData.setDisplayIdentifier(displayIdentifier);
		artifactData.setType(ArtifactData.ArtifactType.BOM);
		artifactData.setBomFormat(ArtifactData.BomFormat.CYCLONEDX);
		
		// Set internalBom - required by LIST_DISTINCT_DTRACK_PROJECTS_BY_ORG query
		InternalBom internalBom = new InternalBom(UUID.randomUUID(), null);
		artifactData.setInternalBom(internalBom);
		
		ArtifactData.DependencyTrackIntegration dti = new ArtifactData.DependencyTrackIntegration();
		dti.setDependencyTrackProject(dtrackProjectId);
		artifactData.setMetrics(dti);
		
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = objectMapper.convertValue(artifactData, Map.class);
		artifact.setRecordData(recordData);
		
		return artifactRepository.save(artifact);
	}
	
	private void linkArtifactToRelease(Release release, UUID artifactUuid) throws Exception {
		Map<String, Object> recordData = release.getRecordData();
		@SuppressWarnings("unchecked")
		List<String> artifacts = (List<String>) recordData.get("artifacts");
		if (artifacts == null) {
			artifacts = new ArrayList<>();
		}
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		release.setRecordData(recordData);
		release.setLastUpdatedDate(ZonedDateTime.now());
		releaseRepository.save(release);
	}
	
	private SourceCodeEntry createSourceCodeEntry(UUID orgUuid, String commitId, UUID artifactUuid) throws Exception {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("commit", commitId);
		
		List<Map<String, String>> artifacts = new ArrayList<>();
		Map<String, String> artifactEntry = new HashMap<>();
		artifactEntry.put("artifactUuid", artifactUuid.toString());
		artifacts.add(artifactEntry);
		recordData.put("artifacts", artifacts);
		
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce);
	}
	
	private Release createReleaseWithSCE(UUID componentUuid, UUID branchUuid, UUID orgUuid, String version, UUID sceUuid) throws Exception {
		Release release = new Release();
		release.setUuid(UUID.randomUUID());
		release.setCreatedDate(ZonedDateTime.now());
		release.setLastUpdatedDate(ZonedDateTime.now());
		release.setSchemaVersion(0);
		
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("component", componentUuid.toString());
		recordData.put("branch", branchUuid.toString());
		recordData.put("org", orgUuid.toString());
		recordData.put("version", version);
		recordData.put("status", "ACTIVE");
		recordData.put("lifecycle", "ASSEMBLED");
		recordData.put("sourceCodeEntry", sceUuid.toString());
		
		release.setRecordData(recordData);
		return releaseRepository.save(release);
	}
	
	private Release createReleaseWithCommits(UUID componentUuid, UUID branchUuid, UUID orgUuid, String version, UUID sceUuid) throws Exception {
		Release release = new Release();
		release.setUuid(UUID.randomUUID());
		release.setCreatedDate(ZonedDateTime.now());
		release.setLastUpdatedDate(ZonedDateTime.now());
		release.setSchemaVersion(0);
		
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("component", componentUuid.toString());
		recordData.put("branch", branchUuid.toString());
		recordData.put("org", orgUuid.toString());
		recordData.put("version", version);
		recordData.put("status", "ACTIVE");
		recordData.put("lifecycle", "ASSEMBLED");
		
		List<String> commits = new ArrayList<>();
		commits.add(sceUuid.toString());
		recordData.put("commits", commits);
		
		release.setRecordData(recordData);
		return releaseRepository.save(release);
	}
	
	private Deliverable createDeliverable(UUID orgUuid, String identifier, UUID artifactUuid) throws Exception {
		Deliverable deliverable = new Deliverable();
		deliverable.setUuid(UUID.randomUUID());
		deliverable.setCreatedDate(ZonedDateTime.now());
		deliverable.setLastUpdatedDate(ZonedDateTime.now());
		deliverable.setSchemaVersion(0);
		
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("identifier", identifier);
		
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		
		deliverable.setRecordData(recordData);
		return deliverableRepository.save(deliverable);
	}
	
	private Variant createVariant(UUID orgUuid, UUID releaseUuid, UUID deliverableUuid) throws Exception {
		Variant variant = new Variant();
		variant.setUuid(UUID.randomUUID());
		variant.setCreatedDate(ZonedDateTime.now());
		variant.setLastUpdatedDate(ZonedDateTime.now());
		variant.setSchemaVersion(0);
		
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("release", releaseUuid.toString());
		
		// outboundDeliverables must be an array of UUID strings, not a map
		List<String> outboundDeliverables = new ArrayList<>();
		outboundDeliverables.add(deliverableUuid.toString());
		recordData.put("outboundDeliverables", outboundDeliverables);
		
		variant.setRecordData(recordData);
		return variantRepository.save(variant);
	}
}
