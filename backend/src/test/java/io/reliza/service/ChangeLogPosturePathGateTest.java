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

import io.reliza.model.OrganizationData;

/**
 * Unit tests for the posture-diff READ gate {@code ChangeLogService.posturePathEnabled} (board task #38):
 * the posture-diff path runs only when the global flag is ON AND the org's finding_change_events_v3
 * backfill is certified at (or above) the current {@link FindingDimKey#KEY_VERSION}. An uncertified org
 * transparently falls back to the legacy pairwise diff -- safe-by-construction, never a silently-wrong
 * reverse-replay against an incomplete event log. Gates on the V3 watermark pair; the pre-v3
 * watermark fields were removed outright -- stale legacy keys in old orgs' settings JSONB are
 * dropped by ignoreUnknown deserialization and can never reach the gate.
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

    private void stubOrg(boolean certified) {
        stubOrg(certified, (int) FindingDimKey.KEY_VERSION);
    }

    private void stubOrg(boolean certified, Integer keyVersion) {
        OrganizationData od = mock(OrganizationData.class);
        OrganizationData.Settings settings = new OrganizationData.Settings();
        if (certified) {
            settings.setFindingChangeV3BackfillCompletedAt(ZonedDateTime.parse("2026-07-01T00:00:00Z"));
            settings.setFindingChangeV3BackfillKeyVersion(keyVersion);
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
        assertTrue(posturePathEnabled(service(true)), "flag ON + v3 backfill certified -> posture path");
    }

    @Test
    void flagOn_unseeded_fallsBackToLegacy() {
        stubOrg(false);
        assertFalse(posturePathEnabled(service(true)),
                "flag ON but org NOT certified -> must fall back to legacy (no silently-wrong reverse-replay)");
    }

    @Test
    void flagOn_missingOrg_fallsBackToLegacy() {
        when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.empty());
        assertFalse(posturePathEnabled(service(true)), "missing org settings -> fail-safe to legacy");
    }

    @Test
    void flagOn_seededAtStaleKeyVersion_fallsBackToLegacy() {
        // Org certified before a key-version bump: the dimension rows hash under an older canonical
        // form, so reverse-replay must stay on legacy until a re-key backfill re-certifies at the
        // current version. (KEY_VERSION is 1 today, so 0 is synthetic -- the test pins the floor.)
        stubOrg(true, FindingDimKey.KEY_VERSION - 1);
        assertFalse(posturePathEnabled(service(true)),
                "watermark at stale key version -> legacy until a re-key backfill bumps it");
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
