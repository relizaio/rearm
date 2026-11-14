// The "Software components without an identifier" panel (FDAW-15 3.6, FDAW-51 3.3): the keyless nodes
// of a release's merged SBOM, which the organisation can describe as vendored code, attest and
// un-describe. The server mints every key from the node's name and version; nothing here
// sends a key or a purl. Documents and rules live in this module so they are tested here
// rather than in ReleaseView, which no spec mounts.

import gql from 'graphql-tag'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { MutationClient } from './setSbomComponentSupport'
import type { SupportDetailResult } from './sbomComponentSupportDetail'
import { orgWriteSentence } from './orgWrite'
import { OVERLAY_REASON_MAX_LENGTH, OVERLAY_SUPPLIER_MAX_LENGTH, overlayFieldValue } from './overlayInput'

/**
 * The panel's list and the vendored Attest seed, one document (FDAW-51 3.3.1). The selection
 * under `component` is the field set SBOM_COMPONENT_SUPPORT_DETAIL selects under `component`,
 * written out literally so the seed cannot drift from the component form's (the drift spec
 * compares the two by parse).
 */
export const RELEASE_UNIDENTIFIED_COMPONENTS = gql`
    query releaseUnidentifiedComponents($releaseUuid: ID!) {
        releaseUnidentifiedComponents(releaseUuid: $releaseUuid) {
            key
            name
            version
            group
            described
            describable
            refusal
            overlay {
                origin
                supplier
                licence
                description
            }
            component {
                uuid
                attestationState
                attestedLevelOfSupport
                justification
                supportParty
                endOfGuaranteedSupportDate
                endOfSupportDate
                endOfLifeDate
                endOfSupportUnknown { code text bulkAct }
                supportMilestones {
                    milestoneType
                    date
                    notes
                }
            }
        }
    }`

export const VENDORED_IDENTIFIER = gql`
    query vendoredIdentifier($releaseUuid: ID!, $name: String!, $version: String) {
        vendoredIdentifier(releaseUuid: $releaseUuid, name: $name, version: $version)
    }`

export const VENDORED_DESCRIPTIONS_BY_NAME = gql`
    query vendoredDescriptionsByName($releaseUuid: ID!, $name: String!) {
        vendoredDescriptionsByName(releaseUuid: $releaseUuid, name: $name) {
            uuid
            name
            version
            origin
            supplier
            licence
            description
        }
    }`

export const DESCRIBE_VENDORED_COMPONENT = gql`
    mutation describeVendoredComponent(
        $releaseUuid: ID!
        $name: String!
        $version: String
        $overlay: SbomComponentOverlayInput!
        $reason: String
    ) {
        describeVendoredComponent(
            releaseUuid: $releaseUuid
            name: $name
            version: $version
            overlay: $overlay
            reason: $reason
        ) {
            key
            name
            version
            described
        }
    }`

export const REMOVE_VENDORED_DESCRIPTION = gql`
    mutation removeVendoredDescription($releaseUuid: ID!, $name: String!, $version: String, $reason: String!) {
        removeVendoredDescription(releaseUuid: $releaseUuid, name: $name, version: $version, reason: $reason)
    }`

/** The description fields of a vendored node, as stored. */
export interface VendoredDescription {
    origin?: string | null
    supplier?: string | null
    licence?: string | null
    description?: string | null
}

/** One keyless node of the release's merged document, as the server lists it. */
export interface VendoredRow {
    key: string | null
    name: string
    version: string | null
    group: string | null
    described: boolean
    describable: boolean
    refusal: string | null
    overlay?: VendoredDescription | null
    component?: { uuid?: string, attestationState?: string | null, [k: string]: unknown } | null
}

/** An earlier description of the same name, offered as a copy source (F-7). */
export interface VendoredCopySource extends VendoredDescription {
    uuid: string
    name: string | null
    version: string | null
}

export const VENDORED_PANEL_TITLE = 'Software components without an identifier'
export const VENDORED_EMPTY_TEXT = 'No software component of the merged SBOM lacks an identifier. Files, data,'
    + ' cryptographic assets, models and devices are not listed here.'
