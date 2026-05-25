/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.DtrackFetchStatus;

/**
 * Guards the FETCH-failure-tracking contract on {@code SharedArtifactService}:
 *   - {@code markArtifactDtrackFetchFailed} bumps the counter, computes the
 *     skip-until from the exponential backoff curve, sets status FAILED,
 *     truncates over-long failureReason, and persists.
 *   - {@code resetArtifactDtrackFetchFailedState} is a guarded no-op when
 *     state is already clean; on a dirty artifact it clears all four fields
 *     and persists.
 */
class SharedArtifactServiceFetchFailureTest {

    private static SharedArtifactService newSvcCapturing(AtomicReference<DependencyTrackIntegration> savedRef,
            AtomicInteger saveCount, Artifact backing) {
        // Empty strings for the @Value-injected URL/namespace; null repository
        // is fine because every read path goes through getArtifact() which we override.
        return new SharedArtifactService(null, "", "") {
            @Override
            public Optional<Artifact> getArtifact(UUID uuid) {
                return Optional.of(backing);
            }

            @Override
            public void saveArtifactMetrics(Artifact a, DependencyTrackIntegration metrics) {
                savedRef.set(metrics);
                saveCount.incrementAndGet();
            }
        };
    }

    private static Artifact artifactWithMetrics(DependencyTrackIntegration dti) {
        Artifact a = new Artifact();
        a.setUuid(UUID.randomUUID());
        // dataFromRecord requires recordData to be populated. Use the minimum
        // shape ArtifactData.dataFromRecord tolerates.
        Map<String, Object> rd = new HashMap<>();
        rd.put("uuid", a.getUuid().toString());
        a.setRecordData(rd);
        if (dti != null) {
            a.setMetrics(io.reliza.common.Utils.OM.convertValue(dti, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        }
        return a;
    }

    @Test
    void markSetsFailedStateAndPersists() {
        AtomicReference<DependencyTrackIntegration> saved = new AtomicReference<>();
        AtomicInteger saveCount = new AtomicInteger(0);
        Artifact a = artifactWithMetrics(null);
        SharedArtifactService svc = newSvcCapturing(saved, saveCount, a);

        ZonedDateTime before = ZonedDateTime.now();
        svc.markArtifactDtrackFetchFailed(a.getUuid(), "transient 502 from DTrack");
        ZonedDateTime after = ZonedDateTime.now();

        assertEquals(1, saveCount.get(), "exactly one persist call expected");
        DependencyTrackIntegration s = saved.get();
        assertNotNull(s);
        assertEquals(DtrackFetchStatus.FAILED, s.getDtrackFetchStatus());
        assertEquals(1, s.getDtrackFetchFailureCount(), "first failure should be count=1");
        assertEquals("transient 502 from DTrack", s.getDtrackFetchFailureReason());
        assertNotNull(s.getDtrackFetchSkipUntil(), "skipUntil should be set after a failure");

        // First failure backs off 60 s; skipUntil should be ~now + 60s.
        ZonedDateTime parsed = ZonedDateTime.parse(s.getDtrackFetchSkipUntil());
        assertTrue(!parsed.isBefore(before.plusSeconds(60).minusSeconds(2))
                        && !parsed.isAfter(after.plusSeconds(60).plusSeconds(2)),
                "skipUntil should be roughly now+60s for first failure; got " + parsed);
    }

    @Test
    void markIncrementsExistingFailureCount() {
        AtomicReference<DependencyTrackIntegration> saved = new AtomicReference<>();
        AtomicInteger saveCount = new AtomicInteger(0);
        DependencyTrackIntegration existing = new DependencyTrackIntegration();
        existing.setDtrackFetchStatus(DtrackFetchStatus.FAILED);
        existing.setDtrackFetchFailureCount(2);
        existing.setDtrackFetchSkipUntil(ZonedDateTime.now().minusMinutes(5).toString());
        Artifact a = artifactWithMetrics(existing);
        SharedArtifactService svc = newSvcCapturing(saved, saveCount, a);

        svc.markArtifactDtrackFetchFailed(a.getUuid(), "another failure");

        DependencyTrackIntegration s = saved.get();
        assertEquals(3, s.getDtrackFetchFailureCount(), "count should bump from 2 to 3");
        // Third failure: 4 minutes (240s). Just confirm it bumped from the earlier value.
        assertTrue(ZonedDateTime.parse(s.getDtrackFetchSkipUntil()).isAfter(ZonedDateTime.now()),
                "skipUntil should be in the future after the new failure");
    }

    @Test
    void markTruncatesOverlongFailureReason() {
        AtomicReference<DependencyTrackIntegration> saved = new AtomicReference<>();
        AtomicInteger saveCount = new AtomicInteger(0);
        Artifact a = artifactWithMetrics(null);
        SharedArtifactService svc = newSvcCapturing(saved, saveCount, a);

        String longReason = "x".repeat(DependencyTrackIntegration.FETCH_FAILURE_REASON_MAX_LEN + 200);
        svc.markArtifactDtrackFetchFailed(a.getUuid(), longReason);

        assertEquals(DependencyTrackIntegration.FETCH_FAILURE_REASON_MAX_LEN,
                saved.get().getDtrackFetchFailureReason().length(),
                "failureReason should be truncated to FETCH_FAILURE_REASON_MAX_LEN");
    }

    @Test
    void resetOnCleanArtifactIsNoOp() {
        AtomicReference<DependencyTrackIntegration> saved = new AtomicReference<>();
        AtomicInteger saveCount = new AtomicInteger(0);
        DependencyTrackIntegration clean = new DependencyTrackIntegration();
        // All fetch-failure fields are null/zero — nothing to clear.
        Artifact a = artifactWithMetrics(clean);
        SharedArtifactService svc = newSvcCapturing(saved, saveCount, a);

        svc.resetArtifactDtrackFetchFailedState(a.getUuid());

        assertEquals(0, saveCount.get(),
                "reset on clean artifact should not call saveArtifactMetrics");
    }

    @Test
    void resetOnFailedArtifactClearsAllFetchFields() {
        AtomicReference<DependencyTrackIntegration> saved = new AtomicReference<>();
        AtomicInteger saveCount = new AtomicInteger(0);
        DependencyTrackIntegration failed = new DependencyTrackIntegration();
        failed.setDtrackFetchStatus(DtrackFetchStatus.FAILED);
        failed.setDtrackFetchFailureCount(5);
        failed.setDtrackFetchFailureReason("transient 502");
        failed.setDtrackFetchSkipUntil(ZonedDateTime.now().plusMinutes(10).toString());
        Artifact a = artifactWithMetrics(failed);
        SharedArtifactService svc = newSvcCapturing(saved, saveCount, a);

        svc.resetArtifactDtrackFetchFailedState(a.getUuid());

        assertEquals(1, saveCount.get(), "reset on dirty artifact should persist exactly once");
        DependencyTrackIntegration s = saved.get();
        assertEquals(DtrackFetchStatus.OK, s.getDtrackFetchStatus());
        assertEquals(0, s.getDtrackFetchFailureCount());
        assertNull(s.getDtrackFetchFailureReason());
        assertNull(s.getDtrackFetchSkipUntil());
    }
}
