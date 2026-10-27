/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;

import io.reliza.model.Component;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;

/**
 * TEA-5 round 2, cases 64 and 65 (T-2), as round 3 amends them: both commit steps re-read the
 * head and the release rows, and build on what they read; the next version follows the stored
 * versions; a hide takes the per-release lock before its first read. Round 3, cases 83 and 84
 * (T-7): an act makes exactly one write to the release row, in its commit, with the aggregate's
 * bind staged into it, and the commits run under the lease the act took; a first publish that
 * fails after its upload archives the unbound aggregate.
 */
class TeaPublicationCommitTest extends TeaPublicationServiceTestBase {

	private CapturingAppender logs;
	private Level previousLevel;

	/** In-memory appender on the service's own logger; only the level and the message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@BeforeEach
	void attach() {
		logs = new CapturingAppender();
		logs.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class);
		previousLevel = logger.getLevel();
		Configurator.setLevel(TeaPublicationService.class.getName(), Level.WARN);
		logger.addAppender(logs);
	}

	@AfterEach
	void detach() {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class)).removeAppender(logs);
		Configurator.setLevel(TeaPublicationService.class.getName(), previousLevel);
		logs.stop();
	}

	private long warnings() {
		return logs.events.stream().filter(e -> Level.WARN == e.getLevel()).count();
	}

	/** Makes {@code refresh} of the head row turn it into what another process committed. */
	private void refreshTurnsTheHeadInto(TeaPublicationState state, int latestVersion) {
		doAnswer(inv -> {
			Object o = inv.getArgument(0);
			if (o instanceof TeaPublication p) {
				p.setState(state);
				p.setLatestVersion(latestVersion);
				TeaPublicationData d = TeaPublicationData.dataFromRecord(p);
				d.setState(state);
				d.setLatestVersion(latestVersion);
				p.setRecordData(d.toRecordData());
			}
			return null;
		}).when(entityManager).refresh(any());
	}

	/** A collection row another process committed at {@code version}, a copy of version 1. */
	private void storedVersion(UUID publication, int version) {
		TeaPublishedCollection v1 = collectionRows.get(publication + ":1");
		TeaPublishedCollection c = new TeaPublishedCollection();
		c.setUuid(publication);
		c.setVersion(version);
		c.setRelease(v1.getRelease());
		c.setRecordData(v1.getRecordData());
		c.setDocument(v1.getDocument());
		c.setDocumentSha256(v1.getDocumentSha256());
		collectionRows.put(publication + ":" + version, c);
	}

	// ---------------------------------------------------------------- 64

	@Test
	void theCommitRefreshesTheHeadAndTheReleaseAndAppendsTheEventToThatRelease() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		documents.put(release.getUuid(), bom("pkg:generic/b@1"));
		clearInvocations(teaPublishLock, publications, entityManager, shared, oss);

		publish(TeaPublishMode.REPUBLISH);

		ArgumentCaptor<Object> refreshed = ArgumentCaptor.forClass(Object.class);
		InOrder order = inOrder(teaPublishLock, publications, entityManager, shared, oss);
		order.verify(teaPublishLock).acquire(eq(release.getUuid()), any());
		// readFresh at the start of the act, then again at the start of the commit
		order.verify(publications).findByRelease(release.getUuid());
		order.verify(entityManager).refresh(any(TeaPublication.class));
		order.verify(shared).getRelease(release.getUuid());
		order.verify(entityManager).refresh(any(Release.class));
		order.verify(publications).findByRelease(release.getUuid());
		order.verify(entityManager).refresh(refreshed.capture());
		order.verify(shared).getRelease(release.getUuid());
		ArgumentCaptor<Release> refreshedRelease = ArgumentCaptor.forClass(Release.class);
		order.verify(entityManager).refresh(refreshedRelease.capture());
		// round 4 (T-10 point 6): the release's component row is refreshed after the release
		order.verify(entityManager).refresh(any(Component.class));
		ArgumentCaptor<Release> saved = ArgumentCaptor.forClass(Release.class);
		order.verify(oss).saveRelease(saved.capture(), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
		assertTrue(refreshed.getAllValues().get(0) instanceof TeaPublication);
		assertSame(refreshedRelease.getValue(), saved.getValue(), "the event goes on the refreshed release row");
	}

