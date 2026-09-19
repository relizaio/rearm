/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The component graph may contain cycles, and walking it must still terminate.
 *
 * <p>A release graph is acyclic -- a release's parents are fixed when it is written. The graph this
 * traversal walks is not the release graph: it is the component graph induced over the last ten
 * releases of each component, and that closes a loop the moment release A1 names a release of B as
 * a parent and a later release B2 names a release of A. Neither release is circular. The components
 * are, and the traversal used to recurse between them until the stack ran out -- taking the
 * analytics scheduler down for every organization, because a StackOverflowError is not an Exception
 * and walked straight through every per-org catch above it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class CyclicComponentTraversalTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private io.reliza.service.oss.OssAnalyticsMetricsService ossAnalyticsMetricsService;

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	private static final Duration PATIENCE = Duration.ofSeconds(60);

	private Component component(Organization org, String prefix) throws RelizaException {
		return componentService.createComponent(prefix + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private UUID release(Organization org, Component c, String version, UUID... parents) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		List<ParentRelease> parentReleases = java.util.Arrays.stream(parents)
				.map(p -> ParentRelease.minimalParentReleaseFactory(p, null)).toList();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.parentReleases(parentReleases).build(), WU).getUuid();
	}

	@Test
	public void twoComponentsPointingAtEachOtherTerminate() throws RelizaException {
		// The shape from the load-test data that stopped the schedulers: A1 depends on B1, and a
		// LATER release of B depends on a release of A. No release is its own ancestor.
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "cyc_a_");
		Component b = component(org, "cyc_b_");

		UUID b1 = release(org, b, "1.0.0");
		UUID a1 = release(org, a, "1.0.0", b1);
		release(org, b, "2.0.0", a1);

		Set<UUID> found = assertTimeoutPreemptively(PATIENCE,
				() -> sharedReleaseService.obtainComponentsOfProductOrComponent(a.getUuid(), Set.of()),
				"the traversal did not terminate on a two-component cycle");
		assertTrue(found.contains(b.getUuid()), "B is reachable from A");
		assertFalse(found.contains(a.getUuid()), "the starting component is never part of its own result");
	}

	@Test
	public void aReleaseWhoseComponentDependsOnItselfTerminates() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "self_a_");
		UUID a1 = release(org, a, "1.0.0");
		release(org, a, "2.0.0", a1);

		Set<UUID> found = assertTimeoutPreemptively(PATIENCE,
				() -> sharedReleaseService.obtainComponentsOfProductOrComponent(a.getUuid(), Set.of()));
		assertFalse(found.contains(a.getUuid()), "a component is not a dependency of itself");
	}

	@Test
	public void aThreeComponentCycleTerminates() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "tri_a_");
		Component b = component(org, "tri_b_");
		Component c = component(org, "tri_c_");

		UUID c1 = release(org, c, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", c1);
		UUID a1 = release(org, a, "1.0.0", b1);
		release(org, c, "2.0.0", a1);

		Set<UUID> found = assertTimeoutPreemptively(PATIENCE,
				() -> sharedReleaseService.obtainComponentsOfProductOrComponent(a.getUuid(), Set.of()),
				"the traversal did not terminate on a three-component cycle");
		assertTrue(found.containsAll(Set.of(b.getUuid(), c.getUuid())));
	}

	@Test
	public void aDiamondReachesEachComponentOnce() throws RelizaException {
		// D is reached through both B and C. It must appear once, and the walk must not redo it.
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "dia_a_");
		Component b = component(org, "dia_b_");
		Component c = component(org, "dia_c_");
		Component d = component(org, "dia_d_");

		UUID d1 = release(org, d, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", d1);
		UUID c1 = release(org, c, "1.0.0", d1);
		release(org, a, "1.0.0", b1, c1);

		Set<UUID> found = assertTimeoutPreemptively(PATIENCE,
				() -> sharedReleaseService.obtainComponentsOfProductOrComponent(a.getUuid(), Set.of()));
		assertTrue(found.containsAll(Set.of(b.getUuid(), c.getUuid(), d.getUuid())));
		assertFalse(found.contains(a.getUuid()));
	}

	@Test
	public void aDeduplicatedComponentIsNotReturned() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "dedup_a_");
		Component b = component(org, "dedup_b_");
		Component c = component(org, "dedup_c_");

		UUID c1 = release(org, c, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", c1);
		release(org, a, "1.0.0", b1);

		// Without the exclusion the whole chain comes back.
		Set<UUID> whole = sharedReleaseService.obtainComponentsOfProductOrComponent(a.getUuid(), Set.of());
		assertTrue(whole.containsAll(Set.of(b.getUuid(), c.getUuid())));

		Set<UUID> found = sharedReleaseService.obtainComponentsOfProductOrComponent(
				a.getUuid(), Set.of(b.getUuid()));
		assertFalse(found.contains(b.getUuid()), "a component in dedupComponents is never returned");
		// C still comes back, and did before this change too: dedupComponents suppresses the
		// component itself and stops the walk from descending THROUGH it a second time, but C is
		// not reached through B here -- it is in the dependency closure of A's own releases, which
		// is the first thing the walk expands.
		assertTrue(found.contains(c.getUuid()),
				"dedup must suppress the named component, not the closure of the starting one");
	}

	@Test
	public void analyticsForAnOrgHoldingACycleCompletes() throws RelizaException {
		// The end-to-end version of the incident: the traversal runs under the analytics compute,
		// and it was the analytics scheduler that a cycle in one org's data took down for all of them.
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "an_a_");
		Component b = component(org, "an_b_");
		UUID b1 = release(org, b, "1.0.0");
		UUID a1 = release(org, a, "1.0.0", b1);
		release(org, b, "2.0.0", a1);

		String dateKey = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
		assertTimeoutPreemptively(PATIENCE, () -> ossAnalyticsMetricsService
				.computeAndRecordAnalyticsMetricsForOrgAndDate(org.getUuid(), dateKey, WU),
				"analytics did not complete for an organization whose component graph holds a cycle");
	}

	/**
	 * The closure is transitive, not one level deep.
	 *
	 * <p>Worth a test of its own because the shared visited set that makes the recursion cycle-safe
	 * is also what can silently truncate it: the child call adds everything it finds to that set, so
	 * a caller that re-filters its child's result against the same set keeps nothing. Every frame
	 * then returns its direct parents and the public method returns depth one -- which no cycle
	 * fixture notices, and which fifteen callers outside this traversal would quietly inherit.
	 */
	@Test
	public void unwindReturnsTheWholeChainNotJustDirectParents() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "chain_a_");
		Component b = component(org, "chain_b_");
		Component c = component(org, "chain_c_");

		UUID c1 = release(org, c, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", c1);
		UUID a1 = release(org, a, "1.0.0", b1);

		Set<UUID> unwound = sharedReleaseService
				.unwindReleaseDependencies(sharedReleaseService.getReleaseData(a1).orElseThrow())
				.stream().map(ReleaseData::getUuid).collect(java.util.stream.Collectors.toSet());
		assertTrue(unwound.contains(b1), "the direct parent is missing");
		assertTrue(unwound.contains(c1), "the grandparent is missing -- the closure stopped at depth one");
		assertEquals(2, unwound.size(), "unexpected extra releases: " + unwound);

		// And through the component-level walk the five callers actually use.
		Set<UUID> components = sharedReleaseService.obtainComponentsOfProductOrComponent(a.getUuid(), Set.of());
		assertTrue(components.containsAll(Set.of(b.getUuid(), c.getUuid())),
				"the component walk lost the second level: " + components);
	}

	@Test
	public void unwindReturnsEachReleaseOfADiamondExactlyOnce() throws RelizaException {
		// a -> b, a -> c, b -> d, c -> d. d is reachable by two paths and must appear once.
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, "dia_a_");
		Component b = component(org, "dia_b_");
		Component c = component(org, "dia_c_");
		Component d = component(org, "dia_d_");

		UUID d1 = release(org, d, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", d1);
		UUID c1 = release(org, c, "1.0.0", d1);
		UUID a1 = release(org, a, "1.0.0", b1, c1);

		List<UUID> unwound = sharedReleaseService
				.unwindReleaseDependencies(sharedReleaseService.getReleaseData(a1).orElseThrow())
				.stream().map(ReleaseData::getUuid).toList();
		assertEquals(Set.of(b1, c1, d1), Set.copyOf(unwound), "unexpected closure: " + unwound);
		assertEquals(3, unwound.size(), "a release reachable by two paths was returned twice: " + unwound);
	}
}
