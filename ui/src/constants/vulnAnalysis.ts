export const ANALYSIS_STATE_OPTIONS = [
    { label: 'Exploitable', value: 'EXPLOITABLE' },
    { label: 'In Triage', value: 'IN_TRIAGE' },
    { label: 'False Positive', value: 'FALSE_POSITIVE' },
    { label: 'Not Affected', value: 'NOT_AFFECTED' }
]

export const ANALYSIS_JUSTIFICATION_OPTIONS = [
    { label: 'N/A', value: '' },
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
