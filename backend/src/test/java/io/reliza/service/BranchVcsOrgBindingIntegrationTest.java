/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.BranchRepository;
import io.reliza.ws.App;
import io.reliza.service.VersionAssignmentService.GetNewVersionDto;
import io.reliza.ws.BranchDataFetcher;
import io.reliza.ws.SourceCodeEntryDataFetcher;
import io.reliza.ws.oss.TestInitializer;

/**
 * Cross-org binding of a branch's VCS repository and of the source code entry merge.
 *
 * <p>updateBranch used to store any vcs uuid; with another org's repository on its branch an
 * org could read that repository through Branch.vcsRepositoryDetails and, on its next build of a
 * commit already recorded there, merge its artifacts into the other org's source code entry and
 * re-home that entry to itself. Each test runs against two freshly created orgs. "Legacy"
 * references (stored before the check existed) are stamped straight into the row.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class BranchVcsOrgBindingIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private BranchRepository branchRepository;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private BranchDataFetcher branchDataFetcher;
	@Autowired private SourceCodeEntryDataFetcher sourceCodeEntryDataFetcher;
	@Autowired private ReleaseVersionService releaseVersionService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private JdbcTemplate jdbcTemplate;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private record OrgFixture(UUID org, UUID vcs, String vcsUri, UUID component, UUID branch) {}

	private OrgFixture orgFixture(String label) throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String uri = "github.com/cross-org-vcs-test/" + label + "-" + UUID.randomUUID();
		VcsRepository vcs = vcsRepositoryService.createVcsRepository(label + "-repo", org, uri, VcsType.GIT, WU);
		Component component = componentService.createComponent(label + "-component-" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", vcs.getUuid(), WU);
		Branch base = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		return new OrgFixture(org, vcs.getUuid(), uri, component.getUuid(), base.getUuid());
	}

	private static String commit() {
		return UUID.randomUUID().toString().replace("-", "") + "abcdefgh";
	}

	/** A release build's source code entry input, as parseSceFromReleaseCreate shapes it. */
	private static SceDto build(OrgFixture fx, String commit, UUID artifact) {
		return SceDto.builder()
				.branch(fx.branch())
				.organizationUuid(fx.org())
				.commit(commit)
				.vcsBranch("main")
				.artifacts(List.of(new SCEArtifact(artifact, fx.component())))
				.build();
	}

	/** Write a reference straight into the branch row, as data stored before the check existed. */
	private void stampLegacyBranchVcs(UUID branchUuid, UUID vcs) {
		Branch b = branchRepository.findById(branchUuid).orElseThrow();
		BranchData bd = BranchData.branchDataFromDbRecord(b);
		bd.setVcs(vcs);
		b.setRecordData(Utils.dataToRecord(bd));
		branchRepository.save(b);
	}

	/** Write a vcs straight into the component row, as data stored before the check existed. */
	private void stampComponentVcs(UUID component, UUID vcs) {
		jdbcTemplate.update("UPDATE rearm.components SET record_data = jsonb_set(record_data, '{vcs}', to_jsonb(?::text)) WHERE uuid = ?",
				vcs.toString(), component);
	}

	private Map<String, Object> branchRow(UUID uuid) {
		return jdbcTemplate.queryForMap(
				"SELECT record_data::text AS rd, revision, last_updated_date FROM rearm.branches WHERE uuid = ?", uuid);
	}

	private Map<String, Object> sceRow(UUID uuid) {
		return jdbcTemplate.queryForMap(
				"SELECT record_data::text AS rd, revision, last_updated_date FROM rearm.source_code_entries WHERE uuid = ?", uuid);
	}

	private List<UUID> sceUuidsOn(UUID vcs, String commit) {
		return jdbcTemplate.queryForList(
				"SELECT uuid FROM rearm.source_code_entries WHERE record_data->>'vcs' = ? AND record_data->>'commit' = ?",
				UUID.class, vcs.toString(), commit);
	}

	private static List<UUID> artifactsOf(SourceCodeEntryData sced) {
		return sced.getArtifacts().stream().map(SCEArtifact::artifactUuid).toList();
	}

	private Optional<VcsRepositoryData> vcsRepositoryDetails(UUID branchUuid) {
		BranchData bd = branchService.getBranchData(branchUuid).orElseThrow();
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(bd);
		return branchDataFetcher.vcsRepoOfProject(dfe);
	}

	private Optional<VcsRepositoryData> sceVcsRepository(UUID sceUuid) {
		SourceCodeEntryData sced = getSourceCodeEntryService.getSourceCodeEntryData(sceUuid).orElseThrow();
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(sced);
		return sourceCodeEntryDataFetcher.vcsRepositoryOfSourceCodeEntry(dfe);
	}

	private void stampSceJson(UUID sceUuid, String key, Object value) {
		if (null == value) {
			jdbcTemplate.update("UPDATE rearm.source_code_entries SET record_data = record_data - ? WHERE uuid = ?", key, sceUuid);
		} else {
			jdbcTemplate.update("UPDATE rearm.source_code_entries SET record_data = jsonb_set(record_data, ?::text[], to_jsonb(?::text)) WHERE uuid = ?",
					"{" + key + "}", value.toString(), sceUuid);
		}
	}

	private long vcsRepositoryCount(UUID org) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.vcs_repositories WHERE record_data->>'org' = ?",
				Long.class, org.toString());
	}

	private List<String> branchNames(UUID component) {
		return branchService.listBranchDataOfComponent(component, null).stream().map(BranchData::getName).toList();
	}

	/** getversion as the CI datafetcher calls it, reserving a PENDING release. */
	private String getversion(UUID component, SceDto sce) throws Exception {
		return releaseVersionService.getNewVersionWrapper(new GetNewVersionDto(component, "main", null, null, null, null,
				ReleaseLifecycle.PENDING, false, sce, null, VersionTypeEnum.DEV, null), WU).version();
	}

		// ----- updateBranch -----

	@Test
	public void updateBranchRefusesForeignAndMissingVcsAlikeAndWritesNothing() throws Exception {
		OrgFixture a = orgFixture("upd-a");
		OrgFixture b = orgFixture("upd-b");
		Map<String, Object> before = branchRow(b.branch());

		RelizaException foreign = assertThrows(RelizaException.class, () -> branchService.updateBranch(
				BranchDto.builder().uuid(b.branch()).vcs(a.vcs()).vcsBranch("hijack").build(), WU));
		RelizaException missing = assertThrows(RelizaException.class, () -> branchService.updateBranch(
				BranchDto.builder().uuid(b.branch()).vcs(UUID.randomUUID()).vcsBranch("hijack").build(), WU));

		assertEquals(missing.getMessage(), foreign.getMessage(), "missing and foreign vcs must be refused alike");
		assertEquals(before, branchRow(b.branch()), "a refused updateBranch must leave the branch row unchanged");
		assertEquals(b.vcs(), branchService.getBranchData(b.branch()).orElseThrow().getVcs());
	}

	@Test
	public void updateBranchStoresOwnOrgVcs() throws Exception {
		OrgFixture b = orgFixture("own-b");
		VcsRepository second = vcsRepositoryService.createVcsRepository("second", b.org(),
				"github.com/cross-org-vcs-test/second-" + UUID.randomUUID(), VcsType.GIT, WU);

		BranchData updated = branchService.updateBranch(
				BranchDto.builder().uuid(b.branch()).vcs(second.getUuid()).build(), WU);

		assertEquals(second.getUuid(), updated.getVcs());
		assertEquals(second.getUuid(), branchService.getBranchData(b.branch()).orElseThrow().getVcs());
	}

	@Test
	public void updateBranchResendingStoredVcsUnchangedIsAccepted() throws Exception {
		// The UI resends the stored vcs on every edit: a branch carrying a legacy reference
		// stays editable, and the resend introduces no new reference.
		OrgFixture a = orgFixture("resend-a");
		OrgFixture b = orgFixture("resend-b");
		stampLegacyBranchVcs(b.branch(), a.vcs());

		BranchData updated = branchService.updateBranch(
				BranchDto.builder().uuid(b.branch()).vcs(a.vcs()).vcsBranch("renamed").build(), WU);

		assertEquals("renamed", updated.getVcsBranch());
	}

	// ----- Branch.vcsRepositoryDetails -----

	@Test
	public void vcsRepositoryDetailsHidesLegacyForeignVcs() throws Exception {
		OrgFixture a = orgFixture("details-a");
		OrgFixture b = orgFixture("details-b");

		assertEquals(b.vcs(), vcsRepositoryDetails(b.branch()).orElseThrow().getUuid(),
				"the branch's own org's repository resolves");

		stampLegacyBranchVcs(b.branch(), a.vcs());
		assertTrue(vcsRepositoryDetails(b.branch()).isEmpty(),
				"a stored reference to another org's repository must resolve to null");
	}

	// ----- populateSourceCodeEntryByVcsAndCommit -----

	@Test
	public void buildOnBranchWithLegacyForeignVcsDoesNotTouchForeignSce() throws Exception {
		OrgFixture a = orgFixture("merge-a");
		OrgFixture b = orgFixture("merge-b");
		String sha = commit();
		UUID artA = UUID.randomUUID();
		UUID artB = UUID.randomUUID();
		SourceCodeEntryData sceA = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, sha, artA), true, WU).orElseThrow();
		stampLegacyBranchVcs(b.branch(), a.vcs());
		Map<String, Object> before = sceRow(sceA.getUuid());

		// No VCS data supplied: the foreign link is unusable, refused like a missing one.
		assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, artB), true, WU));
		assertEquals(before, sceRow(sceA.getUuid()), "org A's entry must be byte-for-byte unchanged");

		// VCS data supplied: treated like a branch with no repository, the build records on
		// org B's own repository for that uri, never on org A's entry.
		SceDto withUri = build(b, sha, artB);
		withUri.setUri(b.vcsUri());
		withUri.setType(VcsType.GIT);
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(withUri, true, WU).orElseThrow();

		assertNotEquals(sceA.getUuid(), sceB.getUuid(), "org B must not merge into org A's entry");
		assertEquals(b.org(), sceB.getOrg());
		assertEquals(b.vcs(), sceB.getVcs(), "org B's build binds to org B's own repository");
		assertEquals(List.of(artB), artifactsOf(sceB), "org B's entry must not inherit org A's artifacts");
		assertEquals(before, sceRow(sceA.getUuid()), "org A's entry must be byte-for-byte unchanged");
		assertEquals(List.of(sceA.getUuid()), sceUuidsOn(a.vcs(), sha));
	}

	@Test
	public void legacySquattedSceOnOwnVcsIsNeitherMergedNorRehomed() throws Exception {
		// Org A recorded org B's commit on org B's repository before the branch check existed
		// (pre-squatting). Org B's own build of that commit must refuse rather than merge into
		// org A's entry or re-home it.
		OrgFixture a = orgFixture("squat-a");
		OrgFixture b = orgFixture("squat-b");
		String sha = commit();
		UUID artA = UUID.randomUUID();
		SourceCodeEntryData squat = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, sha, artA), true, WU).orElseThrow();
		jdbcTemplate.update("UPDATE rearm.source_code_entries SET record_data = jsonb_set(record_data, '{vcs}', to_jsonb(?::text)) WHERE uuid = ?",
				b.vcs().toString(), squat.getUuid());
		Map<String, Object> before = sceRow(squat.getUuid());

		RelizaException refused = assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, UUID.randomUUID()), true, WU));

		assertEquals(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE, refused.getMessage());
		assertEquals(before, sceRow(squat.getUuid()), "org A's entry must be byte-for-byte unchanged");
		assertEquals(List.of(squat.getUuid()), sceUuidsOn(b.vcs(), sha));
	}

	@Test
	public void preSquattingNowLandsOnAttackersOwnRepository() throws Exception {
		// Org A tries to squat org B's commit: pointing its branch at org B's repository is
		// refused, so its entry for the sha lands on its own repository, and org B's later
		// build creates org B's own entry with only org B's artifacts.
		OrgFixture a = orgFixture("presquat-a");
		OrgFixture b = orgFixture("presquat-b");
		String sha = commit();
		UUID artA = UUID.randomUUID();
		UUID artB = UUID.randomUUID();

		assertThrows(RelizaException.class, () -> branchService.updateBranch(
				BranchDto.builder().uuid(a.branch()).vcs(b.vcs()).build(), WU));
		SourceCodeEntryData sceA = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, sha, artA), true, WU).orElseThrow();
		assertEquals(a.vcs(), sceA.getVcs());
		Map<String, Object> before = sceRow(sceA.getUuid());

		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, artB), true, WU).orElseThrow();

		assertNotEquals(sceA.getUuid(), sceB.getUuid());
		assertEquals(b.org(), sceB.getOrg());
		assertEquals(b.vcs(), sceB.getVcs());
		assertEquals(List.of(artB), artifactsOf(sceB));
		assertEquals(before, sceRow(sceA.getUuid()), "org A's entry must be byte-for-byte unchanged");
	}

	@Test
	public void controlSameOrgBuildsOfOneCommitStillMerge() throws Exception {
		// Control: two components of one org built from the same commit of one repository
		// (monorepo) still share and merge into one entry.
		OrgFixture b = orgFixture("control-b");
		Component second = componentService.createComponent("control-second-" + UUID.randomUUID(), b.org(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", b.vcs(), WU);
		UUID secondBranch = branchService.getBaseBranchOfComponent(second.getUuid()).orElseThrow().getUuid();
		String sha = commit();
		UUID art1 = UUID.randomUUID();
		UUID art2 = UUID.randomUUID();

		SourceCodeEntryData first = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, art1), true, WU).orElseThrow();
		SourceCodeEntryData merged = sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(
				build(new OrgFixture(b.org(), b.vcs(), b.vcsUri(), second.getUuid(), secondBranch), sha, art2),
				true, WU).orElseThrow();

		assertEquals(first.getUuid(), merged.getUuid(), "same-org builds of one commit merge");
		assertEquals(List.of(art1, art2), artifactsOf(merged));
	}

	// ----- branch creation -----

	@Test
	public void createBranchRefusesForeignAndMissingExplicitVcsAlike() throws Exception {
		OrgFixture a = orgFixture("create-a");
		OrgFixture b = orgFixture("create-b");
		var cd = getComponentService.getComponentData(b.component()).orElseThrow();
		List<String> before = branchNames(b.component());

		RelizaException foreign = assertThrows(RelizaException.class, () -> branchService.createBranch(
				"feature/foreign", cd, BranchType.FEATURE, a.vcs(), null, null, null, WU));
		RelizaException missing = assertThrows(RelizaException.class, () -> branchService.createBranch(
				"feature/missing", cd, BranchType.FEATURE, UUID.randomUUID(), null, null, null, WU));

		assertEquals(missing.getMessage(), foreign.getMessage());
		assertEquals(before, branchNames(b.component()), "a refused createBranch must create nothing");
	}

	@Test
	public void createBranchInheritsOnlyOwnOrgComponentVcs() throws Exception {
		OrgFixture a = orgFixture("inherit-a");
		OrgFixture b = orgFixture("inherit-b");

		Branch own = branchService.createBranch("feature/own", b.component(), BranchType.FEATURE, WU);
		assertEquals(b.vcs(), BranchData.branchDataFromDbRecord(own).getVcs(), "own-org component vcs is inherited");

		stampComponentVcs(b.component(), a.vcs());
		Branch legacy = branchService.createBranch("feature/legacy", b.component(), BranchType.FEATURE, WU);
		assertNull(BranchData.branchDataFromDbRecord(legacy).getVcs(),
				"a legacy foreign component vcs is dropped, not inherited");
	}

	@Test
	public void autoCreatedBranchDropsAForeignOrMissingComponentVcs() throws Exception {
		// A build on a new branch name auto-creates the branch from the component's defaults: an
		// unusable stored component vcs is dropped there too, not turned into a refusal.
		OrgFixture a = orgFixture("autobranch-a");
		OrgFixture b = orgFixture("autobranch-b");
		for (UUID stored : List.of(a.vcs(), UUID.randomUUID())) {
			stampComponentVcs(b.component(), stored);
			String name = "feature/x-" + UUID.randomUUID();
			Branch created = branchService.findBranchByName(b.component(), name, true, WU).orElseThrow();
			BranchData bd = BranchData.branchDataFromDbRecord(created);
			assertEquals(name, bd.getName());
			assertNull(bd.getVcs(), "a foreign or missing component vcs is dropped, not inherited");
		}
		// control: an own-org component vcs is still inherited
		stampComponentVcs(b.component(), b.vcs());
		Branch own = branchService.findBranchByName(b.component(), "feature/own-" + UUID.randomUUID(), true, WU).orElseThrow();
		assertEquals(b.vcs(), BranchData.branchDataFromDbRecord(own).getVcs());
	}

	@Test
	public void cloneBranchInheritsOnlyOwnOrgVcs() throws Exception {
		OrgFixture a = orgFixture("clone-a");
		OrgFixture b = orgFixture("clone-b");

		Branch own = branchService.cloneBranch(branchService.getBranchData(b.branch()).orElseThrow(),
				"clone-own", null, BranchType.FEATURE, WU);
		assertEquals(b.vcs(), BranchData.branchDataFromDbRecord(own).getVcs());

		stampLegacyBranchVcs(b.branch(), a.vcs());
		Branch legacy = branchService.cloneBranch(branchService.getBranchData(b.branch()).orElseThrow(),
				"clone-legacy", null, BranchType.FEATURE, WU);
		assertNull(BranchData.branchDataFromDbRecord(legacy).getVcs(),
				"a legacy foreign vcs is dropped, not propagated to the clone");
	}

	// ----- SourceCodeEntry.vcsRepository -----

	@Test
	public void sceVcsRepositoryIsScopedToSceOrg() throws Exception {
		OrgFixture a = orgFixture("scevcs-a");
		OrgFixture b = orgFixture("scevcs-b");
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, commit(), UUID.randomUUID()), true, WU).orElseThrow();

		assertEquals(b.vcs(), sceVcsRepository(sceB.getUuid()).orElseThrow().getUuid());

		stampSceJson(sceB.getUuid(), "vcs", a.vcs());
		assertTrue(sceVcsRepository(sceB.getUuid()).isEmpty(),
				"a stored reference to another org's repository must resolve to null");
	}

	// ----- legacy org-less entries, ordering, type default -----

	@Test
	public void legacyOrgLessSceOfAnotherOrgsBranchIsNotTakenOver() throws Exception {
		// An entry stored without an org is owned by its branch's org. One on org B's repository
		// whose branch is org A's must not be merged into or re-homed by org B's build.
		OrgFixture a = orgFixture("orgless-a");
		OrgFixture b = orgFixture("orgless-b");
		String sha = commit();
		SourceCodeEntryData legacy = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, sha, UUID.randomUUID()), true, WU).orElseThrow();
		stampSceJson(legacy.getUuid(), "vcs", b.vcs());
		stampSceJson(legacy.getUuid(), "org", null);
		Map<String, Object> before = sceRow(legacy.getUuid());

		RelizaException refused = assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, UUID.randomUUID()), true, WU));

		assertEquals(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE, refused.getMessage());
		assertEquals(before, sceRow(legacy.getUuid()), "the org-less entry must be byte-for-byte unchanged");
	}

	@Test
	public void legacyOrgLessSceOfOwnBranchStillMerges() throws Exception {
		// Control: an org-less entry whose branch is the caller's own is the caller's.
		OrgFixture b = orgFixture("orgless-own-b");
		String sha = commit();
		UUID art1 = UUID.randomUUID();
		UUID art2 = UUID.randomUUID();
		SourceCodeEntryData legacy = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, art1), true, WU).orElseThrow();
		stampSceJson(legacy.getUuid(), "org", null);

		SourceCodeEntryData merged = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, art2), true, WU).orElseThrow();

		assertEquals(legacy.getUuid(), merged.getUuid());
		assertEquals(b.org(), merged.getOrg());
		assertEquals(List.of(art1, art2), artifactsOf(merged));
	}

	@Test
	public void refusedBuildCommitsNothing() throws Exception {
		// Org B's branch carries a legacy foreign vcs, and the commit is squatted by org A on org
		// B's own repository. Org B's build supplies its uri, which would provision/relink: the
		// refusal must come first, so neither the branch nor any repository row changes.
		OrgFixture a = orgFixture("order-a");
		OrgFixture b = orgFixture("order-b");
		String sha = commit();
		SourceCodeEntryData squat = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, sha, UUID.randomUUID()), true, WU).orElseThrow();
		stampSceJson(squat.getUuid(), "vcs", b.vcs());
		stampLegacyBranchVcs(b.branch(), a.vcs());
		Map<String, Object> branchBefore = branchRow(b.branch());
		Map<String, Object> sceBefore = sceRow(squat.getUuid());
		long reposBefore = vcsRepositoryCount(b.org());

		SceDto withUri = build(b, sha, UUID.randomUUID());
		withUri.setUri(b.vcsUri());
		withUri.setType(VcsType.GIT);
		assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(withUri, true, WU));

		assertEquals(branchBefore, branchRow(b.branch()), "a refused build must not relink the branch");
		assertEquals(sceBefore, sceRow(squat.getUuid()));
		assertEquals(reposBefore, vcsRepositoryCount(b.org()), "a refused build must not provision a repository");
	}

	@Test
	public void legacyForeignVcsRelinksOnlyWhenUriAndTypeSupplied() throws Exception {
		OrgFixture a = orgFixture("relink-a");
		OrgFixture b = orgFixture("relink-b");
		stampLegacyBranchVcs(b.branch(), a.vcs());
		Map<String, Object> branchBefore = branchRow(b.branch());

		// A uri without a type cannot provision the org's own repository: refused, with a
		// message naming what to supply, and nothing written.
		SceDto noType = build(b, commit(), UUID.randomUUID());
		noType.setUri(b.vcsUri());
		RelizaException refused = assertThrows(RelizaException.class,
				() -> sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(noType, true, WU));
		assertEquals(CommonVariables.BRANCH_VCS_UNUSABLE_MESSAGE, refused.getMessage());
		assertTrue(refused.getMessage().contains("vcstype"));
		assertEquals(branchBefore, branchRow(b.branch()));

		// A missing vcs gets the same message as the foreign one.
		stampLegacyBranchVcs(b.branch(), UUID.randomUUID());
		RelizaException missing = assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, commit(), UUID.randomUUID()), true, WU));
		assertEquals(refused.getMessage(), missing.getMessage());
		stampLegacyBranchVcs(b.branch(), a.vcs());

		// With the type, the branch relinks to org B's own repository.
		SceDto withType = build(b, commit(), UUID.randomUUID());
		withType.setUri(b.vcsUri());
		withType.setType(VcsType.GIT);
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(withType, true, WU).orElseThrow();

		assertEquals(b.vcs(), sceB.getVcs());
		assertEquals(b.vcs(), branchService.getBranchData(b.branch()).orElseThrow().getVcs(),
				"the branch is relinked to org B's own repository");
	}

	// ----- entries whose owner cannot be told (no org, no branch) -----

	@Test
	public void unknownOwnerSceResolvesNoVcsRepository() throws Exception {
		// The readers scope by GetSourceCodeEntryService.ownerOrg, like SourceCodeEntry.releases:
		// an entry with no known owner belongs to no org and shows none.
		OrgFixture b = orgFixture("unknown-read-b");
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, commit(), UUID.randomUUID()), true, WU).orElseThrow();
		assertEquals(b.vcs(), sceVcsRepository(sceB.getUuid()).orElseThrow().getUuid(), "control: an owned entry resolves");
		stampSceJson(sceB.getUuid(), "org", null);
		assertEquals(b.vcs(), sceVcsRepository(sceB.getUuid()).orElseThrow().getUuid(),
				"an org-less entry is its branch's org's");
		stampSceJson(sceB.getUuid(), "branch", null);

		assertTrue(sceVcsRepository(sceB.getUuid()).isEmpty(), "an entry with no known owner resolves to null");
	}

	@Test
	public void unknownOwnerSceIsNotMergedInto() throws Exception {
		// The write rule: an entry with no known owner belongs to no org, so no build merges
		// into it or re-homes it.
		OrgFixture b = orgFixture("unknown-merge-b");
		String sha = commit();
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, UUID.randomUUID()), true, WU).orElseThrow();
		stampSceJson(sceB.getUuid(), "org", null);
		stampSceJson(sceB.getUuid(), "branch", null);
		Map<String, Object> before = sceRow(sceB.getUuid());

		RelizaException refused = assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(b, sha, UUID.randomUUID()), true, WU));
		assertEquals(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE, refused.getMessage());
		assertEquals(before, sceRow(sceB.getUuid()));
	}

	@Test
	public void storedCommitMessageLookupIgnoresUnknownOwner() throws Exception {
		OrgFixture a = orgFixture("unknown-lookup-a");
		OrgFixture b = orgFixture("unknown-lookup-b");
		String sha = commit();
		SceDto stored = build(b, sha, UUID.randomUUID());
		stored.setCommitMessage("feat: stored message");
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(stored, true, WU).orElseThrow();
		stampSceJson(sceB.getUuid(), "org", null);
		stampSceJson(sceB.getUuid(), "branch", null);
		BranchData bBranch = branchService.getBranchData(b.branch()).orElseThrow();
		BranchData aBranch = branchService.getBranchData(a.branch()).orElseThrow();

		assertTrue(sourceCodeEntryService.findStoredCommitMessage(bBranch, sha).isEmpty(),
				"an entry with no known owner is nobody's, even on the branch's own repository");
		assertTrue(sourceCodeEntryService.findStoredCommitMessage(aBranch, sha).isEmpty(),
				"another org's branch looks on its own repository and finds nothing");
		stampSceJson(sceB.getUuid(), "branch", b.branch());
		assertEquals(Optional.of("feat: stored message"), sourceCodeEntryService.findStoredCommitMessage(bBranch, sha),
				"control: an org-less entry of the branch's org is found");
	}

	@Test
	public void populateRefusesMissingOrForeignOrganization() throws Exception {
		OrgFixture a = orgFixture("dtoorg-a");
		OrgFixture b = orgFixture("dtoorg-b");
		SceDto noOrg = build(b, commit(), UUID.randomUUID());
		noOrg.setOrganizationUuid(null);
		SceDto otherOrg = build(b, commit(), UUID.randomUUID());
		otherOrg.setOrganizationUuid(a.org());

		RelizaException none = assertThrows(RelizaException.class,
				() -> sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(noOrg, true, WU));
		RelizaException foreign = assertThrows(RelizaException.class,
				() -> sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(otherOrg, true, WU));
		assertEquals(none.getMessage(), foreign.getMessage());
	}

	// ----- getversion bump lookup -----

	@Test
	public void crossOrgGetversionNeitherReadsNorMergesForeignSce() throws Exception {
		// Org A's getversion names org B's branch, repository, org and a sha org B recorded with
		// a breaking-change message. Org B's entry must be untouched, org A's release must not
		// carry org B's message, and the outcome must equal naming a branch and sha that do not
		// exist (no oracle).
		OrgFixture b = orgFixture("gv-b");
		String sha = commit();
		SceDto bBuild = build(b, sha, UUID.randomUUID());
		bBuild.setCommitMessage("feat!: secret-b breaking change");
		SourceCodeEntryData sceB = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(bBuild, true, WU).orElseThrow();
		Map<String, Object> before = sceRow(sceB.getUuid());

		OrgFixture a1 = orgFixture("gv-a1");
		getversion(a1.component(), null);
		String named = getversion(a1.component(), SceDto.builder().commit(sha).vcs(b.vcs())
				.branch(b.branch()).organizationUuid(b.org()).build());

		OrgFixture a2 = orgFixture("gv-a2");
		getversion(a2.component(), null);
		String bogus = getversion(a2.component(), SceDto.builder().commit(commit()).vcs(UUID.randomUUID())
				.branch(UUID.randomUUID()).organizationUuid(UUID.randomUUID()).build());

		assertEquals(bogus, named, "naming another org's branch and sha must behave like naming nothing");
		assertEquals(before, sceRow(sceB.getUuid()), "org B's entry must be byte-for-byte unchanged");
		ReleaseData ra = sharedReleaseService.findReleaseByComponentAndVersion(a1.component(), named)
				.map(ReleaseData::dataFromRecord).orElseThrow();
		SourceCodeEntryData sceA = getSourceCodeEntryService.getSourceCodeEntryData(ra.getSourceCodeEntry()).orElseThrow();
		assertNotEquals(sceB.getUuid(), sceA.getUuid());
		assertEquals(a1.org(), sceA.getOrg());
		assertFalse(String.valueOf(sceA.getCommitMessage()).contains("secret-b"),
				"org B's commit message must not reach org A's release");
	}

	@Test
	public void ownOrgGetversionStillBumpsFromStoredMessage() throws Exception {
		// Control: the lookup still finds the org's own stored message for the bump.
		OrgFixture a = orgFixture("gv-own");
		String sha = commit();
		SceDto stored = build(a, sha, UUID.randomUUID());
		stored.setCommitMessage("feat!: breaking change");
		// No artifacts: the release getversion reserves attaches to this entry, and creating a
		// release resolves every artifact on it.
		stored.setArtifacts(null);
		sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(stored, true, WU).orElseThrow();
		getversion(a.component(), null);
		String withStored = getversion(a.component(), SceDto.builder().commit(sha).vcs(a.vcs()).build());

		OrgFixture c = orgFixture("gv-own-control");
		getversion(c.component(), null);
		String withoutStored = getversion(c.component(), SceDto.builder().commit(commit()).vcs(c.vcs()).build());

		assertNotEquals(withoutStored, withStored, "the stored breaking-change message must drive the bump");
	}

	// ----- a build with several commits is refused whole -----

	@Test
	public void aForeignEntryAtALaterCommitRefusesTheWholeBuild() throws Exception {
		// Org A squatted org B's second commit on org B's repository. Org B's getversion with
		// both commits must refuse before recording the first one.
		OrgFixture a = orgFixture("multi-a");
		OrgFixture b = orgFixture("multi-b");
		String first = commit();
		String second = commit();
		SourceCodeEntryData squat = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, second, UUID.randomUUID()), true, WU).orElseThrow();
		stampSceJson(squat.getUuid(), "vcs", b.vcs());
		Map<String, Object> squatBefore = sceRow(squat.getUuid());
		Map<String, Object> branchBefore = branchRow(b.branch());

		SceDto head = SceDto.builder().commit(first).build();
		List<SceDto> commits = List.of(SceDto.builder().commit(first).build(), SceDto.builder().commit(second).build());
		RelizaException refused = assertThrows(RelizaException.class, () -> releaseVersionService.getNewVersionWrapper(
				new GetNewVersionDto(b.component(), "main", null, null, null, null, ReleaseLifecycle.PENDING, false,
						head, commits, VersionTypeEnum.DEV, null), WU));

		assertEquals(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE, refused.getMessage());
		assertTrue(sceUuidsOn(b.vcs(), first).isEmpty(), "the first commit must not be recorded either");
		assertEquals(squatBefore, sceRow(squat.getUuid()));
		assertEquals(branchBefore, branchRow(b.branch()));

		// no version was taken for the first commit: building it alone now succeeds
		String version = releaseVersionService.getNewVersionWrapper(new GetNewVersionDto(b.component(), "main", null, null,
				null, null, ReleaseLifecycle.PENDING, false, SceDto.builder().commit(first).build(), null,
				VersionTypeEnum.DEV, null), WU).version();
		assertTrue(sharedReleaseService.findReleaseByComponentAndVersion(b.component(), version).isPresent());
		assertEquals(1, sceUuidsOn(b.vcs(), first).size());
	}

	@Test
	public void aBranchWithoutAUsableVcsIsRefusedBeforeAnythingIsWritten() throws Exception {
		// The write path's other refusal, mirrored up front: the branch links a foreign vcs and
		// the build supplies no uri and type to relink it.
		OrgFixture a = orgFixture("novcs-multi-a");
		OrgFixture b = orgFixture("novcs-multi-b");
		stampLegacyBranchVcs(b.branch(), a.vcs());
		Map<String, Object> branchBefore = branchRow(b.branch());
		String first = commit();
		List<SceDto> commits = List.of(SceDto.builder().commit(first).build(), SceDto.builder().commit(commit()).build());

		RelizaException refused = assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.requireBuildRecordable(branchService.getBranchData(b.branch()).orElseThrow(), commits));
		assertEquals(CommonVariables.BRANCH_VCS_UNUSABLE_MESSAGE, refused.getMessage());
		RelizaException viaGetversion = assertThrows(RelizaException.class, () -> releaseVersionService.getNewVersionWrapper(
				new GetNewVersionDto(b.component(), "main", null, null, null, null, ReleaseLifecycle.PENDING, false,
						null, commits, VersionTypeEnum.DEV, null), WU));
		assertEquals(CommonVariables.BRANCH_VCS_UNUSABLE_MESSAGE, viaGetversion.getMessage());
		assertEquals(branchBefore, branchRow(b.branch()));
		assertTrue(sceUuidsOn(b.vcs(), first).isEmpty());

		// with the uri and type on the first commit the same build goes through
		SceDto relinking = SceDto.builder().commit(first).uri(b.vcsUri()).type(VcsType.GIT).build();
		releaseVersionService.getNewVersionWrapper(new GetNewVersionDto(b.component(), "main", null, null, null, null,
				ReleaseLifecycle.PENDING, false, null, List.of(relinking, commits.get(1)), VersionTypeEnum.DEV, null), WU);
		assertEquals(b.vcs(), branchService.getBranchData(b.branch()).orElseThrow().getVcs());
		assertEquals(1, sceUuidsOn(b.vcs(), first).size());
	}

	@Test
	public void aRelinkingFirstCommitIsFollowedByTheLaterCommitsCheck() throws Exception {
		// Org B's branch links a legacy foreign vcs. The first commit carries org B's uri and
		// type, so it would relink the branch to org B's repository, where org A squatted the
		// second commit: refused up front, with no relink and no entry written.
		OrgFixture a = orgFixture("relink-multi-a");
		OrgFixture b = orgFixture("relink-multi-b");
		String first = commit();
		String second = commit();
		SourceCodeEntryData squat = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(build(a, second, UUID.randomUUID()), true, WU).orElseThrow();
		stampSceJson(squat.getUuid(), "vcs", b.vcs());
		stampLegacyBranchVcs(b.branch(), a.vcs());
		Map<String, Object> branchBefore = branchRow(b.branch());

		SceDto relinking = SceDto.builder().commit(first).uri(b.vcsUri()).type(VcsType.GIT).build();
		List<SceDto> commits = List.of(relinking, SceDto.builder().commit(second).build());
		RelizaException refused = assertThrows(RelizaException.class, () -> sourceCodeEntryService
				.requireBuildRecordable(branchService.getBranchData(b.branch()).orElseThrow(), commits));
		assertEquals(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE, refused.getMessage());

		RelizaException viaGetversion = assertThrows(RelizaException.class, () -> releaseVersionService.getNewVersionWrapper(
				new GetNewVersionDto(b.component(), "main", null, null, null, null, ReleaseLifecycle.PENDING, false,
						null, commits, VersionTypeEnum.DEV, null), WU));
		assertEquals(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE, viaGetversion.getMessage());
		assertEquals(branchBefore, branchRow(b.branch()), "the branch must not be relinked");
		assertTrue(sceUuidsOn(b.vcs(), first).isEmpty(), "the first commit must not be recorded");
	}
}
