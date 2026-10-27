/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * One part of a submission version (task TEA-35), in manifest order: a PRODUCED part names the
 * uploaded artifact, a REFERENCED one an inventory artifact (one record per artifact), a SKIPPED
 * one its skip reason and detail.
 */
public record SubmissionPartRecord(PackagePart part, PackagePartRequirement requirement,
		SubmissionPartOutcome outcome, UUID artifact, String artifactVersion, String fileName, String mediaType,
		Long size, String sha256, PartSkipReason skipReason, String detail) implements Serializable {}
