/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.dto.ChangelogRecords;
import io.reliza.model.OrganizationData;

/**
 * Unit tests for the posture-diff READ gate {@code ChangeLogService.posturePathEnabled} (board task #38):
 * the posture-diff path runs only when the global flag is ON AND the org's finding_change_events backfill
 * is complete (watermark set). An un-seeded org transparently falls back to the legacy pairwise diff --
 * safe-by-construction, never a silently-wrong reverse-replay against an incomplete event log.
 */
@ExtendWith(MockitoExtension.class)
public class ChangeLogPosturePathGateTest {

    private static final UUID ORG = UUID.fromString("b8e7c851-0000-0000-0000-000000000009");

    private final GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);

    private ChangeLogService service(boolean flagOn) {
        ChangeLogService svc = new ChangeLogService();
        ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
        ReflectionTestUtils.setField(svc, "changelogPostureDiffEnabled", flagOn);
        return svc;
    }

    private void stubOrg(boolean seeded) {
        stubOrg(seeded, ChangelogRecords.FINDING_CHANGE_EVENT_VOCAB_VERSION);
    }

    private void stubOrg(boolean seeded, Integer vocabVersion) {
        OrganizationData od = mock(OrganizationData.class);
        OrganizationData.Settings settings = new OrganizationData.Settings();
        if (seeded) {
            settings.setFindingChangeBackfillCompletedAt(ZonedDateTime.parse("2026-07-01T00:00:00Z"));
            settings.setFindingChangeBackfillVocabVersion(vocabVersion);
        }
        lenient().when(od.getSettings()).thenReturn(settings);
        lenient().when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));
    }

    private boolean posturePathEnabled(ChangeLogService svc) {
        return (boolean) ReflectionTestUtils.invokeMethod(svc, "posturePathEnabled", ORG);
    }

    @Test
    void flagOn_seeded_enablesPosturePath() {
        stubOrg(true);
        assertTrue(posturePathEnabled(service(true)), "flag ON + backfill complete -> posture path");
    }

    @Test
    void flagOn_unseeded_fallsBackToLegacy() {
        stubOrg(false);
        assertFalse(posturePathEnabled(service(true)),
                "flag ON but org NOT seeded -> must fall back to legacy (no silently-wrong reverse-replay)");
    }

    @Test
    void flagOn_missingOrg_fallsBackToLegacy() {
        when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.empty());
        assertFalse(posturePathEnabled(service(true)), "missing org settings -> fail-safe to legacy");
    }

    @Test
    void flagOn_seededAtStaleVocab_fallsBackToLegacy() {
        // Org certified before a vocabulary widening (or pre-versioning, vocab null -> 1): the event log
        // lacks the newly-emittable kinds reverse-replay depends on, so it must stay on legacy until a
        // full-range reseed re-certifies at the current version.
        stubOrg(true, null);
        assertFalse(posturePathEnabled(service(true)),
                "watermark at stale vocabulary -> legacy until a full-range reseed bumps it");
    }

    @Test
    void flagOn_orgLookupThrows_failsSafeToLegacy() {
        when(getOrganizationService.getOrganizationData(ORG)).thenThrow(new RuntimeException("db down"));
        assertFalse(posturePathEnabled(service(true)),
                "org lookup exception -> fail-safe to legacy, never a request failure");
    }

    @Test
    void flagOff_seeded_staysLegacy() {
        // flag off short-circuits; org lookup must not even be required.
        assertFalse(posturePathEnabled(service(false)), "global flag OFF -> legacy regardless of watermark");
    }
}
