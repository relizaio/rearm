/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Parent releases are validated on every write path, not only on update.
 *
 * <p>The release graph is acyclic by construction -- a release's parents are fixed when it is
 * written -- and the point of checking on create as well is that it stays that way whichever door
 * the data comes through. Note what is deliberately NOT refused here: a cycle at the COMPONENT
 * level, where a later release of a component depends on a release of a component that depends on
 * it. That shape is legal (see the javadoc on {@code obtainComponentsOfProductOrComponent}); what
 * makes it safe is that traversals are cycle-safe, not that the data is acyclic.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ParentReleaseValidationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private Component component(Organization org, ComponentType type) throws RelizaException {
		return componentService.createComponent("parval_" + UUID.randomUUID(), org.getUuid(),
				type, "semver", "Branch.Micro", null, WU);
	}

	private java.util.Optional<UUID> createProductReleaseInTx(BranchData featureSet, UUID org, UUID parent) {
		return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(
				status -> ossReleaseService.createProductRelease(featureSet, org,
						List.of(ParentRelease.minimalParentReleaseFactory(parent, null))));
	}

	private UUID release(Organization org, Component c, String version, UUID... parents) throws RelizaException {
		return release(org, c, version, ReleaseLifecycle.ASSEMBLED, parents);
	}

	private UUID release(Organization org, Component c, String version, ReleaseLifecycle lifecycle,
			UUID... parents) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		List<ParentRelease> parentReleases = java.util.Arrays.stream(parents)
				.map(p -> ParentRelease.minimalParentReleaseFactory(p, null)).toList();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(lifecycle)
				.parentReleases(parentReleases).build(), WU).getUuid();
	}

	/**
	 * Writes a cycle into stored data behind the service's back -- exactly the legacy rows this
	 * validation exists to stop being created, and the only way to set up the tests below now that
	 * every write path refuses them.
	 */
	private void forceParent(UUID releaseUuid, UUID parentUuid) {
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		rd.setParentReleases(List.of(ParentRelease.minimalParentReleaseFactory(parentUuid, null)));
		ossReleaseService.saveRelease(r, rd, WU);
	}

	@Test
	public void theCreatePathRefusesAParentThatIsItsOwnAncestor() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, ComponentType.COMPONENT);
		Component b = component(org, ComponentType.COMPONENT);
		Component c = component(org, ComponentType.COMPONENT);

		UUID a1 = release(org, a, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", a1);
		forceParent(a1, b1); // a1 -> b1 -> a1

		RelizaException re = assertThrows(RelizaException.class,
				() -> release(org, c, "1.0.0", b1),
				"a create attaching a cyclic parent must be refused, not stored");
		assertTrue(re.getMessage().contains(b1.toString()) && re.getMessage().contains(a1.toString()),
				"the refusal must name both releases so an operator can find the loop: " + re.getMessage());
	}

	@Test
	public void theProductReleasePathRefusesACyclicParent() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, ComponentType.COMPONENT);
		Component b = component(org, ComponentType.COMPONENT);
		Component product = component(org, ComponentType.PRODUCT);

		UUID a1 = release(org, a, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", a1);
		forceParent(a1, b1);

		BranchData featureSet = branchService.getBranchData(
				branchService.getBaseBranchOfComponent(product.getUuid()).orElseThrow().getUuid()).orElseThrow();
		// Control first: the same feature set mints a product release fine over an ACYCLIC parent.
		// Without it, "empty" would prove nothing -- createProductRelease reports every failure the
		// same way, so a fixture that cannot mint at all would pass this test with the fix removed.
		// In a transaction because that is how the auto-integrate caller runs it: parts of the
		// create path are Propagation.MANDATORY and fail outright without one.
		UUID controlComponentRelease = release(org, component(org, ComponentType.COMPONENT), "1.0.0");
		assertTrue(createProductReleaseInTx(featureSet, org.getUuid(), controlComponentRelease).isPresent(),
				"fixture is not able to mint a product release at all");

		var created = createProductReleaseInTx(featureSet, org.getUuid(), b1);
		assertTrue(created.isEmpty(), "auto-integration must not mint a product release over cyclic parents");
	}

	@Test
	public void theUpdatePathStillRefusesAReleaseDependingOnItself() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, ComponentType.COMPONENT);
		Component b = component(org, ComponentType.COMPONENT);

		// DRAFT on a1: an ASSEMBLED release refuses parent edits outright, on lifecycle grounds,
		// before the cycle check is ever consulted -- which would make this test prove nothing.
		UUID a1 = release(org, a, "1.0.0", ReleaseLifecycle.DRAFT);
		UUID b1 = release(org, b, "1.0.0", a1);

		// Unchanged behaviour: making a1 depend on b1, which already depends on a1.
		RelizaException re = assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(a1).org(org.getUuid())
					.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(b1, null))).build(), WU));
		assertTrue(re.getMessage().contains(a1.toString()), re.getMessage());
	}

	@Test
	public void anOrdinaryParentIsUnaffected() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, ComponentType.COMPONENT);
		Component b = component(org, ComponentType.COMPONENT);

		UUID a1 = release(org, a, "1.0.0");
		UUID b1 = release(org, b, "1.0.0", a1);
		ReleaseData rd = sharedReleaseService.getReleaseData(b1).orElseThrow();
		assertEquals(1, rd.getParentReleases().size());
		assertEquals(a1, rd.getParentReleases().get(0).getRelease());
	}

	/**
	 * The component-level cycle stays legal: B's later release depends on a release of A whose own
	 * release depends on B. No release is its own ancestor, so nothing is refused.
	 */
	@Test
	public void aComponentLevelCycleIsStillAccepted() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org, ComponentType.COMPONENT);
		Component b = component(org, ComponentType.COMPONENT);

		UUID b1 = release(org, b, "1.0.0");
		UUID a1 = release(org, a, "1.0.0", b1);
		UUID b2 = release(org, b, "2.0.0", a1);
		assertTrue(sharedReleaseService.getReleaseData(b2).isPresent());
	}
}
