export enum AnalysisState {
    EXPLOITABLE = 'EXPLOITABLE',
    IN_TRIAGE = 'IN_TRIAGE',
    FALSE_POSITIVE = 'FALSE_POSITIVE',
    NOT_AFFECTED = 'NOT_AFFECTED',
    FIXED = 'FIXED'
}

export enum AnalysisResponse {
    CAN_NOT_FIX = 'CAN_NOT_FIX',
    WILL_NOT_FIX = 'WILL_NOT_FIX',
    UPDATE = 'UPDATE',
    ROLLBACK = 'ROLLBACK',
    WORKAROUND_AVAILABLE = 'WORKAROUND_AVAILABLE'
}

/**
 * States considered "non-affecting" / suppressed. Used for UI filters,
 * metrics counts, VDR filtering (client side previews), and BOV export.
 */
export const SUPPRESSED_ANALYSIS_STATES: ReadonlySet<string> = new Set([
    AnalysisState.FALSE_POSITIVE,
    AnalysisState.NOT_AFFECTED,
    AnalysisState.FIXED
])

export const isSuppressedAnalysisState = (state: string | null | undefined): boolean =>
    !!state && SUPPRESSED_ANALYSIS_STATES.has(state)

export const ANALYSIS_STATE_OPTIONS = [
    { label: 'Exploitable', value: AnalysisState.EXPLOITABLE },
    { label: 'In Triage', value: AnalysisState.IN_TRIAGE },
    { label: 'False Positive', value: AnalysisState.FALSE_POSITIVE },
    { label: 'Not Affected', value: AnalysisState.NOT_AFFECTED },
    { label: 'Fixed', value: AnalysisState.FIXED }
]

export const ANALYSIS_RESPONSE_OPTIONS = [
    { label: 'Cannot Fix', value: AnalysisResponse.CAN_NOT_FIX },
    { label: 'Will Not Fix', value: AnalysisResponse.WILL_NOT_FIX },
    { label: 'Update Available', value: AnalysisResponse.UPDATE },
    { label: 'Rollback', value: AnalysisResponse.ROLLBACK },
    { label: 'Workaround Available', value: AnalysisResponse.WORKAROUND_AVAILABLE }
]

export const ANALYSIS_JUSTIFICATION_OPTIONS = [
    { label: 'Code Not Present', value: 'CODE_NOT_PRESENT' },
    { label: 'Code Not Reachable', value: 'CODE_NOT_REACHABLE' },
    { label: 'Requires Configuration', value: 'REQUIRES_CONFIGURATION' },
    { label: 'Requires Dependency', value: 'REQUIRES_DEPENDENCY' },
    { label: 'Requires Environment', value: 'REQUIRES_ENVIRONMENT' },
    { label: 'Protected by Compiler', value: 'PROTECTED_BY_COMPILER' },
    { label: 'Protected at Runtime', value: 'PROTECTED_AT_RUNTIME' },
    { label: 'Protected at Perimeter', value: 'PROTECTED_AT_PERIMETER' },
    { label: 'Protected by Mitigating Control', value: 'PROTECTED_BY_MITIGATING_CONTROL' }
]
