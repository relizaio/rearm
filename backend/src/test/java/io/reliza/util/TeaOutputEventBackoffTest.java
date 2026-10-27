/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * TEA-7, section 4.2 case 17: the TEA act queue's retry curve, 1, 2, 4, 8 then 15 minutes (the
 * act lease) for failure counts 1 to 5 and beyond.
 */
class TeaOutputEventBackoffTest {

    @Test
    void theCurveDoublesFromOneMinuteToTheLease() {
        assertEquals(60, BackoffPolicy.teaActRetrySeconds(1));
        assertEquals(120, BackoffPolicy.teaActRetrySeconds(2));
        assertEquals(240, BackoffPolicy.teaActRetrySeconds(3));
        assertEquals(480, BackoffPolicy.teaActRetrySeconds(4));
        assertEquals(900, BackoffPolicy.teaActRetrySeconds(5));
    }

    @Test
    void itCapsAtTheLease() {
        assertEquals(900, BackoffPolicy.teaActRetrySeconds(6));
        assertEquals(900, BackoffPolicy.teaActRetrySeconds(100));
        assertEquals(900, BackoffPolicy.teaActRetrySeconds(Integer.MAX_VALUE));
    }

    @Test
    void aNonPositiveCountAnswersOneMinute() {
        assertEquals(60, BackoffPolicy.teaActRetrySeconds(0));
        assertEquals(60, BackoffPolicy.teaActRetrySeconds(-3));
        assertEquals(60, BackoffPolicy.teaActRetrySeconds(Integer.MIN_VALUE));
    }
}
