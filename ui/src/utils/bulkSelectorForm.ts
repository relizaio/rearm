// The bulk dialog's selector forms (FDAW-14 3.4, 3.9): which components a sweep reaches when
// it is not the shown list. Pure helpers and the two selector documents, so the rules are
// tested here rather than in ReleaseView, which no spec mounts.

import gql from 'graphql-tag'
import type { MutationClient } from './setSbomComponentSupport'
import { bulkSupportVariables, newBulkOutcome, tallyBulkResponse, validateBulkInput } from './useBulkAttest'
import type { BulkAttestInput, BulkOutcome } from './useBulkAttest'

/** `EXPLICIT` is today's walk of the shown list; the other two are server-side selectors. */
export type BulkSelectorForm = 'EXPLICIT' | 'TYPE' | 'NAMESPACE'
/** Where a selector looks: the page's release, or every release of the organization. */
export type BulkSelectorScope = 'RELEASE' | 'ORGANIZATION'

export interface BulkSelectorChoice {
    form: BulkSelectorForm
    type: string
    namespace: string
    scope: BulkSelectorScope
}

/** The server's cap on one selector call; above it the call is refused, never truncated. */
export const BULK_SELECTOR_CAP = 1000

/** The purl type rule the server applies (3.11). */
export const SELECTOR_TYPE_PATTERN = /^[a-z0-9.+-]{1,64}$/

export const SELECTOR_NAMESPACE_MAX = 256

export const SELECTOR_FORM_OPTIONS: Array<{ label: string, value: BulkSelectorForm }> = [
    { label: 'Shown components', value: 'EXPLICIT' },
    { label: 'By ecosystem', value: 'TYPE' },
    { label: 'By namespace', value: 'NAMESPACE' }
]

export const SELECTOR_SCOPE_OPTIONS: Array<{ label: string, value: BulkSelectorScope }> = [
    { label: 'This release', value: 'RELEASE' },
    { label: 'Whole organization (across the organization, every product)', value: 'ORGANIZATION' }
]

/** The over-cap line of the confirm stage; the confirm button is disabled beside it. */
export const SELECTOR_OVER_CAP_TEXT = `Matches more than ${BULK_SELECTOR_CAP} components; narrow the selector`
    + ' by namespace or to this release, or use Shown components'

/** Defaults: today's form, and This release for the two selector forms (r2, F-2). */
export function emptySelectorChoice (): BulkSelectorChoice {
    return { form: 'EXPLICIT', type: '', namespace: '', scope: 'RELEASE' }
}

export function isSelectorForm (choice: BulkSelectorChoice): boolean {
    return choice.form === 'TYPE' || choice.form === 'NAMESPACE'
}

/** Problems with the selector, phrased as the server's own refusals (3.11). Empty for EXPLICIT. */
export function validateSelector (choice: BulkSelectorChoice): string[] {
    if (!isSelectorForm(choice)) return []
    const out: string[] = []
    if (!SELECTOR_TYPE_PATTERN.test(choice.type ?? '')) {
        out.push('The ecosystem (purl type) must match [a-z0-9.+-]{1,64}, for example deb or npm.')
    }
    if (choice.form === 'NAMESPACE') {
        const ns = (choice.namespace ?? '').trim()
        if (!ns) out.push('A namespace is required for By namespace, for example debian.')
        // eslint-disable-next-line no-control-regex
        else if (ns.length > SELECTOR_NAMESPACE_MAX || /[\u0000-\u001f]/.test(ns)) {
            out.push(`The namespace is longer than ${SELECTOR_NAMESPACE_MAX} characters or holds a control character.`)
        }
    }
    return out
}

/** The `SupportBulkSelectorInput` of a selector form; null for EXPLICIT. */
export function selectorInput (choice: BulkSelectorChoice): { form: string, type: string, namespace: string | null } | null {
    if (!isSelectorForm(choice)) return null
    return {
        form: choice.form,
        type: choice.type,
        namespace: choice.form === 'NAMESPACE' ? choice.namespace.trim() : null
    }
}

/** The release a selector call names: the page's release for This release, null across the organization. */
export function selectorRelease (choice: BulkSelectorChoice, pageReleaseUuid: string | null | undefined): string | null {
    return choice.scope === 'RELEASE' ? (pageReleaseUuid || null) : null
}

