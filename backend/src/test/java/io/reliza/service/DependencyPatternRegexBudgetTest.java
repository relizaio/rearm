/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.common.SafeRegex;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.DependencyPattern;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.service.DependencyPatternService.PatternSweep;
import io.reliza.service.DependencyPatternService.PatternSweepOutcome;

/**
 * Feature-set dependency patterns are matched under the step budget; a runaway one matches
 * nothing.
 *
 * <p>The reverse lookup runs on the release path for every feature set with patterns in the org,
 * and the forward sweep matches a pattern against every component of the org; unbounded, one
 * saved {@code (.*a){20}} held those threads for minutes per component name.
 */
@ExtendWith(MockitoExtension.class)
class DependencyPatternRegexBudgetTest {

	private static final String RUNAWAY = "(.*a){20}";
	private static final String SUBJECT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

	@Mock private BranchService branchService;
	@Mock private ComponentService componentService;
	@Mock private GetComponentService getComponentService;
	@InjectMocks private DependencyPatternService service;

	private final UUID org = UUID.randomUUID();

	private static DependencyPattern pattern(String regex) {
		return DependencyPattern.builder().uuid(UUID.randomUUID()).pattern(regex).build();
	}

	private ComponentData component(String name) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setName(name);
		cd.setType(ComponentType.COMPONENT);
		return cd;
	}

	private static BranchData featureSet(DependencyPattern... patterns) {
		BranchData fs = mock(BranchData.class);
		when(fs.getComponent()).thenReturn(UUID.randomUUID());
		when(fs.getDependencyPatterns()).thenReturn(List.of(patterns));
		return fs;
	}

	/** Matches ordinary service names cheaply; runs away on {@link #SUBJECT}. */
	private static final String SOMETIMES_RUNAWAY = "svc-.*|(.*a){20}";

	@Test
	@Timeout(10)
	void theReleasePathLookupTreatsARunawayPatternAsNoMatchFast() {
		long start = System.nanoTime();
		boolean alone = service.componentMatchesAnyPattern(SUBJECT, List.of(pattern(RUNAWAY)));
		boolean withNext = service.componentMatchesAnyPattern(SUBJECT, List.of(pattern(RUNAWAY), pattern("a+!")));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertFalse(alone);
		assertTrue(withNext, "a later pattern still matches");
	}

	@Test
	@Timeout(30)
	void upToTheTripThresholdATrippedComponentIsLeftOutAndTheRestStillMatch() {
		// The runaway names first: the outcome must not depend on the order the query returns.
		List<ComponentData> all = new ArrayList<>();
		for (int i = 0; i < SafeRegex.MAX_TRIPS_PER_SWEEP; i++) all.add(component(SUBJECT));
		ComponentData web = component("svc-web");
		ComponentData api = component("svc-api");
		all.add(web);
		all.add(api);
		when(componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT)).thenReturn(all);

		long start = System.nanoTime();
		PatternSweep sweep = service.sweepComponentsByPattern(org, SOMETIMES_RUNAWAY);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertEquals(PatternSweepOutcome.SKIPPED_SOME, sweep.outcome());
		assertEquals(List.of(web, api), sweep.matched());
	}

	@Test
	@Timeout(30)
	void pastTheTripThresholdThePatternIsAbandonedAndContributesNothing() {
		// A match found before the trips is dropped too, so the result is the same in any order;
		// and the names after the abandoning trip are never read.
		ComponentData web = component("svc-web");
		List<ComponentData> all = new ArrayList<>(List.of(web));
		for (int i = 0; i < 10; i++) all.add(spy(component(SUBJECT)));
		when(componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT)).thenReturn(all);

		long start = System.nanoTime();
		PatternSweep sweep = service.sweepComponentsByPattern(org, SOMETIMES_RUNAWAY);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertEquals(PatternSweepOutcome.ABANDONED, sweep.outcome());
		assertEquals(List.of(), sweep.matched());
		assertEquals(List.of(), service.findComponentsByPattern(org, SOMETIMES_RUNAWAY));
		int abandonedAt = 1 + SafeRegex.MAX_TRIPS_PER_SWEEP + 1;
		for (ComponentData after : all.subList(abandonedAt, all.size())) verify(after, never()).getName();
	}

	@Test
	void overlongNamesAreLeftOutWithoutCountingAsTrips() {
		List<ComponentData> all = new ArrayList<>();
		for (int i = 0; i <= SafeRegex.MAX_TRIPS_PER_SWEEP; i++) {
			all.add(component("svc-" + "a".repeat(SafeRegex.MAX_INPUT_LENGTH)));
		}
		ComponentData ordinary = component("svc-web");
		all.add(ordinary);
		when(componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT)).thenReturn(all);

		PatternSweep sweep = service.sweepComponentsByPattern(org, "svc-.*");
		assertEquals(PatternSweepOutcome.COMPLETED, sweep.outcome());
		assertEquals(List.of(ordinary), sweep.matched());
	}

	@Test
	void anInvalidStoredPatternSweepsToNothing() {
		when(componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT))
				.thenReturn(List.of(component("svc-web")));
		PatternSweep sweep = service.sweepComponentsByPattern(org, "svc-(");
		assertEquals(PatternSweepOutcome.INVALID_PATTERN, sweep.outcome());
		assertEquals(List.of(), service.findComponentsByPattern(org, "svc-("));
	}

	@Test
	@Timeout(10)
	void theBranchLookupTreatsARunawayPatternAsNoMatchAndStillHonoursTheNextOne() {
		ComponentData cd = component(SUBJECT);
		when(getComponentService.getComponentData(cd.getUuid())).thenReturn(Optional.of(cd));
		UUID baseUuid = UUID.randomUUID();
		Branch base = mock(Branch.class);
		when(base.getUuid()).thenReturn(baseUuid);
		when(branchService.getBaseBranchOfComponent(cd.getUuid())).thenReturn(Optional.of(base));

		BranchData evilOnly = featureSet(pattern(RUNAWAY));
		BranchData evilThenGood = featureSet(pattern(RUNAWAY), pattern("a+!"));
		when(branchService.findFeatureSetDataWithDependencyPatterns(org)).thenReturn(List.of(evilOnly, evilThenGood));

		long start = System.nanoTime();
		List<BranchData> matched = service.findFeatureSetsMatchingBranchByPattern(org, cd.getUuid(), baseUuid);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertEquals(List.of(evilThenGood), matched);
	}

	@Test
	void writesAcceptABlankPatternAsBefore() throws RelizaException {
		DependencyPatternService.validatePatterns(List.of(pattern("svc-.*"), pattern(""), pattern(" "), pattern(null)), null);
	}

	@Test
	void legacyStoredPatternsDoNotBlockAnUnrelatedEdit() throws RelizaException {
		// The branch form sends the whole list. A pattern saved before the checks -- over the
		// length cap, or no longer compiling -- must not stop renaming the feature set or adding
		// a good pattern; a new bad one is still refused.
		String overCap = "svc-" + "a".repeat(SafeRegex.MAX_PATTERN_LENGTH);
		List<DependencyPattern> stored = List.of(pattern(overCap), pattern("svc-("));
		DependencyPatternService.validatePatterns(List.of(pattern(overCap), pattern("svc-("), pattern("api-.*")), stored);
		assertThrows(RelizaException.class, () -> DependencyPatternService.validatePatterns(
				List.of(pattern(overCap), pattern("svc-("), pattern("api-(")), stored));
		assertThrows(RelizaException.class, () -> DependencyPatternService.validatePatterns(
				List.of(pattern(overCap + "b")), stored), "an edited pattern is a new one");
	}

	@Test
	void writesRefuseATooLongOrInvalidPatternOrANullEntry() {
		assertEquals("Dependency pattern [0] exceeds " + SafeRegex.MAX_PATTERN_LENGTH + " characters",
				assertThrows(RelizaException.class, () -> DependencyPatternService.validatePatterns(
						List.of(pattern("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1))), null)).getMessage());
		assertTrue(assertThrows(RelizaException.class, () -> DependencyPatternService.validatePatterns(
				List.of(pattern(""), pattern("svc-(")), null)).getMessage().startsWith("Dependency pattern [1] is not a valid regex: "));
		assertEquals("Dependency pattern [0] is null", assertThrows(RelizaException.class,
				() -> DependencyPatternService.validatePatterns(Arrays.asList((DependencyPattern) null), null)).getMessage());
	}
}
