/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.Release;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;

/**
 * TEA-5 round 3, case 87 (T-7), against the test database: a change another writer commits to
 * the release while a first publish's merge is in flight survives the publish, because the act
 * makes its one write to the release row in the commit, on the row refreshed there. Round 4
 * (T-8): the publish runs as a request does, inside {@code asRequest}, whose pre-reads leave the
 * pre-change release managed in the act's persistence context; that is what makes the case red
 * on the round 2 code. Case 93 (T-10 points 6 and 7): a component or release archived before or
 * during a publish refuses it with nothing committed; a hide still hides.
 */
public class TeaPublicationConcurrentChangeIntegrationTest extends TeaPublicationIntegrationTestBase {

	@FunctionalInterface
	private interface Change {
		void run() throws Exception;
	}

	/**
	 * How the publishing thread runs: as a GraphQL request (its persistence context spans the act,
	 * with the fetcher's reads done first; round 4, T-8), or as part 6's background executor runs
	 * it (no context spans the act; each transaction reads afresh). A lost update shows in the
	 * first shape when the act saves a managed instance it did not refresh, and in the second
	 * when it saves an instance it read before the lease.
	 */
	enum Shape { REQUEST, BACKGROUND }

	/** Publishes {@code release} with the merge held open while {@code change} commits. */
	private TeaPublishResult publishAround(Shape shape, UUID release, Change change) throws Exception {
		return publishAround(shape, release, null, 0, change);
	}

