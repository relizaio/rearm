/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

/**
 * Outcome of parsing one OpenVEX document. Statements that pass validation are in `valid`;
 * those that don't are in `invalid` paired with their error messages, so the importer can
 * record per-statement ERRORED proposals rather than failing the whole document.
 */
public record OpenVexParseResult(
    List<OpenVexStatement> valid,
    List<InvalidStatement> invalid,
    String docError  // null on success; non-null when the document itself is unparseable
) {
    public record InvalidStatement(OpenVexStatement statement, List<String> errors) {}
}
