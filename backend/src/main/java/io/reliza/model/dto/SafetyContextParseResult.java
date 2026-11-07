/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

/**
 * Parse result for the CycloneDX 2.0 development schema. Raw document JSON is
 * preserved verbatim for downstream persistence and future schema evolution.
 * Warnings describe unresolved links; they do not invalidate otherwise parsed entries.
 */
public record SafetyContextParseResult(
    String specVersion,
    String rawDocumentJson,
    List<SafetyContextEntry> entries,
    List<String> errors,
    List<String> warnings
) {
    public boolean valid() {
        return errors == null || errors.isEmpty();
    }
}
