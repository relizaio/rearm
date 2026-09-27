/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.FindingChangeV3BranchSeedRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Proof (against live Postgres) of the RESUMABLE, bounded per-tick v3 (events-lite) backfill DRAIN (board
 * task #38 follow-on) -- {@link FindingChangeEventBackfillService#drainV3Backfill}. Asserts the four drain
 * invariants against a FRESH per-test org (so the shared rearm-test-pg cannot contaminate the org-scoped
 * assertions -- other uncertified orgs may share the global per-tick budget, so every assertion here is
 * scoped to THIS org's own marker rows / certification, never a global processed count):
 *
 * <ul>
 *   <li>batch bound -- one drain marks at most {@code batchPerTick} of this org's branches;</li>
 *   <li>skip-already-marked -- a marked branch is not re-marked (idempotent upsert, count stable);</li>
 *   <li>certify-when-all-branches -- once every branch is marked the org is certified;</li>
 *   <li>vocab bump -- markers are keyed by KEY_VERSION, so a higher version sees no marks (re-drain);</li>
 *   <li>resume -- budget exhausted mid-org leaves it uncertified; a follow-up drain finishes + certifies.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
// Disable the SCHEDULED drain tick so it cannot race the explicit drainV3Backfill calls below and mark/
// certify this test's org concurrently -- the direct service-method calls here are unaffected (they do not
// consult the flag), so every assertion measures exactly the drain invoked by the test, deterministically.
@TestPropertySource(properties = "relizaprops.findingChangeV3BackfillDrainEnabled=false")
public class FindingChangeV3DrainTest {

	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private FindingChangeEventBackfillService backfillService;
	@Autowired private FindingDimBackfillService findingDimBackfillService;
	@Autowired private FindingChangeV3BranchSeedRepository seedRepository;
	@Autowired private TestInitializer testInitializer;

	private static final int KV = FindingDimKey.KEY_VERSION;
	/**
	 * A budget large enough to fully drain the whole test DB in one tick. The drain iterates EVERY
	 * uncertified org (this class's other test orgs + any leftover from sibling test classes share the
	 * global per-tick budget), so a "finish the org" drain must be able to absorb that whole backlog, not
	 * just this org's branch count -- otherwise another org could starve this org's completion.
	 */
	private static final int DRAIN_ALL = 100_000;

	/**
	 * BATCH BOUND: with N un-marked branches and batchPerTick < N, one drain marks at most batchPerTick of
	 * this org's branches and does NOT certify it; a large follow-up drain marks the rest and certifies.
	 */
	@Test
	public void drainBoundsBranchesPerTickThenResumes() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = newComponent(orgUuid);
		Branch base = baseBranch(component); // component auto-creates its base branch
		seedOneRelease(org, component, base); // gives the org metrics_audit history (so the drain iterates it)
		// 4 more feature branches -> 5 branches total on this org.
		for (int i = 0; i < 4; i++) {
			branchService.createBranch("feature-" + i, component.getUuid(), BranchType.FEATURE,
					WhoUpdated.getTestWhoUpdated());
		}
		int totalBranches = branchService.listBranchesOfOrg(orgUuid).size();
		assertEquals(5, totalBranches, "fixture has 5 branches on the org");
		assertTrue(findingDimBackfillService.needsV3Backfill(orgUuid), "fresh org needs a v3 backfill");

		int batch = 2;
		backfillService.drainV3Backfill(batch);
		long markedAfterFirst = seedRepository.countSeeded(orgUuid, KV);
		assertTrue(markedAfterFirst <= batch,
				"one drain marks at most batchPerTick of this org's branches (got " + markedAfterFirst + ")");
		assertTrue(findingDimBackfillService.needsV3Backfill(orgUuid),
				"org not certified while un-marked branches remain");

		// Follow-up drain(s) with ample budget finish the org.
		backfillService.drainV3Backfill(DRAIN_ALL);
		assertEquals(totalBranches, seedRepository.countSeeded(orgUuid, KV),
				"a subsequent drain marks every remaining branch");
		assertFalse(findingDimBackfillService.needsV3Backfill(orgUuid),
				"org is v3-certified once all branches are marked");
	}

	/** SKIP ALREADY-MARKED: re-running a fully-drained org marks no new rows (idempotent upsert). */
	@Test
	public void drainSkipsAlreadyMarkedBranches() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = newComponent(orgUuid);
		Branch base = baseBranch(component);
		seedOneRelease(org, component, base);
		branchService.createBranch("feature", component.getUuid(), BranchType.FEATURE,
				WhoUpdated.getTestWhoUpdated());
		int totalBranches = branchService.listBranchesOfOrg(orgUuid).size();

		backfillService.drainV3Backfill(DRAIN_ALL);
		long marked = seedRepository.countSeeded(orgUuid, KV);
		assertEquals(totalBranches, marked, "all branches marked after the first full drain");
		assertFalse(findingDimBackfillService.needsV3Backfill(orgUuid), "org certified");

		// Second drain: org is certified so needsV3Backfill short-circuits it -- marks unchanged.
		backfillService.drainV3Backfill(DRAIN_ALL);
		assertEquals(marked, seedRepository.countSeeded(orgUuid, KV),
				"a certified org is skipped; no branch is re-marked");
	}

	/** CERTIFY-WHEN-ALL-BRANCHES: an org fully drained in one tick flips needsV3Backfill false. */
	@Test
	public void drainCertifiesWhenEveryBranchMarked() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = newComponent(orgUuid);
		Branch base = baseBranch(component);
		seedOneRelease(org, component, base);
		int totalBranches = branchService.listBranchesOfOrg(orgUuid).size();

		assertTrue(findingDimBackfillService.needsV3Backfill(orgUuid), "uncertified before the drain");
		backfillService.drainV3Backfill(DRAIN_ALL);
		assertEquals(totalBranches, seedRepository.countSeeded(orgUuid, KV), "every branch marked");
		assertFalse(findingDimBackfillService.needsV3Backfill(orgUuid),
				"org certified once every branch is marked");
	}

	/**
	 * VOCAB BUMP: markers are keyed by KEY_VERSION, so a query at a HIGHER version sees none of the current
	 * marks -- exactly what makes a vocabulary bump re-drain every branch resumably (KEY_VERSION is a compile
	 * constant so we cannot bump it at runtime; this asserts the parameterization that gives the property).
	 */
	@Test
	public void markersAreScopedByKeyVersionSoAHigherVersionSeesNone() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = newComponent(orgUuid);
		Branch base = baseBranch(component);
		seedOneRelease(org, component, base);
		int totalBranches = branchService.listBranchesOfOrg(orgUuid).size();

		backfillService.drainV3Backfill(DRAIN_ALL);
		assertEquals(totalBranches, seedRepository.countSeeded(orgUuid, KV),
				"branches marked at the current key version");
		assertTrue(seedRepository.findSeededBranchUuids(orgUuid, KV + 1).isEmpty(),
				"the same marks are INVISIBLE at a higher key version -> a vocab bump re-drains the branches");
		assertEquals(0L, seedRepository.countSeeded(orgUuid, KV + 1),
				"count at a higher key version is zero");
	}

	/**
	 * RESUME: a budget that runs out mid-org leaves it UNCERTIFIED with a partial marker set; a follow-up
	 * drain marks the remainder and certifies. The drain is scoped to this test's org, so each batch=1 tick
	 * marks exactly one of ITS branches: the budget is global, and in the shared test database other orgs
	 * can turn uncertified at any moment (a fresh context's startup work runs on scheduling threads while
	 * the test does), which used to spend this test's ticks on them.
	 */
	@Test
	public void budgetExhaustedMidOrgResumesAndCertifies() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = newComponent(orgUuid);
		Branch base = baseBranch(component);
		seedOneRelease(org, component, base);
		branchService.createBranch("feature-a", component.getUuid(), BranchType.FEATURE,
				WhoUpdated.getTestWhoUpdated());
		branchService.createBranch("feature-b", component.getUuid(), BranchType.FEATURE,
				WhoUpdated.getTestWhoUpdated());
		int totalBranches = branchService.listBranchesOfOrg(orgUuid).size();
		assertTrue(totalBranches >= 3, "need at least 3 branches to exercise mid-org resume");

		// batch=1 marks exactly one of this org's branches per tick, and the org cannot certify until all
		// are marked -- the mid-org resume path.
		boolean certifiedMidPartial = false;
		for (int tick = 0; tick < totalBranches; tick++) {
			backfillService.drainV3Backfill(1, orgUuid::equals);
			long marked = seedRepository.countSeeded(orgUuid, KV);
			assertEquals(tick + 1, marked, "each batch=1 tick marks one more of this org's branches");
			boolean certified = !findingDimBackfillService.needsV3Backfill(orgUuid);
			if (marked < totalBranches) {
				// mid-org: never certified while a branch is still un-marked
				assertFalse(certified,
						"org must stay UNCERTIFIED while " + (totalBranches - marked) + " branch(es) un-marked");
			} else {
				certifiedMidPartial = certified;
			}
		}
		assertEquals(totalBranches, seedRepository.countSeeded(orgUuid, KV),
				"the resumed drain eventually marks every branch");
		assertTrue(certifiedMidPartial, "the tick that marks the last branch certifies the org");
		assertFalse(findingDimBackfillService.needsV3Backfill(orgUuid), "org certified after resume");
	}

	// ---- helpers ----

	private Component newComponent(UUID orgUuid) throws RelizaException {
		return componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
	}

	/** The base branch the component auto-created (the only branch on the org at this point). */
	private Branch baseBranch(Component component) {
		UUID orgUuid = getComponentService.getComponentData(component.getUuid()).orElseThrow().getOrg();
		return branchService.listBranchesOfOrg(orgUuid)
				.stream()
				.filter(b -> component.getUuid().equals(
						branchService.getBranchData(b.getUuid()).orElseThrow().getComponent()))
				.findFirst()
				.orElseThrow();
	}

	/** One release with one scanned finding -> populates metrics_audit so the org is drain-visible. */
	private void seedOneRelease(Organization org, Component component, Branch branch) throws RelizaException {
		ReleaseDto dto = ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(io.reliza.model.ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(io.reliza.model.ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.build();
		UUID releaseUuid = ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated()).getUuid();
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r, metrics());
		Release r2 = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r2,
				metrics(vuln("CVE-A", "pkg:npm/a@1", VulnerabilitySeverity.HIGH)));
	}

	private static VulnerabilityDto vuln(String id, String purl, VulnerabilitySeverity sev) {
		return new VulnerabilityDto(purl, id, sev, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, null);
	}

	private static ReleaseMetricsDto metrics(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}
}
