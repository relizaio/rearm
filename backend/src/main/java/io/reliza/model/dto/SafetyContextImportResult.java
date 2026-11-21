/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

/** Outcome of creating staged safety-context proposals from one source document. */
public record SafetyContextImportResult(
    int total,
    int matched,
    int unmatched,
    int ambiguous,
    int deferred,
    int invalid,
    List<UUID> proposalUuids,
    List<String> errors
) {}
