/**
 * Centralised help text for the Create / Update / View vuln-analysis modals.
 *
 * Keep this in sync with the CISA VEX validation in
 * `backend/.../VulnAnalysisService.validateCisaConstraints` and the CycloneDX
 * spec (https://cyclonedx.org/docs/1.6/json/#vulnerabilities_items_analysis).
 */

import { AnalysisState } from '@/constants/vulnAnalysis'

/**
 * Where the residual risk fields leave ReARM (FDAW-162 F-3): one sentence, so the three help texts that end with it
 * cannot drift.
 */
export const FDA_SUMMARY_ONLY = 'Printed in the vulnerability assessment summary of a regulatory package (fda/); not included in the VDR or VEX.'

/** One-line tooltip shown next to each form-field label. */
export const FIELD_HELP: Record<string, string> = {
    state:
        'Your current determination about this finding. Maps to CycloneDX analysis.state.',
    justification:
        'Why this vulnerability is NOT exploitable (e.g., code not reachable). Required by CISA VEX when state is NOT_AFFECTED or FALSE_POSITIVE unless Details are provided. Maps to CycloneDX analysis.justification.',
    responses:
        'Planned or available actions (e.g., update, workaround_available). Required by CISA VEX when state is EXPLOITABLE unless a Recommendation is provided. Maps to CycloneDX analysis.response.',
    recommendation:
        'Free-text remediation advice. Part of the CISA VEX action statement for EXPLOITABLE findings. Maps to CycloneDX vulnerability.recommendation.',
    workaround:
        'Temporary mitigation steps. Maps to CycloneDX vulnerability.workaround.',
    details:
        'Free-text details / impact statement. Required by CISA VEX when state is NOT_AFFECTED or FALSE_POSITIVE unless a Justification is provided. Maps to CycloneDX analysis.detail.',
    severity:
        'Your assessment of severity; may differ from public advisories.',
    findingAliases:
        'Alternative identifiers for the same finding (e.g., GHSA, ALPINE, RHSA).',
    discoveryMethod:
        'How the finding was found (FDA premarket cybersecurity guidance V.A.4(b)). Shown once a state other than In Triage is chosen.',
    riskLevel:
        'Your assessed risk of this finding for the device, with its risk controls in place. Shown for an exploitable or resolved finding.',
    controls:
        'Up to 10 risk controls, each a kind and a one-line description of how the risk is controlled. Shown for an exploitable or resolved finding.',
    residualRisk:
        'Whether the residual risk of this finding is acceptable for the device, with its risk controls in place. Needed with its rationale for an exploitable finding before a regulatory package can be confirmed or finalized. Shown for an exploitable finding. ' + FDA_SUMMARY_ONLY,
    residualRiskRationale:
        'Why the residual risk is or is not acceptable, for example how the controls reduce it and when the fix ships. ' + FDA_SUMMARY_ONLY,
    riskFileReference:
        'Optional: where your security risk management file records this conclusion, for example a document number and section. One line. ' + FDA_SUMMARY_ONLY + ' Do not enter internal links or credentials.'
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
        'CISA VEX impact statement: provide a Justification or a non-empty Details (impact statement).',
    [AnalysisState.RESOLVED]:
        'Remediated. No additional fields required.'
}
