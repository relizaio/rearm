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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;

/**
 * TEA-4: SyntheticArtifactService, the only writer of ReleaseData.syntheticArtifacts. It forces
 * belongsTo SYNTHETIC, binds through the synthetic list only, records SYNTHETIC_ARTIFACT events
 * and saves with no trigger, no collection resolve and no reconcile.
 */
class SyntheticArtifactServiceTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private SyntheticArtifactService service;
	private SharedReleaseService shared;
	private OssReleaseService oss;
	private ArtifactService artifactService;

	private final Map<UUID, Release> releaseRows = new HashMap<>();
	private final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	private final Map<UUID, ReleaseData> lastSaved = new HashMap<>();

	@BeforeEach
	void setUp() {
		service = new SyntheticArtifactService();
		shared = mock(SharedReleaseService.class);
		oss = mock(OssReleaseService.class);
		artifactService = mock(ArtifactService.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(service, "ossReleaseService", oss);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		when(shared.getRelease(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(releaseRows.get(inv.<UUID>getArgument(0))));
		when(shared.getReleaseData(any(UUID.class))).thenAnswer(inv -> Optional
				.ofNullable(releaseRows.get(inv.<UUID>getArgument(0))).map(ReleaseData::dataFromRecord));
		when(artifactService.getArtifactData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(artifacts.get(inv.<UUID>getArgument(0))));
		when(artifactService.getArtifactDataListLight(any())).thenAnswer(inv -> {
			List<ArtifactData> out = new ArrayList<>();
			for (Object id : (Iterable<?>) inv.getArgument(0)) {
				if (artifacts.containsKey(id)) out.add(artifacts.get(id));
			}
			return out;
		});
		when(oss.saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class)))
				.thenAnswer(inv -> {
					// persist what was saved, so a later call re-reads it; the update events live
					// beside the record, so the saved data object is kept as well
					Release r = inv.getArgument(0);
					ReleaseData saved = inv.getArgument(1);
					r.setRecordData(Utils.dataToRecord(saved));
					lastSaved.put(r.getUuid(), saved);
					return r;
				});
	}

	// ---- fixtures ----

	private ReleaseData release(List<UUID> inventory, UUID... synthetic) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", UUID.randomUUID());
		rd.setVersion("3.1.4");
		rd.setArtifacts(new LinkedList<>(inventory));
		rd.setIdentifiers(new ArrayList<>(List.of(
				new RearmIdentifier(RearmIdentifierType.PURL, "pkg:generic/pump@3.1.4"))));
		Arrays.stream(synthetic).forEach(rd::addSyntheticArtifact);
		Release r = new Release();
		UUID uuid = UUID.randomUUID();
		r.setUuid(uuid);
		r.setRecordData(Utils.dataToRecord(rd));
		releaseRows.put(uuid, r);
		return stored(uuid);
	}

	private ReleaseData stored(UUID releaseUuid) {
		return ReleaseData.dataFromRecord(releaseRows.get(releaseUuid));
	}

	/** The release as last saved, events included. */
	private ReleaseData saved(UUID releaseUuid) {
		return lastSaved.get(releaseUuid);
	}

	private ArtifactData artifact(UUID org, TagRecord... tags) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setOrg(org);
		ad.setType(ArtifactType.BOM);
		ad.setDisplayIdentifier("generated.cdx.json");
		ad.setTags(new ArrayList<>(List.of(tags)));
		artifacts.put(ad.getUuid(), ad);
		return ad;
	}

	private static Resource file() {
		return new ByteArrayResource("{}".getBytes());
	}

	private static List<ReleaseUpdateEvent> syntheticEvents(ReleaseData rd) {
		return rd.getUpdateEvents().stream().filter(e -> e.rus() == ReleaseUpdateScope.SYNTHETIC_ARTIFACT).toList();
	}

	// ---- write ----

	@Test
	void writeForcesSyntheticNormalizesTheDtoAndBindsThroughTheSyntheticListOnly() throws Exception {
		UUID inventory = UUID.randomUUID();
		ReleaseData rd = release(List.of(inventory));
		ArtifactData uploaded = artifact(ORG);
		when(artifactService.uploadArtifact(any(), any(), any(), any())).thenReturn(uploaded.getUuid());
		TagRecord callerTag = new TagRecord(CommonVariables.VDR_SNAPSHOT_KEY_TAG_KEY, "lifecycle:GA:x", Removable.NO);
		ArtifactDto dto = ArtifactDto.builder().type(ArtifactType.BOM).displayIdentifier("aggregated.cdx.json")
				.org(UUID.randomUUID()).storedIn(ArtifactData.StoredIn.EXTERNALLY)
				.tags(new ArrayList<>(List.of(callerTag))).build();
		RebomOptions callerOptions = new RebomOptions("n", "g", "1", ArtifactBelongsTo.RELEASE, "h", StripBom.TRUE, "p");

		UUID written = service.write(rd.getUuid(), dto, file(), callerOptions, WU);

		assertEquals(uploaded.getUuid(), written);
		ArgumentCaptor<ArtifactDto> dtoCaptor = ArgumentCaptor.forClass(ArtifactDto.class);
		ArgumentCaptor<RebomOptions> optionsCaptor = ArgumentCaptor.forClass(RebomOptions.class);
		verify(artifactService).uploadArtifact(dtoCaptor.capture(), any(), optionsCaptor.capture(), eq(WU));
		assertEquals(ArtifactBelongsTo.SYNTHETIC, optionsCaptor.getValue().belongsTo(),
				"belongsTo is forced whatever the caller passed");
		assertEquals(callerOptions.withBelongsTo(ArtifactBelongsTo.SYNTHETIC), optionsCaptor.getValue(),
				"every other option is the caller's");
		ArtifactDto sent = dtoCaptor.getValue();
		assertEquals(ORG, sent.getOrg(), "the release's org, never the caller's");
		assertEquals(ArtifactData.StoredIn.REARM, sent.getStoredIn());
		assertTrue(sent.getTags().contains(callerTag), "the caller's own tags survive: " + sent.getTags());
		assertTrue(sent.getTags().contains(new TagRecord(CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY, "true", Removable.NO)));
		assertTrue(sent.getTags().contains(new TagRecord(CommonVariables.SYNTHETIC_OF_RELEASE_TAG_KEY,
				rd.getUuid().toString(), Removable.NO)));

		ArgumentCaptor<ReleaseData> saved = ArgumentCaptor.forClass(ReleaseData.class);
		ArgumentCaptor<Boolean> triggers = ArgumentCaptor.forClass(Boolean.class);
		ArgumentCaptor<AcollectionMode> mode = ArgumentCaptor.forClass(AcollectionMode.class);
		verify(oss).saveRelease(any(Release.class), saved.capture(), eq(WU), triggers.capture(), mode.capture());
		assertEquals(List.of(uploaded.getUuid()), saved.getValue().getSyntheticArtifacts());
		assertEquals(List.of(inventory), saved.getValue().getArtifacts(), "inventory unchanged");
		List<ReleaseUpdateEvent> events = saved.getValue().getUpdateEvents();
		assertEquals(1, events.size(), events.toString());
		assertEquals(ReleaseUpdateScope.SYNTHETIC_ARTIFACT, events.get(0).rus());
		assertEquals(ReleaseUpdateAction.ADDED, events.get(0).rua());
		assertEquals(uploaded.getUuid(), events.get(0).objectId());
		assertFalse(triggers.getValue(), "no trigger fires on the writer's save");
		assertEquals(AcollectionMode.SKIP, mode.getValue());
	}

	/**
	 * The writer can neither reconcile nor merge: it is not wired to either service, so a call to
	 * requestReconcile or reconcileMergedSbomRoutine cannot be added without this failing.
	 */
	@Test
	void theWriterIsNotWiredToReconcileOrMerge() {
		for (Field f : SyntheticArtifactService.class.getDeclaredFields()) {
			assertFalse(f.getType() == ReleaseService.class || f.getType() == SbomComponentService.class
					|| f.getType() == AcollectionService.class, "unexpected collaborator " + f);
		}
	}

	@Test
	void writeWithoutOptionsUsesTheReleaseScopedDefaults() throws Exception {
		ReleaseData rd = release(List.of());
		ArtifactData uploaded = artifact(ORG);
		when(artifactService.uploadArtifact(any(), any(), any(), any())).thenReturn(uploaded.getUuid());

		service.write(rd.getUuid(), ArtifactDto.builder().type(ArtifactType.BOM).build(), file(), null, WU);

		ArgumentCaptor<RebomOptions> optionsCaptor = ArgumentCaptor.forClass(RebomOptions.class);
		verify(artifactService).uploadArtifact(any(), any(), optionsCaptor.capture(), any());
		RebomOptions o = optionsCaptor.getValue();
		assertEquals(ArtifactBelongsTo.SYNTHETIC, o.belongsTo());
		assertEquals(StripBom.FALSE, o.stripBom());
		assertEquals("3.1.4", o.version());
		assertEquals("pkg:generic/pump@3.1.4", o.purl());
	}

	@Test
	void anUploadFailurePropagatesAndBindsNothing() throws Exception {
		ReleaseData rd = release(List.of());
		when(artifactService.uploadArtifact(any(), any(), any(), any())).thenThrow(new RelizaException("rebom down"));

		RelizaException e = assertThrows(RelizaException.class, () -> service.write(rd.getUuid(),
				ArtifactDto.builder().type(ArtifactType.BOM).build(), file(), null, WU));

		assertEquals("rebom down", e.getMessage());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class));
	}

	@Test
	void writeOnAMissingReleaseIsRefusedBeforeAnyUpload() throws Exception {
		UUID missing = UUID.randomUUID();

		RelizaException e = assertThrows(RelizaException.class, () -> service.write(missing,
				ArtifactDto.builder().type(ArtifactType.BOM).build(), file(), null, WU));

		assertEquals("release " + missing + " not found", e.getMessage());
		verify(artifactService, never()).uploadArtifact(any(), any(), any(), any());
	}

	// ---- bind ----

	@Test
	void bindRefusesInventoryMissingAndForeignArtifacts() {
		ArtifactData inventory = artifact(ORG);
		ReleaseData rd = release(List.of(inventory.getUuid()));
		UUID missing = UUID.randomUUID();
		ArtifactData foreign = artifact(UUID.randomUUID());

		RelizaException inv = assertThrows(RelizaException.class, () -> service.bind(rd.getUuid(), inventory.getUuid(), WU));
		assertTrue(inv.getMessage().contains(inventory.getUuid().toString()) && inv.getMessage().contains(rd.getUuid().toString()),
				inv.getMessage());
		RelizaException miss = assertThrows(RelizaException.class, () -> service.bind(rd.getUuid(), missing, WU));
		assertEquals("artifact " + missing + " not found", miss.getMessage());
		IllegalStateException sec = assertThrows(IllegalStateException.class, () -> service.bind(rd.getUuid(), foreign.getUuid(), WU));
		assertTrue(sec.getMessage().startsWith("SECURITY:"), sec.getMessage());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class));
	}

	@Test
	void bindTwiceKeepsOneEntryOneEventOneSave() throws Exception {
		ReleaseData rd = release(List.of());
		ArtifactData s = artifact(ORG);

		assertEquals(s.getUuid(), service.bind(rd.getUuid(), s.getUuid(), WU));
		assertEquals(s.getUuid(), service.bind(rd.getUuid(), s.getUuid(), WU));

		assertEquals(List.of(s.getUuid()), stored(rd.getUuid()).getSyntheticArtifacts());
		assertEquals(1, syntheticEvents(saved(rd.getUuid())).size());
		verify(oss, times(1)).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class));
	}

	// ---- remove ----

	@Test
	void removeWithNoOtherHolderUnbindsRecordsAndArchives() throws Exception {
		ArtifactData s = artifact(ORG);
		ReleaseData rd = release(List.of(), s.getUuid());
		when(shared.gatherReleasesForArtifact(s.getUuid(), ORG)).thenReturn(List.of());

		assertTrue(service.remove(rd.getUuid(), s.getUuid(), WU));

		assertEquals(List.of(), stored(rd.getUuid()).getSyntheticArtifacts());
		List<ReleaseUpdateEvent> events = syntheticEvents(saved(rd.getUuid()));
		assertEquals(1, events.size());
		assertEquals(ReleaseUpdateAction.REMOVED, events.get(0).rua());
		assertEquals(s.getUuid(), events.get(0).objectId());
		verify(oss).saveRelease(any(Release.class), any(ReleaseData.class), eq(WU), eq(false), eq(AcollectionMode.SKIP));
		verify(artifactService).archiveArtifact(s.getUuid(), WU);
	}

	@Test
	void removeKeepsAnArtifactAnotherReleaseHolds() throws Exception {
		ArtifactData s = artifact(ORG);
		ReleaseData rd = release(List.of(), s.getUuid());
		ReleaseData other = release(List.of(), s.getUuid());
		when(shared.gatherReleasesForArtifact(s.getUuid(), ORG)).thenReturn(List.of(other));

		assertTrue(service.remove(rd.getUuid(), s.getUuid(), WU));

		verify(artifactService, never()).archiveArtifact(any(), any());
	}

	@Test
	void removeOfAnUnboundArtifactIsFalseAndSavesNothing() throws Exception {
		ReleaseData rd = release(List.of());

		assertFalse(service.remove(rd.getUuid(), UUID.randomUUID(), WU));

		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class));
		verify(artifactService, never()).archiveArtifact(any(), any());
	}

	// ---- reads ----

	@Test
	void listKeepsListOrderAndSkipsAMissingIdAndFindByTagAnswersTheFirstMatch() {
		ArtifactData second = artifact(ORG, new TagRecord("k", "v", Removable.NO));
		ArtifactData first = artifact(ORG, new TagRecord("k", "v", Removable.NO));
		UUID gone = UUID.randomUUID();
		ReleaseData rd = release(List.of(), first.getUuid(), gone, second.getUuid());

		List<ArtifactData> listed = service.listSyntheticArtifacts(rd);

		assertEquals(List.of(first.getUuid(), second.getUuid()), listed.stream().map(ArtifactData::getUuid).toList());
		assertSame(first, service.findByTag(rd, "k", "v").orElseThrow());
		assertTrue(service.findByTag(rd, "k", "other").isEmpty());
		assertTrue(service.findByTag(rd, "absent", "v").isEmpty());
	}
}
