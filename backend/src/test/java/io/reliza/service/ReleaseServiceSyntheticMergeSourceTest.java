/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.MergedSbomUnavailableException.Reason;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * TEA-4: a generated document is never a merge source. A synthetic BOM bound to a release
 * (ReleaseData.syntheticArtifacts) does not reach the component merge or the product merge, and
 * one that somehow sits in the inventory list as well is dropped with a WARN. Built like
 * ReleaseServiceProductMergeTest: collaborators mocked, rebom recorded.
 */
class ReleaseServiceSyntheticMergeSourceTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private ReleaseService svc;
	private final List<List<UUID>> merges = new ArrayList<>();
	private final Map<UUID, ComponentData> components = new HashMap<>();
	private final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	private final Map<UUID, ReleaseData> releases = new HashMap<>();
	private CapturingAppender logs;
	private Level previousLevel;

	/** In-memory appender on ReleaseService's own logger. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@BeforeEach
	void setUp() {
		svc = new ReleaseService(null);
		SharedReleaseService shared = mock(SharedReleaseService.class);
		ReleaseRebomService releaseRebomService = mock(ReleaseRebomService.class);
		GetComponentService getComponentService = mock(GetComponentService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		GetDeliverableService getDeliverableService = mock(GetDeliverableService.class);
		VariantService variantService = mock(VariantService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getName()).thenReturn("Acme Medical");
		when(od.getUuid()).thenReturn(ORG);
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));
		when(shared.getReleaseData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(releases.get(inv.<UUID>getArgument(0))));
		when(shared.unwindReleaseDependencies(any(ReleaseData.class))).thenReturn(new LinkedHashSet<>());
		when(getComponentService.getComponentData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(components.get(inv.<UUID>getArgument(0))));
		when(artifactService.getArtifactData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(artifacts.get(inv.<UUID>getArgument(0))));
		when(releaseRebomService.getReleaseBoms(any(ReleaseData.class))).thenReturn(List.of());
		when(variantService.getBaseVariantForRelease(any(ReleaseData.class))).thenReturn(new VariantData());
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of());
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public UUID mergeAndStoreBoms(List<UUID> bomIds, RebomOptions rebomOptions, UUID org) throws RelizaException {
				merges.add(List.copyOf(bomIds));
				return UUID.randomUUID();
			}
		};
		ReflectionTestUtils.setField(svc, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		ReflectionTestUtils.setField(svc, "getSourceCodeEntryService", mock(GetSourceCodeEntryService.class));
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
		ReflectionTestUtils.setField(svc, "rebomService", rebom);

		logs = new CapturingAppender();
		logs.start();
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class);
		previousLevel = logger.getLevel();
		Configurator.setLevel(ReleaseService.class.getName(), Level.WARN);
		logger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class);
		logger.removeAppender(logs);
		Configurator.setLevel(ReleaseService.class.getName(), previousLevel);
		logs.stop();
	}

	// ---- fixtures ----

	private ReleaseData release(String name, ComponentType type) {
		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(cd, "org", ORG);
		cd.setName(name);
		cd.setType(type);
		components.put(cd.getUuid(), cd);
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		rd.setVersion("9000.1.0");
		rd.setArtifacts(new ArrayList<>());
		releases.put(rd.getUuid(), rd);
		return rd;
	}

	private ArtifactData bom(ArtifactBelongsTo belongsTo) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), belongsTo));
		artifacts.put(ad.getUuid(), ad);
		return ad;
	}

	/** An inventory BOM in rd.artifacts; answers it. */
	private ArtifactData inventory(ReleaseData rd) {
		ArtifactData a = bom(ArtifactBelongsTo.RELEASE);
		rd.getArtifacts().add(a.getUuid());
		return a;
	}

	/** A generated BOM bound through the synthetic list only; answers it. */
	private ArtifactData synthetic(ReleaseData rd) {
		ArtifactData s = bom(ArtifactBelongsTo.SYNTHETIC);
		rd.addSyntheticArtifact(s.getUuid());
		return s;
	}

	private UUID export(ReleaseData rd) throws Exception {
		return svc.getReleaseBomId(rd.getUuid(), false, false, null, BomStructureType.FLAT, WU, null);
	}

	private List<LogEvent> syntheticWarnings() {
		return logs.events.stream().filter(e -> e.getLevel() == Level.WARN
				&& e.getMessage().getFormattedMessage().contains("[SYNTHETIC-IN-INVENTORY]")).toList();
	}

	// ---- cases ----

	@Test
	void aComponentMergeTakesTheInventoryBomAndNotTheGeneratedOne() throws Exception {
		ReleaseData rd = release("firmware", ComponentType.COMPONENT);
		ArtifactData a = inventory(rd);
		synthetic(rd);

		export(rd);

		assertEquals(List.of(List.of(a.getInternalBom().id())), merges);
	}

	/** The 9000.1.0 shape of TEA-1: a product with its own SBOM and no component releases. */
	@Test
	void aProductWithoutChildrenMergesItsOwnInventoryOnly() throws Exception {
		ReleaseData product = release("device", ComponentType.PRODUCT);
		ArtifactData a = inventory(product);
		synthetic(product);

		export(product);

		assertEquals(List.of(List.of(a.getInternalBom().id())), merges);
	}

	@Test
	void aProductCarryingOnlyAGeneratedBomHasNoSbomAndDoesNotNameIt() {
		ReleaseData product = release("device", ComponentType.PRODUCT);
		ArtifactData s = synthetic(product);

		MergedSbomUnavailableException e = assertThrows(MergedSbomUnavailableException.class, () -> export(product));

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertFalse(e.getMessage().contains(s.getUuid().toString()), e.getMessage());
		assertFalse(e.getMessage().contains(s.getInternalBom().id().toString()), e.getMessage());
		assertTrue(merges.isEmpty(), merges.toString());
	}

	@Test
	void aGeneratedBomListedAsInventoryTooIsDroppedWithAWarning() throws Exception {
		ReleaseData rd = release("firmware", ComponentType.COMPONENT);
		ArtifactData a = inventory(rd);
		ArtifactData s = synthetic(rd);
		rd.getArtifacts().add(s.getUuid());

		export(rd);

		assertEquals(List.of(List.of(a.getInternalBom().id())), merges);
		List<LogEvent> warnings = syntheticWarnings();
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).getMessage().getFormattedMessage().contains(s.getUuid().toString()));
	}

	@Test
	void collectOwnBomIdsNeverAnswersTheGeneratedBom() {
		ReleaseData rd = release("firmware", ComponentType.COMPONENT);
		ArtifactData a = inventory(rd);
		ArtifactData s = synthetic(rd);
		rd.getArtifacts().add(s.getUuid());

		for (ArtifactBelongsTo filter : new ArtifactBelongsTo[] { null, ArtifactBelongsTo.RELEASE }) {
			RebomOptions options = new RebomOptions(filter, false, false, BomStructureType.FLAT);
			assertEquals(List.of(a.getInternalBom().id()),
					svc.collectOwnBomIds(rd, ComponentType.COMPONENT, options, null), "belongsTo " + filter);
			assertEquals(List.of(a.getInternalBom().id()),
					svc.collectOwnBomIds(rd, ComponentType.PRODUCT, options, null), "belongsTo " + filter);
		}
	}
}
