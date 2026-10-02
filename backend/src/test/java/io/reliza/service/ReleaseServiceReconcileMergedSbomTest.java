/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * TEA-1: what the merged-SBOM reconcile regenerates after an artifact lands on a release.
 *
 * <p>It used to regenerate, for each bundling product, the configurations cached on the
 * changed release rather than the product's own, and never the changed release itself -- so a
 * release no product bundles (every top-level product, the sandbox's 9000.1.0 among them)
 * served its first merged document forever once one was cached.
 */
class ReleaseServiceReconcileMergedSbomTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final RebomOptions X = new RebomOptions(null, false, false, BomStructureType.FLAT);
	private static final RebomOptions Y = new RebomOptions(ArtifactBelongsTo.RELEASE, true, false, BomStructureType.HIERARCHICAL);

	private record Call(UUID release, RebomOptions options, Boolean forced, UUID componentFilter) {}

	private final List<Call> calls = new ArrayList<>();
	private final Map<UUID, List<ReleaseBom>> caches = new HashMap<>();
	/** Regenerating one of these (release, options) pairs throws. */
	private final Map<UUID, RebomOptions> failing = new HashMap<>();

	private ReleaseService svc;
	private SharedReleaseService shared;
	private SbomComponentService sbomComponentService;
	private CapturingAppender logs;

	/** In-memory appender on ReleaseService's own logger; only the level and the message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@BeforeEach
	void setUp() {
		svc = new ReleaseService(null) {
			@Override
			Optional<UUID> matchOrGenerateSingleBomForRelease(ReleaseData rd, RebomOptions rebomMergeOptions,
					Boolean forced, UUID componentFilter, WhoUpdated wu,
					List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) throws RelizaException {
				calls.add(new Call(rd.getUuid(), rebomMergeOptions, forced, componentFilter));
				if (rebomMergeOptions.equals(failing.get(rd.getUuid()))) {
					ComponentData cd = new ComponentData();
					cd.setName("device");
					cd.setType(ComponentType.PRODUCT);
					throw MergedSbomUnavailableException.childMergeFailed(rd, cd, List.of());
				}
				return Optional.empty();
			}
		};
		shared = mock(SharedReleaseService.class);
		ReleaseRebomService releaseRebomService = mock(ReleaseRebomService.class);
		when(releaseRebomService.getReleaseBoms(any(ReleaseData.class)))
				.thenAnswer(inv -> caches.getOrDefault(inv.<ReleaseData>getArgument(0).getUuid(), List.of()));
		sbomComponentService = mock(SbomComponentService.class);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		ReflectionTestUtils.setField(svc, "sbomComponentService", sbomComponentService);

		logs = new CapturingAppender();
		logs.start();
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class)).addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class)).removeAppender(logs);
		logs.stop();
	}

	private static ReleaseData release(String version) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setVersion(version);
		return rd;
	}

	private void bundledBy(ReleaseData rd, ReleaseData... products) {
		Set<ReleaseData> set = new LinkedHashSet<>(List.of(products));
		when(shared.greedylocateProductsOfRelease(rd)).thenReturn(set);
	}

	private void cached(ReleaseData rd, RebomOptions... options) {
		caches.put(rd.getUuid(), java.util.Arrays.stream(options).map(o -> new ReleaseBom(UUID.randomUUID(), o)).toList());
	}

	@Test
	void eachTargetRegeneratesItsOwnCachedConfigurations() {
		ReleaseData component = release("1.0.0");
		ReleaseData product = release("9000.1.0");
		cached(component, Y);
		cached(product, X);
		bundledBy(component, product);

		svc.reconcileMergedSbomRoutine(component, WhoUpdated.getAutoWhoUpdated());

		assertEquals(List.of(
				new Call(component.getUuid(), Y, true, component.getUuid()),
				new Call(product.getUuid(), X, true, component.getUuid())), calls,
				"the component's Y for the component, the product's X for the product, and no (product, Y)");
	}

	@Test
	void aReleaseNoProductBundlesIsRegeneratedItself() {
		ReleaseData topLevel = release("9000.1.0");
		cached(topLevel, X, Y);
		bundledBy(topLevel);

		svc.reconcileMergedSbomRoutine(topLevel, WhoUpdated.getAutoWhoUpdated());

		assertEquals(List.of(
				new Call(topLevel.getUuid(), X, true, topLevel.getUuid()),
				new Call(topLevel.getUuid(), Y, true, topLevel.getUuid())), calls);
	}

	@Test
	void aConfigurationThatCannotBeRegeneratedIsLoggedAndTheRoutineContinues() {
		ReleaseData component = release("1.0.0");
		ReleaseData product = release("9000.1.0");
		cached(component, X, Y);
		cached(product, X);
		failing.put(component.getUuid(), X);
		bundledBy(component, product);

		svc.reconcileMergedSbomRoutine(component, WhoUpdated.getAutoWhoUpdated());

		assertEquals(List.of(
				new Call(component.getUuid(), X, true, component.getUuid()),
				new Call(component.getUuid(), Y, true, component.getUuid()),
				new Call(product.getUuid(), X, true, component.getUuid())), calls,
				"the failure stops neither the next configuration nor the next target");
		List<LogEvent> errors = logs.events.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
		assertEquals(1, errors.size(), errors.toString());
		assertTrue(errors.get(0).getMessage().getFormattedMessage().contains("CHILD_MERGE_FAILED"),
				errors.get(0).getMessage().getFormattedMessage());
		verify(sbomComponentService).requestReconcile(component.getUuid());
		verify(sbomComponentService).requestReconcile(product.getUuid());
	}
}
