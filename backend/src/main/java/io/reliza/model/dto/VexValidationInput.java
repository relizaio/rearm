/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;

/**
 * Bundles the user-supplied (or post-merge) fields that VEX-framework validators inspect.
 *
 * <p>Replaces the 7-positional-argument form of {@code validateVexConstraints} /
 * {@code validateCisaConstraints}. On the update path {@code state} carries the post-merge
 * effective state (null means "no state change" — validators short-circuit). Free-text fields
 * are nullable; validators treat null and blank uniformly.
 */
public record VexValidationInput(
		AnalysisState state,
		AnalysisJustification justification,
		String details,
		List<AnalysisResponse> responses,
		String recommendation,
		String workaround) {}
