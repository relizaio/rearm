// Release narrative form state -> updateRelease variables, honouring the PATCH contract.

/**
 * Build the narrow partial for a per-release narrative write.
 *
 * Same narrowness rule as the device window: { uuid, org } plus the one field, and nothing
 * else. updateRelease treats a null list as "no change", so a payload that grew an
 * artifacts or commits key could DETACH a release's contents -- and widening it also arms
 * the DRAFT_ONLY gate, which only stays dormant because isAssemblyRequested trips on fields
 * this payload omits.
 *
 * PATCH semantics, matching the org-level prose form:
 *   unchanged         -> omitted, so an unrelated release save cannot disturb the narrative
 *   emptied from text -> sent as '', which the server reads as a deliberate CLEAR
 *   changed           -> sent trimmed
 *
 * Trimmed on both sides before comparison, so whitespace-only input equals empty in both
 * directions: typing spaces into an empty field is not a change, and replacing text with
 * spaces IS a clear. The server rejects surrounding whitespace anyway.
 */
export function releaseNarrativeVariables (
    uuid: string,
    org: string,
    current: string | null | undefined,
    baseline: string | null | undefined
): Record<string, unknown> | null {
    const now = (current || '').trim()
    const was = (baseline || '').trim()
    // NULL, not an empty object: "nothing to send" is a different answer from "send a patch
    // with no fields", and the caller must not fire a mutation for an unchanged form.
    if (now === was) return null
    return { uuid, org, fdaAssessmentNarrative: now }
}

/**
 * True when the form differs from what the server last confirmed.
 *
 * Named "differs" rather than "dirty" because ReleaseView owns a computed called
 * releaseNarrativeDirty; importing this under an alias would have given one function two
 * names, tested under one and read under the other.
 */
export function releaseNarrativeDiffers (
    current: string | null | undefined,
    baseline: string | null | undefined
): boolean {
    return (current || '').trim() !== (baseline || '').trim()
}
