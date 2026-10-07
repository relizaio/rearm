/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.ReleaseChangeHook;

/**
 * The lifecycle NOTIFY GATE -- {@code processReleaseLifecycleEvents} -- as
 * distinct from the hook it calls.
 *
 * <p>Worth its own class because the gap it guards lived here, untested, while
 * {@code ReleaseChangeHookImplTest} covered the hook thoroughly. The hook did
 * exactly what it was asked; the caller only asked on four of eleven
 * lifecycles, so a subscription to {@code RELEASE_LIFECYCLE_CHANGED} saved,
 * validated, and then stayed silent for {@code READY_TO_SHIP},
 * {@code GENERAL_AVAILABILITY} and every end-of-life stage. Testing a producer
 * without testing what decides to call it is how that survived.
 *
 * <p>The assertions below pin the {@code (old, new)} ARGUMENT ORDER rather than
 * using bare {@code any()}. That is not defensiveness: this method takes
 * {@code (rData, curLifecycle, oldLifecycle)} while the hook it calls takes
 * {@code (rd, oldLifecycle, newLifecycle)} -- reversed -- and a swap there is
 * invisible to a count-only assertion while corrupting the dedup key, the
 * rendered message and every CEL filter on {@code newLifecycle}.
 *
 * <p>CE copy of Pro's test: CE has no follow instances or marketing releases, so the gate's
 * side effects here are product auto-integration alone.
 */
class ReleaseLifecycleNotifyGateTest {

	private OssReleaseService service;
	private ReleaseChangeHook hook;

	@BeforeEach
	void wire() {
		service = spy(new OssReleaseService(mock(ReleaseRepository.class)));
		hook = mock(ReleaseChangeHook.class);
		ReflectionTestUtils.setField(service, "releaseChangeHook", hook);
		// Same-class call, so it needs the spy rather than a field stub.
		doNothing().when(service).autoIntegrateProducts(any());
	}