	@Test
	void theHideCommitRefreshesTheHeadAndTheRelease() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		clearInvocations(teaPublishLock, publications, entityManager, shared, oss);

		service.hide(release.getUuid(), null, WU);

		ArgumentCaptor<Object> refreshed = ArgumentCaptor.forClass(Object.class);
		InOrder order = inOrder(teaPublishLock, publications, entityManager, shared, oss);
		order.verify(teaPublishLock).acquire(eq(release.getUuid()), any());
		// readFresh at the start of the act, then again at the start of the commit
		order.verify(publications).findByRelease(release.getUuid());
		order.verify(entityManager).refresh(any(TeaPublication.class));
		order.verify(shared).getRelease(release.getUuid());
		order.verify(entityManager).refresh(any(Release.class));
		order.verify(publications).findByRelease(release.getUuid());
		order.verify(entityManager).refresh(refreshed.capture());
		order.verify(shared).getRelease(release.getUuid());
		ArgumentCaptor<Release> refreshedRelease = ArgumentCaptor.forClass(Release.class);
		order.verify(entityManager).refresh(refreshedRelease.capture());
		// round 4 (T-10 point 6): the release's component row is refreshed after the release
		order.verify(entityManager).refresh(any(Component.class));
		ArgumentCaptor<Release> saved = ArgumentCaptor.forClass(Release.class);
		order.verify(oss).saveRelease(saved.capture(), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
		assertSame(refreshedRelease.getValue(), saved.getValue());
	}

