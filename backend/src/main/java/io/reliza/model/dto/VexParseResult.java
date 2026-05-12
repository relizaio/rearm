/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

/**
 * Format-agnostic VEX parse output. The pipeline operates on this canonical shape regardless
 * of source format (OpenVEX, CDX-VEX); per-format parsers are the only place that knows the
 * source vocabulary.
 *
 * Fields:
 *   entries — successfully parsed + validated statements, normalized to CDX-VEX vocabulary.
 *   docError — null if the document parsed; non-null if the document was malformed.
 *   invalidStatements — count of per-statement validation failures (currently OpenVEX-only).
 *   errorMessages — aggregated human-readable errors (doc-level + per-statement).
 */
public record VexParseResult(
    List<VexParseEntry> entries,
    String docError,
    int invalidStatements,
    List<String> errorMessages
) {}
