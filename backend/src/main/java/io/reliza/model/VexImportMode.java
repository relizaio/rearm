/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Deprecated alias of {@link ImportMode}, kept for the {@code vexImportMode} upload field.
 * Uploads resolve it into {@code importMode} (see {@link io.reliza.model.dto.ArtifactDto#resolveImportAliases}).
 */
@Deprecated
public enum VexImportMode { AUTO_ACCEPT, STAGE, REJECT }
