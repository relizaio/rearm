// The assessment-narrative prompt before a regulatory submission's Create or Resubmit (any release,
// component or product): the cover summary written for this release, which the package carries, and
// the release write it needs first. The organization default is shown as a starting point only: a
// package is not finalized on it unchanged (FDAW-16 3.8, F-2). The write is the per-release narrative
// editor's: the same narrow updateRelease partial and its PATCH contract ('' clears back to the
// organization default, which the release page's editor still does; an omitted field stays as it is).
import gql from 'graphql-tag'
import { FDA_PROSE_MAX_LENGTH } from './fdaProseInput'
import { releaseNarrativeVariables } from './releaseNarrativeInput'

export const NO_ORG_NARRATIVE = 'No organization default is set'

/** Why the prompt cannot continue on the organization default unchanged (FDAW-16 3.9 text 14). */
export const NARRATIVE_IS_ORG_DEFAULT = "Write this release's own cover summary; the organization default is only a starting point"

/** The per-release narrative write, shared by the release page's Support tab and the submission prompt. */
export const UPDATE_RELEASE_NARRATIVE_GQL = gql`
    mutation updateReleaseNarrative($release: ReleaseInput!) {
        updateRelease(release: $release) { uuid fdaAssessmentNarrative }
    }`

const blank = (s: string | null | undefined) => !(s || '').trim()

/**
 * Whether a kind's submission carries the assessment narrative: its catalog entry (packageKinds)
 * has a REQUIRED NARRATIVE part. A kind the catalog does not answer gets no prompt; the server's
 * preview still refuses a NARRATIVE that resolves to nothing.
 */
export function kindNeedsNarrative (entry: { parts?: { part?: string | null, requirement?: string | null }[] | null } | null | undefined): boolean {
    return (entry?.parts ?? []).some(p => p?.part === 'NARRATIVE' && p?.requirement === 'REQUIRED')
}

/** What the cover summary starts from: the override, else the organization default as a starting point. */
export function narrativeInitialText (override: string | null | undefined, orgDefault: string | null | undefined): string {
    return !blank(override) ? (override || '') : (orgDefault || '')
}

/**
 * Why the prompt cannot confirm yet, or null. The input caps the length; this repeats it for a paste.
 * A text equal to the organization default, trimmed, cannot confirm: the release writes its own.
 */
export function narrativeChoiceError (text: string | null | undefined, orgDefault: string | null | undefined): string | null {
    if (blank(text)) return 'Write the assessment narrative for this release'
    if ((text || '').length > FDA_PROSE_MAX_LENGTH) return 'At most ' + FDA_PROSE_MAX_LENGTH + ' characters'
    if ((text || '').trim() === (orgDefault || '').trim()) return NARRATIVE_IS_ORG_DEFAULT
    return null
}

/**
 * The updateRelease variables the written text needs before the act, or null for no write:
 *   differs from the override -> the trimmed text
 *   same as the override      -> null
 */
export function narrativeChoiceVariables (uuid: string, org: string, text: string | null | undefined,
    storedOverride: string | null | undefined): Record<string, unknown> | null {
    return releaseNarrativeVariables(uuid, org, text, storedOverride)
}
