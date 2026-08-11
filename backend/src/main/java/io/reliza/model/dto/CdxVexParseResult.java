/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;

/**
 * Raw CDX-VEX parse output. {@code skippedNoAnalysis} counts vulnerabilities[]
 * entries dropped because they carry no analysis block (a CycloneDX
 * vulnerability without analysis is a plain vulnerability report, not a VEX
 * statement) -- surfaced so an upload that yields zero statements can tell the
 * user why instead of reporting a bare "0 statements".
 */
public record CdxVexParseResult(List<CdxVexStatement> statements, String docError, int skippedNoAnalysis) {}
