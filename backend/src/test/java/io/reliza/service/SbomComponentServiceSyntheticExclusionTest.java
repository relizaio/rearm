/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.HbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;

/**
 * TEA-4: the sbom_components reconcile (and through it the Dependency-Track fan-out, which reads
 * only what the reconcile wrote) and the HBOM reconcile never take a generated document. Its
 * collector never reads ReleaseData.syntheticArtifacts; a SYNTHETIC BOM that somehow sits in an
 * inventory list is skipped with a WARN before it is mapped. Built as
 * SbomComponentReleaseInventoryTest builds the service.
 */
class SbomComponentServiceSyntheticExclusionTest {

	private static final UUID ORG = UUID.randomUUID();

	private SbomComponentService service;
	private SbomComponentService self;
	private SharedReleaseService sharedReleaseService;
	private ArtifactService artifactService;
	private RebomService rebomService;
	private ArtifactCanonicalMapRepository canonicalMapRepository;
	private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	private ReleaseArtifactIndexRepository indexRepository;
	private VariantService variantService;
	private GetDeliverableService getDeliverableService;
	private CapturingAppender sbomLogs;
	private CapturingAppender hbomLogs;

	private ReleaseData rd;
	private ArtifactData inventory;
	private ArtifactData synthetic;
	private final UUID inventoryCanonical = UUID.randomUUID();

	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		private Level previousLevel;
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		List<LogEvent> syntheticWarnings() {
			return events.stream().filter(e -> e.getLevel() == Level.WARN
					&& e.getMessage().getFormattedMessage().contains("[SYNTHETIC-IN-INVENTORY]")).toList();
		}
	}

	private static CapturingAppender capture(Class<?> loggerClass) {
		CapturingAppender a = new CapturingAppender();
		a.start();
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerClass);
		a.previousLevel = logger.getLevel();
		Configurator.setLevel(loggerClass.getName(), Level.WARN);
		logger.addAppender(a);
		return a;
	}

	private static void release(Class<?> loggerClass, CapturingAppender a) {
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerClass);
		logger.removeAppender(a);
		Configurator.setLevel(loggerClass.getName(), a.previousLevel);
		a.stop();
	}

	@BeforeEach
	void setUp() {
		canonicalMapRepository = mock(ArtifactCanonicalMapRepository.class);
		artifactSbomComponentRepository = mock(ArtifactSbomComponentRepository.class);
		indexRepository = mock(ReleaseArtifactIndexRepository.class);
		service = new SbomComponentService(mock(SbomComponentRepository.class), artifactSbomComponentRepository,
				indexRepository, canonicalMapRepository,
				mock(SbomComponentSupportRepository.class), mock(SbomComponentSupportAuditRepository.class));
		self = mock(SbomComponentService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		artifactService = mock(ArtifactService.class);
		rebomService = mock(RebomService.class);
		variantService = mock(VariantService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		when(variantService.findBaseVariantForRelease(any())).thenReturn(Optional.empty());
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of());
		ReflectionTestUtils.setField(service, "self", self);
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		ReflectionTestUtils.setField(service, "rebomService", rebomService);
		ReflectionTestUtils.setField(service, "variantService", variantService);
		ReflectionTestUtils.setField(service, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(service, "getSourceCodeEntryService", mock(GetSourceCodeEntryService.class));

		inventory = bom(ArtifactBelongsTo.RELEASE);
		synthetic = bom(ArtifactBelongsTo.SYNTHETIC);
		ArtifactCanonicalMap map = new ArtifactCanonicalMap();
		ReflectionTestUtils.setField(map, "canonicalArtifactUuid", inventoryCanonical);
		when(canonicalMapRepository.findByArtifactUuid(inventory.getUuid())).thenReturn(Optional.of(map));
		when(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(any())).thenReturn(false);
		when(self.parseAndUpsertArtifactSbomComponents(any(), any(), any())).thenReturn(true);

		rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setArtifacts(new LinkedList<>(List.of(inventory.getUuid())));
		rd.addSyntheticArtifact(synthetic.getUuid());
		when(sharedReleaseService.getReleaseData(rd.getUuid())).thenReturn(Optional.of(rd));

		sbomLogs = capture(SbomComponentService.class);
		hbomLogs = capture(HbomComponentService.class);
	}

	@AfterEach
	void tearDown() {
		release(SbomComponentService.class, sbomLogs);
		release(HbomComponentService.class, hbomLogs);
	}

	private ArtifactData bom(ArtifactBelongsTo belongsTo) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setOrg(ORG);
		ad.setType(ArtifactType.BOM);
		ad.setStoredIn(StoredIn.REARM);
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), belongsTo));
		when(artifactService.getArtifactData(ad.getUuid())).thenReturn(Optional.of(ad));
		return ad;
	}

	@SuppressWarnings("unchecked")
	private Set<UUID> indexedCanonicals() {
		ArgumentCaptor<Set<UUID>> set = ArgumentCaptor.forClass(Set.class);
		verify(self).rebuildReleaseArtifactIndex(eq(rd.getUuid()), eq(ORG), set.capture(), anyBoolean());
		return set.getValue();
	}

	private HbomComponentService hbomService() {
		HbomComponentService hbom = new HbomComponentService(mock(HbomComponentRepository.class));
		ReflectionTestUtils.setField(hbom, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(hbom, "artifactService", artifactService);
		ReflectionTestUtils.setField(hbom, "rebomService", rebomService);
		ReflectionTestUtils.setField(hbom, "variantService", variantService);
		ReflectionTestUtils.setField(hbom, "getDeliverableService", getDeliverableService);
		return hbom;
	}

	@Test
	void theReconcileNeverLoadsTheSyntheticList() {
		int skipped = service.reconcileReleaseSbomComponents(rd.getUuid());

		assertEquals(0, skipped);
		verify(artifactService, never()).getArtifactData(synthetic.getUuid());
		verify(self, times(1)).parseAndUpsertArtifactSbomComponents(any(), any(), any());
		verify(self).parseAndUpsertArtifactSbomComponents(inventory, inventoryCanonical, ORG);
		verify(canonicalMapRepository, never()).findByArtifactUuid(synthetic.getUuid());
		verify(artifactSbomComponentRepository, never()).existsByCanonicalArtifactUuid(synthetic.getUuid());
		assertEquals(Set.of(inventoryCanonical), indexedCanonicals());
		verify(indexRepository, never()).saveAll(any());
		assertTrue(sbomLogs.syntheticWarnings().isEmpty());
	}

	@Test
	void aSyntheticBomListedAsInventoryIsSkippedBeforeItIsMapped() {
		rd.getArtifacts().add(synthetic.getUuid());

		int skipped = service.reconcileReleaseSbomComponents(rd.getUuid());

		assertEquals(0, skipped, "the same count as without it: not a retryable skip");
		verify(artifactService).getArtifactData(synthetic.getUuid());
		verify(canonicalMapRepository, never()).findByArtifactUuid(synthetic.getUuid());
		verify(self, never()).parseAndUpsertArtifactSbomComponents(eq(synthetic), any(), any());
		assertEquals(Set.of(inventoryCanonical), indexedCanonicals());
		List<LogEvent> warnings = sbomLogs.syntheticWarnings();
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).getMessage().getFormattedMessage().contains(synthetic.getUuid().toString()));
	}

	@Test
	void theHbomReconcileParsesTheInventoryOnly() {
		hbomService().reconcile(rd.getUuid());

		verify(rebomService).parseHbom(inventory.getInternalBom().id(), ORG);
		verify(rebomService, never()).parseHbom(eq(synthetic.getInternalBom().id()), any());
		verify(artifactService, never()).getArtifactData(synthetic.getUuid());

		rd.getArtifacts().add(synthetic.getUuid());
		hbomService().reconcile(rd.getUuid());

		verify(rebomService, times(2)).parseHbom(inventory.getInternalBom().id(), ORG);
		verify(rebomService, never()).parseHbom(eq(synthetic.getInternalBom().id()), any());
		List<LogEvent> warnings = hbomLogs.syntheticWarnings();
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).getMessage().getFormattedMessage().contains(synthetic.getUuid().toString()));
	}
}
