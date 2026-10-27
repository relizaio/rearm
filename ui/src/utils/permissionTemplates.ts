import gql from 'graphql-tag'
import { isSchemaDriftError } from './graphqlDriftFallback'
import { editorKeepsScope } from './boardPermissions'
import { permissionsFromScopedModel } from './apiKeyGroupInput'

/**
 * TPF-113 (TPF-87 part 2, architecture 4.4): a user's permission templates, named permission sets
 * the user saves once per organization and starts a device-login approval or a key request from.
 * A template grants nothing: a pick fills the editor by value and the page sends what the editor
 * holds, so no template uuid ever travels to an approval or a request (decision 1).
 *
 * The selections are written out, never interpolated: scripts/validate-graphql.mjs skips a gql
 * template containing ${...}, and these operations are Pro-only (absent from the CE mirror).
 */
export const MY_PERMISSION_TEMPLATES_QUERY = gql`
    query myPermissionTemplates($org: ID) {
        myPermissionTemplates(org: $org) {
            uuid name description org user
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`
export const CREATE_PERMISSION_TEMPLATE_MUTATION = gql`
    mutation createPermissionTemplate($template: CreatePermissionTemplateInput!) {
        createPermissionTemplate(template: $template) {
            uuid name description org user
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`
export const UPDATE_PERMISSION_TEMPLATE_MUTATION = gql`
    mutation updatePermissionTemplate($template: UpdatePermissionTemplateInput!) {
        updatePermissionTemplate(template: $template) {
            uuid name description org user
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`
export const DELETE_PERMISSION_TEMPLATE_MUTATION = gql`
    mutation deletePermissionTemplate($uuid: ID!) {
        deletePermissionTemplate(uuid: $uuid)
    }`

/** The sentence shown under the picker once a template is picked (architecture 4.4, verbatim). */
export const REDUCTIONS_SENTENCE = 'Reduced to what you hold here. The server may reduce this further, object grants and functions included.'

/** The organization-wide levels, lowest first (CliLogin.vue PERM_ORDER). */
export const PERM_ORDER = ['NONE', 'ESSENTIAL_READ', 'READ_ONLY', 'READ_WRITE', 'ADMIN']

/** The server's description bound counts code points, not UTF-16 units (decision 4). */
export const countCodePoints = (s: string): number => Array.from(s || '').length

export interface ScopedModel { orgPermission: any, scopedPermissions: any[] }

/**
 * Loads the caller's templates of one organization. A schema-drift error (a server without
 * templates, e.g. CE) answers supported false with no templates; every other error is rethrown so
 * the caller shows it. The client is a parameter so a spec passes a stub.
 */
export async function loadMyPermissionTemplates (client: { query: Function }, org: string | null): Promise<{ templates: any[], supported: boolean }> {
    try {
        const resp: any = await client.query({
            query: MY_PERMISSION_TEMPLATES_QUERY,
            variables: { org },
            fetchPolicy: 'no-cache'
        })
        return { templates: resp?.data?.myPermissionTemplates || [], supported: true }
    } catch (e: any) {
        if (isSchemaDriftError(e)) return { templates: [], supported: false }
        throw e
    }
}

const byText = (a: string, b: string): number => (a || '').localeCompare(b || '', undefined, { sensitivity: 'base' })

/** The picker's options: { label: name, value: uuid }, sorted by name ignoring case. */
export function templateOptions (templates: any[] | null | undefined): { label: string, value: string }[] {
    return (templates || [])
        .map((t: any) => ({ label: t.name, value: t.uuid }))
        .sort((a, b) => byText(a.label, b.label))
}

const listOrNone = (fs: string[]): string => fs.length ? fs.join(', ') : 'none'

/**
 * What the approver sees before approving (decision 3: display, not control). The organization-wide
 * level is reduced to maxOrgType; the ORGANIZATION row's functions are intersected with orgFunctions
 * (the owner's organization-wide functions); each scoped row's functions with allowedFunctions (the
 * union of the owner's functions). Scoped rows are never re-typed or dropped here: the owner's
 * object-level rights are not on the page and the server's clamp decides them. An undefined bound
 * clips nothing. Returns a copy; the input model is left as it is.
 */
export function clipModelToBounds (model: ScopedModel, maxOrgType?: string, allowedFunctions?: string[],
    orgFunctions?: string[]): { model: ScopedModel, reductions: string[] } {
    const out: ScopedModel = JSON.parse(JSON.stringify(model || { orgPermission: null, scopedPermissions: [] }))
    if (!out.orgPermission) out.orgPermission = { type: 'NONE', functions: [], approvals: [] }
    if (!Array.isArray(out.scopedPermissions)) out.scopedPermissions = []
    const reductions: string[] = []
    const org = out.orgPermission
    const type = org.type || 'NONE'
    if (maxOrgType !== undefined && PERM_ORDER.indexOf(type) > PERM_ORDER.indexOf(maxOrgType)) {
        org.type = maxOrgType
        reductions.push(`organization-wide ${type} reduced to ${maxOrgType}`)
    }
    if (orgFunctions !== undefined) {
        const had: string[] = org.functions || []
        const kept = had.filter(f => orgFunctions.includes(f))
        if (kept.length < had.length) {
            org.functions = kept
            reductions.push(`organization-wide functions reduced to ${listOrNone(kept)}`)
        }
    }
    if (allowedFunctions !== undefined) {
        for (const sp of out.scopedPermissions) {
            const had: string[] = sp.functions || []
            const kept = had.filter(f => allowedFunctions.includes(f))
            if (kept.length < had.length) {
                sp.functions = kept
                reductions.push(`${String(sp.scope || '').toLowerCase()} ${sp.objectName || sp.objectId} functions reduced to ${listOrNone(kept)}`)
            }
        }
    }
    return { model: out, reductions }
}

/** The CreatePermissionTemplateInput: the name trimmed, the editor model as UserGroupPermissionInput rows. */
export function templateInputFromModel (name: string, description: string | null | undefined, org: string, model: ScopedModel): any {
    return {
        org,
        name: (name || '').trim(),
        description,
        permissions: permissionsFromScopedModel(model, org)
    }
}

/** The UpdatePermissionTemplateInput: permissions REPLACES the stored set, so it is always the whole model. */
export function templateUpdateInput (uuid: string, name: string, description: string | null | undefined, model: ScopedModel, org: string): any {
    return {
        uuid,
        name: (name || '').trim(),
        description,
        permissions: permissionsFromScopedModel(model, org)
    }
}

/** The ORGANIZATION row's type of a template in this organization, NONE when it has none. */
export function templateOrgType (template: any, org: string): string {
    const row = (template?.permissions?.permissions || []).find((p: any) => p?.scope === 'ORGANIZATION' && p.object === org)
    return row?.type || 'NONE'
}

/** The number of object grants (every scope the editor keeps) of a template in this organization. */
export function templateGrantCount (template: any, org: string): number {
    return (template?.permissions?.permissions || []).filter((p: any) => p?.org === org && editorKeepsScope(p.scope)).length
}

export const grantsText = (n: number): string => `${n} grant${n === 1 ? '' : 's'}`

/** One line for a template: "ADMIN, 3 grants", or "none" when it holds nothing. */
export function templateSummary (template: any, org: string): string {
    const type = templateOrgType(template, org)
    const n = templateGrantCount(template, org)
    if (type === 'NONE' && !n) return 'none'
    return `${type} \u00B7 ${grantsText(n)}`
}
