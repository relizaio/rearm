/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Analysis justification enum based on CycloneDX 1.7 specification
 * https://cyclonedx.org/docs/1.7/json/#vulnerabilities_items_analysis_justification
 */
public enum AnalysisJustification {
	CODE_NOT_PRESENT,
	CODE_NOT_REACHABLE,
	REQUIRES_CONFIGURATION,
	REQUIRES_DEPENDENCY,
	REQUIRES_ENVIRONMENT,
	PROTECTED_BY_COMPILER,
	PROTECTED_AT_RUNTIME,
	PROTECTED_AT_PERIMETER,
	PROTECTED_BY_MITIGATING_CONTROL
}
