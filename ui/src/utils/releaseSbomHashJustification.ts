// The hash half of the bulk dialog (FDAW-14 3.3, 3.9): justify why components of a release
// SBOM carry no hash. It needs COMPONENT WRITE, not organization write, so it is offered to a
// product-scoped writer whose support half is disabled. A head is one release's document, so
// there is no release-scope radio here.

import gql from 'graphql-tag'
import type { MutationClient } from './setSbomComponentSupport'
import type { BulkSelectorChoice } from './bulkSelectorForm'
import { selectorInput, validateSelector } from './bulkSelectorForm'
import { validateJustifiedUnknown } from './justifiedUnknownLabels'

/** Shown, and nothing else, when the release has no release SBOM identity yet. */
export const NO_IDENTITY_SENTENCE = 'No release SBOM identity exists for this release yet: publish it to TEA'
    + ' or build a package first'

export interface ReleaseSbomHead { uuid: string, profileName: string, latestVersion: number, hashlessCount: number }

export interface HashlessComponent {
    key: string
    name: string | null
    version: string | null
    justification: { code: string, text: string | null, bulkAct: string | null } | null
}

export interface HashJustificationResult {
    releaseSbom: string
    revision: number
    hashes: Array<{ key: string, code: string, bulkAct: string | null }>
    skippedKeys: string[]
}

export const RELEASE_SBOMS_QUERY = gql`
    query releaseSboms($releaseUuid: ID!) {
        releaseSboms(releaseUuid: $releaseUuid) { uuid profileName latestVersion hashlessCount }
    }`

export const RELEASE_SBOM_HASHLESS_QUERY = gql`
    query releaseSbomHashlessComponents($releaseSbom: ID!) {
        releaseSbomHashlessComponents(releaseSbom: $releaseSbom) {
            key
            name
            version
            justification { code text bulkAct }
        }
    }`

export const RELEASE_SBOM_HASHLESS_SELECTOR_COUNT = gql`
    query releaseSbomHashlessSelectorCount($releaseSbom: ID!, $selector: SupportBulkSelectorInput!) {
        releaseSbomHashlessSelectorCount(releaseSbom: $releaseSbom, selector: $selector)
    }`

const RESULT_SELECTION = `
            releaseSbom
            revision
            hashes { key code bulkAct }
            skippedKeys`

export const SET_HASH_JUSTIFICATION = gql`
    mutation setReleaseSbomHashJustification($releaseSbom: ID!, $keys: [String!]!, $code: JustifiedUnknownReason!,
            $text: String, $reason: String) {
        setReleaseSbomHashJustification(releaseSbom: $releaseSbom, keys: $keys, code: $code, text: $text,
                reason: $reason) {${RESULT_SELECTION}
        }
    }`

export const CLEAR_HASH_JUSTIFICATION = gql`
    mutation clearReleaseSbomHashJustification($releaseSbom: ID!, $keys: [String!]!, $reason: String!) {
        clearReleaseSbomHashJustification(releaseSbom: $releaseSbom, keys: $keys, reason: $reason) {${RESULT_SELECTION}
        }
    }`

export const BULK_HASH_JUSTIFICATION_BY_SELECTOR = gql`
    mutation bulkSetReleaseSbomHashJustificationBySelector($releaseSbom: ID!, $selector: SupportBulkSelectorInput!,
            $code: JustifiedUnknownReason!, $text: String, $reason: String) {
        bulkSetReleaseSbomHashJustificationBySelector(releaseSbom: $releaseSbom, selector: $selector, code: $code,
                text: $text, reason: $reason) {${RESULT_SELECTION}
        }
    }`

type QueryClient = { query (opts: any): Promise<{ data?: any }> }

/** The select label of a head: profile name, version n, how many nodes lack a hash. */
export function headLabel (h: ReleaseSbomHead): string {
    return `${h.profileName}, version ${h.latestVersion}, ${h.hashlessCount} without a hash`
}

/** `left as justified` entries of a result: the keys a sweep did not replace. */
export function skippedKeysText (r: HashJustificationResult | null): string[] {
    return (r?.skippedKeys ?? []).map(k => `${k}: left as justified`)
}

export async function loadReleaseSbomHeads (client: QueryClient, releaseUuid: string): Promise<ReleaseSbomHead[]> {
    const resp = await client.query({ query: RELEASE_SBOMS_QUERY, variables: { releaseUuid }, fetchPolicy: 'no-cache' })
    return (resp.data as any)?.releaseSboms ?? []
}

export async function loadHashlessComponents (client: QueryClient, releaseSbom: string): Promise<HashlessComponent[]> {
    const resp = await client.query({ query: RELEASE_SBOM_HASHLESS_QUERY, variables: { releaseSbom },
        fetchPolicy: 'no-cache' })
    return (resp.data as any)?.releaseSbomHashlessComponents ?? []
}

export async function countHashlessBySelector (client: QueryClient, releaseSbom: string,
    choice: BulkSelectorChoice): Promise<number> {
    const resp = await client.query({ query: RELEASE_SBOM_HASHLESS_SELECTOR_COUNT,
        variables: { releaseSbom, selector: selectorInput(choice) }, fetchPolicy: 'no-cache' })
    return (resp.data as any)?.releaseSbomHashlessSelectorCount ?? 0
}

/** The input of one hash write: a code, its text, and the audit reason. */
export interface HashJustificationInput { code: string | null, text: string, reason: string }

/** Problems before the round trip; the server stays the authority. */
export function validateHashJustification (choice: BulkSelectorChoice, keys: string[],
    input: HashJustificationInput): string[] {
    const out: string[] = []
    if (choice.form === 'EXPLICIT' && !keys.length) out.push('Select at least one component.')
    out.push(...validateSelector(choice))
    if (!input.code) out.push('Choose why the hash cannot be given.')
    else out.push(...validateJustifiedUnknown(input.code, input.text))
    return out
}

/**
 * Shown (the checked keys) goes to setReleaseSbomHashJustification; By ecosystem or By
 * namespace goes to the selector mutation. Exactly one call either way.
 */
export async function submitHashJustification (client: MutationClient, releaseSbom: string,
    choice: BulkSelectorChoice, keys: string[], input: HashJustificationInput): Promise<HashJustificationResult> {
    const common = { releaseSbom, code: input.code, text: input.text.trim() || null, reason: input.reason.trim() || null }
    if (choice.form === 'EXPLICIT') {
        const resp = await client.mutate({ mutation: SET_HASH_JUSTIFICATION, variables: { ...common, keys } })
        return (resp.data as any)?.setReleaseSbomHashJustification
    }
    const resp = await client.mutate({ mutation: BULK_HASH_JUSTIFICATION_BY_SELECTOR,
        variables: { ...common, selector: selectorInput(choice) } })
    return (resp.data as any)?.bulkSetReleaseSbomHashJustificationBySelector
}

/** Clearing needs a reason: the audit row is the only record that it happened. */
export async function clearHashJustification (client: MutationClient, releaseSbom: string, keys: string[],
    reason: string): Promise<HashJustificationResult> {
    if (!reason.trim()) throw new Error('Clearing a hash justification needs a reason.')
    const resp = await client.mutate({ mutation: CLEAR_HASH_JUSTIFICATION,
        variables: { releaseSbom, keys, reason: reason.trim() } })
    return (resp.data as any)?.clearReleaseSbomHashJustification
}
