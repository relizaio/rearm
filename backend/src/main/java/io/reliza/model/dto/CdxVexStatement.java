/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/** Normalized CDX-VEX statement after bom-ref resolution and CDX → ReARM enum mapping. */
public record CdxVexStatement(
    String vulnerabilityId,
    List<String> aliasIds,
    List<String> productPurls,
    AnalysisState state,
    AnalysisJustification justification,
    String details,
    String recommendation,
    String workaround,
    List<AnalysisResponse> responses,
    VulnerabilitySeverity severity,
    String rawJson
) {}
