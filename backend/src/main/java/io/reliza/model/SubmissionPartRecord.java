/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * One part of a submission version (task TEA-35), in manifest order: a PRODUCED part names the
 * uploaded artifact, a REFERENCED one an inventory artifact or the pinned release SBOM (one record per
 * artifact), a SKIPPED one its skip reason and detail. A VDR, a VEX and the pinned release SBOM carry
 * their document identity, {@code documentSerial} ({@code urn:uuid:<serial>}) and {@code documentVersion}
 * (FDAW-7 3.7); a record stored before reads them as null.
 */
public record SubmissionPartRecord(PackagePart part, PackagePartRequirement requirement,
		SubmissionPartOutcome outcome, UUID artifact, String artifactVersion, String fileName, String mediaType,
		Long size, String sha256, PartSkipReason skipReason, String detail, String documentSerial,
		Integer documentVersion) implements Serializable {

	/** A record without a document identity. */
	public SubmissionPartRecord(PackagePart part, PackagePartRequirement requirement, SubmissionPartOutcome outcome,
			UUID artifact, String artifactVersion, String fileName, String mediaType, Long size, String sha256,
			PartSkipReason skipReason, String detail) {
		this(part, requirement, outcome, artifact, artifactVersion, fileName, mediaType, size, sha256, skipReason,
				detail, null, null);
	}
}
