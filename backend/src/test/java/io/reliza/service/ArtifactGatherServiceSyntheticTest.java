/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;

/**
 * TEA-4: the release artifact gather (every release save, the release metrics, the acollection
 * snapshot) never reads ReleaseData.syntheticArtifacts, so a generated document never enters the
 * release collection.
 */
class ArtifactGatherServiceSyntheticTest {

	private GetSourceCodeEntryService sceService;
	private ArtifactGatherService service;
	private final UUID component = UUID.randomUUID();

	@BeforeEach
	void wire() {
		sceService = mock(GetSourceCodeEntryService.class);
		VariantService variantService = mock(VariantService.class);
		when(variantService.getVariantsOfRelease(any())).thenReturn(List.of());
		service = new ArtifactGatherService();
		ReflectionTestUtils.setField(service, "getSourceCodeEntryService", sceService);
		ReflectionTestUtils.setField(service, "variantService", variantService);
		ReflectionTestUtils.setField(service, "getDeliverableService", mock(GetDeliverableService.class));
	}

	private ReleaseData release(List<UUID> inventory, UUID synthetic, UUID sce) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "component", component);
		ReflectionTestUtils.setField(rd, "sourceCodeEntry", sce);
		ReflectionTestUtils.setField(rd, "artifacts", new LinkedList<>(inventory));
		rd.addSyntheticArtifact(synthetic);
		return rd;
	}

	@Test
	void theGatherTakesInventoryAndSourceCodeEntryArtifactsButNeverTheSyntheticList() {
		UUID a = UUID.randomUUID();
		UUID s = UUID.randomUUID();
		UUID sceArtifact = UUID.randomUUID();
		UUID sce = UUID.randomUUID();
		SourceCodeEntryData sced = mock(SourceCodeEntryData.class);
		when(sced.getArtifacts()).thenReturn(List.of(new SCEArtifact(sceArtifact, component)));
		when(sceService.getSourceCodeEntryData(sce)).thenReturn(Optional.of(sced));

		assertEquals(Set.of(a, sceArtifact), service.gatherReleaseArtifacts(release(List.of(a), s, sce)));
	}

	@Test
	void aReleaseWhoseOnlyArtifactIsGeneratedGathersNothingOfIt() {
		UUID s = UUID.randomUUID();

		Set<UUID> gathered = service.gatherReleaseArtifacts(release(List.of(), s, null));

		assertFalse(gathered.contains(s), gathered.toString());
		assertEquals(Set.of(), gathered);
	}
}
