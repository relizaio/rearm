/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseBranchScope;
import io.reliza.model.dto.CveSearchResultDto.ComponentWithBranches;
import io.reliza.ws.App;

/**
 * TPF-135 architecture-2 section 4.1 test 4 (a) to (c): the SBOM-component search groups with the
 * scope, so under ACTIVE_BRANCHES an archived branch, a component left with no branch and a product
 * on an archived feature set drop out; ALL_BRANCHES is the grouping as it was; the release-id lookup
 * that PurlReleaseResolver shares is untouched. Row (d), the fetcher's perspective filter, needs the
 * fetcher and is in ReleaseBranchScopeGraphqlIntegrationTest.
 */
@SpringBootTest(classes = {App.class})
public class ReleaseBranchScopeSbomSearchIntegrationTest extends ReleaseBranchScopeTestBase {

	@Test
	public void theGroupingAppliesTheScopeAndTheLookupKeepsEveryRelease() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID sbomComponent = UUID.randomUUID();
		ComponentAndBranch first = component(org, ComponentType.COMPONENT);
		ComponentAndBranch firstArchived = archivedBranch(first);
		UUID r1 = plainRelease(org, first);
		UUID r2 = plainRelease(org, firstArchived);
		ComponentAndBranch second = component(org, ComponentType.COMPONENT);
		ComponentAndBranch secondArchived = archivedBranch(second);
		UUID r3 = plainRelease(org, secondArchived);
		ComponentAndBranch product = component(org, ComponentType.PRODUCT);
		ComponentAndBranch productArchived = featureBranch(product);
		UUID p1 = productRelease(org, productArchived, r1);
		branchService.archiveBranch(productArchived.branch().getUuid(), WU);
		for (UUID r : List.of(r1, r2, r3)) {
			indexRelease(org, r, sbomComponent);
		}

		Set<UUID> ids = sbomComponentService.findReleaseUuidsBySbomComponents(Set.of(sbomComponent), org);
		assertEquals(Set.of(r1, r2, r3, p1), ids, "(c) the lookup is untouched");

		List<ComponentWithBranches> active = sharedReleaseService.findReleaseDatasByReleaseIds(ids, org,
				ReleaseBranchScope.ACTIVE_BRANCHES);
		assertEquals(Map.of(first.component().getUuid(), Set.of(first.branch().getUuid())),
				branchesByComponent(active), "(a)");
		assertEquals(Set.of(r1), releasesOf(active), "(a)");

		List<ComponentWithBranches> all = sharedReleaseService.findReleaseDatasByReleaseIds(ids, org,
				ReleaseBranchScope.ALL_BRANCHES);
		assertEquals(Map.of(
				first.component().getUuid(), Set.of(first.branch().getUuid(), firstArchived.branch().getUuid()),
				second.component().getUuid(), Set.of(secondArchived.branch().getUuid()),
				product.component().getUuid(), Set.of(productArchived.branch().getUuid())),
				branchesByComponent(all), "(b)");
		assertEquals(Set.of(r1, r2, r3, p1), releasesOf(all), "(b)");
		for (ComponentAndBranch cb : List.of(firstArchived, secondArchived, productArchived)) {
			assertEquals(StatusEnum.ARCHIVED, branchOf(all, cb.branch().getUuid()).status(), "(b)");
		}
		assertNotEquals(StatusEnum.ARCHIVED, branchOf(all, first.branch().getUuid()).status(), "(b) the live branch");
	}

	@Test
	public void aStatuslessBranchIsListedUnderActiveBranches() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID sbomComponent = UUID.randomUUID();
		ComponentAndBranch c = component(org, ComponentType.COMPONENT);
		ComponentAndBranch statusless = statuslessBranch(c);
		UUID r = plainRelease(org, statusless);
		indexRelease(org, r, sbomComponent);
		Set<UUID> ids = sbomComponentService.findReleaseUuidsBySbomComponents(Set.of(sbomComponent), org);
		assertEquals(Set.of(r), releasesOf(sharedReleaseService.findReleaseDatasByReleaseIds(ids, org,
				ReleaseBranchScope.ACTIVE_BRANCHES)));
	}
}
