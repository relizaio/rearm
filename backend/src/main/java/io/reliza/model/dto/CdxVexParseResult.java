/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;

public record CdxVexParseResult(List<CdxVexStatement> statements, String docError) {}
