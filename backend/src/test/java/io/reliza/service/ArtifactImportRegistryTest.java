/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ImportSummary;

/**
 * {@link ArtifactImportRegistry}: type lookup, preconditions once per type, and best-effort
 * dispatch (a failing import never fails the upload and stores no summary).
 */
class ArtifactImportRegistryTest {

	private ArtifactImportHandler vexHandler;
	private ArtifactService artifactService;
	private ArtifactImportRegistry registry;
	private final ReleaseData rd = mock(ReleaseData.class);
	private final WhoUpdated wu = mock(WhoUpdated.class);
	private final UUID artId = UUID.randomUUID();
	private final byte[] content = "{}".getBytes();

	@BeforeEach
	void setUp() {
		vexHandler = mock(ArtifactImportHandler.class);
		when(vexHandler.type()).thenReturn(ArtifactType.VEX);
		artifactService = mock(ArtifactService.class);
		registry = new ArtifactImportRegistry();
		registry.handlerBeans = List.of(vexHandler);
		registry.artifactService = artifactService;
		registry.indexHandlers();
	}

	private static ArtifactDto dtoOf(ArtifactType type) {
		return ArtifactDto.builder().type(type).build();
	}

	@Test
	void onlyRegisteredTypesAreImportable() {
		assertTrue(registry.isImportable(ArtifactType.VEX));
		assertFalse(registry.isImportable(ArtifactType.BOM));
		assertFalse(registry.isImportable(null));
	}

	@Test
	void twoHandlersForOneTypeRefuseToStart() {
		ArtifactImportHandler other = mock(ArtifactImportHandler.class);
		when(other.type()).thenReturn(ArtifactType.VEX);
		ArtifactImportRegistry r = new ArtifactImportRegistry();
		r.handlerBeans = List.of(vexHandler, other);
		assertThrows(IllegalStateException.class, r::indexHandlers);
	}

	@Test
	void preconditionsRunOncePerImportableType() throws RelizaException {
		registry.verifyPreconditions(List.of(ArtifactType.VEX, ArtifactType.BOM, ArtifactType.VEX), rd);
		verify(vexHandler, times(1)).verifyPreconditions(rd);
	}

	@Test
	void noImportableTypeRunsNoPrecondition() throws RelizaException {
		registry.verifyPreconditions(List.of(ArtifactType.BOM), rd);
		verify(vexHandler, never()).verifyPreconditions(any());
	}

	@Test
	void dispatchStoresTheSummaryOnTheArtifact() throws RelizaException {
		ArtifactDto dto = dtoOf(ArtifactType.VEX);
		ImportSummary summary = new ImportSummary();
		when(vexHandler.dispatch(artId, dto, rd, ArtifactBelongsTo.RELEASE, content, wu)).thenReturn(summary);
		registry.dispatchAndRecord(artId, dto, rd, ArtifactBelongsTo.RELEASE, content, wu);
		verify(artifactService).saveArtifactImportSummary(artId, summary, wu);
	}

	@Test
	void failingImportDoesNotFailTheUploadAndStoresNothing() throws RelizaException {
		ArtifactDto dto = dtoOf(ArtifactType.VEX);
		when(vexHandler.dispatch(any(), any(), any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("boom"));
		assertDoesNotThrow(() -> registry.dispatchAndRecord(artId, dto, rd, ArtifactBelongsTo.RELEASE, content, wu));
		verify(artifactService, never()).saveArtifactImportSummary(any(), any(), any());
	}

	@Test
	void failingSummarySaveDoesNotFailTheUpload() throws RelizaException {
		ArtifactDto dto = dtoOf(ArtifactType.VEX);
		ImportSummary summary = new ImportSummary();
		when(vexHandler.dispatch(any(), any(), any(), any(), any(), any())).thenReturn(summary);
		doThrow(new RelizaException("gone"))
			.when(artifactService).saveArtifactImportSummary(artId, summary, wu);
		assertDoesNotThrow(() -> registry.dispatchAndRecord(artId, dto, rd, ArtifactBelongsTo.RELEASE, content, wu));
	}

	@Test
	void typeWithoutHandlerIsANoOp() throws RelizaException {
		registry.dispatchAndRecord(artId, dtoOf(ArtifactType.BOM), rd, ArtifactBelongsTo.RELEASE, content, wu);
		verify(vexHandler, never()).dispatch(any(), any(), any(), any(), any(), any());
		verify(artifactService, never()).saveArtifactImportSummary(any(), any(), any());
	}
}