/** `deb` or `deb/debian`, for the confirm stage. */
export function selectorLabel (choice: BulkSelectorChoice): string {
    return choice.form === 'NAMESPACE' ? `${choice.type}/${choice.namespace.trim()}` : choice.type
}

/** The count line of the confirm stage (`bulk-selector-count`). */
export function selectorCountText (matched: number, scope: BulkSelectorScope): string {
    const where = scope === 'RELEASE' ? 'in this release' : 'across the organization'
    return `Matches ${matched} ${matched === 1 ? 'component' : 'components'} ${where}`
}

export const SUPPORT_BULK_SELECTOR_COUNT = gql`
    query supportBulkSelectorCount($orgUuid: ID!, $selector: SupportBulkSelectorInput!, $releaseUuid: ID) {
        supportBulkSelectorCount(orgUuid: $orgUuid, selector: $selector, releaseUuid: $releaseUuid) {
            matched
            overCap
        }
    }`

export const BULK_SET_SUPPORT_BY_SELECTOR = gql`
    mutation bulkSetSbomComponentSupportBySelector(
        $orgUuid: ID!
        $selector: SupportBulkSelectorInput!
        $releaseUuid: ID
        $levelOfSupport: LevelOfSupport
        $justification: String
        $supportParty: SupportParty
        $endOfGuaranteedSupportDate: String
        $endOfSupportDate: String
        $endOfLifeDate: String
        $endOfSupportUnknown: JustifiedUnknownInput
        $reason: String
        $assessedAt: String
    ) {
        bulkSetSbomComponentSupportBySelector(
            orgUuid: $orgUuid
            selector: $selector
            releaseUuid: $releaseUuid
            levelOfSupport: $levelOfSupport
            justification: $justification
            supportParty: $supportParty
            endOfGuaranteedSupportDate: $endOfGuaranteedSupportDate
            endOfSupportDate: $endOfSupportDate
            endOfLifeDate: $endOfLifeDate
            endOfSupportUnknown: $endOfSupportUnknown
            reason: $reason
            assessedAt: $assessedAt
        ) {
            bulkAct
            matchedCount
            appliedCount
            skippedCount
            failedCount
            results { sbomComponentUuid outcome message }
        }
    }`

export interface SelectorCount { matched: number, overCap: boolean }

/** The confirm stage's preview, with the same selector and scope the write will send. */
export async function fetchSelectorCount (
    client: { query (opts: any): Promise<{ data?: any }> },
    orgUuid: string,
    choice: BulkSelectorChoice,
    pageReleaseUuid: string | null | undefined
): Promise<SelectorCount> {
    const resp = await client.query({
        query: SUPPORT_BULK_SELECTOR_COUNT,
        variables: { orgUuid, selector: selectorInput(choice), releaseUuid: selectorRelease(choice, pageReleaseUuid) },
        fetchPolicy: 'no-cache'
    })
    const c = (resp.data as any)?.supportBulkSelectorCount
    if (!c) throw new Error('the server returned no count for this selector')
    return { matched: c.matched, overCap: !!c.overCap }
}

/**
 * One selector call: no walk, no 200-id batching. The server refuses above the cap, so a
 * refusal comes back as an error and nothing is written.
 */
export async function submitBySelector (
    client: MutationClient,
    orgUuid: string,
    choice: BulkSelectorChoice,
    pageReleaseUuid: string | null | undefined,
    input: BulkAttestInput
): Promise<BulkOutcome> {
    const out = newBulkOutcome()
    const invalid = [...validateSelector(choice), ...validateBulkInput(input)]
    if (invalid.length) return { ...out, aborted: true, error: invalid.join(' ') }
    try {
        const resp = await client.mutate({
            mutation: BULK_SET_SUPPORT_BY_SELECTOR,
            variables: {
                orgUuid,
                selector: selectorInput(choice),
                releaseUuid: selectorRelease(choice, pageReleaseUuid),
                ...bulkSupportVariables(input)
            }
        })
        const r = (resp.data as any)?.bulkSetSbomComponentSupportBySelector
        if (!r) throw new Error('the server returned no result for this sweep')
        out.matched = r.matchedCount ?? 0
        tallyBulkResponse(out, r)
    } catch (err: any) {
        out.aborted = true
        out.error = err?.message || String(err)
        // One call, one transaction per item: re-running reports the written ones as skipped.
        out.retryable = true
    }
    return out
}
