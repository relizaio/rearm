// What an import's summary says beyond its counts: why statements were staged instead of applied,
// and whether the upload's issuer override was applied. Shared by the VEX and SAFETY_CONTEXT upload
// outcome dialogs (CreateArtifact.vue). The text is static: the client does not know the uploader's
// grants, so it reports what the server did, not what it would do.

export type ImportDemotionReason = 'NO_FINDING_ANALYSIS_WRITE' | 'SAME_SCOPE_CONFLICT' | 'BROADER_SCOPE_CONFLICT'
    | 'SEVERITY_MISSING' | 'SEVERITY_CHANGE'

export const DEMOTION_TEXT: Record<ImportDemotionReason, string> = {
    NO_FINDING_ANALYSIS_WRITE: 'staged: you need Finding Analysis write at this scope',
    SAME_SCOPE_CONFLICT: 'staged: an existing analysis at this scope says otherwise',
    BROADER_SCOPE_CONFLICT: 'staged: an analysis at a broader scope already exists with a different suppression class',
    SEVERITY_MISSING: 'staged: the statement has no severity and none could be derived',
    SEVERITY_CHANGE: 'staged: the statement changes the severity'
}

export const OVERRIDE_IGNORED_TEXT = 'Issuer override not applied'

export const IMPORT_PERMISSION_NOTE = 'Without Finding Analysis write at the chosen scope, every statement is staged for review.'

export const ISSUER_OVERRIDE_HINT = 'Choosing a less trusted class than the upload\'s own always applies. Choosing a more '
    + 'trusted class needs Finding Analysis write at the chosen scope; without it the upload\'s own class is used.'

interface DemotionCount { reason: string, count: number }
interface SummaryExtras { demotions?: DemotionCount[] | null, overrideOutcome?: string | null }

/**
 * One line per demotion reason ("2 staged: ..."), in the order the server sent them, then
 * "Issuer override not applied" when the override was ignored. A reason this build does not know
 * is shown by its name rather than dropped.
 */
export function importOutcomeLines (summary: SummaryExtras | null | undefined): string[] {
    if (!summary) return []
    const lines = (summary.demotions ?? [])
        .filter((d) => d && d.count > 0)
        .map((d) => `${d.count} ${DEMOTION_TEXT[d.reason as ImportDemotionReason] ?? `staged: ${d.reason}`}`)
    if (summary.overrideOutcome === 'IGNORED') lines.push(OVERRIDE_IGNORED_TEXT)
    return lines
}
