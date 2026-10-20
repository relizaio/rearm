/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.springframework.core.io.Resource;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.dto.ArtifactUploadResponseDto;
import io.reliza.model.dto.OASResponseDto;

/**
 * Stubs and checks on the package-private registry push and row write of a spied
 * {@link ArtifactService}, for tests outside this package (SCORE-47 T-17, T-18).
 */
public final class ArtifactServiceSpies {

	private ArtifactServiceSpies() {}

	/** The registry accepts every push. */
	public static void registryAccepts(ArtifactService spy) {
		ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
		oci.setDigest("sha256:abcd1234");
		OASResponseDto push = new OASResponseDto();
		push.setOciResponse(oci);
		push.setOciRepositoryName("downloadable-artifacts-2026-10");
		doReturn(push).when(spy).uploadFileToConfiguredOci(any(Resource.class), anyString(), anyString());
	}

	/** Nothing reached the registry and no artifact row was written. */
	public static void nothingRetainedOrWritten(ArtifactService spy) throws RelizaException {
		verify(spy, never()).uploadFileToConfiguredOci(any(Resource.class), any(), any());
		verify(spy, never()).persistArtifact(any(), any());
	}

	/** No artifact row was written (the registry may have been reached). */
	public static void nothingWritten(ArtifactService spy) throws RelizaException {
		verify(spy, never()).persistArtifact(any(), any());
	}
}
