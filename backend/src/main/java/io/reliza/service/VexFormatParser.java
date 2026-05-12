/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import io.reliza.model.SourceFormat;
import io.reliza.model.dto.VexParseResult;

/**
 * Parses a VEX document of a specific source format into the canonical CDX-VEX shape.
 * The orchestrator selects an implementation by {@link #format()} and consumes only the
 * unified {@link VexParseResult} downstream.
 *
 * v1.x has two implementations (OpenVEX, CDX-VEX); CSAF-VEX is a v2 add and slots in here.
 */
public interface VexFormatParser {
    SourceFormat format();
    VexParseResult parse(String json);
}
