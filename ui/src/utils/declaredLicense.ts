import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import { isSchemaDriftError, type DriftFallbackClient } from './graphqlDriftFallback'

/**
 * The declared licence and the component supplier (SCORE-42, design ADR-1): what the three
 * surfaces share -- organization settings, a component's settings and the release page.
 *
 * Every read and write goes through its OWN small document, never the shared ones. The
 * organizations query must keep serving a CE mirror with supportInjection, COMPONENT_FULL_DATA
 * is the response selection of the updateComponent mutation and SINGLE_RELEASE_GQL_DATA that of
 * updateRelease: a field the CE schema lacks in any of them invalidates the whole document there,
 * and with it every save on the page. The device support window and the default view are the
 * precedents. The mutations select the fields back so a form shows the server's canonical form
 * (apache-2.0 is stored as Apache-2.0) from the mutation response.
 *
 * Reads and writes run with fetchPolicy 'no-cache', as the device window's do: these documents
 * select a few fields of shared objects, and Settings has no key fields, so a partial settings
 * selection written into the Apollo cache would replace the cached organization's settings.
 */

export const ORG_DECLARED_LICENSE_QUERY = gql`
    query orgDeclaredLicense($orgUuid: ID!) {
        organization(orgUuid: $orgUuid) {
            uuid
            settings { defaultLicenseExpression defaultLicenseUrl }
        }
    }`

export const UPDATE_ORG_DECLARED_LICENSE = gql`
    mutation updateOrgDeclaredLicense($orgUuid: ID!, $settings: SettingsInput!) {
        updateOrganizationSettings(orgUuid: $orgUuid, settings: $settings) {
            uuid
            settings { defaultLicenseExpression defaultLicenseUrl }
        }
    }`

export const COMPONENT_DECLARED_LICENSE_QUERY = gql`
    query componentDeclaredLicense($componentUuid: ID!) {
        component(componentUuid: $componentUuid) {
            uuid
            licenseExpression
            licenseUrl
            supplierName
        }
    }`

export const UPDATE_COMPONENT_DECLARED_LICENSE = gql`
    mutation updateComponentDeclaredLicense($component: UpdateComponentInput!) {
        updateComponent(component: $component) {
            uuid
            licenseExpression
            licenseUrl
            supplierName
        }
    }`

export const RELEASE_DECLARED_LICENSE_QUERY = gql`
    query releaseDeclaredLicense($releaseID: ID!, $orgID: ID) {
        release(releaseUuid: $releaseID, orgUuid: $orgID) {
            uuid
            licenseExpression
            effectiveLicense { expression url level }
        }
    }`

export const UPDATE_RELEASE_DECLARED_LICENSE = gql`
    mutation updateReleaseDeclaredLicense($release: ReleaseInput!) {
        updateRelease(release: $release) {
            uuid
            licenseExpression
            effectiveLicense { expression url level }
        }
    }`

/** A client that can also write; the Apollo client the views hold is one. */
export interface DeclaredLicenseClient extends DriftFallbackClient {
    mutate (opts: { mutation: DocumentNode, variables: Record<string, any>, fetchPolicy?: string }): Promise<{ data?: any }>
}

/** `DeclaredLicenseLevel` of the schema; a value this build does not know is printed as served. */
export type DeclaredLicenseLevel = 'ORGANIZATION' | 'COMPONENT' | 'RELEASE'

/** `Release.effectiveLicense` as served: the licence ReARM writes on the release's node. */
export interface DeclaredLicense {
    expression: string
    url: string | null
    level: DeclaredLicenseLevel | string
}

/** A form's values. Null and absent read as ''; supplier only exists at the component level. */
export interface DeclaredLicenseForm {
    expression: string
    url?: string
    supplier?: string
}

export interface OrgDeclaredLicenseResult { supported: boolean, expression: string, url: string }
export interface ComponentDeclaredLicenseResult { supported: boolean, expression: string, url: string, supplier: string }
export interface ReleaseDeclaredLicenseResult { supported: boolean, override: string, effective: DeclaredLicense | null }

// ---- copy (design 3.7, word for word) -------------------------------------------------------

/**
 * The supplierName schema comment's last three sentences (SCORE-41 round 5 section 3.1, pinned
 * there by T-23 in the SDL files). This UI carries them to the operator unchanged; a paraphrase
 * would be a second rule. declaredLicenseSchemaDrift.spec.ts compares it with the served
 * description when the Pro schema is present.
 */
