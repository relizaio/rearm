/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.PullRequestData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.service.oss.OssReleaseService.ProductParentsCheck;
import io.reliza.service.oss.OssReleaseService.ProductParentsLogging;
import io.reliza.ws.App;
import io.reliza.ws.PullRequestDataFetcher;
import io.reliza.ws.ReleaseDatafetcher;
import io.reliza.ws.oss.TestInitializer;

/**
 * A release may only reference source code entries (sourceCodeEntry, commits[]) and parent releases
 * that exist in its own organization. Before this, updateRelease and addReleaseManual stored any
 * uuid after a write check on the release alone, and the Release resolvers then showed another
 * organization's commit (hash, message, author, email) through it.
 *
 * <p>Write side: every newly supplied reference is checked, and a refusal stores nothing. Read
 * side: a reference already stored before the check resolves as if it were missing.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseReferenceOrgValidationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private PullRequestDataFetcher pullRequestDataFetcher;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private VersionAssignmentService versionAssignmentService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	/** An org with one component, its base branch and a repository to record commits on. */
	private record Rig(UUID org, Component component, Branch branch, UUID vcs) {}

	private Rig rig() throws RelizaException {
		return rigIn(testInitializer.obtainOrganization().getUuid());
	}

	/** {@link #rig()} in a given org -- used for the shared external-components org. */
	private Rig rigIn(UUID org) throws RelizaException {
		return rigIn(org, ComponentType.COMPONENT);
	}

	private Rig rigIn(UUID org, ComponentType type) throws RelizaException {
		Component component = componentService.createComponent("refval_" + UUID.randomUUID(), org,
				type, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org,
				"github.com/example/refval-" + UUID.randomUUID(), VcsType.GIT, WU);
		return new Rig(org, component, branch, vcs);
	}

	private UUID sce(Rig r) throws RelizaException {
		return sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(r.branch().getUuid()).vcs(r.vcs()).organizationUuid(r.org())
				.commit(UUID.randomUUID().toString().replace("-", "") + "bbbbbbbb").build(), WU)
				.getUuid();
	}

	private ReleaseDto.ReleaseDtoBuilder releaseDto(Rig r, String version) {
		return ReleaseDto.builder().component(r.component().getUuid()).org(r.org())
				.branch(r.branch().getUuid()).version(version).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT);
	}

	/** A DRAFT release, so updateRelease's default DRAFT_ONLY strength accepts reference changes. */
	private UUID draft(Rig r, String version) throws RelizaException {
		return ossReleaseService.createRelease(releaseDto(r, version).build(), WU).getUuid();
	}

	private ReleaseData stored(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow();
	}

	private static List<UUID> parentUuids(ReleaseData rd) {
		return rd.getParentReleases().stream().map(ParentRelease::getRelease).toList();
	}

	/**
	 * Stores references behind the service's back -- the legacy rows written before the check, and
	 * the only way to set them up now that every write path refuses them.
	 */
	private void forceReferences(UUID release, UUID sourceCodeEntry, List<UUID> commits) {
		Release r = sharedReleaseService.getRelease(release).orElseThrow();
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		rd.setSourceCodeEntry(sourceCodeEntry);
		rd.setCommits(commits);
		ossReleaseService.saveRelease(r, rd, WU);
	}

	private static DgsDataFetchingEnvironment dfeFor(Object source) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(source);
		return dfe;
	}

	// ---------------- updateRelease ----------------

	@Test
	public void updateRefusesANonexistentSourceCodeEntry() throws RelizaException {
		Rig a = rig();
		UUID release = draft(a, "1.0.0");
		UUID nonexistent = UUID.randomUUID();

		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release).sourceCodeEntry(nonexistent).build(), WU));
		assertTrue(re.getMessage().contains(nonexistent.toString()), re.getMessage());
		assertNull(stored(release).getSourceCodeEntry(), "a refused reference must not be stored");
	}

	@Test
	public void updateRefusesAnotherOrgsSourceCodeEntry() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		UUID release = draft(a, "1.0.0");
		UUID foreign = sce(b);

		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release).sourceCodeEntry(foreign).build(), WU));
		assertTrue(re.getMessage().contains(foreign.toString()), re.getMessage());
		assertNull(stored(release).getSourceCodeEntry(), "a refused reference must not be stored");
	}

	@Test
	public void updateRefusesTheAllZeroPlaceholderAsASourceCodeEntry() throws RelizaException {
		// getSceDataList answers the all-zero uuid with a synthetic "details unavailable" entry; it
		// must not pass for a real one.
		Rig a = rig();
		UUID release = draft(a, "1.0.0");

		assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release).sourceCodeEntry(SourceCodeEntryData.NULL_SCE_UUID).build(), WU));
		assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release).commits(List.of(SourceCodeEntryData.NULL_SCE_UUID)).build(), WU));
		assertNull(stored(release).getSourceCodeEntry());
		assertTrue(null == stored(release).getCommits() || stored(release).getCommits().isEmpty());
	}

	@Test
	public void updateRefusesAnotherOrgsCommitAndStoresNoneOfTheList() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		UUID release = draft(a, "1.0.0");
		UUID own = sce(a);
		UUID foreign = sce(b);

		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release).commits(List.of(own, foreign)).build(), WU));
		assertTrue(re.getMessage().contains(foreign.toString()) && !re.getMessage().contains(own.toString()),
				"the refusal names only the offending entry: " + re.getMessage());
		List<UUID> commits = stored(release).getCommits();
		assertTrue(null == commits || commits.isEmpty(), "a refused update must store none of the list: " + commits);
	}

	@Test
	public void updateRefusesAnotherOrgsParentRelease() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		UUID release = draft(a, "1.0.0");
		UUID foreignParent = draft(b, "1.0.0");

		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release)
					.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(foreignParent, null))).build(), WU));
		assertTrue(re.getMessage().contains(foreignParent.toString()), re.getMessage());
		assertTrue(stored(release).getParentReleases().isEmpty(), "a refused parent must not be stored");
	}

	@Test
	public void updateRefusesANonexistentParentRelease() throws RelizaException {
		Rig a = rig();
		UUID release = draft(a, "1.0.0");

		assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(release)
					.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(UUID.randomUUID(), null))).build(), WU));
		assertTrue(stored(release).getParentReleases().isEmpty());
	}

	@Test
	public void updateStoresSameOrgReferences() throws RelizaException {
		Rig a = rig();
		UUID release = draft(a, "1.0.0");
		UUID parent = draft(a, "0.9.0");
		UUID head = sce(a);
		UUID commit = sce(a);

		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).sourceCodeEntry(head)
				.commits(List.of(head, commit))
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(parent, null))).build(), WU);
		ReleaseData rd = stored(release);
		assertEquals(head, rd.getSourceCodeEntry());
		assertEquals(List.of(head, commit), rd.getCommits());
		assertEquals(List.of(parent), parentUuids(rd));
	}

	@Test
	public void anUnchangedStoredReferenceDoesNotBlockAnUnrelatedUpdate() throws RelizaException {
		// A legacy row already pointing at another org's entry: echoing it back (as the UI does with
		// the whole release) must not refuse the edit the caller actually made. A NEW foreign entry in
		// the same update is still refused.
		Rig a = rig();
		Rig b = rig();
		UUID release = draft(a, "1.0.0");
		UUID legacyForeign = sce(b);
		forceReferences(release, legacyForeign, List.of(legacyForeign));

		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).sourceCodeEntry(legacyForeign)
				.commits(List.of(legacyForeign)).notes("edited").build(), WU);
		assertEquals("edited", stored(release).getNotes());

		UUID newForeign = sce(b);
		assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(release).commits(List.of(legacyForeign, newForeign)).build(), WU));
		assertEquals(List.of(legacyForeign), stored(release).getCommits());
	}

	// ---------------- createRelease (addReleaseManual) ----------------

	@Test
	public void createRefusesAnotherOrgsSourceCodeEntryAndCreatesNothing() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		UUID foreign = sce(b);

		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
				releaseDto(a, "1.0.0").sourceCodeEntry(foreign).build(), WU));
		assertTrue(re.getMessage().contains(foreign.toString()), re.getMessage());
		assertTrue(sharedReleaseService.listReleaseDatasOfComponent(a.component().getUuid(), 10, 0).isEmpty(),
				"no release may be created over a refused reference");
	}

	@Test
	public void createRefusesAnotherOrgsCommitAndParent() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		UUID foreignCommit = sce(b);
		UUID foreignParent = draft(b, "1.0.0");

		assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
				releaseDto(a, "1.0.0").commits(List.of(foreignCommit)).build(), WU));
		assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
				releaseDto(a, "1.0.1").parentReleases(
						List.of(ParentRelease.minimalParentReleaseFactory(foreignParent, null))).build(), WU));
		assertTrue(sharedReleaseService.listReleaseDatasOfComponent(a.component().getUuid(), 10, 0).isEmpty());
	}

	@Test
	public void createStoresSameOrgReferences() throws RelizaException {
		Rig a = rig();
		UUID parent = draft(a, "0.9.0");
		UUID head = sce(a);

		UUID release = ossReleaseService.createRelease(releaseDto(a, "1.0.0").sourceCodeEntry(head)
				.commits(List.of(head))
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(parent, null))).build(), WU)
				.getUuid();
		ReleaseData rd = stored(release);
		assertEquals(head, rd.getSourceCodeEntry());
		assertEquals(List.of(head), rd.getCommits());
		assertEquals(List.of(parent), parentUuids(rd));
	}

	// ---------------- read side ----------------

	@Test
	public void aStoredForeignEntryIsNotShownThroughTheRelease() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		UUID release = draft(a, "1.0.0");
		UUID own = sce(a);
		UUID foreign = sce(b);
		forceReferences(release, foreign, List.of(foreign, own));
		ReleaseData rd = stored(release);

		assertNull(releaseDatafetcher.sceOfReleaseWithDep(dfeFor(rd)),
				"another org's sourceCodeEntry must resolve as missing");
		List<SourceCodeEntryData> commits = releaseDatafetcher.commitsOfReleaseWithDep(dfeFor(rd));
		assertEquals(List.of(own), commits.stream().map(SourceCodeEntryData::getUuid).toList(),
				"another org's commit must be dropped, the release's own kept");
	}

	@Test
	public void aSameOrgEntryIsShownAsBefore() throws RelizaException {
		Rig a = rig();
		UUID head = sce(a);
		UUID release = ossReleaseService.createRelease(releaseDto(a, "1.0.0").sourceCodeEntry(head)
				.commits(List.of(head)).build(), WU).getUuid();
		ReleaseData rd = stored(release);

		SourceCodeEntryData shown = releaseDatafetcher.sceOfReleaseWithDep(dfeFor(rd));
		assertEquals(head, shown.getUuid());
		List<SourceCodeEntryData> commits = releaseDatafetcher.commitsOfReleaseWithDep(dfeFor(rd));
		assertEquals(List.of(head), commits.stream().map(SourceCodeEntryData::getUuid).toList());
	}

	// ---------------- the shared external-components org ----------------

	@Test
	public void updateAcceptsAnEntryAndAParentOfTheExternalOrg() throws RelizaException {
		// Consistent with the org-scoped release lookup, the dependency unwind and the ticket
		// lookup, which all admit the external org next to the release's own.
		Rig a = rig();
		Rig external = rigIn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		UUID release = draft(a, "1.0.0");
		UUID externalSce = sce(external);
		UUID externalParent = draft(external, "1.0.0");

		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).sourceCodeEntry(externalSce)
				.commits(List.of(externalSce))
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(externalParent, null))).build(), WU);
		ReleaseData rd = stored(release);
		assertEquals(externalSce, rd.getSourceCodeEntry());
		assertEquals(List.of(externalSce), rd.getCommits());
		assertEquals(List.of(externalParent), parentUuids(rd));
		assertEquals(externalSce, releaseDatafetcher.sceOfReleaseWithDep(dfeFor(rd)).getUuid(),
				"an external-org entry is shown, like an own-org one");
	}

	// ---------------- the org-scoped batch read ----------------

	@Test
	public void referenceableReadKeepsInputOrderAndDropsMissingForeignAndPlaceholder() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		Rig external = rigIn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		UUID own1 = sce(a);
		UUID own2 = sce(a);
		UUID foreign = sce(b);
		UUID ext = sce(external);

		List<UUID> requested = Arrays.asList(own2, foreign, UUID.randomUUID(), ext, null,
				SourceCodeEntryData.NULL_SCE_UUID, own1, own2);
		assertEquals(List.of(own2, ext, own1, own2),
				getSourceCodeEntryService.getReferenceableSceDataList(requested, a.org()).stream()
					.map(SourceCodeEntryData::getUuid).toList(),
				"own and external entries in input order (duplicates kept); missing, foreign, null and "
				+ "the placeholder dropped");
		assertTrue(getSourceCodeEntryService.getReferenceableSceData(foreign, a.org()).isEmpty());
		assertEquals(foreign, getSourceCodeEntryService.getReferenceableSceData(foreign, b.org()).orElseThrow().getUuid());
	}

	@Test
	public void pullRequestCommitDetailsShowOnlyThePrsOrg() throws RelizaException {
		// PullRequest.commitDetails is the other parent SourceCodeEntry.releases resolves from.
		Rig a = rig();
		Rig b = rig();
		UUID own = sce(a);
		UUID foreign = sce(b);
		PullRequestData prd = new PullRequestData();
		prd.setOrg(a.org());
		prd.setCommits(new LinkedList<>(List.of(foreign, own)));

		assertEquals(List.of(own), pullRequestDataFetcher.commitDetails(dfeFor(prd)).stream()
				.map(SourceCodeEntryData::getUuid).toList());
	}

	// ---------------- createRelease: the rebuild / PENDING arms ----------------

	@Test
	public void aPendingCompletionReSendingAStoredLegacyReferenceIsNotBlocked() throws RelizaException {
		// The PENDING arm goes through updateRelease, which checks only what differs from the stored
		// release; the full-list check of a fresh create must not run there.
		Rig a = rig();
		Rig b = rig();
		UUID pending = ossReleaseService.createRelease(releaseDto(a, "1.0.0")
				.lifecycle(ReleaseLifecycle.PENDING).build(), WU).getUuid();
		UUID legacyForeign = sce(b);
		forceReferences(pending, legacyForeign, List.of(legacyForeign));

		ossReleaseService.createRelease(releaseDto(a, "1.0.0").sourceCodeEntry(legacyForeign)
				.commits(List.of(legacyForeign)).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU);
		assertEquals(ReleaseLifecycle.ASSEMBLED, stored(pending).getLifecycle());

		// A new foreign entry on the same path is still refused, by updateRelease.
		UUID pending2 = ossReleaseService.createRelease(releaseDto(a, "2.0.0")
				.lifecycle(ReleaseLifecycle.PENDING).build(), WU).getUuid();
		UUID newForeign = sce(b);
		assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(releaseDto(a, "2.0.0")
				.sourceCodeEntry(newForeign).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU));
		assertNull(stored(pending2).getSourceCodeEntry());
	}

	// ---------------- product releases: checked before the version is minted ----------------

	@Test
	public void aProductReleaseOverAnotherOrgsParentMintsNoVersion() throws RelizaException {
		Rig a = rig();
		Rig b = rig();
		Rig product = rigIn(a.org(), ComponentType.PRODUCT);
		BranchData featureSet = branchService.getBranchData(product.branch().getUuid()).orElseThrow();
		UUID own = ossReleaseService.createRelease(releaseDto(a, "1.0.0")
				.lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		UUID foreign = ossReleaseService.createRelease(releaseDto(b, "1.0.0")
				.lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		List<ParentRelease> parents = List.of(ParentRelease.minimalParentReleaseFactory(own, null),
				ParentRelease.minimalParentReleaseFactory(foreign, null));

		assertTrue(ossReleaseService.createProductRelease(featureSet, a.org(), parents).isEmpty());
		assertTrue(versionAssignmentService.getLatestVersionAssignmentOfBranch(featureSet.getUuid(), 10).isEmpty(),
				"no version may be minted for a product release that is refused anyway");
		assertTrue(sharedReleaseService.listReleaseDataOfBranch(featureSet.getUuid()).isEmpty());

		// The three outcomes: a foreign parent is permanent (skip, no retry) and wins over a missing
		// one, which may be transient (not minted, but retried).
		UUID missing = UUID.randomUUID();
		assertEquals(ProductParentsCheck.REFERENCEABLE, checkProductParents(featureSet, a.org(), own));
		assertEquals(ProductParentsCheck.FOREIGN, checkProductParents(featureSet, a.org(), own, foreign));
		assertEquals(ProductParentsCheck.MISSING, checkProductParents(featureSet, a.org(), own, missing));
		assertEquals(ProductParentsCheck.FOREIGN, checkProductParents(featureSet, a.org(), missing, foreign));
		assertTrue(ossReleaseService.createProductRelease(featureSet, a.org(),
				List.of(ParentRelease.minimalParentReleaseFactory(missing, null))).isEmpty());
		assertTrue(versionAssignmentService.getLatestVersionAssignmentOfBranch(featureSet.getUuid(), 10).isEmpty(),
				"a missing parent mints nothing either");
	}

	private ProductParentsCheck checkProductParents(BranchData featureSet, UUID org, UUID... parents) {
		return ossReleaseService.checkProductParents(featureSet.getUuid(), org, Arrays.stream(parents)
				.map(u -> ParentRelease.minimalParentReleaseFactory(u, null)).toList(), "test",
				ProductParentsLogging.ALWAYS);
	}

	@Test
	public void aRebuildOrPendingArmChecksAddedParentsBeforeTheCycleChecks() throws RelizaException {
		// The PENDING arm leaves source code entries to updateRelease, but a parent it ADDS is checked
		// up front like on a fresh create, so a foreign parent's ancestry is never walked and the
		// refusal is the uniform one.
		Rig a = rig();
		Rig b = rig();
		UUID pending = ossReleaseService.createRelease(releaseDto(a, "1.0.0")
				.lifecycle(ReleaseLifecycle.PENDING).build(), WU).getUuid();
		UUID foreignParent = draft(b, "1.0.0");

		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
				releaseDto(a, "1.0.0").lifecycle(ReleaseLifecycle.ASSEMBLED)
					.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(foreignParent, null))).build(), WU));
		assertTrue(re.getMessage().startsWith("Parent release not found in this organization")
				&& re.getMessage().contains(foreignParent.toString()), re.getMessage());
		assertTrue(stored(pending).getParentReleases().isEmpty());
		assertEquals(ReleaseLifecycle.PENDING, stored(pending).getLifecycle());
	}

	@Test
	public void validateReleaseReferencesRefusesANullOrgAsUserInput() throws RelizaException {
		// No org on a fresh create that sets references: a RelizaException, not a 500.
		Rig a = rig();
		Rig b = rig();
		UUID foreign = sce(b);
		assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(releaseDto(a, "1.0.0")
				.org(null).sourceCodeEntry(foreign).build(), WU));
	}

	// ---------------- the refusal does not reveal which uuid exists elsewhere ----------------

	/** The refused uuids as the message lists them, e.g. "[a, b]". */
	private static String listed(UUID... uuids) {
		return Arrays.asList(uuids).toString();
	}

	@Test
	public void theRefusalListsMissingAndForeignInTheOrderSentOnCreate() throws RelizaException {
		// Both orders, so a foreign uuid cannot be told from a missing one by its position. The
		// fresh-create arm is the service path addReleaseManual goes through.
		Rig a = rig();
		Rig b = rig();
		UUID foreignParent = draft(b, "1.0.0");
		UUID missingParent = UUID.randomUUID();
		UUID foreignSce = sce(b);
		UUID missingSce = UUID.randomUUID();
		for (UUID[] order : List.of(new UUID[] {foreignParent, missingParent}, new UUID[] {missingParent, foreignParent})) {
			RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
					releaseDto(a, "1.0.0").parentReleases(Arrays.stream(order)
						.map(u -> ParentRelease.minimalParentReleaseFactory(u, null)).toList()).build(), WU));
			assertTrue(re.getMessage().endsWith(listed(order)), re.getMessage());
		}
		for (UUID[] order : List.of(new UUID[] {foreignSce, missingSce}, new UUID[] {missingSce, foreignSce})) {
			RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.createRelease(
					releaseDto(a, "1.0.0").commits(List.of(order)).build(), WU));
			assertTrue(re.getMessage().endsWith(listed(order)), re.getMessage());
		}
		assertTrue(sharedReleaseService.listReleaseDatasOfComponent(a.component().getUuid(), 10, 0).isEmpty());
	}

	@Test
	public void theRefusalListsMissingAndForeignInTheOrderSentOnUpdate() throws RelizaException {
		// updateRelease checks only what is new against the stored release; that delta must keep the
		// caller's order too (it used to come from a HashSet diff). Several fresh uuid pairs, so a
		// hash order that happened to agree once does not pass by luck.
		Rig a = rig();
		Rig b = rig();
		UUID release = draft(a, "1.0.0");
		UUID ownParent = draft(a, "0.9.0");
		UUID ownCommit = sce(a);
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).commits(List.of(ownCommit))
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(ownParent, null))).build(), WU);
		for (int i = 0; i < 4; i++) {
			UUID foreignParent = draft(b, "1.0." + i);
			UUID missingParent = UUID.randomUUID();
			UUID foreignSce = sce(b);
			UUID missingSce = UUID.randomUUID();
			for (UUID[] order : List.of(new UUID[] {foreignParent, missingParent}, new UUID[] {missingParent, foreignParent})) {
				List<ParentRelease> parents = new LinkedList<>(List.of(ParentRelease.minimalParentReleaseFactory(ownParent, null)));
				Arrays.stream(order).forEach(u -> parents.add(ParentRelease.minimalParentReleaseFactory(u, null)));
				RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
						ReleaseDto.builder().uuid(release).parentReleases(parents).build(), WU));
				assertTrue(re.getMessage().endsWith(listed(order)), re.getMessage());
			}
			for (UUID[] order : List.of(new UUID[] {foreignSce, missingSce}, new UUID[] {missingSce, foreignSce})) {
				List<UUID> commits = new LinkedList<>(List.of(ownCommit));
				commits.addAll(List.of(order));
				RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
						ReleaseDto.builder().uuid(release).commits(commits).build(), WU));
				assertTrue(re.getMessage().endsWith(listed(order)), re.getMessage());
			}
		}
		assertEquals(List.of(ownParent), parentUuids(stored(release)));
		assertEquals(List.of(ownCommit), stored(release).getCommits());
	}

	// ---------------- the hourly rate limit of the cross-org product ERROR ----------------

	@Test
	public void theForeignParentErrorIsRateLimitedPerFeatureSetAndRefusedSet() {
		Map<String, Instant> last = new HashMap<>();
		Instant t0 = Instant.parse("2026-10-02T10:00:00Z");
		String key = "fs|a,b";
		assertTrue(OssReleaseService.shouldReportForeignParents(last, key, ProductParentsLogging.RATE_LIMITED, t0));
		assertFalse(OssReleaseService.shouldReportForeignParents(last, key, ProductParentsLogging.RATE_LIMITED,
				t0.plus(Duration.ofMinutes(59))), "a repeat within the hour is suppressed");
		assertTrue(OssReleaseService.shouldReportForeignParents(last, "fs|a,c", ProductParentsLogging.RATE_LIMITED,
				t0.plus(Duration.ofMinutes(1))), "a different refused set is its own key");
		assertTrue(OssReleaseService.shouldReportForeignParents(last, key, ProductParentsLogging.ALWAYS,
				t0.plus(Duration.ofMinutes(2))), "the on-demand trigger always reports");
		assertFalse(OssReleaseService.shouldReportForeignParents(last, key, ProductParentsLogging.RATE_LIMITED,
				t0.plus(Duration.ofMinutes(3))), "an ALWAYS report does not reset the automatic clock");
		assertTrue(OssReleaseService.shouldReportForeignParents(last, key, ProductParentsLogging.RATE_LIMITED,
				t0.plus(OssReleaseService.FOREIGN_PARENT_LOG_INTERVAL).plusSeconds(1)), "reported again once the hour is up");
	}
}
