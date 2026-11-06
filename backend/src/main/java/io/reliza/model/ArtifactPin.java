/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.UUID;

/**
 * One package version pinning one artifact (task FDAW-5, B17): the artifact, the version's head
 * ({@code submission}), its kind and number, and its release with the release label in the form
 * {@code SubmissionComposer.labelOf} writes ({@code <component name> <release version> (<release uuid>)}).
 */
public record ArtifactPin(UUID artifact, UUID submission, PackageKind kind, int version, UUID release,
		String releaseLabel) {}