export const VENDORED_NO_SBOM_NOTE = 'A component release that has no SBOM and no purl is listed here by its name and'
    + ' version. A description of it is written to the JSON SBOM export of this release. An FDA package leaves such'
    + ' a release out of its SBOM, so the description is not in the package.'
export const VENDORED_ORG_WRITE_SENTENCE = orgWriteSentence('Describing a vendored component')
export const VENDORED_IDENTIFIER_NOTE = 'minted by ReARM from the component\'s name and version; the exported'
    + ' purl carries ?vendored=true'
export const VENDORED_COPY_FROM_LABEL = 'Copy from'
export const VENDORED_COPY_FROM_HINT = 'copies the fields of an earlier version; saved under this component\'s own'
    + ' identifier'

export type VendoredStateTag = 'not described' | 'described' | 'described, assessed'

/**
 * The panel's state tag (FDAW-51 3.3.5). Assessed means ATTESTED only: a WITHDRAWN attestation
 * is not injected, so the export counts the node unassessed and the tag follows the export.
 */
export function vendoredStateTag (row: Pick<VendoredRow, 'described' | 'component'>): VendoredStateTag {
    if (!row.described) return 'not described'
    return row.component?.attestationState === 'ATTESTED' ? 'described, assessed' : 'described'
}

/** `Software components without an identifier <middle dot> N`: every listed node, the non-describable ones too. */
export function vendoredPanelTitle (rows: VendoredRow[] | null | undefined): string {
    return `${VENDORED_PANEL_TITLE} \u00b7 ${(rows ?? []).length}`
}

/**
 * The note under a product's list (FDAW-98 3.6): only a product's merge lists a component release
 * that has no SBOM, and the FDA package's merge leaves it out. Null for any other release and for
 * an empty list.
 */
export function vendoredNoSbomNote (rows: VendoredRow[] | null | undefined,
    componentType: string | null | undefined): string | null {
    return componentType === 'PRODUCT' && (rows ?? []).length > 0 ? VENDORED_NO_SBOM_NOTE : null
}

/** `libdosecalc 2.3`, or the name alone for a node without a version. */
export function vendoredLabel (row: { name: string | null, version: string | null }): string {
    return [row.name, row.version].filter(Boolean).join(' ')
}

/** The Remove confirmation's consequence sentence (FDAW-15 3.10). */
export function vendoredRemoveSentence (row: { name: string, version: string | null }): string {
    return `Removing the description makes ${vendoredLabel(row)} unidentified again in the next export, and an`
        + ' open draft built with the description must be rebuilt before Finalize. Its support attestation, if any,'
        + ' is kept and reunited if the component is described again.'
}

/**
 * Which actions a row offers (FDAW-51 3.3.2): none for a reader; for a writer Describe and,
 * once described, Attest and Remove description, every one disabled with the sentence for a
 * caller without organization write. A node the server cannot describe offers Describe
 * disabled with its refusal and no Attest.
 */
export interface VendoredRowActions {
    describe: { shown: boolean, disabled: boolean }
    attest: { shown: boolean, disabled: boolean }
    remove: { shown: boolean, disabled: boolean }
    refusal: string | null
}

export function vendoredRowActions (row: VendoredRow, isWritable: boolean, canWriteOrg: boolean): VendoredRowActions {
    const hidden = { shown: false, disabled: true }
    if (!isWritable) return { describe: hidden, attest: hidden, remove: hidden, refusal: null }
    if (!row.describable) {
        return { describe: { shown: true, disabled: true }, attest: hidden, remove: hidden, refusal: row.refusal }
    }
    return {
        describe: { shown: true, disabled: !canWriteOrg },
        attest: { shown: true, disabled: !canWriteOrg || !row.described },
        remove: { shown: row.described, disabled: !canWriteOrg },
        refusal: null
    }
}

/** One read of the panel's list; network-only so a refetch after a save is never a cached copy. */
export async function loadUnidentifiedComponents (
    client: DriftFallbackClient,
    releaseUuid: string
): Promise<VendoredRow[]> {
    const resp = await client.query({
        query: RELEASE_UNIDENTIFIED_COMPONENTS,
        variables: { releaseUuid },
        fetchPolicy: 'network-only'
    })
    return ((resp.data as any)?.releaseUnidentifiedComponents ?? []) as VendoredRow[]
}

