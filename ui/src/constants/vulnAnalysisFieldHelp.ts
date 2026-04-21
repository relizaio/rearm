/**
 * Centralised help text for the Create / Update / View vuln-analysis modals.
 *
 * Keep this in sync with the CISA VEX validation in
 * `backend/.../VulnAnalysisService.validateCisaConstraints` and the CycloneDX
 * spec (https://cyclonedx.org/docs/1.6/json/#vulnerabilities_items_analysis).
 */

import { AnalysisState } from '@/constants/vulnAnalysis'

/** One-line tooltip shown next to each form-field label. */
export const FIELD_HELP: Record<string, string> = {
    state:
        'Your current determination about this finding. Maps to CycloneDX analysis.state.',
    justification:
        'Why this vulnerability is NOT exploitable (e.g., code not reachable). Required by CISA VEX when state is NOT_AFFECTED unless Details are provided. Maps to CycloneDX analysis.justification.',
    responses:
        'Planned or available actions (e.g., update, workaround_available). Required by CISA VEX when state is EXPLOITABLE unless a Recommendation is provided. Maps to CycloneDX analysis.response.',
    recommendation:
        'Free-text remediation advice. Part of the CISA VEX action statement for EXPLOITABLE findings. Maps to CycloneDX vulnerability.recommendation.',
    workaround:
        'Temporary mitigation steps. Maps to CycloneDX vulnerability.workaround.',
    details:
        'Free-text details / impact statement. Required by CISA VEX when state is NOT_AFFECTED unless a Justification is provided. Maps to CycloneDX analysis.detail.',
    severity:
        'Your assessment of severity; may differ from public advisories.',
    findingAliases:
        'Alternative identifiers for the same finding (e.g., GHSA, ALPINE, RHSA).'
}

/** Plain-language summary of which fields each state requires (CISA VEX). */
export const STATE_GUIDANCE: Record<string, string> = {
    [AnalysisState.IN_TRIAGE]:
        'Under investigation. No additional fields required.',
    [AnalysisState.EXPLOITABLE]:
        'CISA VEX action statement: provide at least one Response or a non-empty Recommendation.',
    [AnalysisState.NOT_AFFECTED]:
        'CISA VEX impact statement: provide a Justification or a non-empty Details (impact statement).',
    [AnalysisState.FALSE_POSITIVE]:
        'Not actually affected. No additional fields required.',
    [AnalysisState.RESOLVED]:
        'Remediated. No additional fields required.'
}
