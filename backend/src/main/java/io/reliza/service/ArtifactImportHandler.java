/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ImportSummary;

/**
 * Inbound import for one artifact type: an uploaded artifact of {@link #type()} with a file is
 * parsed into proposals once it is stored. {@link ArtifactImportRegistry} selects the handler by
 * type for both upload paths (addArtifactManual and addArtifactProgrammatic).
 * {@link VexImportService} is the first implementation.
 */
public interface ArtifactImportHandler {

	ArtifactType type();

	/**
	 * Refuses an upload the import could never run on, for example a release with no SBOM
	 * inventory to match against. Called before the artifact is stored, so a refusal leaves no
	 * orphan file.
	 */
	void verifyPreconditions(ReleaseData rd) throws RelizaException;

	/**
	 * Runs the import for a stored artifact and returns its outcome; the registry saves it on
	 * the artifact. Implementations run in their own transaction (REQUIRES_NEW) so a failed
	 * import cannot roll back the upload that called it. {@code artDto} carries the resolved
	 * generic import controls ({@code importScope}, {@code importMode},
	 * {@code userIssuerClassOverride}).
	 */
	ImportSummary dispatch(UUID artifactUuid, ArtifactDto artDto, ReleaseData rd,
			ArtifactBelongsTo belongsTo, byte[] content, WhoUpdated wu) throws RelizaException;
}
