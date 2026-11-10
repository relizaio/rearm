import gql from 'graphql-tag'
import { byText } from './apiKeyGroupInput'

/**
 * FDAW-25 3.12 (FDAW-40 3.5): the pure parts of the Customers panel. A customer is an API key group of
 * kind CUSTOMER: external keys of the organization as members and TEA read grants, read live on every
 * TEA request. The server decides membership by kind, normalizes the grants and checks the bounds and
 * the name rule; these helpers only build what the panel sends and shows.
 *
 * Each document is written out in full, never interpolated: scripts/validate-graphql.mjs skips a template
 * containing an interpolation, and apiKeyGroupKindSchemaDrift.spec.ts validates these against both schemas.
 */
export const CREATE_CUSTOMER_GROUP = gql`
    mutation createCustomerGroup($apiKeyGroup: CreateApiKeyGroupInput!) {
        createApiKeyGroup(apiKeyGroup: $apiKeyGroup) {
            uuid name description org status kind apiKeys
            permissions { permissions { org scope object type functions } }
        }
    }`

export const UPDATE_CUSTOMER_GROUP = gql`
    mutation updateCustomerGroup($apiKeyGroup: UpdateApiKeyGroupInput!) {
        updateApiKeyGroup(apiKeyGroup: $apiKeyGroup) {
            uuid name description org status kind apiKeys
            permissions { permissions { org scope object type functions } }
        }
    }`

export const DEACTIVATE_CUSTOMER_GROUP = gql`
    mutation deactivateCustomerGroup($groupUuid: ID!) {
        deleteApiKeyGroup(groupUuid: $groupUuid) {
            uuid name description org status kind apiKeys
            permissions { permissions { org scope object type functions } }
        }
    }`

export interface CustomerKeyRow { uuid: string, holderName: string, status: string | null, member: boolean, label: string }

export interface CustomerForm { name: string, description: string, apiKeys: string[] }

/**
 * The editor's key checkboxes: one row per external key of the list, sorted by holder name, member =
 * the group holds it, labelled "<holder> (<uuid>)"; then one row per member the list does not hold,
 * labelled with its uuid alone and checked, so a save never drops a member the admin did not uncheck.
 */
export function customerKeyRows (externalKeys: any[] | null | undefined, group: any | null | undefined): CustomerKeyRow[] {
    const members: string[] = group?.apiKeys || []
    const rows: CustomerKeyRow[] = (externalKeys || [])
        .filter((k: any) => !!k?.uuid)
        .map((k: any) => ({
            uuid: k.uuid,
            holderName: k.holderName || '',
            status: k.status ?? null,
            member: members.includes(k.uuid),
            label: k.holderName ? `${k.holderName} (${k.uuid})` : k.uuid
        }))
        .sort((a, b) => byText(a.holderName, b.holderName))
    const listed = new Set(rows.map(r => r.uuid))
    for (const uuid of members) {
        if (listed.has(uuid)) continue
        rows.push({ uuid, holderName: '', status: null, member: true, label: uuid })
        listed.add(uuid)
    }
    return rows
}

/** The member holders' names (the uuid for a key the list does not hold), sorted, joined with ", "; "" for none. */
export function customerKeysTitle (externalKeys: any[] | null | undefined, group: any | null | undefined): string {
    const byUuid = new Map<string, string>((externalKeys || []).filter((k: any) => !!k?.uuid).map((k: any) => [k.uuid, k.holderName || '']))
    return (group?.apiKeys || [])
        .map((uuid: string) => byUuid.get(uuid) || uuid)
        .sort(byText)
        .join(', ')
}

/**
 * A grant list in comparable form: the sorted set of scope|objectId|type, read from a stored row
 * (object) or a ScopedPermissions row (objectId). Functions and approvals are not compared: the server
 * rewrites a customer group's grants to TEA_READ and none.
 */
export function grantRows (permissions: any[] | null | undefined): string[] {
    const set = new Set<string>()
    for (const p of (permissions || [])) {
        if (!p) continue
        set.add(`${p.scope}|${p.objectId ?? p.object}|${p.type}`)
    }
    return [...set].sort()
}

const sameSet = (a: string[], b: string[]): boolean => {
    const x = new Set(a)
    const y = new Set(b)
    return x.size === y.size && [...x].every(v => y.has(v))
}

/**
 * The updateApiKeyGroup input of an edit: groupId plus only the changed fields, or null when nothing
 * changed. Texts compare trimmed against the stored value (null reads ""); a changed text is sent
 * trimmed, so a cleared description sends "" and the server stores null. A changed list is sent whole,
 * [] when emptied, never null: null keeps the stored list on the server.
 */
export function customerUpdateInput (original: any, form: CustomerForm, permissions: any[]): any | null {
    const input: any = { groupId: original.uuid }
    const name = (form.name || '').trim()
    if (name !== (original.name || '')) input.name = name
    const description = (form.description || '').trim()
    if (description !== (original.description || '')) input.description = description
    const apiKeys = [...new Set(form.apiKeys || [])]
    if (!sameSet(apiKeys, original.apiKeys || [])) input.apiKeys = apiKeys
    if (!sameSet(grantRows(permissions), grantRows(original.permissions?.permissions))) input.permissions = permissions || []
    return Object.keys(input).length > 1 ? input : null
}

/**
 * A customer create: the createApiKeyGroup input (kind CUSTOMER, the name trimmed, a blank description
 * sent as null), and follow = an updateApiKeyGroup with the members and grants must come after it,
 * since the create input carries neither.
 */
export function customerCreateSteps (form: CustomerForm, apiKeys: string[], permissions: any[], org: string): { create: any, follow: boolean } {
    return {
        create: { org, name: (form.name || '').trim(), description: (form.description || '').trim() || null, kind: 'CUSTOMER' },
        follow: (apiKeys || []).length > 0 || (permissions || []).length > 0
    }
}
