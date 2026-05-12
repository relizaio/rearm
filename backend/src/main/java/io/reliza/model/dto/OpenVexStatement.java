/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

/** In-memory mirror of one OpenVEX statement. Field names match the OpenVEX 0.2.0 spec. */
public record OpenVexStatement(
    String vulnerability,
    List<String> products,
    String status,
    String justification,
    String impactStatement,
    String actionStatement
) {}
