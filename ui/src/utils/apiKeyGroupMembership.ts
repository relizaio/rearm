import gql from 'graphql-tag'
import { isSchemaDriftError, markDriftRejected } from './graphqlDriftFallback'
import { byText } from './apiKeyGroupInput'

/**
 * TPF-124: the key side of API key group membership. Both documents are written out in full, never
 * interpolated (validate-graphql.mjs skips a template containing an interpolation).
 */
export const COMBINED_API_KEY_ORG_PERMISSIONS_QUERY = gql`
    query combinedApiKeyOrgPermissions($orgUuid: ID!, $apiKeyUuid: ID!) {
        combinedApiKeyOrgPermissions(orgUuid: $orgUuid, apiKeyUuid: $apiKeyUuid) {
            apiKeyUuid org
            permissions {
                org scope object type functions meta approvals
                sources { kind groupUuid groupName type functions approvals }
            }
        }
    }`

export const SET_API_KEY_GROUPS_OF_KEY_MUTATION = gql`
    mutation setApiKeyGroupsOfKey($orgUuid: ID!, $apiKeyUuid: ID!, $groupUuids: [ID!]!) {
        setApiKeyGroupsOfKey(orgUuid: $orgUuid, apiKeyUuid: $apiKeyUuid, groupUuids: $groupUuids) {
            uuid name status apiKeys
        }
    }`

/**
 * A key's effective rows with their sources. A schema-drift error (a server without the key-side group
 * operations) answers supported false with no rows; every other error (auth, transport, server) is
 * rethrown so the caller shows it. The client is a parameter so a spec passes a stub.
 */
export async function loadCombinedApiKeyOrgPermissions (client: { query: Function }, orgUuid: string, apiKeyUuid: string): Promise<{ rows: any[], supported: boolean }> {
    try {
        const resp: any = await client.query({
            query: COMBINED_API_KEY_ORG_PERMISSIONS_QUERY,
            variables: { orgUuid, apiKeyUuid },
            fetchPolicy: 'no-cache'
        })
        return { rows: resp?.data?.combinedApiKeyOrgPermissions?.permissions || [], supported: true }
    } catch (e: any) {
        if (isSchemaDriftError(e)) {
            markDriftRejected(COMBINED_API_KEY_ORG_PERMISSIONS_QUERY)
            return { rows: [], supported: false }
        }
        throw e
    }
}

/** Sets the key's groups to exactly groupUuids. Every error is rethrown: a refusal must reach the toast. */
export async function setApiKeyGroupsOfKey (client: { mutate: Function }, orgUuid: string, apiKeyUuid: string, groupUuids: string[]): Promise<any[]> {
    const resp: any = await client.mutate({
        mutation: SET_API_KEY_GROUPS_OF_KEY_MUTATION,
        variables: { orgUuid, apiKeyUuid, groupUuids },
        fetchPolicy: 'no-cache'
    })
    return resp?.data?.setApiKeyGroupsOfKey || []
}

export interface MembershipRow { uuid: string, name: string, status: string, member: boolean, disabled: boolean }

/**
 * Every group of the organization (any status) as a checkbox row, sorted by name: member = the group
 * holds the key; disabled = INACTIVE and not a member (a key is never added to an inactive group; it
 * may be removed from one, so an inactive member row stays enabled).
 */
export function membershipRows (groups: any[] | null | undefined, keyUuid: string | null | undefined): MembershipRow[] {
    return (groups || [])
        .filter((g: any) => !!g?.uuid)
        .map((g: any) => {
            const member = !!keyUuid && (g.apiKeys || []).includes(keyUuid)
            return { uuid: g.uuid, name: g.name || g.uuid, status: g.status, member, disabled: g.status === 'INACTIVE' && !member }
        })
        .sort((a, b) => byText(a.name, b.name))
}

/** The desired set: checked uuids that are rows and not disabled, in row order, each once. */
export function desiredGroupUuids (rows: MembershipRow[], checked: string[]): string[] {
    const want = new Set(checked || [])
    return rows.filter(r => !r.disabled && want.has(r.uuid)).map(r => r.uuid)
}

/** What a save would change, for the dirty flag and the success toast. */
export function membershipDiff (rows: MembershipRow[], checked: string[]): { added: string[], removed: string[] } {
    const want = new Set(desiredGroupUuids(rows, checked))
    return {
        added: rows.filter(r => !r.member && want.has(r.uuid)).map(r => r.uuid),
        removed: rows.filter(r => r.member && !want.has(r.uuid)).map(r => r.uuid)
    }
}
