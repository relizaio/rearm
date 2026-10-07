/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.SafeRegex;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.DependencyPatternService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

/**
 * previewPattern takes its regex straight from the request with only org READ, so a pattern that
 * runs out of match budget is an error the user sees -- not an empty preview that reads as "this
 * pattern matches nothing".
 */
@ExtendWith(MockitoExtension.class)
class BranchDataFetcherPreviewPatternTest {

	private static final String SUBJECT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";
	/** Matches ordinary service names cheaply; runs away on runaway names. */
	private static final String SOMETIMES_RUNAWAY = "svc-.*|(.*a){20}";

	@Mock private UserService userService;
	@Mock private AuthorizationService authorizationService;
	@Mock private GetOrganizationService getOrganizationService;
	@Mock private ComponentService componentService;
	@InjectMocks private BranchDataFetcher fetcher;

	private final UUID org = UUID.randomUUID();

	@BeforeEach
	void wire() {
		// The real sweep, over mocked components.
		DependencyPatternService dependencyPatternService = new DependencyPatternService();
		ReflectionTestUtils.setField(dependencyPatternService, "componentService", componentService);
		ReflectionTestUtils.setField(fetcher, "dependencyPatternService", dependencyPatternService);
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(mock(UserData.class)));
		when(getOrganizationService.getOrganizationData(org)).thenReturn(Optional.of(mock(OrganizationData.class)));
	}

	private ComponentData component(String name) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setName(name);
		cd.setType(ComponentType.COMPONENT);
		return cd;
	}

	private void components(int runaway) {
		List<ComponentData> all = new ArrayList<>(List.of(component("svc-web")));
		for (int i = 0; i < runaway; i++) all.add(component("a".repeat(i) + SUBJECT));
		when(componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT)).thenReturn(all);
	}

	@Test
	@Timeout(30)
	void aPatternAbandonedForRunningAwayIsAnError() {
		components(SafeRegex.MAX_TRIPS_PER_SWEEP + 1);
		long start = System.nanoTime();
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.previewPattern(org, SOMETIMES_RUNAWAY, null, null, null, null));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertTrue(e.getMessage().contains("match budget"), e.getMessage());
	}

	@Test
	@Timeout(10)
	void aPatternThatLeftSomeComponentsOutIsAnErrorToo() {
		components(1);
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.previewPattern(org, SOMETIMES_RUNAWAY, null, null, null, null));
		assertTrue(e.getMessage().contains("match budget"), e.getMessage());
	}

	@Test
	void aPatternOverTheCapIsRefusedBeforeAnyMatching() {
		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.previewPattern(org,
				"a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1), null, null, null, null));
		assertEquals("Pattern exceeds " + SafeRegex.MAX_PATTERN_LENGTH + " characters", e.getMessage());
	}
}
