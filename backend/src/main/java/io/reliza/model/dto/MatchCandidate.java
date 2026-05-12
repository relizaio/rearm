/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.model.AnalysisScope;

/**
 * One scope-target the matcher emits for a parsed VEX statement; the orchestrator turns
 * each candidate into a VexStatementProposal at that (scope, scopeUuid).
 */
public record MatchCandidate(AnalysisScope scope, UUID scopeUuid) {}
