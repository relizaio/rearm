// The component overlay editor (FDAW-15 3.6): a third-party supplier and licence filled by
// hand on a component row whose uploaded SBOM left them empty. The server stores them as an
// overlay and applies them on export to empty fields only; this module holds the documents
// and the variables rule so both are tested here rather than in ReleaseView.

import gql from 'graphql-tag'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { MutationClient } from './setSbomComponentSupport'
import { orgWriteSentence } from './orgWrite'

/**
 * The overlay of one component, loaded when the editor opens, not with the list (the
 * sbomComponentSupportDetail.ts shape, the same single-component read).
 */
export const SBOM_COMPONENT_OVERLAY_DETAIL = gql`
    query sbomComponentOverlayDetail($releaseUuid: ID!, $sbomComponentUuid: ID!) {
        getReleaseSbomComponentGraph(
            releaseUuid: $releaseUuid
            sbomComponentUuid: $sbomComponentUuid
        ) {
            component {
                uuid
                overlay {
                    supplier
                    licence
                    revision
                    assertedByName
                    assertedAt
                    supplierBulkAct
                }
            }
        }
    }`

export const SET_SBOM_COMPONENT_OVERLAY = gql`
    mutation setSbomComponentOverlay(
        $sbomComponentUuid: ID!
        $overlay: SbomComponentOverlayInput!
        $reason: String
    ) {
        setSbomComponentOverlay(
            sbomComponentUuid: $sbomComponentUuid
            overlay: $overlay
            reason: $reason
        ) {
            uuid
            overlay {
                supplier
                licence
                revision
                assertedByName
                assertedAt
                supplierBulkAct
            }
        }
    }`

/** The stored overlay of a component row, as SBOM_COMPONENT_OVERLAY_DETAIL answers it. */
export interface StoredOverlay {
    supplier?: string | null
    licence?: string | null
    revision?: number | null
    assertedByName?: string | null
    assertedAt?: string | null
    /** Set when a supplier sweep wrote the supplier; the editor shows a bulk tag beside it. */
    supplierBulkAct?: string | null
}

export interface OverlayForm {
    supplier: string
    licence: string
    reason: string
}

/** The server's bounds (SupportTextBounds): one-line supplier and licence, multi-line reason. */
export const OVERLAY_SUPPLIER_MAX_LENGTH = 512
export const OVERLAY_LICENCE_MAX_LENGTH = 512
export const OVERLAY_REASON_MAX_LENGTH = 2048

/** The sentence at the top of the read-only editor of a caller without organization write. */
export const OVERLAY_ORG_WRITE_SENTENCE = orgWriteSentence('Editing the supplier and licence')

/** The form seeded from the stored overlay, or empty for a row that holds none. */
export function overlayFormOf (stored: StoredOverlay | null | undefined): OverlayForm {
    return { supplier: stored?.supplier ?? '', licence: stored?.licence ?? '', reason: '' }
}

/**
 * One field of the patch, the supportAttestationInput.ts clear rule: null when untouched
 * (the typed value, stripped, equals the stored one), `""` when erased, else the stripped
 * value. Null keeps the stored value on the server; `""` clears it.
 */
export function overlayFieldValue (typed: string | null | undefined, stored: string | null | undefined): string | null {
    const t = (typed ?? '').trim()
    if (t === (stored ?? '').trim()) return null
    return t
}

/** The variables of setSbomComponentOverlay for this form against the stored overlay. */
export function overlayVariables (
    sbomComponentUuid: string,
    form: OverlayForm,
    stored: StoredOverlay | null | undefined
): { sbomComponentUuid: string, overlay: { supplier: string | null, licence: string | null }, reason: string | null } {
    return {
        sbomComponentUuid,
        overlay: {
            supplier: overlayFieldValue(form.supplier, stored?.supplier),
            licence: overlayFieldValue(form.licence, stored?.licence)
        },
        reason: (form.reason ?? '').trim() || null
    }
}

/** False when the save would change nothing: the server would write no revision for it. */
export function overlaySendsAnything (form: OverlayForm, stored: StoredOverlay | null | undefined): boolean {
    const v = overlayVariables('', form, stored).overlay
    return v.supplier !== null || v.licence !== null
}

// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/
// eslint-disable-next-line no-control-regex
const CONTROL_BUT_TAB_LF = /[\u0000-\u0008\u000b-\u001f\u007f]/

/** The bounds, phrased as the server's refusals; the SPDX check stays the server's. */
export function validateOverlay (form: OverlayForm): string[] {
    const out: string[] = []
    const line = (field: string, value: string, max: number) => {
        const v = (value ?? '').trim()
        if (v.length > max) out.push(`${field} is longer than ${max} characters`)
        else if (CONTROL.test(v)) out.push(`${field} holds a control character`)
    }
    line('supplier', form.supplier, OVERLAY_SUPPLIER_MAX_LENGTH)
    line('licence', form.licence, OVERLAY_LICENCE_MAX_LENGTH)
    const reason = (form.reason ?? '').trim()
    if (reason.length > OVERLAY_REASON_MAX_LENGTH) out.push(`reason is longer than ${OVERLAY_REASON_MAX_LENGTH} characters`)
    else if (CONTROL_BUT_TAB_LF.test(reason)) out.push('reason holds a control character')
    return out
}

/** Read-only, with the sentence, for a caller without organization write (FDAW-15 3.6, REQ-13). */
export function overlayEditorAccess (canWriteOrg: boolean): { readOnly: boolean, sentence: string | null } {
    return canWriteOrg ? { readOnly: false, sentence: null } : { readOnly: true, sentence: OVERLAY_ORG_WRITE_SENTENCE }
}

/** The stored overlay of one component, never cached: the editor seeds its patch from it. */
export async function loadComponentOverlay (
    client: DriftFallbackClient,
    releaseUuid: string,
    sbomComponentUuid: string
): Promise<StoredOverlay | null> {
    const resp = await client.query({
        query: SBOM_COMPONENT_OVERLAY_DETAIL,
        variables: { releaseUuid, sbomComponentUuid },
        fetchPolicy: 'network-only'
    })
    return (resp.data as any)?.getReleaseSbomComponentGraph?.component?.overlay ?? null
}

/** One overlay write. Throws on refusal (the server's `licence invalid: ...` text included). */
export async function saveComponentOverlay (
    client: MutationClient,
    variables: ReturnType<typeof overlayVariables>
): Promise<StoredOverlay | null> {
    const resp = await client.mutate({ mutation: SET_SBOM_COMPONENT_OVERLAY, variables })
    return (resp.data as any)?.setSbomComponentOverlay?.overlay ?? null
}