	@Test
	void aStaleHeadThatRefreshesToHiddenVersionTwoMakesTheCommitWriteVersionThree() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		UUID publication = head().getUuid();
		storedVersion(publication, 2);
		refreshTurnsTheHeadInto(TeaPublicationState.HIDDEN, 2);
		documents.put(release.getUuid(), bom("pkg:generic/c@1"));

		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
		assertEquals(3, r.collection().getVersion());
		assertEquals(TeaPublicationState.PUBLISHED, head().getState());
		assertEquals(3, head().getLatestVersion());
		List<ReleaseUpdateEvent> events = teaEvents(release.getUuid());
		ReleaseUpdateEvent last = events.get(events.size() - 1);
		assertEquals(ReleaseUpdateAction.CHANGED, last.rua());
		assertEquals("HIDDEN", last.oldValue());
		assertEquals("PUBLISHED v3", last.newValue());
	}

	@Test
	void aStoredVersionAheadOfTheHeadIsFollowedWithOneWarning() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		UUID publication = head().getUuid();
		storedVersion(publication, 2);
		documents.put(release.getUuid(), bom("pkg:generic/d@1"));
		long before = warnings();

		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(3, r.collection().getVersion());
		assertEquals(3, head().getLatestVersion());
		assertEquals(before + 1, warnings());
		String message = logs.events.stream().filter(e -> Level.WARN == e.getLevel()).reduce((a, b) -> b).orElseThrow()
				.getMessage().getFormattedMessage();
		assertTrue(message.contains(publication.toString()) && message.contains("1") && message.contains("2"), message);
		ReleaseUpdateEvent last = teaEvents(release.getUuid()).get(teaEvents(release.getUuid()).size() - 1);
		assertEquals("v2", last.oldValue());
		assertEquals("v3", last.newValue());
		// the next changed re-publish follows on without a duplicate key
		documents.put(release.getUuid(), bom("pkg:generic/e@1"));
		assertEquals(4, publish(TeaPublishMode.REPUBLISH).collection().getVersion());
	}

	@Test
	void theLatestCollectionForClassifyIsReadAtTheStoredMaximum() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		UUID publication = head().getUuid();
		storedVersion(publication, 2);
		clearInvocations(collections);

		assertEquals(TeaPublishOutcome.UNCHANGED, publish(TeaPublishMode.REPUBLISH).outcome());

		verify(collections).findVersion(publication, 2);
		verify(collections, never()).save(any());
		assertEquals(2, head().getLatestVersion(), "an unchanged publish heals a head that fell behind");
	}

	@Test
	void aHideCommitOnARowAlreadyHiddenOnceRefreshedWritesNothing() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		refreshTurnsTheHeadInto(TeaPublicationState.HIDDEN, 1);
		clearInvocations(publications, oss, hook);
		int events = teaEvents(release.getUuid()).size();

		TeaPublicationData d = service.commitHide(lease(release.getUuid()), release.getUuid(), null, WU);

		assertEquals(TeaPublicationState.HIDDEN, d.getState());
		verify(publications, never()).save(any());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
		verify(hook, never()).onReleaseTeaHidden(any(), any());
		assertEquals(events, teaEvents(release.getUuid()).size());
	}

	// ---------------------------------------------------------------- 65

	@Test
	void aHideWaitsForThePerReleaseLockBeforeItsFirstRead() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		CountDownLatch read = new CountDownLatch(1);
		when(shared.getRelease(eq(release.getUuid()))).thenAnswer(inv -> {
			read.countDown();
			return java.util.Optional.of(releaseRows.get(release.getUuid()));
		});
		clearInvocations(publications);
		ReentrantLock lock = ReflectionTestUtils.invokeMethod(service, "releaseLock", release.getUuid());
		ExecutorService pool = Executors.newSingleThreadExecutor();
		lock.lock();
		Future<TeaPublicationData> hidden;
		try {
			hidden = pool.submit(() -> service.hide(release.getUuid(), null, WU));
			assertFalse(read.await(300, TimeUnit.MILLISECONDS), "the hide read the release while the lock was held");
			verify(publications, never()).findByRelease(any());
		} finally {
			lock.unlock();
		}
		try {
			assertTrue(read.await(10, TimeUnit.SECONDS));
			assertEquals(TeaPublicationState.HIDDEN, hidden.get(10, TimeUnit.SECONDS).getState());
		} finally {
			pool.shutdownNow();
		}
	}

	// ---------------------------------------------------------------- 83

	private ArgumentCaptor<ReleaseData> releaseSaves(int times) {
		ArgumentCaptor<ReleaseData> rd = ArgumentCaptor.forClass(ReleaseData.class);
		verify(oss, times(times)).saveRelease(any(Release.class), rd.capture(), any(), eq(false),
				eq(AcollectionMode.SKIP));
		return rd;
	}

	@Test
	void aFirstPublishWritesTheReleaseRowOnceWithTheBindThenTheEvent() throws Exception {
		publish(TeaPublishMode.PUBLISH);

		UUID aggregate = TeaAggregates.defaultAggregate(head());
		ReleaseData saved = releaseSaves(1).getValue();
		assertEquals(List.of(aggregate), saved.getSyntheticArtifacts());
		List<ReleaseUpdateEvent> events = saved.getUpdateEvents();
		ReleaseUpdateEvent bind = events.get(events.size() - 2);
		ReleaseUpdateEvent added = events.get(events.size() - 1);
		assertEquals(ReleaseUpdateScope.SYNTHETIC_ARTIFACT, bind.rus());
		assertEquals(ReleaseUpdateAction.ADDED, bind.rua());
		assertEquals(aggregate, bind.objectId());
		assertEquals(ReleaseUpdateScope.TEA_PUBLICATION, added.rus());
		assertEquals(ReleaseUpdateAction.ADDED, added.rua());
		assertEquals("v1", added.newValue());
		verify(synthetic, times(1)).upload(any(), any(), any(), any(), any());
		verify(synthetic, never()).write(any(), any(), any(), any(), any());
		// stageBind gets the data of the release row refreshed inside the commit
		ArgumentCaptor<ReleaseData> staged = ArgumentCaptor.forClass(ReleaseData.class);
		InOrder order = inOrder(entityManager, synthetic, oss);
		order.verify(entityManager, times(2)).refresh(releaseRows.get(release.getUuid()));
		order.verify(synthetic).stageBind(staged.capture(), eq(aggregate), any());
		order.verify(oss).saveRelease(eq(releaseRows.get(release.getUuid())), any(ReleaseData.class), any(), eq(false),
				eq(AcollectionMode.SKIP));
		assertSame(staged.getValue(), saved);
	}

	@Test
	void aChangedRepublishWritesOnceAnUnchangedOneNotAtAllAndAHideOnce() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		documents.put(release.getUuid(), bom("pkg:generic/b@1"));
		clearInvocations(oss, synthetic);

		publish(TeaPublishMode.REPUBLISH);

		ReleaseData changed = releaseSaves(1).getValue();
		verify(synthetic).stageBind(any(), any(), any());
		assertEquals(1, changed.getSyntheticArtifacts().size());
		ReleaseUpdateEvent last = changed.getUpdateEvents().get(changed.getUpdateEvents().size() - 1);
		assertEquals(ReleaseUpdateAction.CHANGED, last.rua());
		assertEquals(1, changed.getUpdateEvents().stream().filter(e -> ReleaseUpdateScope.SYNTHETIC_ARTIFACT == e.rus())
				.count(), "the in-place bump binds nothing again");

		clearInvocations(oss);
		assertEquals(TeaPublishOutcome.UNCHANGED, publish(TeaPublishMode.REPUBLISH).outcome());
		releaseSaves(0);

		clearInvocations(oss);
		service.hide(release.getUuid(), null, WU);
		ReleaseData hidden = releaseSaves(1).getValue();
		assertEquals(ReleaseUpdateAction.REMOVED,
				hidden.getUpdateEvents().get(hidden.getUpdateEvents().size() - 1).rua());
	}

	@Test
	void theCommitsRunUnderTheLeaseTheActTookAndRefuseToRunWithoutOne() throws Exception {
		TeaPublicationService proxy = spy(service);
		ReflectionTestUtils.setField(service, "self", proxy);
		publish(TeaPublishMode.PUBLISH);
		service.hide(release.getUuid(), null, WU);

		ArgumentCaptor<TeaPublishLock.Lease> publishLease = ArgumentCaptor.forClass(TeaPublishLock.Lease.class);
		verify(proxy).commitPublication(publishLease.capture(), any(), any());
		assertSame(leases.get(0), publishLease.getValue());
		ArgumentCaptor<TeaPublishLock.Lease> hideLease = ArgumentCaptor.forClass(TeaPublishLock.Lease.class);
		verify(proxy).commitHide(hideLease.capture(), eq(release.getUuid()), any(), any());
		assertSame(leases.get(1), hideLease.getValue());

		clearInvocations(publications, shared);
		TeaPublicationService.Commit commit = new TeaPublicationService.Commit(release.getUuid(), ORG,
				component.getUuid(), component.getType(), UUID.randomUUID(), resolvedFromOrg(profile), null,
				List.of(new TeaPublicationData.TeaAggregateArtifact("default", inventoryBom)), null, List.of(), null, WU);
		assertThrows(NullPointerException.class, () -> service.commitPublication(null, commit, List.of()));
		assertThrows(NullPointerException.class, () -> service.commitHide(null, release.getUuid(), null, WU));
		verify(publications, never()).findByRelease(any());
		verify(shared, never()).getRelease(any(UUID.class));
	}

	// ---------------------------------------------------------------- task TEA-28, design 4.6: the commit's uuid guard (F-5, D16)

	/** A row whose system publication tag names {@code uuid}, bound to the release: the recovery reads it. */
	private void recoverableUuid(UUID uuid) {
		UUID row = artifact(ArtifactType.BOM, "stale.cdx.json", "1", StoredIn.REARM, true, "ff66");
		artifacts.get(row).setTags(new java.util.ArrayList<>(List.of(
				new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, uuid.toString(), Removable.NO))));
		Release r = releaseRows.get(release.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.addSyntheticArtifact(row);
		r.setRecordData(Utils.dataToRecord(d));
	}

	private void assertRefusedUnderHeldUuid(UUID uuid) throws Exception {
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("publication uuid " + uuid + " is held by another release"), e.getMessage());
		verify(collections, never()).maxVersion(any());
		verify(collections, never()).save(any());
		verify(publications, never()).save(any());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
		assertEquals(1, writtenDtos.size());
		UUID uploaded = artifacts.values().stream()
				.filter(a -> "pump-1.2.3-default-tea-aggregate.cdx.json".equals(a.getDisplayIdentifier())).findFirst()
				.orElseThrow().getUuid();
		verify(artifactService, times(1)).archiveArtifact(uploaded, WU);
	}

	@Test
	void aFirstCommitUnderAUuidThatGainedAHeadOfAnotherReleaseRefusesAndWritesNothing() throws Exception {
		UUID uuid = UUID.randomUUID();
		recoverableUuid(uuid);
		TeaPublication other = new TeaPublication();
		other.setUuid(uuid);
		other.setRelease(UUID.randomUUID());
		// free when the act chose it, held when it commits: the race the commit's re-check exists for
		doReturn(Optional.empty(), Optional.of(other)).when(publications).findById(uuid);

		assertRefusedUnderHeldUuid(uuid);
	}

	@Test
	void aFirstCommitUnderAUuidThatGainedAVersionOfAnotherReleaseRefusesAndWritesNothing() throws Exception {
		UUID uuid = UUID.randomUUID();
		recoverableUuid(uuid);
		doReturn(0L, 1L).when(collections).countForeign(eq(uuid), any(UUID.class), any(UUID.class));

		assertRefusedUnderHeldUuid(uuid);
	}

	// ---------------------------------------------------------------- 84

	@Test
	void aFirstPublishWhoseCommitFailsArchivesTheUnboundAggregate() throws Exception {
		when(collections.save(any(TeaPublishedCollection.class))).thenThrow(new IllegalStateException("db down"));

		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertEquals(1, writtenDtos.size());
		UUID uploaded = artifacts.keySet().stream().filter(id -> !id.equals(inventoryBom)).findFirst().orElseThrow();
		verify(artifactService, times(1)).archiveArtifact(uploaded, WU);
	}

	@Test
	void aFirstPublishWhoseRideAlongStepFailsArchivesTheUnboundAggregate() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		doThrow(new IllegalStateException("db down")).when(artifactService).getArtifactDataList(any());

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.PUBLISH).reason());

		UUID uploaded = artifacts.keySet().stream().filter(id -> !id.equals(inventoryBom)).findFirst().orElseThrow();
		verify(artifactService, times(1)).archiveArtifact(uploaded, WU);
		verify(publications, never()).save(any());
	}

	@Test
	void aRepublishWhoseCommitFailsArchivesNothing() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		documents.put(release.getUuid(), bom("pkg:generic/b@1"));
		when(collections.save(any(TeaPublishedCollection.class))).thenThrow(new IllegalStateException("db down"));

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.REPUBLISH).reason());

		assertEquals(2, writtenDtos.size(), "the bump went up");
		verify(artifactService, never()).archiveArtifact(any(), any());
	}

	@Test
	void aFirstPublishWhoseUploadFailsHasNothingToArchive() throws Exception {
		when(synthetic.upload(any(), any(), any(), any(), any())).thenThrow(new RelizaException("registry down"));

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.PUBLISH).reason());

		verify(artifactService, never()).archiveArtifact(any(), any());
	}
}
