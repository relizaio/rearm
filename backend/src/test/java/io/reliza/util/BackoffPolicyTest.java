/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Guards the exponential backoff table for per-artifact Dependency-Track
 * fetch failures: 1, 2, 4, 8, 16, 32 minutes then a 60-minute cap.
 */
class BackoffPolicyTest {

    @Test
    void firstFailureBacksOffOneMinute() {
        assertEquals(60, BackoffPolicy.dtrackFetchSkipSeconds(1));
    }

    @Test
    void exponentialDoubling() {
        assertEquals(60, BackoffPolicy.dtrackFetchSkipSeconds(1));
        assertEquals(120, BackoffPolicy.dtrackFetchSkipSeconds(2));
        assertEquals(240, BackoffPolicy.dtrackFetchSkipSeconds(3));
        assertEquals(480, BackoffPolicy.dtrackFetchSkipSeconds(4));
        assertEquals(960, BackoffPolicy.dtrackFetchSkipSeconds(5));
        assertEquals(1920, BackoffPolicy.dtrackFetchSkipSeconds(6));
    }

    @Test
    void capsAtOneHourFromSeventhFailureOnward() {
        assertEquals(3600, BackoffPolicy.dtrackFetchSkipSeconds(7));
        assertEquals(3600, BackoffPolicy.dtrackFetchSkipSeconds(8));
        assertEquals(3600, BackoffPolicy.dtrackFetchSkipSeconds(100));
        assertEquals(3600, BackoffPolicy.dtrackFetchSkipSeconds(Integer.MAX_VALUE));
    }

    @Test
    void defensiveHandlingOfNonPositiveInput() {
        // Caller should always pass >=1, but if a corrupted count slips through,
        // a sensible default is better than a negative shift or zero backoff.
        assertEquals(60, BackoffPolicy.dtrackFetchSkipSeconds(0));
        assertEquals(60, BackoffPolicy.dtrackFetchSkipSeconds(-1));
        assertEquals(60, BackoffPolicy.dtrackFetchSkipSeconds(Integer.MIN_VALUE));
    }

    @Test
    void autoIntegrateStartsAtTwoMinutesAndDoublesToACapOfSixtyFour() {
        assertEquals(120, BackoffPolicy.autoIntegrateSkipSeconds(1));
        assertEquals(240, BackoffPolicy.autoIntegrateSkipSeconds(2));
        assertEquals(480, BackoffPolicy.autoIntegrateSkipSeconds(3));
        assertEquals(960, BackoffPolicy.autoIntegrateSkipSeconds(4));
        assertEquals(1920, BackoffPolicy.autoIntegrateSkipSeconds(5));
    }

    @Test
    void autoIntegrateCapsFromTheSixthFailureOnward() {
        assertEquals(3840, BackoffPolicy.autoIntegrateSkipSeconds(6));
        assertEquals(3840, BackoffPolicy.autoIntegrateSkipSeconds(7));
        // Retries never stop, so the count keeps climbing for as long as the release stays broken;
        // the curve must stay flat rather than overflow or wrap.
        assertEquals(3840, BackoffPolicy.autoIntegrateSkipSeconds(1000));
        assertEquals(3840, BackoffPolicy.autoIntegrateSkipSeconds(Integer.MAX_VALUE));
    }

    @Test
    void autoIntegrateDefensiveHandlingOfNonPositiveInput() {
        assertEquals(120, BackoffPolicy.autoIntegrateSkipSeconds(0));
        assertEquals(120, BackoffPolicy.autoIntegrateSkipSeconds(-1));
        // MIN_VALUE is the interesting one for a shift-based curve.
        assertEquals(120, BackoffPolicy.autoIntegrateSkipSeconds(Integer.MIN_VALUE));
    }
}