export const SUPPLIER_SENTENCE = 'Applies to the nodes ReARM makes at the next export and to SBOMs uploaded after it is set. ' +
    'A root uploaded earlier keeps the supplier recorded at its upload; a newly generated SBOM (a new serialNumber) ' +
    'uploaded to a release made after the change carries the new value in that release\'s export. ' +
    'An identical file is answered with the stored row, unchanged.'

export const EXPRESSION_HINT = 'An SPDX licence expression: an id from the SPDX list (Apache-2.0, AGPL-3.0-only, ' +
    'MIT OR Apache-2.0, GPL-2.0-only WITH Classpath-exception-2.0) or LicenseRef- followed by letters, digits, dots ' +
    'and hyphens for a licence that is not on the list (LicenseRef-Acme-Commercial). The id\'s case is corrected to ' +
    'the list\'s. NOASSERTION, NONE and licence names are refused.'

export const URL_HINT = 'Where the text of the licence is (http or https). The exported SBOM carries it only when the ' +
    'licence is a single LicenseRef- id; for an SPDX id or a compound expression the URL is kept here and the SBOM ' +
    'carries the expression alone. Needs a licence expression at the same level.'

export const SUPPLIER_HINT = 'Named as supplier on the SBOM nodes ReARM makes for this component\'s releases. Leave it ' +
    'empty to name the organization for its own (INTERNAL) software and nobody for EXTERNAL software; a deliverable\'s ' +
    'publisher wins over this field. ' + SUPPLIER_SENTENCE

export const ORG_INTRO = 'Written on the SBOM nodes ReARM makes (a release\'s node, a product\'s root and the ' +
    'placeholders for releases without an SBOM) for every release whose component and release declare no licence of ' +
    'their own. Leave it empty to declare none.'

export const COMPONENT_TOOLTIP = 'The licence and the supplier ReARM writes on the SBOM nodes it makes for this ' +
    'component\'s releases. A release can declare a different licence on its Meta tab; the organization default ' +
    'applies when nothing is declared here.'

export const RELEASE_INTRO = 'Declares a different licence for this release only. Leave it empty to inherit the ' +
    'component\'s licence, else the organization default. While this release declares its own licence, no licence ' +
    'URL is carried.'

export const EFFECTIVE_NONE = 'No licence declared at any level.'
export const UNSUPPORTED_NOTE = 'This server does not offer a declared licence yet. A ReARM CE installation gains it at its next sync.'

export const EXPRESSION_PLACEHOLDER = 'Apache-2.0, AGPL-3.0-only, LicenseRef-Acme-Commercial'
export const URL_PLACEHOLDER = 'https://example.com/licence.txt'
export const SUPPLIER_PLACEHOLDER = 'Acme Corp'
export const RELEASE_PLACEHOLDER = 'Leave empty to inherit'

export const ORG_SAVED = 'Default declared licence updated.'
export const ORG_CLEARED = 'Default declared licence cleared.'
export const COMPONENT_SAVED = 'Declared licence and supplier updated.'
export const RELEASE_SAVED = 'Release licence updated.'
export const RELEASE_CLEARED = 'Release licence cleared; this release now inherits.'

export const LEVEL_WORDS: Record<DeclaredLicenseLevel, string> = { ORGANIZATION: 'organization', COMPONENT: 'component', RELEASE: 'release' }

// ---- loaders --------------------------------------------------------------------------------

const text = (v: unknown): string => (typeof v === 'string' ? v : '')

/**
 * Run one read, telling a server that cannot answer (schema drift: `supported: false`) apart
 * from a request that failed (rethrown, so the caller decides; every caller catches it).
 */
async function readOrDrift (client: DriftFallbackClient, query: DocumentNode, variables: Record<string, any>): Promise<any | null> {
    try {
        const resp = await client.query({ query, variables, fetchPolicy: 'no-cache' })
        return resp?.data ?? {}
    } catch (e: any) {
        if (isSchemaDriftError(e)) return null
        throw e
    }
}

export async function loadOrgDeclaredLicense (client: DriftFallbackClient, orgUuid: string): Promise<OrgDeclaredLicenseResult> {
    const data = await readOrDrift(client, ORG_DECLARED_LICENSE_QUERY, { orgUuid })
    if (data === null) return { supported: false, expression: '', url: '' }
    const s = data.organization?.settings
    return { supported: true, expression: text(s?.defaultLicenseExpression), url: text(s?.defaultLicenseUrl) }
}

export async function loadComponentDeclaredLicense (client: DriftFallbackClient, componentUuid: string): Promise<ComponentDeclaredLicenseResult> {
    const data = await readOrDrift(client, COMPONENT_DECLARED_LICENSE_QUERY, { componentUuid })
    if (data === null) return { supported: false, expression: '', url: '', supplier: '' }
    const c = data.component
    return { supported: true, expression: text(c?.licenseExpression), url: text(c?.licenseUrl), supplier: text(c?.supplierName) }
}

