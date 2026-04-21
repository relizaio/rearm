/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * VEX compliance framework that layers additional validation rules on top of the
 * CycloneDX baseline data model.
 *
 * CycloneDX itself mandates structure but no field-requirement policy. Enforcement
 * is opt-in per organization via {@code OrganizationData.Settings.vexComplianceFramework}.
 *
 * <ul>
 *   <li>{@link #NONE} — no additional validation; pure CycloneDX baseline.</li>
 *   <li>{@link #CISA} — CISA VEX minimum requirements.
 *       NOT_AFFECTED requires a machine-readable justification or an impact statement (details);
 *       EXPLOITABLE requires an action statement (a response or a recommendation).
 *       Spec: https://www.cisa.gov/sites/default/files/2023-04/minimum-requirements-for-vex-508c.pdf</li>
 * </ul>
 *
 * Future frameworks (e.g. sector-specific or vendor-specific) are added by extending this enum
 * and branching in {@code VulnAnalysisService.validateVexConstraints}.
 */
public enum VexComplianceFramework {
	NONE,
	CISA
}
