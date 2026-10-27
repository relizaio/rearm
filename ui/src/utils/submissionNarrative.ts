// The assessment-narrative prompt before a regulatory submission's Create or Resubmit (any release,
// component or product): which narrative the release carries into the package -- the organization
// default, or one written for this release -- and the release write that choice needs first. The
// write is the per-release narrative editor's: the same narrow updateRelease partial and its PATCH
// contract ('' clears back to the organization default; an omitted field stays as it is).
import gql from 'graphql-tag'
import { FDA_PROSE_MAX_LENGTH } from './fdaProseInput'
import { releaseNarrativeVariables } from './releaseNarrativeInput'

export type NarrativeChoice = 'ORGANIZATION' | 'RELEASE'

export const NARRATIVE_CHOICE_ORGANIZATION: NarrativeChoice = 'ORGANIZATION'
export const NARRATIVE_CHOICE_RELEASE: NarrativeChoice = 'RELEASE'

export const NO_ORG_NARRATIVE = 'No organization default is set'

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

/** The choice the prompt opens on: the release's own override, else the organization default, else writing one. */
export function narrativeInitialChoice (override: string | null | undefined, orgDefault: string | null | undefined): NarrativeChoice {
    if (!blank(override)) return NARRATIVE_CHOICE_RELEASE
    return blank(orgDefault) ? NARRATIVE_CHOICE_RELEASE : NARRATIVE_CHOICE_ORGANIZATION
}

/** What "Write one for this release" starts from: the override, else the organization default as a starting point. */
export function narrativeInitialText (override: string | null | undefined, orgDefault: string | null | undefined): string {
    return !blank(override) ? (override || '') : (orgDefault || '')
}

/** Why the prompt cannot confirm yet, or null. The input caps the length; this repeats it for a paste. */
export function narrativeChoiceError (choice: NarrativeChoice | null, text: string | null | undefined,
    orgDefault: string | null | undefined): string | null {
    if (choice === NARRATIVE_CHOICE_ORGANIZATION) return blank(orgDefault) ? NO_ORG_NARRATIVE : null
    if (choice !== NARRATIVE_CHOICE_RELEASE) return 'Choose the assessment narrative'
    if (blank(text)) return 'Write the assessment narrative for this release'
    if ((text || '').length > FDA_PROSE_MAX_LENGTH) return 'At most ' + FDA_PROSE_MAX_LENGTH + ' characters'
    return null
}

/**
 * The updateRelease variables the choice needs before the act, or null for no write:
 *   organization default, release overrides -> fdaAssessmentNarrative '' (clears the override)
 *   organization default, no override       -> null
 *   written, differs from the override      -> the trimmed text
 *   written, same as the override           -> null
 */
export function narrativeChoiceVariables (uuid: string, org: string, choice: NarrativeChoice, text: string | null | undefined,
    storedOverride: string | null | undefined): Record<string, unknown> | null {
    if (choice === NARRATIVE_CHOICE_ORGANIZATION) {
        return blank(storedOverride) ? null : { uuid, org, fdaAssessmentNarrative: '' }
    }
    return releaseNarrativeVariables(uuid, org, text, storedOverride)
}
