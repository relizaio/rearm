/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.ws.App;

/**
 * The pepper is what stops two installations left on the shipped defaults from deriving the same
 * signing key, so the one thing it must never do is differ between two readers of the same
 * database -- a token signed under one value would be rejected under the other.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ApiTokenPepperTest {

	@Autowired private SystemInfoService systemInfoService;

	@Test
	public void theInstallationGetsOneAndKeepsIt() {
		String first = systemInfoService.getOrCreateApiTokenPepper();
		assertTrue(first != null && first.length() >= 32, "expected a long random value, got: " + first);
		assertEquals(first, systemInfoService.getOrCreateApiTokenPepper(), "the pepper must be stable");
		assertEquals(first, systemInfoService.getSystemInfoData().getApiTokenPepper(), "and persisted");
	}

	@Test
	public void concurrentFirstUseAgreesOnOneValue() throws Exception {
		// Two pods signing their first token at the same moment. Read-then-write would let both
		// generate and the last write would win, leaving tokens signed by the loser unverifiable.
		String established = systemInfoService.getOrCreateApiTokenPepper();
		assertTrue(established != null);

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			Callable<String> read = () -> systemInfoService.getOrCreateApiTokenPepper();
			List<Future<String>> futures = pool.invokeAll(List.of(read, read, read, read));
			Set<String> seen = new java.util.HashSet<>();
			for (Future<String> f : futures) seen.add(f.get(30, TimeUnit.SECONDS));
			assertEquals(1, seen.size(), "every caller must see the same pepper, got: " + seen);
			assertEquals(established, seen.iterator().next());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void theDerivedKeyDependsOnIt() {
		// Same password and salt, different pepper, different key: this is the property the whole
		// change rests on.
		String pepper = systemInfoService.getOrCreateApiTokenPepper();
		byte[] withInstallationPepper = ApiTokenService.hkdfSha256("pw", "salt", pepper);
		byte[] withAnother = ApiTokenService.hkdfSha256("pw", "salt", "some-other-installation");
		assertNotEquals(ApiTokenService.kidOf(withInstallationPepper), ApiTokenService.kidOf(withAnother));
	}
}
