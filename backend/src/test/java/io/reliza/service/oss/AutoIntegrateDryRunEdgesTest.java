/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateReleaseSource;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateResult;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.VersionAssignmentRepository;
import io.reliza.service.ReleaseService;
import io.reliza.versioning.VersionApi.ActionEnum;
import io.reliza.ws.App;

/**
 * TPF-119 mutation round: what AutoIntegrateDryRunTest leaves open in the evaluator and in the version
 * peek it projects with. An ignored dependency, a dependency whose component and branch are gone, a JOB
 * without a release; the rows being unmodifiable; the transaction being read-only; the bump action reaching
 * the projected version (visible only on a semver feature set: the default Branch.Micro ignores it); an OPEN
 * assignment being the projection, of the right type; the followed version's rules on the mint's side and on
 * the old getNextVersion's; and the lock the old peek takes and the new one does not.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateDryRunEdgesTest extends AutoIntegrateDryRunTestBase {

	@Autowired private VersionAssignmentRepository versionAssignmentRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	private Component semverProduct(Organization org) throws Exception {
		return componentService.createComponent("edgeSemverProduct_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "semver", null, WU);
	}

	// ---------------- the rows ----------------

	@Test
	public void anIgnoredDependencyIsShownAsIgnoredAndIsNeverAParent() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeC1");
		Comp c2 = component(org, "edgeC2");
		ReleaseData r1 = assembled(org, c1, "1.0.0");
		assembled(org, c2, "2.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED),
				dep(c2, StatusEnum.IGNORED));

		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);

		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		AutoIntegrateDependencyRow ignored = row(ev, c2);
		assertEquals(StatusEnum.IGNORED, ignored.status());
		assertEquals(AutoIntegrateReleaseSource.NONE, ignored.source());
		assertNull(ignored.release());
		assertEquals("ignored", ignored.note());
		assertEquals(c2.branchUuid(), ignored.branch().uuid());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(List.of(r1.getUuid()), parentsOf(explained.release().orElseThrow()));
	}

	@Test
	public void aDependencyWhoseComponentAndBranchAreGoneIsARowWithNoNames() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeC1");
		assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		UUID ghostComponent = UUID.randomUUID();
		UUID ghostBranch = UUID.randomUUID();
		ChildComponent ghost = ChildComponent.builder().uuid(ghostComponent).branch(ghostBranch)
				.status(StatusEnum.TRANSIENT).build();
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED), ghost);

		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);

		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		AutoIntegrateDependencyRow r = ev.dependencies().stream().filter(x -> x.component().uuid().equals(ghostComponent))
				.findFirst().orElseThrow();
		assertNull(r.component().name(), "an absent component has no name, not an empty one");
		assertEquals(ghostBranch, r.branch().uuid());
		assertNull(r.branch().name());
		assertEquals(AutoIntegrateReleaseSource.NONE, r.source());
		assertNull(r.release());
		assertEquals("branch " + ghostBranch + " not found", r.note());
	}

	@Test
	public void aJobDependencyWithoutAReleaseDoesNotBlock() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeC1");
		Comp job = component(org, "edgeJob");
		assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED),
				dep(job, StatusEnum.JOB));

		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);

		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		assertEquals("no release on branch " + job.branchName(), row(ev, job).note());
		assertEquals(StatusEnum.JOB, row(ev, job).status());
	}

	@Test
	public void everyOutcomeAnswersUnmodifiableRowsAndOnlyTheDecidedFields() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeC1");
		Comp missing = component(org, "edgeMissing");
		assembled(org, c1, "1.0.0");
		Component product = product(org);

		UUID disabled = featureSet(product, "fsDisabled");
		AutoIntegrateEvaluation d = releaseService.evaluateAutoIntegrate(
				configure(disabled, AutoIntegrateState.DISABLED, dep(c1, StatusEnum.REQUIRED)));
		assertEquals(AutoIntegrateOutcome.AUTO_INTEGRATE_DISABLED, d.outcome());
		assertNull(d.note());
		assertThrows(UnsupportedOperationException.class, () -> d.dependencies().add(null));

		UUID blocked = featureSet(product, "fsBlocked");
		AutoIntegrateEvaluation b = releaseService.evaluateAutoIntegrate(
				configure(blocked, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED), dep(missing, StatusEnum.REQUIRED)));
		assertEquals(AutoIntegrateOutcome.REQUIRED_DEPENDENCY_WITHOUT_RELEASE, b.outcome());
		assertNull(b.note(), "the reason is on the rows, not on the evaluation");
		assertNull(b.existingRelease());
		assertNull(b.projectedVersion());
		assertThrows(UnsupportedOperationException.class, () -> b.dependencies().add(null));

		UUID creating = featureSet(product, "fsCreating");
		BranchData fs = configure(creating, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		AutoIntegrateEvaluation w = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, w.outcome());
		assertNull(w.note());
		assertThrows(UnsupportedOperationException.class, () -> w.dependencies().add(null));

		releaseService.autoIntegrateFeatureSetOnDemand(fs).orElseThrow();
		AutoIntegrateEvaluation m = releaseService.evaluateAutoIntegrate(read(creating));
		assertEquals(AutoIntegrateOutcome.MATCHING_PRODUCT_RELEASE_EXISTS, m.outcome());
		assertNull(m.note());
		assertThrows(UnsupportedOperationException.class, () -> m.dependencies().add(null));
	}

	@Test
	public void theEvaluatorRunsInAReadOnlyTransaction() throws Exception {
		Method m = ReleaseService.class.getMethod("evaluateAutoIntegrate", BranchData.class);
		Transactional t = AnnotationUtils.findAnnotation(m, Transactional.class);
		assertTrue(t != null && t.readOnly(), "the dry run writes nothing, and the database is told so");
	}

	// ---------------- the projected version ----------------

	@Test
	public void theBumpActionOfTheParentsReachesTheProjectedVersion() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeAction");
		assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(semverProduct(org), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		releaseService.autoIntegrateFeatureSetOnDemand(read(fsUuid)).orElseThrow();
		assertEquals("0.0.1-fs", peek(fsUuid), "the old peek of a feature set that does not follow: its own bump");
		assembled(org, c1, "2.0.0");
		BranchData fs = read(fsUuid);

		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);

		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		assertNotEquals(versionAssignmentService.getCurrentNextVersion(fsUuid, ActionEnum.BUMP, VersionTypeEnum.DEV),
				ev.projectedVersion(), "a major change of the dependency is not a plain bump");
		assertEquals("1.0.0-fs", ev.projectedVersion());
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(ev.projectedVersion(),
				sharedReleaseService.getReleaseData(explained.release().orElseThrow()).orElseThrow().getVersion());
	}

	@Test
	public void anOpenAssignmentOfTheFeatureSetIsTheProjectionAndOneOfAnotherTypeIsNot() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeOpen");
		assembled(org, c1, "1.0.0");
		Component product = product(org);

		UUID dev = featureSet(product, "fs");
		BranchData fs = configure(dev, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		String natural = releaseService.evaluateAutoIntegrate(fs).projectedVersion();
		assertTrue(versionAssignmentService.setNextVesion(dev, "fs.7"));
		assertEquals("fs.7", releaseService.evaluateAutoIntegrate(read(dev)).projectedVersion(), "the OPEN version is what is minted next");
		assertEquals("fs.7", peek(dev));
		assertNotEquals("fs.7", natural);

		UUID other = featureSet(product, "fsm");
		BranchData fsOther = configure(other, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		String naturalOther = releaseService.evaluateAutoIntegrate(fsOther).projectedVersion();
		VersionAssignment va = new VersionAssignment();
		va.setOrg(org.getUuid());
		va.setComponent(product.getUuid());
		va.setBranch(other);
		va.setVersion("marketing-9");
		va.setAssignmentType(VersionAssignment.AssignmentTypeEnum.OPEN);
		va.setVersionType(VersionTypeEnum.MARKETING);
		versionAssignmentRepository.save(va);
		assertEquals(naturalOther, releaseService.evaluateAutoIntegrate(read(other)).projectedVersion(),
				"an OPEN assignment of the marketing type is not the DEV projection");
	}

	@Test
	public void aFollowedFeatureSetProjectsTheVersionTheMintFollows() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c = component(org, "edgeFollowed");
		ReleaseData r345 = assembled(org, c, "3.4.5");
		UUID fsUuid = featureSet(product(org), "fs");
		ChildComponent followed = dep(c, StatusEnum.REQUIRED);
		followed.setIsFollowVersion(true);
		configure(fsUuid, AutoIntegrateState.ENABLED, followed);
		assertEquals("3.4.5", releaseService.evaluateAutoIntegrate(read(fsUuid)).projectedVersion(),
				"the latest assembled release of the followed branch");

		release(org, c, "3.6.0", ReleaseLifecycle.DRAFT);
		assertEquals("3.4.5", releaseService.evaluateAutoIntegrate(read(fsUuid)).projectedVersion(),
				"a draft of the followed branch is not followed");

		assembled(org, c, "3.5.0");
		assertEquals("3.5.0", releaseService.evaluateAutoIntegrate(read(fsUuid)).projectedVersion());

		ChildComponent pinned = dep(c, StatusEnum.REQUIRED);
		pinned.setIsFollowVersion(true);
		pinned.setRelease(r345.getUuid());
		configure(fsUuid, AutoIntegrateState.ENABLED, pinned);
		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(read(fsUuid));
		assertEquals("3.4.5", ev.projectedVersion(), "the pinned release is followed, not the newer one");
		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(read(fsUuid));
		assertEquals("3.4.5", sharedReleaseService.getReleaseData(explained.release().orElseThrow()).orElseThrow().getVersion());
	}

	@Test
	public void theOldPeekOfAFollowingFeatureSetStartsFromItsOwnLatestReleaseOfAnyLifecycle() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c = component(org, "edgePeek");
		assembled(org, c, "3.4.5");
		UUID fsUuid = featureSet(product(org), "fs");
		ChildComponent followed = dep(c, StatusEnum.REQUIRED);
		followed.setIsFollowVersion(true);
		configure(fsUuid, AutoIntegrateState.ENABLED, followed);
		String before = peek(fsUuid);

		ossReleaseService.createRelease(ReleaseDto.builder().component(read(fsUuid).getComponent()).branch(fsUuid)
				.org(org.getUuid()).status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).version("3.9.0").build(),
				WU, false, true);

		assertEquals("3.9.0-0", peek(fsUuid), "a draft of its own counts");
		assertNotEquals(before, peek(fsUuid));
	}

	@Test
	public void theOldPeekOfAFeatureSetThatFollowsNothingDoesNotStartFromItsOwnLatestRelease() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c = component(org, "edgePeekNoFollow");
		assembled(org, c, "3.4.5");
		UUID fsUuid = featureSet(product(org), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(c, StatusEnum.REQUIRED));
		String before = peek(fsUuid);

		ossReleaseService.createRelease(ReleaseDto.builder().component(read(fsUuid).getComponent()).branch(fsUuid)
				.org(org.getUuid()).status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).version("3.9.0").build(),
				WU, false, true);

		assertEquals(before, peek(fsUuid), "a feature set that follows nothing does not start from its own draft");
		assertNotEquals("3.9.0-0", peek(fsUuid));
	}

	// ---------------- the lock of the peek ----------------

	/** Holds the OPEN assignment row of the feature set locked until released; the peeks run meanwhile. */
	@Test
	public void theOldPeekWaitsForTheOpenAssignmentRowAndTheNewOneDoesNot() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "edgeLock");
		assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		assertTrue(versionAssignmentService.setNextVesion(fsUuid, "fs.7"));
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
				jdbcTemplate.queryForList("select uuid from rearm.version_assignments where branch = ?"
						+ " and assignment_type = 'OPEN' for update", fsUuid);
				locked.countDown();
				try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}));
			assertTrue(locked.await(30, TimeUnit.SECONDS), "the holder has the row");

			Future<String> old = pool.submit(() -> versionAssignmentService.getCurrentNextVersion(fsUuid, VersionTypeEnum.DEV));
			assertThrows(TimeoutException.class, () -> old.get(1500, TimeUnit.MILLISECONDS),
					"the old peek reads the OPEN assignment under its lock, as the mint does");

			Future<String> peek = pool.submit(() -> versionAssignmentService.getCurrentNextVersion(fsUuid, ActionEnum.BUMP,
					VersionTypeEnum.DEV));
			assertEquals("fs.7", peek.get(20, TimeUnit.SECONDS), "the dry run's peek takes no lock and so does not wait");

			release.countDown();
			holder.get(30, TimeUnit.SECONDS);
			assertEquals("fs.7", old.get(30, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}
}
