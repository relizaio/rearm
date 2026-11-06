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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.ArtifactRetainedException;
import io.reliza.model.ArtifactPin;
import io.reliza.model.PackageKind;
import io.reliza.service.ArtifactRetentionLock.Retention;

/**
 * FDAW-5, design T2: the retention lock over its hook. No hook (CE) never refuses and calls nothing;
 * one hook call per question; the message of 3.5 exactly; unbind refuses only on a pin of the same
 * release and returns the other releases' pins for the caller's archive decision.
 */
class ArtifactRetentionLockTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID R1 = UUID.randomUUID();
	private static final UUID R2 = UUID.randomUUID();
	private static final String L1 = "GlucoView 2.4.0 (" + R1 + ")";
	private static final String L2 = "GlucoView 2.5.0 (" + R2 + ")";

	private ArtifactRetentionLock lock;
	private ArtifactRetentionHook hook;

	@BeforeEach
	void setUp() {
		lock = new ArtifactRetentionLock();
		hook = mock(ArtifactRetentionHook.class);
		ReflectionTestUtils.setField(lock, "hook", hook);
	}

	private static ArtifactPin pin(UUID artifact, UUID release, String label, int version) {
		return new ArtifactPin(artifact, UUID.randomUUID(), PackageKind.FDA_PREMARKET, version, release, label);
	}

	@Test
	void withoutAHookNothingIsPinnedAndNothingIsRefused() {
		ArtifactRetentionLock ce = new ArtifactRetentionLock();
		UUID a = UUID.randomUUID();

		assertSame(Retention.NONE, ce.pinsOf(ORG, List.of(a)));
		ce.assertNotArchivable(ORG, a);
		assertSame(Retention.NONE, ce.assertNotUnbindable(ORG, R1, List.of(a)));
		assertSame(Retention.NONE, ce.assertNotReplaceable(ORG, R1, List.of(a)));
	}

	@Test
	void aNullArtifactIsNeverArchivableRefusedAndNeverReachesTheHook() {
		lock.assertNotArchivable(ORG, null);

		verify(hook, never()).pinsOf(any(), any());
	}

	@Test
	void anEmptyArtifactListNeverReachesTheHook() {
		assertSame(Retention.NONE, lock.pinsOf(ORG, List.of()));
		assertSame(Retention.NONE, lock.assertNotUnbindable(ORG, R1, List.of()));

		verify(hook, never()).pinsOf(any(), any());
	}

	@Test
	void onePinRefusesTheArchiveWithTheExactMessage() {
		UUID a = UUID.randomUUID();
		ArtifactPin p = pin(a, R1, L1, 1);
		when(hook.pinsOf(ORG, List.of(a))).thenReturn(List.of(p));

		ArtifactRetainedException e = assertThrows(ArtifactRetainedException.class, () -> lock.assertNotArchivable(ORG, a));

		assertEquals("artifact " + a + " cannot be archived: it is pinned by package version FDA_PREMARKET v1 of release "
				+ L1 + "; a file a package version pins is retained", e.getMessage());
		assertEquals(List.of(p), e.pins());
		assertEquals(R1, e.release());
		verify(hook, times(1)).pinsOf(any(), any());
	}

	@Test
	void twoPinsOfOneArtifactAreOneSentence() {
		UUID a = UUID.randomUUID();
		when(hook.pinsOf(ORG, List.of(a))).thenReturn(List.of(pin(a, R1, L1, 1), pin(a, R1, L1, 2)));

		ArtifactRetainedException e = assertThrows(ArtifactRetainedException.class, () -> lock.assertNotArchivable(ORG, a));

		assertEquals("artifact " + a + " cannot be archived: it is pinned by package version FDA_PREMARKET v1 of release "
				+ L1 + ", FDA_PREMARKET v2 of release " + L1 + "; a file a package version pins is retained",
				e.getMessage());
	}

	@Test
	void twoRefusedArtifactsAreTwoSentencesInTheHooksOrder() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		when(hook.pinsOf(eq(ORG), any())).thenReturn(List.of(pin(b, R1, L1, 1), pin(a, R1, L1, 1)));

		ArtifactRetainedException e = assertThrows(ArtifactRetainedException.class,
				() -> lock.assertNotUnbindable(ORG, R1, List.of(a, b)));

		String action = "removed from release " + R1;
		assertEquals("artifact " + b + " cannot be " + action + ": it is pinned by package version FDA_PREMARKET v1 of "
				+ "release " + L1 + "; a file a package version pins is retained; artifact " + a + " cannot be " + action
				+ ": it is pinned by package version FDA_PREMARKET v1 of release " + L1
				+ "; a file a package version pins is retained", e.getMessage());
		verify(hook, times(1)).pinsOf(any(), any());
	}

	@Test
	void anUnbindIsRefusedOnlyByAPinOfTheSameReleaseAndOtherwiseReturnsTheOtherPins() {
		UUID ownPinned = UUID.randomUUID();
		UUID otherPinned = UUID.randomUUID();
		UUID free = UUID.randomUUID();
		ArtifactPin own = pin(ownPinned, R1, L1, 1);
		ArtifactPin other = pin(otherPinned, R2, L2, 3);
		when(hook.pinsOf(eq(ORG), any())).thenReturn(List.of(own, other));

		ArtifactRetainedException e = assertThrows(ArtifactRetainedException.class,
				() -> lock.assertNotUnbindable(ORG, R1, List.of(ownPinned, otherPinned, free)));
		assertEquals(List.of(own), e.pins(), "only the same release's pins refuse");
		assertTrue(e.getMessage().contains("artifact " + ownPinned + " cannot be removed from release " + R1));
		assertFalse(e.getMessage().contains(otherPinned.toString()));

		UUID unpinnedRelease = UUID.randomUUID();
		Retention held = lock.assertNotUnbindable(ORG, unpinnedRelease, List.of(ownPinned, otherPinned, free));
		assertTrue(held.retains(ownPinned));
		assertTrue(held.retains(otherPinned));
		assertFalse(held.retains(free));
	}

	@Test
	void aReplaceIsRefusedWithTheReplacedWording() {
		UUID a = UUID.randomUUID();
		when(hook.pinsOf(eq(ORG), any())).thenReturn(List.of(pin(a, R1, L1, 1)));

		ArtifactRetainedException e = assertThrows(ArtifactRetainedException.class,
				() -> lock.assertNotReplaceable(ORG, R1, List.of(a)));

		assertTrue(e.getMessage().startsWith("artifact " + a + " cannot be replaced on release " + R1 + ": "),
				e.getMessage());
	}

	@Test
	void theRefusalIsUncheckedSoTheRequestRollsBack() {
		assertTrue(RuntimeException.class.isAssignableFrom(ArtifactRetainedException.class));
	}
}
