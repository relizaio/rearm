/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseBranchScope;
import io.reliza.model.dto.CveSearchResultDto.CveSearchResult;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;

/**
 * TPF-135 architecture-2 section 4.1 tests 1 to 3: the CVE search lists the releases of non-archived
 * branches under ACTIVE_BRANCHES, inside the statement, so the window count and the LIMIT see the
 * scope; ALL_BRANCHES is the search as it was; the perspective filter still runs after the page.
 */
@SpringBootTest(classes = {App.class})
public class ReleaseBranchScopeCveSearchIntegrationTest extends ReleaseBranchScopeTestBase {

	@Autowired private ReleaseRepository releaseRepository;

	private static Object uuidOf(Object[] row) {
		return UUID.fromString(row[0].toString());
	}

	/** Test 1 (a) to (e). */
	@Test
	public void activeBranchesLeavesOutTheArchivedBranchAndKeepsAStatuslessOne() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID foreignOrg = testInitializer.obtainOrganization().getUuid();
		String cve = cveId();
		ComponentAndBranch base = component(org, ComponentType.COMPONENT);
		ComponentAndBranch archived = archivedBranch(base);
		UUID onBase = cveRelease(org, base, cve, 30);
		UUID onArchived = cveRelease(org, archived, cve, 20);
		UUID foreign = cveRelease(foreignOrg, component(foreignOrg, ComponentType.COMPONENT), cve, 10);

		CveSearchResult active = sharedReleaseService.findReleasesByCveId(org, cve, null, ReleaseBranchScope.ACTIVE_BRANCHES);
		assertEquals(Set.of(onBase), releasesOf(active.components()), "(a)");
		assertEquals(1, active.totalReleases(), "(a)");
		assertFalse(active.truncated(), "(a)");

		CveSearchResult all = sharedReleaseService.findReleasesByCveId(org, cve, null, ReleaseBranchScope.ALL_BRANCHES);
		assertEquals(Set.of(onBase, onArchived), releasesOf(all.components()), "(b)");
		assertEquals(2, all.totalReleases(), "(b)");
		assertEquals(Map.of(base.component().getUuid(), Set.of(base.branch().getUuid(), archived.branch().getUuid())),
				branchesByComponent(all.components()), "(b)");
		assertEquals(StatusEnum.ARCHIVED, branchOf(all.components(), archived.branch().getUuid()).status(), "(e)");

		ComponentAndBranch statusless = statuslessBranch(base);
		UUID onStatusless = cveRelease(org, statusless, cve, 5);
		CveSearchResult withLegacy = sharedReleaseService.findReleasesByCveId(org, cve, null,
				ReleaseBranchScope.ACTIVE_BRANCHES);
		assertEquals(Set.of(onBase, onStatusless), releasesOf(withLegacy.components()), "(c)");
		assertEquals(2, withLegacy.totalReleases(), "(c)");

		for (ReleaseBranchScope scope : ReleaseBranchScope.values()) {
			assertFalse(releasesOf(sharedReleaseService.findReleasesByCveId(org, cve, null, scope).components())
					.contains(foreign), "(d) " + scope);
		}
		assertEquals(Set.of(foreign), releasesOf(sharedReleaseService.findReleasesByCveId(foreignOrg, cve, null,
				ReleaseBranchScope.ACTIVE_BRANCHES).components()), "(d) the foreign release is found in its own org");
	}

	/** Test 2: the window count and the LIMIT see the scope. */
	@Test
	public void theCountAndTheCapAreTakenOverTheScope() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String cve = cveId();
		ComponentAndBranch base = component(org, ComponentType.COMPONENT);
		ComponentAndBranch archived = archivedBranch(base);
		cveRelease(org, base, cve, 40);
		UUID newestLive = cveRelease(org, base, cve, 30);
		cveRelease(org, archived, cve, 20);
		UUID newestArchived = cveRelease(org, archived, cve, 10);

		List<Object[]> active = releaseRepository.findReleasesByCveId(org.toString(), cve,
				ReleaseBranchScope.ACTIVE_BRANCHES.name(), 1);
		assertEquals(1, active.size());
		assertEquals(newestLive, uuidOf(active.get(0)));
		assertEquals(2, ((Number) active.get(0)[1]).intValue(), "total_matches counts live-branch matches only");

		List<Object[]> all = releaseRepository.findReleasesByCveId(org.toString(), cve,
				ReleaseBranchScope.ALL_BRANCHES.name(), 1);
		assertEquals(1, all.size());
		assertEquals(newestArchived, uuidOf(all.get(0)));
		assertEquals(4, ((Number) all.get(0)[1]).intValue());
	}

}