	/** As above, holding open only the merge of {@code mergeOf} after {@code skip} of its merges passed. */
	private TeaPublishResult publishAround(Shape shape, UUID release, UUID mergeOf, int skip, Change change)
			throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGateSkip.set(skip);
		mergeGateRelease = mergeOf;
		mergeGate = gate;
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<TeaPublishResult> publish = pool.submit(() -> Shape.REQUEST == shape
					? asRequest(release, () -> publish(release, TeaPublishMode.PUBLISH))
					: publish(release, TeaPublishMode.PUBLISH));
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the publish never reached the merge");
			change.run();
			gate.countDown();
			return publish.get(120, TimeUnit.SECONDS);
		} finally {
			mergeGate = null;
			mergeGateRelease = null;
			mergeGateSkip.set(0);
			gate.countDown();
			pool.shutdownNow();
		}
	}

	private static boolean has(List<ReleaseUpdateEvent> events, ReleaseUpdateScope scope, ReleaseUpdateAction action,
			String oldValue, String newValue) {
		return events.stream().anyMatch(e -> scope == e.rus() && action == e.rua()
				&& java.util.Objects.equals(oldValue, e.oldValue()) && java.util.Objects.equals(newValue, e.newValue()));
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aLifecycleChangeDuringAFirstPublishSurvivesIt(Shape shape) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		TeaPublishResult r = publishAround(shape, w.release(),
				() -> ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.CANCELLED, WU));

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(1, r.collection().getVersion());
		ReleaseData rd = stored(w.release());
		assertEquals(ReleaseLifecycle.CANCELLED, rd.getLifecycle());
		List<ReleaseUpdateEvent> events = rd.getUpdateEvents();
		assertTrue(has(events, ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED, "ASSEMBLED", "CANCELLED"),
				events.toString());
		assertTrue(has(events, ReleaseUpdateScope.TEA_PUBLICATION, ReleaseUpdateAction.ADDED, null, "v1"));
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow();
		assertEquals(List.of(TeaAggregates.defaultAggregate(head)), rd.getSyntheticArtifacts());
		assertEquals(1, events(w.release(), ReleaseUpdateScope.SYNTHETIC_ARTIFACT).size());
		TeaPublicationExposure e = teaPublicationService.exposureOf(head);
		assertEquals(TeaExposureState.CONCEALED, e.state());
		assertEquals(TeaConcealReason.LIFECYCLE_BELOW_ASSEMBLED, e.concealedBecause());
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void anArtifactAddedDuringAFirstPublishSurvivesIt(Shape shape) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID second = seedBom("second.cdx.json");

		TeaPublishResult r = publishAround(shape, w.release(), () -> releaseService.addArtifact(second, w.release(), WU));

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		ReleaseData rd = stored(w.release());
		assertEquals(List.of(w.inventory(), second), rd.getArtifacts());
		List<ReleaseUpdateEvent> artifactEvents = events(w.release(), ReleaseUpdateScope.ARTIFACT);
		assertTrue(artifactEvents.stream().anyMatch(e -> ReleaseUpdateAction.ADDED == e.rua()
				&& second.equals(e.objectId())), artifactEvents.toString());
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow();
		assertEquals(List.of(TeaAggregates.defaultAggregate(head)), rd.getSyntheticArtifacts());
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aChildLifecycleChangeDuringTheProductMergeSurvivesTheCascade(Shape shape) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World child = componentRelease("pkg:generic/pump@1.0.0");
		UUID product = product(List.of(child.release()));

		TeaPublishResult r = publishAround(shape, product,
				() -> ossReleaseService.updateReleaseLifecycle(child.release(), ReleaseLifecycle.READY_TO_SHIP, WU));

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(TeaChildOutcome.PUBLISHED, r.children().get(0).outcome());
		ReleaseData crd = stored(child.release());
		assertEquals(ReleaseLifecycle.READY_TO_SHIP, crd.getLifecycle());
		assertTrue(has(crd.getUpdateEvents(), ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED, "ASSEMBLED",
				"READY_TO_SHIP"), crd.getUpdateEvents().toString());
		TeaPublicationData childHead = teaPublicationService.getPublicationOfRelease(child.release()).orElseThrow();
		assertEquals(r.publication().getUuid(), childHead.getCascadeOf());
		assertEquals(List.of(TeaAggregates.defaultAggregate(childHead)), crd.getSyntheticArtifacts());
	}

	/**
	 * The cascade's other window (round 4, T-8 point 4): the change commits while the child's own
	 * merge runs, after the child's rows were read under its lease. The scenario above changes the
	 * child during the product's merge, before the child's act reads it, so no code that refreshes
	 * at the child's start can lose that change; this one is the cascade form of the lost update.
	 */
	@ParameterizedTest
	@EnumSource(Shape.class)
	void aChildLifecycleChangeDuringTheChildsOwnMergeSurvivesTheCascade(Shape shape) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World child = componentRelease("pkg:generic/pump@1.0.0");
		UUID product = product(List.of(child.release()));

		// the product's merge merges the child's documents first; the child's own merge is the next one
		TeaPublishResult r = publishAround(shape, product, child.release(), 1,
				() -> ossReleaseService.updateReleaseLifecycle(child.release(), ReleaseLifecycle.READY_TO_SHIP, WU));

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(TeaChildOutcome.PUBLISHED, r.children().get(0).outcome());
		ReleaseData crd = stored(child.release());
		assertEquals(ReleaseLifecycle.READY_TO_SHIP, crd.getLifecycle());
		assertTrue(has(crd.getUpdateEvents(), ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED, "ASSEMBLED",
				"READY_TO_SHIP"), crd.getUpdateEvents().toString());
		TeaPublicationData childHead = teaPublicationService.getPublicationOfRelease(child.release()).orElseThrow();
		assertEquals(List.of(TeaAggregates.defaultAggregate(childHead)), crd.getSyntheticArtifacts());
	}

	// ---------------------------------------------------------------- 93

	/** The aggregate the failed act archived: the one artifact it handed to archiveArtifact. */
	private UUID archivedAggregate() {
		ArgumentCaptor<UUID> archived = ArgumentCaptor.forClass(UUID.class);
		verify(artifactService, times(1)).archiveArtifact(archived.capture(), any());
		return archived.getValue();
	}

	/** No product path archives a release; the status is written through the release funnel, as an import would. */
	private void archiveReleaseRow(UUID release) throws Exception {
		Release r = sharedReleaseService.getRelease(release).orElseThrow();
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		rd.setStatus(ReleaseStatus.ARCHIVED);
		ossReleaseService.saveRelease(r, rd, WU, false, AcollectionMode.SKIP);
	}

	private void assertRefusedWithNothingCommitted(World w, ExecutionException e, TeaPublishRefusal reason) {
		assertTrue(e.getCause() instanceof TeaPublishRefusedException, String.valueOf(e.getCause()));
		assertEquals(reason, ((TeaPublishRefusedException) e.getCause()).reason(), e.getCause().getMessage());
		assertTrue(teaPublicationService.getPublicationOfRelease(w.release()).isEmpty(), "no head");
		ReleaseData rd = stored(w.release());
		assertTrue(rd.getSyntheticArtifacts().isEmpty(), rd.getSyntheticArtifacts().toString());
		assertTrue(events(w.release(), ReleaseUpdateScope.TEA_PUBLICATION).isEmpty());
	}

	@Test
	void aComponentArchivedAfterTheRequestsReadsIsRefusedAtTheGate() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		CountDownLatch read = new CountDownLatch(1);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<TeaPublishResult> publish = pool.submit(() -> asRequest(w.release(), () -> {
				read.countDown();
				assertTrue(go.await(60, TimeUnit.SECONDS));
				return publish(w.release(), TeaPublishMode.PUBLISH);
			}));
			assertTrue(read.await(60, TimeUnit.SECONDS));
			componentService.archiveComponent(w.component(), WU);
			go.countDown();
			ExecutionException e = assertThrows(ExecutionException.class, () -> publish.get(120, TimeUnit.SECONDS));
			assertRefusedWithNothingCommitted(w, e, TeaPublishRefusal.COMPONENT_ARCHIVED);
		} finally {
			go.countDown();
			pool.shutdownNow();
		}
		assertTrue(uploaded.isEmpty(), "nothing generated or uploaded");
		verify(artifactService, never()).archiveArtifact(any(), any());
	}

	@Test
	void aComponentArchivedDuringTheMergeIsRefusedByTheCommit() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		ExecutionException e = assertThrows(ExecutionException.class, () -> publishAround(Shape.REQUEST, w.release(),
				() -> componentService.archiveComponent(w.component(), WU)));

		assertRefusedWithNothingCommitted(w, e, TeaPublishRefusal.COMPONENT_ARCHIVED);
		ArtifactData orphan = artifactService.getArtifactData(archivedAggregate()).orElseThrow();
		assertEquals(StatusEnum.ARCHIVED, orphan.getStatus());
		ComponentData cd = getComponentService.getComponentData(w.component()).orElseThrow();
		assertEquals(StatusEnum.ARCHIVED, cd.getStatus());
		assertNotNull(cd.getTeaUuid(), "the id minted before the archive is kept");
	}

	@Test
	void aReleaseArchivedDuringTheMergeIsRefusedByTheCommit() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		ExecutionException e = assertThrows(ExecutionException.class, () -> publishAround(Shape.REQUEST, w.release(),
				() -> archiveReleaseRow(w.release())));

		assertRefusedWithNothingCommitted(w, e, TeaPublishRefusal.RELEASE_ARCHIVED);
		assertEquals(ReleaseStatus.ARCHIVED, stored(w.release()).getStatus());
		ArtifactData orphan = artifactService.getArtifactData(archivedAggregate()).orElseThrow();
		assertEquals(StatusEnum.ARCHIVED, orphan.getStatus());
	}

	@Test
	void aPublishedReleaseWhoseComponentIsArchivedIsStillHidden() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow();
		TeaPublicationExposure before = teaPublicationService.exposureOf(head);

		componentService.archiveComponent(w.component(), WU);
		TeaPublicationExposure archived = teaPublicationService.exposureOf(head);
		assertEquals(before.state(), archived.state(), "no new concealment for an archived component");
		assertEquals(before.concealedBecause(), archived.concealedBecause());

		TeaPublicationData hidden = asRequest(w.release(), () -> teaPublicationService.hide(w.release(), null, WU));

		assertEquals(TeaPublicationState.HIDDEN, hidden.getState());
		assertTrue(has(stored(w.release()).getUpdateEvents(), ReleaseUpdateScope.TEA_PUBLICATION,
				ReleaseUpdateAction.REMOVED, "PUBLISHED v1", "HIDDEN"));
	}
}
