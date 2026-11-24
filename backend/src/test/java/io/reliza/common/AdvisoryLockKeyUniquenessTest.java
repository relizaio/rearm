/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * TPF-167 mutation round 2 (the tester's K01): every advisory lock key has a value of its own, so the seat lock
 * (LICENSE_SEATS) can never be the lock of another job.
 */
public class AdvisoryLockKeyUniquenessTest {

	@Test
	public void noTwoKeysShareAValue() {
		Set<Integer> seen = new HashSet<>();
		for (AdvisoryLockKey k : AdvisoryLockKey.values()) {
			assertEquals(true, seen.add(k.getQueryVal()), k + " shares its value with another key: " + Arrays.toString(AdvisoryLockKey.values()));
		}
	}
}
