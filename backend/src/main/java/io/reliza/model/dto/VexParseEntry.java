/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

/**
 * One canonical VEX statement plus per-statement provenance notes (e.g. OpenVEX → CDX
 * vocabulary expansion). Notes flow into VexStatementProposalData.translationNotes.
 */
public record VexParseEntry(CdxVexStatement statement, List<String> translationNotes) {}
