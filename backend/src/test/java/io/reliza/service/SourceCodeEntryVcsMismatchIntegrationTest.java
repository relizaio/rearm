/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepository;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SceDto;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Integration test for the VCS mismatch check in
 * {@link SourceCodeEntryService#populateSourceCodeEntryByVcsAndCommit}.
 *
 * <p>The check compares the SCE's supplied VCS URI against the branch's linked
 * repository via {@link io.reliza.common.Utils#uriEquals}. That comparison
 * used to self-compare and always return true, so the check never fired. It is
 * deliberately a warn (not a rejection): URI forms uriEquals cannot reconcile
 * -- Azure DevOps https/ssh split forms, CycloneDX pedigree commit URLs -- are
 * legitimately in flight on this path, and the addrelease flows they arrive
 * through worked historically. These tests pin both sides of that contract:
 * a URI pointing at a different repository still creates the SCE bound to the
 * branch's linked repository, and dirty spellings of the linked repository
 * (scheme, git@, trailing .git, scp colon) resolve without noise.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SourceCodeEntryVcsMismatchIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private TestInitializer testInitializer;

	private static final String LINKED_REPO_URI = "github.com/uriequals-test/repo-a";
	private static final String OTHER_REPO_URI = "github.com/uriequals-test/repo-b";
	private static final String COMMIT = "a4ec2448bf9fd5b6981914781b015b426a134c98";

	private record Fixture(UUID orgUuid, UUID branchUuid, UUID vcsUuid) {}

	private Fixture fixture(String componentName) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();
		VcsRepository vcsRepo = vcsRepositoryService.createVcsRepository(
				"repo-a", org.getUuid(), LINKED_REPO_URI, VcsType.GIT, wu);
		Component component = componentService.createComponent(componentName, org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", vcsRepo.getUuid(), wu);
		Branch baseBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		return new Fixture(org.getUuid(), baseBranch.getUuid(), vcsRepo.getUuid());
	}

	private SceDto sceDto(Fixture fx, String uri) {
		return SceDto.builder()
				.branch(fx.branchUuid())
				.organizationUuid(fx.orgUuid())
				.commit(COMMIT)
				.vcsBranch("main")
				.uri(uri)
				.type(VcsType.GIT)
				.build();
	}

	@Test
	public void mismatchedUriStillBindsSceToBranchLinkedRepository() throws Exception {
		Fixture fx = fixture("uriequals-mismatch-component");
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();

		Optional<SourceCodeEntryData> osced = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(sceDto(fx, OTHER_REPO_URI), true, wu);

		assertNotNull(osced);
		assertTrue(osced.isPresent(), "SCE was not created for a mismatched URI");
		assertEquals(fx.vcsUuid(), osced.get().getVcs(),
				"mismatched-URI SCE must bind to the branch's linked repository");
	}

	@Test
	public void acceptsSceWithDirtySpellingOfLinkedRepository() throws Exception {
		Fixture fx = fixture("uriequals-dirty-spelling-component");
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();

		String dirtyUri = "git@" + LINKED_REPO_URI.replaceFirst("/", ":") + ".git";
		Optional<SourceCodeEntryData> osced = sourceCodeEntryService
				.populateSourceCodeEntryByVcsAndCommit(sceDto(fx, dirtyUri), true, wu);

		assertNotNull(osced);
		assertTrue(osced.isPresent(), "SCE was not created for a dirty spelling of the linked repository");
	}
}
