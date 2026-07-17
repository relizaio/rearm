/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.oss.OssReleaseService;

/**
 * Regression guard for the scheduled-path transaction-propagation bug.
 *
 * <p>{@link ReleaseChangeHookImpl#onReleaseLifecycleChanged} /
 * {@link ReleaseChangeHookImpl#onReleaseCreated} run
 * {@link Propagation#MANDATORY} so the notification-outbox row commits
 * atomically with the release record — they REQUIRE an open transaction.
 * They are reached transitively from {@link OssReleaseService#processRelease}
 * (output-trigger firing -> {@code updateReleaseLifecycle} ->
 * {@code processReleaseLifecycleEvents} -> the hook). On the scheduled
 * metrics path {@code processRelease} is invoked cross-bean from
 * {@code ReleaseService.computeMetricsForReleaseList} with NO ambient
 * transaction, so {@code processRelease} itself must be {@link Transactional}
 * to open one; otherwise the MANDATORY hook throws
 * {@code IllegalTransactionStateException} and the metrics compute (plus
 * every later output trigger for that release) is aborted.
 *
 * <p>This is a pure-reflection test (no Spring context): it pins the two
 * halves of that contract so a future refactor that drops either annotation
 * fails loudly here instead of only under a live scheduled recompute.
 */
class ScheduledTriggerTransactionGuardTest {

	@Test
	void processReleaseIsTransactional() throws NoSuchMethodException {
		Method m = OssReleaseService.class.getDeclaredMethod("processRelease", UUID.class);
		Transactional tx = m.getAnnotation(Transactional.class);
		assertTrue(tx != null,
				"OssReleaseService.processRelease must be @Transactional: on the scheduled metrics "
				+ "path it is the only transaction boundary above the MANDATORY ReleaseChangeHook "
				+ "lifecycle/created hooks. Dropping it reintroduces IllegalTransactionStateException "
				+ "('No existing transaction found for transaction marked with propagation mandatory').");
		// REQUIRED (the default) is what makes it join the create-path tx and
		// open a fresh one on the scheduled path. REQUIRES_NEW would break the
		// create path (it re-reads the not-yet-committed release).
		assertEquals(Propagation.REQUIRED, tx.propagation(),
				"processRelease must use REQUIRED so it joins the release-create transaction and "
				+ "opens a new one only when the scheduled path has none.");
	}

	@Test
	void lifecycleAndCreatedHooksAreMandatory() throws NoSuchMethodException {
		assertMandatory(ReleaseChangeHookImpl.class.getDeclaredMethod(
				"onReleaseLifecycleChanged", ReleaseData.class, ReleaseLifecycle.class, ReleaseLifecycle.class));
		assertMandatory(ReleaseChangeHookImpl.class.getDeclaredMethod(
				"onReleaseCreated", ReleaseData.class, boolean.class));
	}

	private static void assertMandatory(Method m) {
		Transactional tx = m.getAnnotation(Transactional.class);
		assertTrue(tx != null, m.getName() + " must be @Transactional");
		assertEquals(Propagation.MANDATORY, tx.propagation(),
				m.getName() + " is MANDATORY by design (outbox commits with the release); if this "
				+ "changes, revisit why processRelease needs to be transactional.");
	}
}
