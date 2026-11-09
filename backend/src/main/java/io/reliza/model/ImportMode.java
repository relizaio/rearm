/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * The uploader's per-upload import mode for an inbound artifact import. Combined with the
 * import type's trust gate by {@code ImportVerdictResolver}, least permissive
 * wins. Same values as the deprecated {@link VexImportMode}, so stored values read either way.
 */
public enum ImportMode { AUTO_ACCEPT, STAGE, REJECT }
