/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.model.AttestationData.ActorType;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateReleaseSource;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateResult;
import io.reliza.ws.App;

/**
 * The auto-integrate dry run on one organization (TPF-119 design rows 1 to 10 and 16): the operator's
 * regression first, then one test per outcome, the walk past the first miss, the preview writing
 * nothing through its read-only transaction, and the projected version on a followed feature set.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateDryRunTest extends AutoIntegrateDryRunTestBase {

	/** c1 and c2 with ASSEMBLED 1.0.0, a product, a feature set REQUIRED c1 and c2 with the given state. */
	private record Two(Organization org, Comp c1, Comp c2, ReleaseData r1, ReleaseData r2, Component product,
			UUID fs) {}

	private Two two(AutoIntegrateState state, String v1, String v2) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "dryRunC1");
		Comp c2 = component(org, "dryRunC2");
		ReleaseData r1 = assembled(org, c1, v1);
		ReleaseData r2 = assembled(org, c2, v2);
		Component product = product(org);
		UUID fs = featureSet(product, "fs");
		configure(fs, state, dep(c1, StatusEnum.REQUIRED), dep(c2, StatusEnum.REQUIRED));
		return new Two(org, c1, c2, r1, r2, product, fs);
	}

	private Two two() throws Exception {
		return two(AutoIntegrateState.ENABLED, "1.0.0", "1.0.0");
	}

	/** Row 1 and 2 base: P1 integrated from c1 and c2, then c3 added as REQUIRED without a release. */
	private record Regression(Two t, Comp c3, UUID p1, BranchData fs) {}

	private Regression regression() throws Exception {
		Two t = two();
		// The explicit call is what creates P1: every dependency release is deferred, no worker runs.
		Optional<UUID> p1 = releaseService.autoIntegrateFeatureSetOnDemand(read(t.fs()));
		assertTrue(p1.isPresent());
		assertEquals(1, count(t.fs()));
		assertEquals(2, parentsOf(p1.get()).size());
		Comp c3 = component(t.org(), "dryRunC3");
		BranchData fs = configure(t.fs(), AutoIntegrateState.ENABLED, dep(t.c1(), StatusEnum.REQUIRED),
				dep(t.c2(), StatusEnum.REQUIRED), dep(c3, StatusEnum.REQUIRED));
		return new Regression(t, c3, p1.get(), fs);
	}

	/** The blocked half shared by rows 1 and 2: c3 blocks, P1 is not reported, nothing is written. */
	private void assertBlockedOnC3(Regression g, String c3Note) {
		Two t = g.t();
		Snapshot before = snapshot(t.fs(), t.c1(), t.c2(), g.c3());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(g.fs());
		assertEquals(AutoIntegrateOutcome.REQUIRED_DEPENDENCY_WITHOUT_RELEASE, ev.outcome());
		assertNull(ev.existingRelease(), "the earlier two-parent product is not a match");
		assertEquals(List.of(t.c1().uuid(), t.c2().uuid(), g.c3().uuid()), rowComponents(ev));
		AutoIntegrateDependencyRow c3Row = ev.dependencies().get(2);
		assertEquals(g.c3().name(), c3Row.component().name());
		assertEquals(g.c3().branchUuid(), c3Row.branch().uuid());
		assertEquals(g.c3().branchName(), c3Row.branch().name());
		assertEquals(StatusEnum.REQUIRED, c3Row.status());
		assertEquals(AutoIntegrateReleaseSource.NONE, c3Row.source());
		assertNull(c3Row.release());
		assertEquals(c3Note, c3Row.note());
		for (int i = 0; i < 2; i++) {
			AutoIntegrateDependencyRow r = ev.dependencies().get(i);
			assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, r.source());
			assertNotNull(r.release().createdDate());
		}
		assertRowRelease(ev.dependencies().get(0), t.r1());
		assertRowRelease(ev.dependencies().get(1), t.r2());
		assertEquals("1.0.0", ev.dependencies().get(0).release().version());

		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(g.fs());
		assertTrue(explained.release().isEmpty());
		assertEquals(ev, explained.evaluation());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(g.fs()).isEmpty());
		assertEquals(before, snapshot(t.fs(), t.c1(), t.c2(), g.c3()));
		assertEquals(1, count(t.fs()));
	}

	/** The unblocked half: the previewed version is the minted one, three parents. */
	private void assertCreatesWithThree(Regression g, ReleaseData r3) {
		Two t = g.t();
		BranchData fs = read(t.fs());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		assertEquals(3, ev.dependencies().size());
		ev.dependencies().forEach(r -> assertNotNull(r.release()));
		assertRowRelease(ev.dependencies().get(2), r3);
		assertNotNull(ev.projectedVersion());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertTrue(explained.release().isPresent());
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, explained.evaluation().outcome());
		ReleaseData created = sharedReleaseService.getReleaseData(explained.release().get()).orElseThrow();
		assertEquals(List.of(t.r1().getUuid(), t.r2().getUuid(), r3.getUuid()), parentsOf(created.getUuid()));
		assertEquals(ev.projectedVersion(), created.getVersion());
		assertEquals(2, count(t.fs()));
	}

	/** Row 1: the operator's scenario. A third REQUIRED dependency without a release blocks and says so. */
	@Test
	public void row01_regressionNewRequiredDependencyWithoutRelease() throws Exception {
		Regression g = regression();
		assertBlockedOnC3(g, "no release on branch " + g.c3().branchName());
		ReleaseData r3 = assembled(g.t().org(), g.c3(), "1.0.0");
		assertCreatesWithThree(g, r3);
	}

	/** Row 2: as row 1, c3 has only a DRAFT, then an ASSEMBLED 0.2.0. */
	@Test
	public void row02_regressionNewRequiredDependencyWithOnlyDraft() throws Exception {
		Regression g = regression();
		release(g.t().org(), g.c3(), "0.1.0", ReleaseLifecycle.DRAFT);
		assertBlockedOnC3(g, "no ASSEMBLED release on branch " + g.c3().branchName() + "; latest is DRAFT 0.1.0");
		ReleaseData r3 = assembled(g.t().org(), g.c3(), "0.2.0");
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(read(g.t().fs()));
		AutoIntegrateDependencyRow c3Row = row(ev, g.c3());
		assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, c3Row.source());
		assertEquals("0.2.0", c3Row.release().version());
		assertEquals("latest ASSEMBLED release of branch " + g.c3().branchName(), c3Row.note());
		assertCreatesWithThree(g, r3);
	}

	/** Row 3: auto-integrate DISABLED still shows the rows and runs no match. */
	@Test
	public void row03_autoIntegrateDisabled() throws Exception {
		Two t = two(AutoIntegrateState.DISABLED, "1.0.0", "1.0.0");
		BranchData fs = read(t.fs());
		Snapshot before = snapshot(t.fs(), t.c1(), t.c2());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.AUTO_INTEGRATE_DISABLED, ev.outcome());
		assertEquals(2, ev.dependencies().size());
		assertRowRelease(row(ev, t.c1()), t.r1());
		assertRowRelease(row(ev, t.c2()), t.r2());
		assertNull(ev.existingRelease());
		assertNull(ev.projectedVersion());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(AutoIntegrateOutcome.AUTO_INTEGRATE_DISABLED, explained.evaluation().outcome());
		assertTrue(explained.release().isEmpty());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty());
		assertEquals(before, snapshot(t.fs(), t.c1(), t.c2()));
	}

	/** Row 4: a pinned dependency integrates its pin, not the newer release. */
	@Test
	public void row04_pinnedReleaseIsUsed() throws Exception {
		Two t = two();
		ChildComponent pinned = dep(t.c1(), StatusEnum.REQUIRED);
		pinned.setRelease(t.r1().getUuid());
		configure(t.fs(), AutoIntegrateState.ENABLED, pinned, dep(t.c2(), StatusEnum.REQUIRED));
		assembled(t.org(), t.c1(), "2.0.0");
		BranchData fs = read(t.fs());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		AutoIntegrateDependencyRow c1Row = row(ev, t.c1());
		assertEquals(AutoIntegrateReleaseSource.PINNED, c1Row.source());
		assertEquals("1.0.0", c1Row.release().version());
		assertEquals(t.r1().getUuid(), c1Row.release().uuid());
		assertEquals("pinned", c1Row.note());
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertTrue(parentsOf(explained.release().orElseThrow()).contains(t.r1().getUuid()),
				"the product integrates c1 1.0.0, the pin");
		assertEquals(2, parentsOf(explained.release().get()).size());
	}

	/** Row 4, second part: a pin to a release that does not exist blocks a REQUIRED dependency. */
	@Test
	public void row04_pinnedReleaseNotFound() throws Exception {
		Two t = two();
		UUID missing = UUID.randomUUID();
		ChildComponent pinned = dep(t.c1(), StatusEnum.REQUIRED);
		pinned.setRelease(missing);
		BranchData fs = configure(t.fs(), AutoIntegrateState.ENABLED, pinned, dep(t.c2(), StatusEnum.REQUIRED));
		Snapshot before = snapshot(t.fs(), t.c1(), t.c2());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		AutoIntegrateDependencyRow c1Row = row(ev, t.c1());
		assertNull(c1Row.release());
		assertEquals("pinned release " + missing + " not found", c1Row.note());
		assertEquals(AutoIntegrateReleaseSource.PINNED, c1Row.source());
		assertEquals(AutoIntegrateOutcome.REQUIRED_DEPENDENCY_WITHOUT_RELEASE, ev.outcome());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemandExplained(fs).release().isEmpty());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty());
		assertEquals(before, snapshot(t.fs(), t.c1(), t.c2()));
	}

	/** Row 5: a product release with the same parents already exists. */
	@Test
	public void row05_matchingProductReleaseExists() throws Exception {
		Two t = two();
		UUID p1 = releaseService.autoIntegrateFeatureSetOnDemand(read(t.fs())).orElseThrow();
		ReleaseData p1Data = sharedReleaseService.getReleaseData(p1).orElseThrow();
		BranchData fs = read(t.fs());
		int n = count(t.fs());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.MATCHING_PRODUCT_RELEASE_EXISTS, ev.outcome());
		assertEquals(p1, ev.existingRelease().uuid());
		assertEquals(p1Data.getVersion(), ev.existingRelease().version());
		assertEquals(2, ev.dependencies().size());
		ev.dependencies().forEach(r -> assertNotNull(r.release()));
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(AutoIntegrateOutcome.MATCHING_PRODUCT_RELEASE_EXISTS, explained.evaluation().outcome());
		assertEquals(ev, explained.evaluation());
		assertTrue(explained.release().isEmpty());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty());
		assertEquals(n, count(t.fs()));
	}

	/** Row 6: a TRANSIENT dependency without a release is shown and omitted from the parents. */
	@Test
	public void row06_transientWithoutReleaseIsOmitted() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "dryRunC1");
		Comp c2 = component(org, "dryRunC2");
		ReleaseData r1 = assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED),
				dep(c2, StatusEnum.TRANSIENT));
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		AutoIntegrateDependencyRow c2Row = row(ev, c2);
		assertEquals(StatusEnum.TRANSIENT, c2Row.status());
		assertEquals(AutoIntegrateReleaseSource.NONE, c2Row.source());
		assertNull(c2Row.release());
		assertEquals("no release on branch " + c2.branchName(), c2Row.note());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(List.of(r1.getUuid()), parentsOf(explained.release().orElseThrow()));
		assertEquals(ev, explained.evaluation());
	}

	/** Row 6b: a dependency with no branch is reported; the old mutation no longer throws. */
	@Test
	public void row06b_dependencyWithoutBranch() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "dryRunC1");
		Comp c3 = component(org, "dryRunC3");
		ReleaseData r1 = assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		ChildComponent noBranch = ChildComponent.builder().uuid(c3.uuid()).status(StatusEnum.TRANSIENT).build();
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED), noBranch);
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		AutoIntegrateDependencyRow c3Row = row(ev, c3);
		assertNull(c3Row.branch());
		assertEquals(AutoIntegrateReleaseSource.NONE, c3Row.source());
		assertEquals("no branch set on this dependency", c3Row.note());
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		Optional<UUID> created = releaseService.autoIntegrateFeatureSetOnDemand(fs);
		assertTrue(created.isPresent(), "a clean create instead of the NoSuchElementException of today");
		assertEquals(List.of(r1.getUuid()), parentsOf(created.get()));
	}

	/** Row 7: WOULD_CREATE, and the explained mutation mints the previewed version. */
	@Test
	public void row07_wouldCreate() throws Exception {
		Two t = two();
		assertEquals(0, count(t.fs()));
		BranchData fs = read(t.fs());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		ev.dependencies().forEach(r -> assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, r.source()));
		assertNotNull(ev.projectedVersion());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		ReleaseData created = sharedReleaseService.getReleaseData(explained.release().orElseThrow()).orElseThrow();
		assertEquals(ev.projectedVersion(), created.getVersion());
		assertEquals(ev, explained.evaluation());
		assertEquals(1, count(t.fs()));
	}

	/** Row 8: a locked product is CREATE_FAILED in the explanation and still an error on the old mutation. */
	@Test
	public void row08_lockedProduct() throws Exception {
		Two t = two();
		componentLockService.lockComponent(t.product().getUuid(), "frozen", UnlockLevel.ADMIN,
				AttestationRequirement.NONE, WU);
		BranchData fs = read(t.fs());
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, ev.outcome());
		assertTrue(ev.note().contains("is locked, so ReARM will not assign a version"), ev.note());
		assertTrue(ev.note().contains("frozen"), ev.note());
		assertEquals(ev.note(), ev.lockRefusal());
		assertNull(ev.projectedVersion());
		assertNull(ev.existingRelease());
		assertEquals(2, ev.dependencies().size());
		ev.dependencies().forEach(r -> assertNotNull(r.release()));
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, explained.evaluation().outcome());
		assertEquals(ev.note(), explained.evaluation().note());
		assertTrue(explained.release().isEmpty());
		ActionRefusedException ex = assertThrows(ActionRefusedException.class,
				() -> releaseService.autoIntegrateFeatureSetOnDemand(fs));
		assertTrue(ex.getMessage().contains("is locked") && ex.getMessage().contains("frozen"), ex.getMessage());
		assertEquals(ev.note(), ex.getMessage(), "the old mutation's error is today's, byte for byte");
		assertEquals(0, count(t.fs()));
		assertTrue(latestAssignment(t.fs()).isEmpty(), "no mint");

		ComponentLock lock = componentLockService.activeLocks(t.product().getUuid(), t.fs()).get(0).lock();
		componentLockService.releaseLock(t.org().getUuid(), lock.uuid(), "thaw", false, ActorType.USER,
				UUID.randomUUID(), null, WU);
		BranchData thawed = read(t.fs());
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, releaseService.evaluateAutoIntegrate(thawed).outcome());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemandExplained(thawed).release().isPresent());
		assertEquals(1, count(t.fs()));
	}

	/** Row 9: the walk continues past the first REQUIRED miss. */
	@Test
	public void row09_everyMissingRequiredDependencyIsShown() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "dryRunC1");
		Comp c2 = component(org, "dryRunC2");
		UUID fsUuid = featureSet(product(org), "fs");
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED),
				dep(c2, StatusEnum.REQUIRED));
		Snapshot before = snapshot(fsUuid, c1, c2);
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.REQUIRED_DEPENDENCY_WITHOUT_RELEASE, ev.outcome());
		for (Comp c : List.of(c1, c2)) {
			AutoIntegrateDependencyRow r = row(ev, c);
			assertEquals(AutoIntegrateReleaseSource.NONE, r.source());
			assertEquals("no release on branch " + c.branchName(), r.note());
		}
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemandExplained(fs).release().isEmpty());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty());
		assertEquals(before, snapshot(fsUuid, c1, c2));
	}

	/**
	 * Row 10: three previews through the Spring proxy (so the read-only transaction is real and the
	 * peek runs in it) write nothing and agree; with a locking peek the first one fails.
	 */
	@Test
	public void row10_previewWritesNothing() throws Exception {
		Two t = two();
		BranchData fs = read(t.fs());
		Snapshot before = snapshot(t.fs(), t.c1(), t.c2());
		AutoIntegrateEvaluation e1 = releaseService.evaluateAutoIntegrate(fs);
		AutoIntegrateEvaluation e2 = releaseService.evaluateAutoIntegrate(fs);
		AutoIntegrateEvaluation e3 = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, e1.outcome());
		assertEquals(before, snapshot(t.fs(), t.c1(), t.c2()));
		assertEquals(e1, e2);
		assertEquals(e2, e3);
		assertEquals(peek(t.fs()), e3.projectedVersion());
	}

	/** Row 16: on a feature set that follows a dependency, the preview projects what the mint issues. */
	@Test
	public void row16_projectedVersionOnFollowedFeatureSet() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "dryRunC1");
		assembled(org, c1, "3.4.5");
		UUID fsUuid = featureSet(product(org), "fs");
		ChildComponent followed = dep(c1, StatusEnum.REQUIRED);
		followed.setIsFollowVersion(true);
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, followed);
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isPresent());
		assertEquals(1, count(fsUuid));
		ReleaseData r350 = assembled(org, c1, "3.5.0");
		String peek2 = peek(fsUuid);
		fs = read(fsUuid);
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		AutoIntegrateDependencyRow c1Row = row(ev, c1);
		assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, c1Row.source());
		assertEquals(r350.getUuid(), c1Row.release().uuid());
		assertEquals("3.5.0", c1Row.release().version());
		String projected = ev.projectedVersion();
		assertNotNull(projected);
		assertNotEquals(peek2, projected, "the overload follows the dependency as the mint does");
		assertEquals(peek2, peek(fsUuid), "the preview changed nothing");
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		ReleaseData created = sharedReleaseService.getReleaseData(explained.release().orElseThrow()).orElseThrow();
		assertEquals(projected, created.getVersion());
		assertEquals(2, count(fsUuid));
	}
}
