/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import io.reliza.model.ExportMetadataChoice;

/**
 * What one export caller asked for about the two classes of metadata ReARM adds to a served BOM.
 *
 * <p>ONE object rather than two parameters threaded through five signatures: the pair is always
 * read together, a third class of metadata is plausible (the VDR's tool attribution already
 * wants one), and a positional {@code (Boolean, Boolean)} pair is the kind of argument list that
 * gets transposed at one call site and stays wrong until an operator notices a document is
 * missing the wrong half.
 *
 * <p>{@link #callerSilent()} is the value every pre-existing caller supplies, and it must remain
 * byte-identical to what they got before these arguments existed. That is the whole contract:
 * nulls change nothing.
 *
 * @param supportMetadata the per-component support attestations ({@code reliza:support:*},
 *                        {@code reliza:device:*} and the {@code declarations} block)
 * @param internalMetadata ReARM's own markers -- the remaining {@code reliza:*} properties and
 *                        the {@code io.reliza} ReARM entry under {@code metadata.tools}
 */
public record ExportMetadataOptions(
		ExportMetadataChoice supportMetadata,
		ExportMetadataChoice internalMetadata) {

	private static final ExportMetadataOptions SILENT =
			new ExportMetadataOptions(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.DEFAULT);

	public ExportMetadataOptions {
		if (null == supportMetadata) supportMetadata = ExportMetadataChoice.DEFAULT;
		if (null == internalMetadata) internalMetadata = ExportMetadataChoice.DEFAULT;
	}

	/**
	 * What a caller who said nothing gets: today's behaviour on both axes.
	 *
	 * <p>The value the pre-existing signatures supply on behalf of every egress that has no
	 * caller input to forward -- {@code SharedArtifactService.downloadArtifact(ad, device)},
	 * which the signature verifier and the TEA paths reach, and
	 * {@code ReleaseService.exportReleaseSbom}'s nine-argument form. Spelling it once means
	 * "unchanged" is one named value rather than a pair of bare nulls repeated at each site.
	 */
	public static ExportMetadataOptions callerSilent() {
		return SILENT;
	}

	/** The boundary constructor: two wire Booleans become one options object. */
	public static ExportMetadataOptions fromCallerInput(Boolean includeSupportMetadata,
			Boolean includeInternalMetadata) {
		return new ExportMetadataOptions(
				ExportMetadataChoice.fromCallerInput(includeSupportMetadata),
				ExportMetadataChoice.fromCallerInput(includeInternalMetadata));
	}

	/** The caller explicitly asked FOR the support disclosure -- the only case that can be refused. */
	public boolean supportMetadataRequested() {
		return ExportMetadataChoice.INCLUDE == supportMetadata;
	}

	/** The caller explicitly asked for the support disclosure to be left out. */
	public boolean supportMetadataDeclined() {
		return ExportMetadataChoice.EXCLUDE == supportMetadata;
	}

	/** The caller explicitly asked for ReARM's own markers to be left out. */
	public boolean internalMetadataDeclined() {
		return ExportMetadataChoice.EXCLUDE == internalMetadata;
	}
}