	private static ReleaseData release() {
		// org / branch have private (audit-controlled) setters, so reflect them in.
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "branch", UUID.randomUUID());
		return rd;
	}

	private void transition(ReleaseLifecycle from, ReleaseLifecycle to) {
		try {
			Method m = OssReleaseService.class.getDeclaredMethod("processReleaseLifecycleEvents",
					ReleaseData.class, ReleaseLifecycle.class, ReleaseLifecycle.class);
			m.setAccessible(true);
			m.invoke(service, release(), to, from);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not drive the lifecycle gate", e);
		}
	}

	/**
	 * A notified transition. A record rather than a formatted string so the
	 * compiler owns the domain: renaming a ReleaseLifecycle constant must break
	 * the build, not produce a string diff at runtime.
	 */
	private record Transition(ReleaseLifecycle from, ReleaseLifecycle to) {}

	/** Every (old, new) pair the hook was handed, in call order. */
	private List<Transition> notifiedPairs() {
		ArgumentCaptor<ReleaseLifecycle> oldC = ArgumentCaptor.forClass(ReleaseLifecycle.class);
		ArgumentCaptor<ReleaseLifecycle> newC = ArgumentCaptor.forClass(ReleaseLifecycle.class);
		verify(hook, atLeast(0))
				.onReleaseLifecycleChanged(any(), oldC.capture(), newC.capture());
		List<Transition> out = new ArrayList<>();
		for (int i = 0; i < oldC.getAllValues().size(); i++) {
			out.add(new Transition(oldC.getAllValues().get(i), newC.getAllValues().get(i)));
		}
		return out;
	}

	/**
	 * The regression this class exists for: every value is reachable, including
	 * PENDING as a destination. Before the fix only four of eleven fired.
	 */
	@Test
	void everyLifecycleIsNotifiableInBothDirections() {
		List<Transition> expected = new ArrayList<>();
		for (ReleaseLifecycle to : ReleaseLifecycle.values()) {
			ReleaseLifecycle from = (to == ReleaseLifecycle.DRAFT)
					? ReleaseLifecycle.PENDING : ReleaseLifecycle.DRAFT;
			transition(from, to);
			expected.add(new Transition(from, to));
		}
		assertEquals(expected, notifiedPairs(),
				"every lifecycle must notify, with (old, new) in that order");
	}

	@Test
	void theStagesThatUsedToBeSilentNowFireWithTheRightTransition() {
		transition(ReleaseLifecycle.ASSEMBLED, ReleaseLifecycle.READY_TO_SHIP);
		transition(ReleaseLifecycle.READY_TO_SHIP, ReleaseLifecycle.GENERAL_AVAILABILITY);
		transition(ReleaseLifecycle.GENERAL_AVAILABILITY, ReleaseLifecycle.END_OF_MARKETING);
		transition(ReleaseLifecycle.END_OF_MARKETING, ReleaseLifecycle.END_OF_DISTRIBUTION);
		transition(ReleaseLifecycle.END_OF_DISTRIBUTION, ReleaseLifecycle.END_OF_SUPPORT);
		transition(ReleaseLifecycle.END_OF_SUPPORT, ReleaseLifecycle.END_OF_LIFE);
		assertEquals(List.of(
				new Transition(ReleaseLifecycle.ASSEMBLED, ReleaseLifecycle.READY_TO_SHIP),
				new Transition(ReleaseLifecycle.READY_TO_SHIP, ReleaseLifecycle.GENERAL_AVAILABILITY),
				new Transition(ReleaseLifecycle.GENERAL_AVAILABILITY, ReleaseLifecycle.END_OF_MARKETING),
				new Transition(ReleaseLifecycle.END_OF_MARKETING, ReleaseLifecycle.END_OF_DISTRIBUTION),
				new Transition(ReleaseLifecycle.END_OF_DISTRIBUTION, ReleaseLifecycle.END_OF_SUPPORT),
				new Transition(ReleaseLifecycle.END_OF_SUPPORT, ReleaseLifecycle.END_OF_LIFE)),
				notifiedPairs());
	}

	/**
	 * New guard. The old chain carried an old != new check ONLY on the DRAFT
	 * arm, so re-saving an already-CANCELLED release re-announced a transition
	 * that never happened. Dedup masked it only while a window was configured.
	 *
	 * <p>Also asserts the ASSEMBLED side effects STILL run on a non-transition,
	 * because that is the one place the notify rule and the state machinery
	 * disagree: {@code createRelease(rebuildRelease=true)} re-saves an already
	 * ASSEMBLED release, and product auto-integration must still fire for it.
	 * Without this assertion, folding the side effects back under the notify
	 * guard -- the obvious tidy-up -- passes every other test in this class.
	 */
	@Test
	void aNonTransitionDoesNotNotifyButAssembledStillDrivesItsSideEffects() {
		transition(ReleaseLifecycle.ASSEMBLED, ReleaseLifecycle.ASSEMBLED);
		verify(hook, never()).onReleaseLifecycleChanged(any(), any(), any());
		verify(service).autoIntegrateProducts(any());
	}

	@Test
	void aRepeatedCancelDoesNotReAnnounce() {
		transition(ReleaseLifecycle.CANCELLED, ReleaseLifecycle.CANCELLED);
		verify(hook, never()).onReleaseLifecycleChanged(any(), any(), any());
	}

	@Test
	void assembledStillDrivesAutoIntegration() {
		transition(ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED);
		verify(service).autoIntegrateProducts(any());
		assertEquals(List.of(new Transition(ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED)),
				notifiedPairs());
	}

	@Test
	void nonAssembledStagesDoNotDriveAutoIntegration() {
		transition(ReleaseLifecycle.ASSEMBLED, ReleaseLifecycle.GENERAL_AVAILABILITY);
		verify(service, never()).autoIntegrateProducts(any());
	}

	/**
	 * CE has no ReleaseChangeHook bean ({@code @Autowired(required = false)}).
	 * The null check shares a condition with the notify guard, so a refactor
	 * that hoists one without the other either NPEs on every lifecycle change
	 * on CE, or takes the ASSEMBLED side effects down with it.
	 */
	@Test
	void anAbsentHookIsSurvivableAndDoesNotCostTheSideEffects() {
		ReflectionTestUtils.setField(service, "releaseChangeHook", null);
		transition(ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED);
		verify(service).autoIntegrateProducts(any());
	}

	/** Guards the pair helper itself: a swap must be visible, not silently equal. */
	@Test
	void theTransitionAssertionActuallyDistinguishesDirection() {
		transition(ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED);
		verify(hook).onReleaseLifecycleChanged(any(),
				eq(ReleaseLifecycle.DRAFT), eq(ReleaseLifecycle.ASSEMBLED));
	}

}
