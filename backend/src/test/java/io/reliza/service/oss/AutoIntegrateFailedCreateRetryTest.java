/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.BranchData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FlowControl;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.AutoIntegrateDispatcher;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.service.oss.OssReleaseService.UnreferenceableParents;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A product release that FAILS to be created must leave its trigger queued for retry.
 *
 * <p>{@code createProductRelease} catches its own create errors and reports them by returning an
 * empty Optional. The auto-integrate caller used to ignore that return, so the feature set counted
 * as integrated: {@code allOk} stayed true, the release's {@code autoIntegrateRequestedAt} marker
 * was cleared, no backoff fence was armed, and the scheduler drain never revisited it. A transient
 * failure (pool timeout, deadlock, momentary DB error) therefore lost a product release
 * PERMANENTLY, leaving one ERROR line as the only evidence.
 *
 * <p>Note the asymmetry this closes: version exhaustion inside
 * {@link OssReleaseService#createProductRelease} throws and so has always been fenced and retried,
 * while a failed create returned empty and was not.
 *
 * <p>Drives the real queue path ({@code processAutoIntegrateForRelease}) and asserts on persisted
 * {@code flow_control}, because the bug is precisely that the in-memory call returned normally --
 * only the durable marker distinguishes "integrated" from "silently lost".
 *
 * <p>Uses {@code classes={App.class}} without listing {@code TestAsyncConfig}, unlike the sibling
 * {@code AutoIntegrateProductsTest}. Component scanning still picks it up, so a plain {@code @Async}
 * runs inline here; only the {@code autoIntegrateExecutor} pool stays real, which keeps the
 * create-time auto-integrate hop the first test waits on asynchronous. The bean overrides below
 * fork their own application context; the suite's budget for those extra pools is set centrally
 * by the surefire cap in pom.xml.
 */
@SpringBootTest(classes = {App.class})
public class AutoIntegrateFailedCreateRetryTest {

	@Autowired private BranchService branchService;
	@Autowired private ComponentService componentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private VersionAssignmentService versionAssignmentService;
	@Autowired private PlatformTransactionManager transactionManager;

	// Spy, not mock: every other collaborator on this path must stay real so the test exercises
	// the genuine queue/claim/backoff machinery and only the create outcome is forced.
	@MockitoSpyBean private OssReleaseService ossReleaseService;
	@MockitoSpyBean private AutoIntegrateDispatcher autoIntegrateDispatcher;

	@Test
	void failedProductCreateLeavesTheReleaseQueuedForRetry() throws Exception {
		Organization org = testInitializer.obtainOrganization();

		Component component = componentService.createComponent("failCreateC_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();

		Component product = componentService.createComponent("failCreateProd_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch featureSetBranch = branchService.createBranch("failCreateFeatureSet",
			product.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		BranchData fsSeed = branchService.getBranchData(featureSetBranch.getUuid()).get();
		branchService.updateBranch(BranchDto.builder()
			.uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
			.type(fsSeed.getType())
			.dependencies(List.of(ChildComponent.builder().uuid(component.getUuid())
				.branch(componentBranch.getUuid()).status(StatusEnum.REQUIRED).build()))
			.autoIntegrate(AutoIntegrateState.ENABLED).build(), WhoUpdated.getTestWhoUpdated());

		// Force the exact failure createProductRelease reports for a create error.
		doReturn(Optional.empty()).when(ossReleaseService)
			.createProductRelease(any(BranchData.class), any(UUID.class), anyCollection());

		Release release = ossReleaseService.createRelease(ReleaseDto.builder()
			.component(component.getUuid()).branch(componentBranch.getUuid()).org(org.getUuid())
			.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
			.build(), WhoUpdated.getTestWhoUpdated());

		// Let the REAL create-time worker do the integration rather than calling
		// processAutoIntegrateForRelease here: creating an ASSEMBLED release already queues an
		// after-commit hop onto the bounded executor, and a direct call races it, loses the claim
		// (claimAutoIntegrate returns 0) and returns having done nothing, so the assertions below
		// would be reading whatever the in-flight worker had written so far rather than a settled
		// outcome, and would pass or fail on timing.
		FlowControl fc = awaitFlowControl(release.getUuid(), SETTLED);
		assertNotNull(fc, "flow_control should exist after a failed auto-integrate");
		assertNotNull(fc.autoIntegrateRequestedAt(),
			"a failed product-release create must leave the release QUEUED; a cleared marker means "
			+ "the failure was treated as success and the scheduler will never retry it");
		assertNotNull(fc.autoIntegrateSkipUntil(),
			"the backoff fence must be armed so the scheduler drain retries after the cooldown");
		assertNotNull(fc.autoIntegrateFailureCount(), "the failure should be counted");
		assertTrue(fc.autoIntegrateFailureCount() >= 1,
			"expected at least one recorded failure, got " + fc.autoIntegrateFailureCount());

		// Pin that the recorded failure was the STUBBED create and not some other exception on the
		// path (feature-set lookup, dependency gather, notification) -- those would leave byte
		// identical state and the assertions above would pass for the wrong reason.
		// Matched on THIS test's feature set: the sibling test deliberately leaves a release queued,
		// and its create-time worker can call the shared spy while this test is still running.
		verify(ossReleaseService).createProductRelease(
			argThat(fs -> null != fs && featureSetBranch.getUuid().equals(fs.getUuid())),
			any(UUID.class), anyCollection());

		// Nothing was actually created.
		assertTrue(sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).isEmpty(),
			"no product release should exist when the create was forced to fail");
	}

	/**
	 * The claim lease and the failure fence are becoming separate facts about a release.
	 *
	 * <p>They used to share {@code autoIntegrateSkipUntil}: "a worker is running this right now"
	 * and "this failed, do not retry before X". While they did, clearing the fence so an operator's
	 * fix could take effect also cancelled any in-flight lease, letting a second worker claim a
	 * release the first was still integrating. That regression shipped once and was reverted in
	 * a850f64f.
	 *
	 * <p>This is step 1 of a two-release migration, so the claim still writes both keys for
	 * compatibility with a pod running the previous release. What is newly guaranteed here, and
	 * what this pins, is that {@code autoIntegrateClaimedAt} tracks the lease independently and
	 * that a FAILED run releases it -- leaving the retry gated by the fence alone, rather than
	 * held for the whole lease window after its fence has expired.
	 */
	@Test
	void aFailedRunReleasesItsClaimAndLeavesOnlyTheFence() throws RelizaException {
		UUID release = scratchRelease("leaseVsFence");

		ossReleaseService.markAutoIntegrateRequestedTx(release);
		assertEquals(1, ossReleaseService.claimAutoIntegrateTx(release), "the first claim should win");
		assertEquals(0, ossReleaseService.claimAutoIntegrateTx(release),
			"a second claim must be refused while the first worker holds the release");

		FlowControl claimed = flowControlOf(release);
		assertNotNull(claimed.autoIntegrateClaimedAt(), "the claim must stamp the lease key");

		// The run fails: the lease is released, the fence is armed, and the fence alone gates the
		// retry. Without the release, claimedAt would hold the release for the full lease window
		// even once the fence expired.
		ossReleaseService.recordAutoIntegrateFailureTx(release);
		FlowControl failed = flowControlOf(release);
		assertNull(failed.autoIntegrateClaimedAt(),
			"a failed run must release its claim; leaving it holds the release past its fence");
		assertNotNull(failed.autoIntegrateSkipUntil(), "the failure fence must be armed");
		assertEquals(0, ossReleaseService.claimAutoIntegrateTx(release),
			"the fence should hold the retry off");
	}

	/**
	 * Under real concurrency, exactly ONE claim may win.
	 *
	 * <p>The single-threaded test above shows the second claim is refused, but that is the easy
	 * case: the two calls are ordered. This fires them simultaneously, which is the situation the
	 * claim actually exists for -- the after-commit worker and the scheduler drain reaching the
	 * same queued release at once. If the {@code UPDATE ... WHERE} predicate were not evaluated
	 * atomically per row, more than one caller would see an unclaimed release and integrate it
	 * concurrently, which is what produced duplicate same-second product releases historically.
	 *
	 * <p>Also covers the state the fence leaves behind: once the run fails, no amount of
	 * concurrency may get past the backoff.
	 */
	@Test
	void onlyOneOfManySimultaneousClaimsWins() throws Exception {
		final int racers = 6;   // bounded by the surefire connection-pool cap, see pom.xml
		UUID release = scratchRelease("claimRace");
		ossReleaseService.markAutoIntegrateRequestedTx(release);

		assertEquals(1, countWinners(release, racers),
			"exactly one concurrent claim may win; more than one means two workers can integrate "
			+ "the same release at the same time");

		// The winner's run fails: lease released, fence armed. Now nothing may claim it.
		ossReleaseService.recordAutoIntegrateFailureTx(release);
		assertEquals(0, countWinners(release, racers),
			"while the failure fence holds, no concurrent claim may win");
	}

	/** Fires {@code racers} simultaneous claims at one release and returns how many won. */
	private int countWinners(UUID releaseUuid, int racers) throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(racers);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < racers; i++) {
				results.add(pool.submit(() -> {
					gate.await();
					return ossReleaseService.claimAutoIntegrateTx(releaseUuid);
				}));
			}
			gate.countDown();
			int won = 0;
			for (Future<Integer> f : results) {
				won += f.get(60, TimeUnit.SECONDS);
			}
			return won;
		} finally {
			pool.shutdownNow();
		}
	}

	private FlowControl flowControlOf(UUID releaseUuid) {
		return sharedReleaseService.getRelease(releaseUuid).orElseThrow().getFlowControl();
	}

	/** Minimal ACTIVE release on its own component; no feature set, so nothing integrates it. */
	private UUID scratchRelease(String prefix) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent(prefix + "_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch b = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder()
			.component(c.getUuid()).branch(b.getUuid()).org(org.getUuid())
			.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).version("1.0.0")
			.build(), WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	/** A worker is done with the release: it recorded a failure, or cleared the queue entry. */
	private static final Predicate<FlowControl> SETTLED = fc ->
		fc == null || fc.autoIntegrateRequestedAt() == null || fc.autoIntegrateFailureCount() != null;

	/**
	 * Polls {@code flow_control} for up to 30 s until {@code until} holds, and returns it. Returns the
	 * last observed value on timeout so the caller's assertions report the actual state rather than a
	 * bare timeout.
	 */
	private FlowControl awaitFlowControl(UUID releaseUuid, Predicate<FlowControl> until) throws InterruptedException {
		FlowControl fc = null;
		long deadline = System.currentTimeMillis() + 30000;
		while (System.currentTimeMillis() < deadline) {
			fc = flowControlOf(releaseUuid);
			if (until.test(fc)) return fc;
			Thread.sleep(200);
		}
		return fc;
	}

	/**
	 * A failed integration on the BATCH path must leave the release queued for the scheduler.
	 *
	 * <p>Bulk creates pass {@code deferAutoIntegrate=true}, which skips {@code autoIntegrateProducts}
	 * and with it its queue marker, so autoIntegrateProductsForBatch writes the markers itself, for
	 * the whole batch, before integrating. A failure then arms the backoff on that marker exactly
	 * like the single-release path. (The batch loop once discarded both the thrown exception and the
	 * {@code allOk=false} return, so a failure had nothing for the drain to find and the product
	 * release was lost permanently.) Called here with no transaction, so the integration runs inline.
	 */
	@Test
	void batchIntegrationFailureQueuesTheReleaseForRetry() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent("batchC_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		Component product = componentService.createComponent("batchProd_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch featureSetBranch = branchService.createBranch("batchFeatureSet", product.getUuid(),
			BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		BranchData fsSeed = branchService.getBranchData(featureSetBranch.getUuid()).get();
		branchService.updateBranch(BranchDto.builder()
			.uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
			.type(fsSeed.getType())
			.dependencies(List.of(ChildComponent.builder().uuid(component.getUuid())
				.branch(componentBranch.getUuid()).status(StatusEnum.REQUIRED).build()))
			.autoIntegrate(AutoIntegrateState.ENABLED).build(), WhoUpdated.getTestWhoUpdated());

		doReturn(Optional.empty()).when(ossReleaseService)
			.createProductRelease(any(BranchData.class), any(UUID.class), anyCollection());

		// deferAutoIntegrate=true is what the bulk endpoint does, and is the whole point here.
		Release release = ossReleaseService.createRelease(ReleaseDto.builder()
			.component(component.getUuid()).branch(componentBranch.getUuid()).org(org.getUuid())
			.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
			.build(), WhoUpdated.getTestWhoUpdated(), false, true);

		FlowControl before = sharedReleaseService.getRelease(release.getUuid()).orElseThrow().getFlowControl();
		assertTrue(before == null || before.autoIntegrateRequestedAt() == null,
			"precondition: a deferred create must NOT be queued, otherwise this test proves nothing");

		ReleaseData rd = sharedReleaseService.getReleaseData(release.getUuid()).get();
		ossReleaseService.autoIntegrateProductsForBatch(List.of(rd));

		FlowControl fc = awaitFlowControl(release.getUuid(), SETTLED);
		assertNotNull(fc, "a failed batch integration must leave flow_control behind");
		assertNotNull(fc.autoIntegrateRequestedAt(),
			"a failed batch integration must QUEUE the release; without a marker the scheduler drain "
			+ "has nothing to find and the product release is lost permanently");
		assertEquals(1, fc.autoIntegrateFailureCount(),
			"exactly one failed run recorded on the marker the batch wrote up front");
	}

	/**
	 * A dependency release that is MISSING is not a cross-org data problem: it can be a release whose
	 * transaction has not committed yet (see aBatchIntegrateRacingTheBatchCommitIsNotLost). It must
	 * therefore NOT be skipped like a foreign parent -- it must leave
	 * the trigger queued for retry -- and, like the foreign case, nothing may be minted for it.
	 *
	 * <p>The visibility miss is simulated by making the parent lookup report every parent of this
	 * test's org as missing; everything else on the path is real.
	 */
	@Test
	void aMissingDependencyReleaseIsRetriedNotSkipped() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent("missingParentC_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		Component product = componentService.createComponent("missingParentProd_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch featureSetBranch = branchService.createBranch("missingParentFeatureSet", product.getUuid(),
			BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		BranchData fsSeed = branchService.getBranchData(featureSetBranch.getUuid()).get();
		branchService.updateBranch(BranchDto.builder()
			.uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
			.type(fsSeed.getType())
			.dependencies(List.of(ChildComponent.builder().uuid(component.getUuid())
				.branch(componentBranch.getUuid()).status(StatusEnum.REQUIRED).build()))
			.autoIntegrate(AutoIntegrateState.ENABLED).build(), WhoUpdated.getTestWhoUpdated());

		doAnswer(inv -> new UnreferenceableParents(new LinkedList<>(inv.<Collection<UUID>>getArgument(1)),
				new LinkedHashMap<>()))
			.when(ossReleaseService).findUnreferenceableParentReleases(eq(org.getUuid()), anyCollection());

		Release release = ossReleaseService.createRelease(ReleaseDto.builder()
			.component(component.getUuid()).branch(componentBranch.getUuid()).org(org.getUuid())
			.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
			.build(), WhoUpdated.getTestWhoUpdated());

		FlowControl fc = awaitFlowControl(release.getUuid(), SETTLED);
		assertNotNull(fc, "flow_control should exist after a deferred auto-integrate");
		assertNotNull(fc.autoIntegrateRequestedAt(),
			"a missing dependency release must leave the trigger QUEUED; a cleared marker means it was "
			+ "skipped like a cross-org one and the product release is lost");
		assertNotNull(fc.autoIntegrateFailureCount(), "the deferral should be counted as a retryable failure");
		// Reached step 7 (not skipped at 6b), and createProductRelease declined before the mint.
		verify(ossReleaseService).createProductRelease(
			argThat(fs -> null != fs && featureSetBranch.getUuid().equals(fs.getUuid())),
			any(UUID.class), anyCollection());
		assertTrue(versionAssignmentService.getLatestVersionAssignmentOfBranch(featureSetBranch.getUuid(), 10).isEmpty(),
			"no version may be minted while a dependency release is not visible");
		assertTrue(sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).isEmpty());
	}

	/**
	 * The batch path (addReleasesProgrammatic) creates its releases and requests their product
	 * auto-integration inside ONE transaction. The integration must not run before that commit: the
	 * triggering release is then invisible to the worker's transaction, so it reads as a MISSING
	 * parent, the product release is deferred, and the retry marker -- an UPDATE on a row not
	 * committed yet -- matches nothing. Before the fix autoIntegrateProductsForBatch was a bare
	 * {@code @Async} call made inside the transaction; in production a free pool thread ran it
	 * before the commit and the product release was lost (reproduced: no product release and
	 * flow_control=null after the commit).
	 *
	 * <p>This class does not list TestAsyncConfig, but component scanning of io.reliza.service picks
	 * it up, so a plain {@code @Async} runs INLINE here, while the named autoIntegrateExecutor the
	 * fix hops onto stays a real pool. So, inside the still-open transaction, neither the batch hop
	 * nor the feature set's integration may have been invoked -- with the old inline @Async the
	 * integration has already run by the time the call returns, deterministically. After the commit
	 * the hop is dispatched with the release and the product release exists.
	 */
	@Test
	void aBatchIntegrateRacingTheBatchCommitIsNotLost() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent("batchRaceC_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		Component product = componentService.createComponent("batchRaceProd_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch featureSetBranch = branchService.createBranch("batchRaceFeatureSet", product.getUuid(),
			BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		BranchData fsSeed = branchService.getBranchData(featureSetBranch.getUuid()).get();
		branchService.updateBranch(BranchDto.builder()
			.uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
			.type(fsSeed.getType())
			.dependencies(List.of(ChildComponent.builder().uuid(component.getUuid())
				.branch(componentBranch.getUuid()).status(StatusEnum.REQUIRED).build()))
			.autoIntegrate(AutoIntegrateState.ENABLED).build(), WhoUpdated.getTestWhoUpdated());

		UUID[] created = new UUID[1];
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			try {
				Release release = ossReleaseService.createRelease(ReleaseDto.builder()
					.component(component.getUuid()).branch(componentBranch.getUuid()).org(org.getUuid())
					.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
					.build(), WhoUpdated.getTestWhoUpdated(), false, true);
				created[0] = release.getUuid();
				ossReleaseService.autoIntegrateProductsForBatch(List.of(ReleaseData.dataFromRecord(release)));
				verify(autoIntegrateDispatcher, never()).asyncProcessBatch(
					argThat(uuids -> null != uuids && uuids.contains(release.getUuid())));
				verify(ossReleaseService, never()).integrateFeatureSetTx(
					argThat(fs -> null != fs && featureSetBranch.getUuid().equals(fs.getUuid())), any());
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		});

		verify(autoIntegrateDispatcher, timeout(30000)).asyncProcessBatch(List.of(created[0]));
		FlowControl fc = awaitFlowControl(created[0], SETTLED);
		verify(ossReleaseService).integrateFeatureSetTx(
			argThat(fs -> null != fs && featureSetBranch.getUuid().equals(fs.getUuid())), any());
		assertFalse(sharedReleaseService.listReleaseDataOfBranch(featureSetBranch.getUuid()).isEmpty(),
			"after the commit the dependency is visible, so the product release must exist; flow_control=" + fc);
	}

	/**
	 * A request written inside a transaction that STARTED before a worker's claim must survive that
	 * worker's clear. The clear keeps the queue entry only when requestedAt > claimedAt; stamping the
	 * request with now() -- the transaction's start -- dated it before the claim, so the clear wiped
	 * it and the re-trigger was lost. It is stamped with clock_timestamp() now.
	 */
	@Test
	void aRequestFromATransactionOlderThanTheClaimSurvivesTheClear() throws Exception {
		UUID release = scratchRelease("requestVsClaim");
		ossReleaseService.markAutoIntegrateRequestedTx(release);
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				try {
					// The first statement starts the transaction, and with it now().
					flowControlOf(release);
					Thread.sleep(100);
					// A worker claims it meanwhile, in its own transaction.
					assertEquals(1, other.submit(() -> ossReleaseService.claimAutoIntegrateTx(release))
						.get(30, TimeUnit.SECONDS), "the worker's claim should win");
					Thread.sleep(100);
					// A new request arrives, in the older transaction.
					ossReleaseService.markAutoIntegrateRequestedTx(release);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
		} finally {
			other.shutdownNow();
		}
		// The worker finishes its (earlier) run and clears.
		ossReleaseService.clearAutoIntegrateMarkerTx(release);
		FlowControl fc = flowControlOf(release);
		assertNotNull(fc, "the new request must survive the earlier run's clear");
		assertNotNull(fc.autoIntegrateRequestedAt(), "the new request must survive the earlier run's clear");
		assertNull(fc.autoIntegrateClaimedAt(), "the clear still drops the finished run's lease");
	}

	/** A component whose base branch is the one REQUIRED dependency of a new auto-integrating feature set. */
	private record Rig(Component component, Branch componentBranch, Branch featureSet) {}

	private Rig rig(Organization org, String prefix) throws RelizaException {
		Component component = componentService.createComponent(prefix + "C_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch componentBranch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		Component product = componentService.createComponent(prefix + "Prod_" + UUID.randomUUID(),
			org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null,
			WhoUpdated.getTestWhoUpdated());
		Branch featureSet = branchService.createBranch(prefix + "FeatureSet", product.getUuid(),
			BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		BranchData fsSeed = branchService.getBranchData(featureSet.getUuid()).get();
		branchService.updateBranch(BranchDto.builder()
			.uuid(fsSeed.getUuid()).name(fsSeed.getName()).versionSchema(fsSeed.getVersionSchema())
			.type(fsSeed.getType())
			.dependencies(List.of(ChildComponent.builder().uuid(component.getUuid())
				.branch(componentBranch.getUuid()).status(StatusEnum.REQUIRED).build()))
			.autoIntegrate(AutoIntegrateState.ENABLED).build(), WhoUpdated.getTestWhoUpdated());
		return new Rig(component, componentBranch, featureSet);
	}

	/**
	 * One release's failure OUTSIDE its integration -- here its claim throws -- must not stop the rest
	 * of the batch: the other two releases are still integrated, and the failed one stays queued for
	 * the drain. Called with no transaction, so the batch runs inline.
	 */
	@Test
	void oneReleaseFailingOutsideItsIntegrationDoesNotStopTheBatch() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		List<Rig> rigs = List.of(rig(org, "batchIso1"), rig(org, "batchIso2"), rig(org, "batchIso3"));
		List<ReleaseData> batch = new ArrayList<>();
		for (Rig r : rigs) {
			batch.add(ReleaseData.dataFromRecord(ossReleaseService.createRelease(ReleaseDto.builder()
				.component(r.component().getUuid()).branch(r.componentBranch().getUuid()).org(org.getUuid())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version("1.0.0")
				.build(), WhoUpdated.getTestWhoUpdated(), false, true)));
		}
		doThrow(new IllegalStateException("claim failed")).when(ossReleaseService)
			.claimAutoIntegrateTx(batch.get(0).getUuid());

		ossReleaseService.autoIntegrateProductsForBatch(batch);

		assertTrue(sharedReleaseService.listReleaseDataOfBranch(rigs.get(0).featureSet().getUuid()).isEmpty());
		assertNotNull(flowControlOf(batch.get(0).getUuid()).autoIntegrateRequestedAt(),
			"the release whose claim failed stays queued for the drain");
		for (int i = 1; i < 3; i++) {
			assertEquals(1, sharedReleaseService.listReleaseDataOfBranch(rigs.get(i).featureSet().getUuid()).size(),
				"release " + i + " of the batch must still be integrated");
		}
	}

}
