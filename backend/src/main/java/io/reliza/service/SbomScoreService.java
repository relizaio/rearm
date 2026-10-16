/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RebomResponseTooLargeException;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomScoreException.SbomScoreErrorReason;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;

/**
 * Readiness scores of the documents a user can already obtain: the merged release BOM the
 * "Export Release BOM" action produces and a single SBOM artifact's download. Each document is
 * fetched through the existing export or download path, unchanged, and its text is scored by
 * rebom. The report is returned, never stored.
 *
 * <p>Callers authorize first (the export's and the download's own checks): nothing here runs for
 * a caller who may not obtain the document.
 */
@Service
public class SbomScoreService {

	@Autowired
	private ReleaseService releaseService;

	@Autowired
	private SharedArtifactService sharedArtifactService;

	@Autowired
	private RebomService rebomService;

	@Autowired
	private ArtifactDownloadAccessService artifactDownloadAccessService;

	/**
	 * Scores the release's merged BOM exactly as the JSON export returns it, through the same
	 * public method, so the scored document is the exported file by construction.
	 *
	 * <p>With {@code excludeFileComponents} the scored document is the filtered export, file
	 * components already gone; the engine is not asked to skip files itself, so its report says
	 * {@code skipFiles: false} and the number left out is on the document (SCORE-11 ADR-6).
	 *
	 * <p>Writes what the export writes on a merged-BOM cache miss (a stored merged BOM and the
	 * release's rebom list) and nothing of its own: no download-log row, since the document never
	 * leaves the server, and no stored report.
	 */
	public String scoreRelease(ReleaseData rd, Boolean tldOnly, Boolean ignoreDev, boolean excludeFileComponents,
			ArtifactBelongsTo belongsTo, BomStructureType structure, List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes,
			ExportMetadataOptions exportMetadata, WhoUpdated wu, List<String> profiles) throws Exception {
		String bom;
		try {
			bom = releaseService.exportReleaseSbom(rd.getUuid(), tldOnly, ignoreDev, excludeFileComponents, belongsTo, structure,
					BomMediaType.JSON, rd.getOrg(), wu, excludeCoverageTypes, exportMetadata);
		} catch (RebomResponseTooLargeException e) {
			throw tooLargeToRead();
		}
		return rebomService.scoreBom(bom, profiles);
	}

	/**
	 * Scores one artifact's document as the manual download endpoint serves it with no optional
	 * parameter: the augmented download, or the uploaded bytes when {@code raw}.
	 *
	 * @param ad the artifact snapshot to score (a requested version already selected)
	 * @param releases the releases the artifact is on, for the device window of the augmented download
	 */
	public String scoreArtifact(ArtifactData ad, List<ReleaseData> releases, boolean raw, List<String> profiles)
			throws Exception {
		if (ArtifactType.BOM != ad.getType()) {
			throw new SbomScoreException(SbomScoreErrorReason.NOT_SCORABLE,
					"Artifact " + ad.getUuid() + " is not an SBOM");
		}
		if (null == ad.getInternalBom()) {
			throw noStoredSbom(ad);
		}
		ResponseEntity<byte[]> download;
		try {
			download = raw
					? sharedArtifactService.downloadRawArtifact(ad).block()
					: sharedArtifactService.downloadArtifact(ad,
							artifactDownloadAccessService.resolveDeviceLifecycle(releases, null),
							ExportMetadataOptions.fromCallerInput(null, null)).block();
		} catch (RebomResponseTooLargeException e) {
			throw tooLargeToRead();
		}
		if (null == download || !download.getStatusCode().is2xxSuccessful()
				|| null == download.getBody() || download.getBody().length == 0) {
			throw noStoredSbom(ad);
		}
		return rebomService.scoreBom(new String(download.getBody(), StandardCharsets.UTF_8), profiles);
	}

	/**
	 * The document was too large to read back from rebom: larger still than what the scoring
	 * request may carry, so it is refused as the over-size document it is, with the same message as
	 * every other over-size refusal.
	 */
	private static SbomScoreException tooLargeToRead() {
		return new SbomScoreException(SbomScoreErrorReason.INPUT_TOO_LARGE, RebomService.SCORE_TOO_LARGE_MESSAGE);
	}

	private static SbomScoreException noStoredSbom(ArtifactData ad) {
		return new SbomScoreException(SbomScoreErrorReason.NOT_SCORABLE,
				"Artifact " + ad.getUuid() + " has no stored SBOM to score");
	}
}
