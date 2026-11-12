import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import { isSchemaDriftError, type DriftFallbackClient } from './graphqlDriftFallback'

/**
 * The release's displayed version (FDAW-74 3.7): the version the device shows its user. The server
 * writes it into the merged release SBOM (property reliza:displayedVersion on metadata.component),
 * the support addendum and the device support statement; it never replaces the release version.
 *
 * Read and written by its own two documents, never the shared single-release ones: the CE schema
 * lacks the field until the CE catch-up, and a field it lacks in a shared document would invalidate
 * that whole document there (releaseFragmentsSchemaDrift.spec.ts). Reads and writes run with
 * fetchPolicy 'no-cache', as the declared licence's do.
 */

export const RELEASE_DISPLAYED_VERSION_QUERY = gql`
    query releaseDisplayedVersion($releaseID: ID!, $orgID: ID) {
        release(releaseUuid: $releaseID, orgUuid: $orgID) {
            uuid
            displayedVersion
        }
    }`

export const UPDATE_RELEASE_DISPLAYED_VERSION = gql`
    mutation updateReleaseDisplayedVersion($release: ReleaseInput!) {
        updateRelease(release: $release) {
            uuid
            displayedVersion
        }
    }`

/** The server's bound (DisplayedVersion.MAX_LENGTH): UTF-16 units, the unit of the input's maxlength. */
export const DISPLAYED_VERSION_MAX_LENGTH = 128

/** The addendum's row label; character for character the server's DisplayedVersion.DOCUMENT_LABEL. */
export const DISPLAYED_VERSION_DOCUMENT_LABEL = 'Displayed version'
/** The statement line's start; character for character the server's DisplayedVersion.STATEMENT_PREFIX. */
export const DISPLAYED_VERSION_STATEMENT_PREFIX = 'Shown on the device as '

// ---- copy (design 3.7, word for word) -------------------------------------------------------

export const DISPLAYED_VERSION_HEADING = 'Displayed version'
export const DISPLAYED_VERSION_INTRO = 'The version the device shows its user, for example on its About screen. ' +
    'It is written into the release SBOM, the support addendum and the device support statement, so that a ' +
    'customer can match this release to the device in front of them. Optional.'
export const DISPLAYED_VERSION_SHIPPED_NOTE = 'This release is shipped. If its TEA profile publishes at shipped, ' +
    'saving a change publishes this release to TEA again at once, with a new SBOM revision.'
export const DISPLAYED_VERSION_PLACEHOLDER = 'Not set'
export const DISPLAYED_VERSION_SAVE = 'Save displayed version'
export const DISPLAYED_VERSION_CLEAR = 'Clear'
export const DISPLAYED_VERSION_HINT = 'At most 128 characters, on one line. A change applies to documents built ' +
    'after it is saved: rebuild a draft package to carry it. On TEA it appears with the next publication of this ' +
    'release. Package versions that are already final and TEA revisions that are already published are not rewritten.'
export const DISPLAYED_VERSION_SAVED = 'Displayed version saved.'
export const DISPLAYED_VERSION_CLEARED = 'Displayed version cleared.'

/** A client that can also write; the Apollo client the views hold is one. */
export interface DisplayedVersionClient extends DriftFallbackClient {
    mutate (opts: { mutation: DocumentNode, variables: Record<string, any>, fetchPolicy?: string }): Promise<{ data?: any }>
}

export interface ReleaseDisplayedVersionResult { supported: boolean, value: string | null }

const trimmed = (v: string | null | undefined): string => (v || '').trim()

/**
 * The mutation's variables, or null when the trimmed value equals the trimmed baseline (nothing to
 * send). The empty string is the clear (PATCH: an omitted field leaves the stored value).
 */
export function displayedVersionVariables (
    uuid: string, org: string, current: string | null | undefined, baseline: string | null | undefined
): { release: { uuid: string, org: string, displayedVersion: string } } | null {
    const cur = trimmed(current)
    if (cur === trimmed(baseline)) return null
    return { release: { uuid, org, displayedVersion: cur } }
}

/**
 * The release's stored value. A server without the field answers `supported: false` (schema
 * drift); any other failure is rethrown, so the caller decides.
 */
export async function loadReleaseDisplayedVersion (
    client: DriftFallbackClient, releaseUuid: string, orgUuid?: string
): Promise<ReleaseDisplayedVersionResult> {
    let data: any
    try {
        const resp = await client.query({
            query: RELEASE_DISPLAYED_VERSION_QUERY,
            variables: { releaseID: releaseUuid, orgID: orgUuid },
            fetchPolicy: 'no-cache',
        })
        data = resp?.data ?? {}
    } catch (e: any) {
        if (isSchemaDriftError(e)) return { supported: false, value: null }
        throw e
    }
    const v = data?.release?.displayedVersion
    return { supported: true, value: typeof v === 'string' ? v : null }
}

const SHIPPED_OR_LATER = new Set(['GENERAL_AVAILABILITY', 'END_OF_MARKETING', 'END_OF_DISTRIBUTION',
    'END_OF_SUPPORT', 'END_OF_LIFE'])

/** Whether the lifecycle is GENERAL_AVAILABILITY or later, in the server's order. */
export function isShippedOrLater (lifecycle: string | null | undefined): boolean {
    return typeof lifecycle === 'string' && SHIPPED_OR_LATER.has(lifecycle)
}

/** A DISPLAYED_VERSION History row's details, in words; a value is printed as text, never as markup. */
export function displayedVersionHistoryText (row: { rua?: string | null, oldValue?: string | null, newValue?: string | null }): string {
    const has = (v: string | null | undefined): v is string => typeof v === 'string' && v !== ''
    if (row.rua === 'ADDED' && has(row.newValue)) return `Displayed version set to "${row.newValue}"`
    if (row.rua === 'CHANGED' && has(row.oldValue) && has(row.newValue)) {
        return `Displayed version changed from "${row.oldValue}" to "${row.newValue}"`
    }
    if (row.rua === 'REMOVED' && has(row.oldValue)) return `Displayed version cleared (was "${row.oldValue}")`
    return 'Displayed version updated'
}
