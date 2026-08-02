/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.WhoUpdated;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.VcsRepository;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.service.ComponentService.ComponentResolution;
import io.reliza.service.ComponentService.ComponentResolutionStatus;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins VCS-based component resolution against the duplicate-registration
 * incident (30+ components for one (vcs, repoPath), +1 per CI run):
 *
 * <ol>
 *   <li>URI-form stability: resolve and create must canonicalize identically.
 *       The resolve side used to apply less canonicalization than the create
 *       side, so the ordinary '.git' clone URL never resolved the component it
 *       had just created -- while the create side kept finding the one VCS row
 *       and minting a new component against it, silently, every build.</li>
 *   <li>Tri-state outcomes: AMBIGUOUS is data, not an exception to be caught --
 *       the create-on-missing callers used to treat every resolution failure as
 *       "not found", turning two duplicates into N.</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ComponentVcsResolutionTest {

	@Autowired private ComponentService componentService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private TestInitializer testInitializer;

	private static final String REPO_PATH = "svc/alpha";

	private record Fixture(UUID orgUuid, UUID vcsUuid, String rawGitUri, String bareUri) {}

	/** One VCS row (stored canonical), created through the same path CI creation uses. */
	private Fixture fixture() {
		Organization org = testInitializer.obtainOrganization();
		String slug = "it-dupres-" + UUID.randomUUID().toString().substring(0, 8);
		String rawGitUri = "https://github.com/it-dup/" + slug + ".git";
		VcsRepository vcs = vcsRepositoryService
				.getVcsRepositoryByUri(org.getUuid(), rawGitUri, null, null, true, WhoUpdated.getTestWhoUpdated()).get();
		return new Fixture(org.getUuid(), vcs.getUuid(), rawGitUri, "github.com/it-dup/" + slug);
	}

	private UUID createComponentOn(Fixture fx, String name) throws RelizaException {
		CreateComponentDto dto = CreateComponentDto.builder()
				.organization(fx.orgUuid())
				.name(name)
				.type(ComponentType.COMPONENT)
				.vcs(fx.vcsUuid())
				.repoPath(REPO_PATH)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build();
		return componentService.createComponent(dto, WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	/** Both the .git clone URL and the bare form must resolve the one component. */
	@Test
	public void gitSuffixedUriResolvesTheComponentItCreated() throws Exception {
		Fixture fx = fixture();
		UUID componentId = createComponentOn(fx, "resolves-either-form");

		for (String uriForm : new String[] { fx.rawGitUri(), fx.bareUri(), "https://" + fx.bareUri() }) {
			ComponentResolution resolution =
					componentService.resolveComponentResolutionByVcsUriAndPath(fx.orgUuid(), uriForm, REPO_PATH);
			assertEquals(ComponentResolutionStatus.FOUND, resolution.status(),
					"uri form must resolve: " + uriForm + " -> " + resolution.detail());
			assertEquals(componentId, resolution.componentId(), "wrong component for uri form " + uriForm);
		}
	}

	/** An unknown VCS is NOT_FOUND -- the only status that may legitimize creation. */
	@Test
	public void unknownVcsIsNotFoundNotError() {
		Fixture fx = fixture();
		ComponentResolution resolution = componentService.resolveComponentResolutionByVcsUriAndPath(
				fx.orgUuid(), "https://github.com/it-dup/never-created-" + UUID.randomUUID() + ".git", REPO_PATH);
		assertEquals(ComponentResolutionStatus.NOT_FOUND, resolution.status());
		assertNotNull(resolution.detail());
	}

	/** Duplicate registrations resolve AMBIGUOUS -- data, not a swallowed throw. */
	@Test
	public void duplicateRegistrationsResolveAmbiguous() throws Exception {
		Fixture fx = fixture();
		UUID first = createComponentOn(fx, "dup-a");
		UUID second = createComponentOn(fx, "dup-b");

		ComponentResolution resolution =
				componentService.resolveComponentResolutionByVcsUriAndPath(fx.orgUuid(), fx.rawGitUri(), REPO_PATH);
		assertEquals(ComponentResolutionStatus.AMBIGUOUS, resolution.status());
		assertTrue(resolution.detail().contains("Multiple components found"),
				"detail must carry the operator diagnostic: " + resolution.detail());
		assertTrue(resolution.detail().contains(first.toString()) && resolution.detail().contains(second.toString()),
				"detail must enumerate the duplicate component ids: " + resolution.detail());

		// The throwing wrapper (no-create callers: synchronizeBranch, getLatestRelease,
		// versionFeatureSet overrides) surfaces the same diagnostic as an error.
		RelizaException ex = assertThrows(RelizaException.class,
				() -> componentService.findComponentDataByVcsAndPath(fx.vcsUuid(), fx.orgUuid(), REPO_PATH));
		assertTrue(ex.getMessage().contains("Multiple components found"));
	}
}
