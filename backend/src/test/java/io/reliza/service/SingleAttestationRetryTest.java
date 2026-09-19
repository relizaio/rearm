/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.SbomComponentSupport;
import io.reliza.model.SupportAttestationRequest;

import jakarta.persistence.EntityManager;

/**
 * The bounded retry on a single attestation write.
 *
 * <p>The javadoc on the method under test says the OSIV-poisoning it guards against is not
 * reachable from the test suite -- tests are not web requests, so no open-in-view holder is
 * bound. That is true of the POISONING, and it was mistaken for "the loop is untestable".
 * The loop itself is perfectly testable: {@code self} and {@code entityManager} are both
 * injectable, so what each attempt does, and that the context is cleared BETWEEN attempts,
 * can be asserted directly. Without these, the fix for t20260907-044617-29802 had no test of
 * its own at all.
 */
class SingleAttestationRetryTest {

	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID ACTOR = UUID.randomUUID();

	private record Harness(SbomComponentService svc, SbomComponentService self, EntityManager em) {}

	private Harness harness() {
		SbomComponentService svc = new SbomComponentService(null, null, null, null, null, null);
		SbomComponentService self = mock(SbomComponentService.class);
		EntityManager em = mock(EntityManager.class);
		ReflectionTestUtils.setField(svc, "self", self);
		ReflectionTestUtils.setField(svc, "entityManager", em);
		return new Harness(svc, self, em);
	}

	@Test
	void returnsTheFirstAttemptWhenNothingRaces() throws Exception {
		Harness h = harness();
		SbomComponentSupport written = mock(SbomComponentSupport.class);
		when(h.self().setSbomComponentSupportIsolated(any(), any(), any(), any())).thenReturn(written);

		assertSame(written, h.svc().setSbomComponentSupportWithRetry(
				COMPONENT, mock(SupportAttestationRequest.class), ACTOR));
		verify(h.self(), times(1)).setSbomComponentSupportIsolated(any(), any(), any(), any());
		// Nothing failed, so nothing needed discarding.
		verify(h.em(), never()).clear();
	}

	/** THE POINT OF THE FIX: a losing attempt must not leave the next one on stale state. */
	@Test
	void clearsThePersistenceContextBetweenAttempts() throws Exception {
		Harness h = harness();
		SbomComponentSupport written = mock(SbomComponentSupport.class);
		when(h.self().setSbomComponentSupportIsolated(any(), any(), any(), any()))
				.thenThrow(new OptimisticLockingFailureException("lost the race"))
				.thenReturn(written);

		assertSame(written, h.svc().setSbomComponentSupportWithRetry(
				COMPONENT, mock(SupportAttestationRequest.class), ACTOR));
		verify(h.self(), times(2)).setSbomComponentSupportIsolated(any(), any(), any(), any());
		verify(h.em(), times(1)).clear();
	}

	@Test
	void givesUpAfterThreeAttemptsWithAnActionableError() {
		Harness h = harness();
		when(h.self().setSbomComponentSupportIsolated(any(), any(), any(), any()))
				.thenThrow(new OptimisticLockingFailureException("lost again"));

		RelizaException ex = assertThrows(RelizaException.class,
				() -> h.svc().setSbomComponentSupportWithRetry(
						COMPONENT, mock(SupportAttestationRequest.class), ACTOR));
		assertEquals("concurrent update in progress, please retry", ex.getMessage());
		verify(h.self(), times(3)).setSbomComponentSupportIsolated(any(), any(), any(), any());
		// Once per failure, so the third attempt saw fresh state too.
		verify(h.em(), times(3)).clear();
	}

	/**
	 * Only a lost race is retried. Anything else -- a bad date range, a missing component --
	 * is not going to succeed on a second attempt, and retrying it would turn one clear error
	 * into three and delay the answer.
	 */
	@Test
	void doesNotRetryAFailureThatIsNotARace() {
		Harness h = harness();
		when(h.self().setSbomComponentSupportIsolated(any(), any(), any(), any()))
				.thenThrow(new IllegalArgumentException("eos after eol"));

		assertThrows(IllegalArgumentException.class,
				() -> h.svc().setSbomComponentSupportWithRetry(
						COMPONENT, mock(SupportAttestationRequest.class), ACTOR));
		verify(h.self(), times(1)).setSbomComponentSupportIsolated(any(), any(), any(), any());
		verify(h.em(), never()).clear();
	}

	/** The isolated write is what carries REQUIRES_NEW; the retry must go through it. */
	@Test
	void writesThroughTheIsolatedMethodWithNoBatchId() throws Exception {
		Harness h = harness();
		SupportAttestationRequest req = mock(SupportAttestationRequest.class);
		when(h.self().setSbomComponentSupportIsolated(any(), any(), any(), any()))
				.thenReturn(mock(SbomComponentSupport.class));

		h.svc().setSbomComponentSupportWithRetry(COMPONENT, req, ACTOR);
		// null batchId: this is a single write, not part of a sweep, and the batch id would
		// otherwise reach the audit row and imply one.
		verify(h.self()).setSbomComponentSupportIsolated(eq(COMPONENT), eq(req), eq(ACTOR), eq(null));
	}
}