/** Null equals null; a version is never matched by `''` (the server never lists `''`). */
function sameVersion (a: string | null | undefined, b: string | null | undefined): boolean {
    return (a === null || a === undefined) ? (b === null || b === undefined) : a === b
}

/**
 * One node of the list, re-read network-only: the panel's list copy is never a form's seed or
 * baseline. Null when the node is no longer listed.
 */
export async function loadVendoredRow (
    client: DriftFallbackClient,
    releaseUuid: string,
    name: string,
    version: string | null
): Promise<VendoredRow | null> {
    const resp = await client.query({
        query: RELEASE_UNIDENTIFIED_COMPONENTS,
        variables: { releaseUuid },
        fetchPolicy: 'network-only'
    })
    const rows = ((resp.data as any)?.releaseUnidentifiedComponents ?? []) as VendoredRow[]
    return rows.find(r => r.name === name && sameVersion(r.version, version)) ?? null
}

/**
 * The vendored Attest form's seed (FDAW-51 3.3.1): the node's `component`, re-read network-only
 * (the panel's list copy is never the seed, the stale-copy rule of sbomComponentSupportDetail),
 * never through getReleaseSbomComponentGraph, which answers null for a row with no artifact
 * mapping. Null for a described node with no support row yet and for a node that vanished.
 */
export async function loadVendoredSupportDetail (
    client: DriftFallbackClient,
    releaseUuid: string,
    name: string,
    version: string | null
): Promise<SupportDetailResult> {
    const row = await loadVendoredRow(client, releaseUuid, name, version)
    if (!row?.component) return { kind: 'ok', attestation: null }
    return { kind: 'ok', attestation: row.component as any }
}

export interface VendoredDescribeForm {
    origin: string
    supplier: string
    licence: string
    description: string
    reason: string
}

export function vendoredDescribeFormOf (stored: VendoredDescription | null | undefined): VendoredDescribeForm {
    return {
        origin: stored?.origin ?? '',
        supplier: stored?.supplier ?? '',
        licence: stored?.licence ?? '',
        description: stored?.description ?? '',
        reason: ''
    }
}

/**
 * Copy from an earlier description (F-7): pre-fills the four fields, every one still editable;
 * the reason is the operator's own. The source row is not referenced again: the save sends the
 * node's own name and version, so the result is a copy under the node's key, never an alias.
 */
export function copyVendoredDescription (form: VendoredDescribeForm, source: VendoredDescription): void {
    form.origin = source.origin ?? ''
    form.supplier = source.supplier ?? ''
    form.licence = source.licence ?? ''
    form.description = source.description ?? ''
}

/** The overlay patch of a describe, the overlayInput.ts clear rule against the stored description. */
export function vendoredOverlayInput (
    form: VendoredDescribeForm,
    stored: VendoredDescription | null | undefined
): { origin: string | null, supplier: string | null, licence: string | null, description: string | null } {
    return {
        origin: overlayFieldValue(form.origin, stored?.origin),
        supplier: overlayFieldValue(form.supplier, stored?.supplier),
        licence: overlayFieldValue(form.licence, stored?.licence),
        description: overlayFieldValue(form.description, stored?.description)
    }
}

/** False when the save would change nothing: no audit revision for an edit that never happened. */
export function vendoredSendsAnything (form: VendoredDescribeForm, stored: VendoredDescription | null | undefined): boolean {
    return Object.values(vendoredOverlayInput(form, stored)).some(v => v !== null)
}

/** The variables of describeVendoredComponent: the node's name and version, never a key. */
export function describeVendoredVariables (
    releaseUuid: string,
    row: Pick<VendoredRow, 'name' | 'version'>,
    form: VendoredDescribeForm,
    stored: VendoredDescription | null | undefined
): Record<string, unknown> {
    return {
        releaseUuid,
        name: row.name,
        version: row.version,
        overlay: vendoredOverlayInput(form, stored),
        reason: (form.reason ?? '').trim() || null
    }
}

// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/
// eslint-disable-next-line no-control-regex
const CONTROL_BUT_TAB_LF = /[\u0000-\u0008\u000b-\u001f\u007f]/

/** The server's bounds (SupportTextBounds): one-line origin, supplier and licence; multi-line description and reason. */
export const VENDORED_LINE_MAX_LENGTH = OVERLAY_SUPPLIER_MAX_LENGTH
export const VENDORED_TEXT_MAX_LENGTH = OVERLAY_REASON_MAX_LENGTH

/** The bounds of a describe, phrased as the server's refusals; content rules stay the server's. */
export function validateVendoredDescribe (form: VendoredDescribeForm): string[] {
    const out: string[] = []
    const line = (field: string, value: string) => {
        const v = (value ?? '').trim()
        if (v.length > VENDORED_LINE_MAX_LENGTH) out.push(`${field} is longer than ${VENDORED_LINE_MAX_LENGTH} characters`)
        else if (CONTROL.test(v)) out.push(`${field} holds a control character`)
    }
    const text = (field: string, value: string) => {
        const v = (value ?? '').trim()
        if (v.length > VENDORED_TEXT_MAX_LENGTH) out.push(`${field} is longer than ${VENDORED_TEXT_MAX_LENGTH} characters`)
        else if (CONTROL_BUT_TAB_LF.test(v)) out.push(`${field} holds a control character`)
    }
    line('origin', form.origin)
    line('supplier', form.supplier)
    line('licence', form.licence)
    text('description', form.description)
    text('reason', form.reason)
    if (![form.origin, form.supplier, form.licence, form.description].some(v => (v ?? '').trim())) {
        out.push('a description must say something: supply an origin, a supplier, a licence or a description')
    }
    return out
}

/** What the Describe editor shows when it opens. */
export interface VendoredEditorSeed {
    identifier: string
    /** The stored description, re-read when the editor opens: the form's seed and its patch baseline. */
    stored: VendoredDescription | null
    copySources: VendoredCopySource[]
    readOnly: boolean
}

/**
 * Opening the Describe editor: the identifier the server will mint, always; the earlier
 * descriptions of the name only for a node not yet described and an enabled editor. The
 * read-only editor of a caller without organization write never sends that query (r4, F-17):
 * the server refuses it to that caller, and it spans products.
 */
export async function openVendoredEditor (
    client: DriftFallbackClient,
    releaseUuid: string,
    row: Pick<VendoredRow, 'name' | 'version' | 'described'>,
    canWriteOrg: boolean
): Promise<VendoredEditorSeed> {
    const idResp = await client.query({
        query: VENDORED_IDENTIFIER,
        variables: { releaseUuid, name: row.name, version: row.version },
        fetchPolicy: 'network-only'
    })
    const identifier = (idResp.data as any)?.vendoredIdentifier ?? ''
    const fresh = await loadVendoredRow(client, releaseUuid, row.name, row.version)
    const described = fresh ? fresh.described : row.described
    const stored = described ? (fresh?.overlay ?? null) : null
    let copySources: VendoredCopySource[] = []
    if (!described && canWriteOrg) {
        const resp = await client.query({
            query: VENDORED_DESCRIPTIONS_BY_NAME,
            variables: { releaseUuid, name: row.name },
            fetchPolicy: 'network-only'
        })
        copySources = ((resp.data as any)?.vendoredDescriptionsByName ?? []) as VendoredCopySource[]
    }
    return { identifier, stored, copySources, readOnly: !canWriteOrg }
}

export async function describeVendored (client: MutationClient, variables: Record<string, unknown>): Promise<void> {
    await client.mutate({ mutation: DESCRIBE_VENDORED_COMPONENT, variables })
}

/** Remove needs a reason; refused here before any call, as the server would. */
export async function removeVendored (
    client: MutationClient,
    releaseUuid: string,
    row: Pick<VendoredRow, 'name' | 'version'>,
    reason: string
): Promise<void> {
    const r = (reason ?? '').trim()
    if (!r) throw new Error('removing a description needs a reason')
    await client.mutate({
        mutation: REMOVE_VENDORED_DESCRIPTION,
        variables: { releaseUuid, name: row.name, version: row.version, reason: r }
    })
}