export async function loadReleaseDeclaredLicense (
    client: DriftFallbackClient, releaseUuid: string, orgUuid?: string
): Promise<ReleaseDeclaredLicenseResult> {
    const data = await readOrDrift(client, RELEASE_DECLARED_LICENSE_QUERY, { releaseID: releaseUuid, orgID: orgUuid })
    if (data === null) return { supported: false, override: '', effective: null }
    const r = data.release
    return { supported: true, override: text(r?.licenseExpression), effective: r?.effectiveLicense ?? null }
}

// ---- variable builders ----------------------------------------------------------------------

const trimmed = (v: string | null | undefined): string => (v || '').trim()

/**
 * The changed fields only, trimmed, under their GraphQL names; '' for a clear (PATCH: an omitted
 * or null field leaves the stored value alone, an empty string clears it). Whitespace-only input
 * equals empty both ways. Empty object when nothing changed.
 */
function changedFields (current: DeclaredLicenseForm, baseline: DeclaredLicenseForm, names: Partial<Record<keyof DeclaredLicenseForm, string>>): Record<string, string> {
    const out: Record<string, string> = {}
    for (const key of Object.keys(names) as Array<keyof DeclaredLicenseForm>) {
        const now = trimmed(current[key])
        if (now !== trimmed(baseline[key])) out[names[key] as string] = now
    }
    return out
}

/** True when the form differs from what the server last confirmed, field by field, trimmed. */
export function declaredLicenseDiffers (current: DeclaredLicenseForm, baseline: DeclaredLicenseForm): boolean {
    return (['expression', 'url', 'supplier'] as const).some(k => trimmed(current[k]) !== trimmed(baseline[k]))
}

/**
 * `updateOrganizationSettings` variables, or null when nothing changed. When the expression is
 * emptied the URL is left to the server, which clears it with the expression and answers so.
 */
export function orgDeclaredLicenseVariables (
    orgUuid: string, current: DeclaredLicenseForm, baseline: DeclaredLicenseForm
): { orgUuid: string, settings: Record<string, string> } | null {
    const settings = changedFields(current, baseline, { expression: 'defaultLicenseExpression', url: 'defaultLicenseUrl' })
    return Object.keys(settings).length ? { orgUuid, settings } : null
}

/**
 * `updateComponent` variables, or null when nothing changed. `name` is always carried:
 * UpdateComponentInput.name is String!, and a partial without it is refused at variable
 * coercion before the resolver runs (the device window's lesson).
 */
export function componentDeclaredLicenseVariables (
    uuid: string, name: string, current: DeclaredLicenseForm, baseline: DeclaredLicenseForm
): { component: Record<string, string> } | null {
    const fields = changedFields(current, baseline, { expression: 'licenseExpression', url: 'licenseUrl', supplier: 'supplierName' })
    return Object.keys(fields).length ? { component: { uuid, name, ...fields } } : null
}

/**
 * `updateRelease` variables, `{ uuid, org, licenseExpression }` and nothing else, or null when
 * unchanged. Nothing wider: updateRelease reads a null list as no change, so a payload carrying
 * more keys could detach a release's contents (the narrative override's rule).
 */
export function releaseDeclaredLicenseVariables (
    uuid: string, org: string, current: DeclaredLicenseForm, baseline: DeclaredLicenseForm
): { release: { uuid: string, org: string, licenseExpression: string } } | null {
    const now = trimmed(current.expression)
    if (now === trimmed(baseline.expression)) return null
    return { release: { uuid, org, licenseExpression: now } }
}

// ---- display --------------------------------------------------------------------------------

/** "Effective: MIT (from the component)", or EFFECTIVE_NONE when no level declares one. */
export function effectiveLicenseLine (effective: DeclaredLicense | null | undefined): string {
    if (!effective || !effective.expression) return EFFECTIVE_NONE
    const level = effective.level || ''
    const word = LEVEL_WORDS[level as DeclaredLicenseLevel] ?? level.toLowerCase()
    return `Effective: ${effective.expression} (from the ${word})`
}

/**
 * The URL when it is an http or https link, else null: a template renders a link only through
 * this, and the text otherwise. The server refuses any other scheme (LicenseUrls); this keeps a
 * javascript:, data: or file: value out of an href should it ever serve one.
 */
export function licenseUrlHref (url: string | null | undefined): string | null {
    if (!url) return null
    try {
        const protocol = new URL(url).protocol
        return protocol === 'http:' || protocol === 'https:' ? url : null
    } catch {
        return null
    }
}
