// Org settings form state -> updateOrganizationSettings variables, honouring the
// mutation's PATCH contract for the four FDA prose fields.

/**
 * The four org-level prose slots, in the order they appear on the settings form.
 *
 * Exported as a const tuple so a typo in a field name is a COMPILE error rather than a
 * silently-omitted field: the server ignores unknown keys, so a misspelling here would
 * present as "the save worked but nothing changed", with nothing pointing at the cause.
 */
export const FDA_PROSE_FIELDS = ['fdaAssessmentNarrative', 'fdaPatchesMayCeaseStatement',
    'fdaRiskTransferProcessRef', 'fdaRiskIncreasesNotice'] as const

/**
 * Mirrors OrganizationData.FDA_PROSE_MAX_LENGTH on the backend.
 *
 * The server checks the RAW length before trimming and rejects the whole settings mutation
 * on overflow, which would take the operator's unrelated toggle and sid-PURL edits with it
 * and name the wire field rather than the label they see. Capping the input is the only way
 * to make that unreachable; the server check stays authoritative for every other writer.
 */
export const FDA_PROSE_MAX_LENGTH = 8000

export type FdaProseField = typeof FDA_PROSE_FIELDS[number]
export type FdaProseState = Partial<Record<FdaProseField, string | null | undefined>>

/**
 * Diff prose against the baseline captured at load.
 *
 *   unchanged           -> omitted, so saving an unrelated toggle cannot touch prose
 *   emptied from text   -> sent as '', which the server reads as a deliberate CLEAR
 *   changed             -> sent trimmed
 *
 * Sending '' for a field that was ALREADY empty is pointless traffic, but worse than that
 * it would be a clear the user did not ask for on a field somebody else may have filled in
 * since this form loaded. So only a genuine emptying is sent.
 *
 * Both sides are trimmed before comparison, which makes whitespace-only input equivalent
 * to empty in BOTH directions: typing spaces into an empty field is not a change, and
 * replacing text with spaces IS a clear. The server rejects leading/trailing whitespace
 * anyway, so anything else would send a payload it would refuse.
 */
export function proseDiff (current: FdaProseState, baseline: FdaProseState): Record<string, string> {
    const out: Record<string, string> = {}
    for (const f of FDA_PROSE_FIELDS) {
        const now = (current[f] || '').trim()
        const was = (baseline[f] || '').trim()
        if (now === was) continue
        out[f] = now
    }
    return out
}

/**
 * The baseline to hold AFTER a save, taken from the mutation's OWN response rather than a
 * follow-up read.
 *
 * The distinction is load-bearing. An earlier revision refreshed the baseline only via a
 * re-read nested inside the save's try block, so a failure of that read reported a
 * committed save as "Save Failed" AND left the baseline stale. The next clear then compared
 * '' against a stale '', omitted the field, and told the operator it had saved -- while the
 * server still held the text, which is exactly the fabricated-prose failure this feature
 * exists to prevent. The mutation already selects these fields; use them.
 */
export function proseBaselineFrom (settings: FdaProseState | null | undefined): Record<FdaProseField, string> {
    const out = {} as Record<FdaProseField, string>
    for (const f of FDA_PROSE_FIELDS) out[f] = (settings?.[f] || '')
    return out
}
