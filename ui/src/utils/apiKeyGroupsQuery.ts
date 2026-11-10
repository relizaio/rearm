import gql from 'graphql-tag'
import { isSchemaDriftError } from './graphqlDriftFallback'

/**
 * TPF-92 (architecture 4.5, decision 1): the organization's API key groups, active and inactive.
 * OrgSettings owns the one list: the API Key Groups tab, the Free Form Keys Groups column and the
 * key modal's groups line all read it, and a tab pane exists only while it is active.
 *
 * The selection is written out, never interpolated: scripts/validate-graphql.mjs skips a gql
 * template containing ${...}, and this document is Pro-ahead of the CE mirror.
 */
export const API_KEY_GROUPS_OF_ORG_QUERY = gql`
    query apiKeyGroupsOfOrg($org: ID!, $kind: ApiKeyGroupKind) {
        apiKeyGroupsOfOrg(org: $org, kind: $kind) {
            uuid name description org status kind apiKeys
            apiKeyDetails { uuid keyOrder type status notes declaredName }
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`

/** The two kinds of API key group (FDAW-25 3.4): INTERNAL holds Free Form keys, CUSTOMER external keys. */
export type ApiKeyGroupKind = 'INTERNAL' | 'CUSTOMER'

/**
 * A group's kind as the server reads it: CUSTOMER when it says so, INTERNAL otherwise (an absent
 * kind, from a server or a stored list older than the kind, reads INTERNAL). The views filter on it
 * as a display guard, so a group never shows on the other kind's view.
 */
export function kindOf (g: any): ApiKeyGroupKind {
    return g?.kind === 'CUSTOMER' ? 'CUSTOMER' : 'INTERNAL'
}

/**
 * Loads the groups of one kind; kind is required, so no caller lists both kinds by omission. A
 * schema-drift error (a server without API key groups or without the kind, e.g. CE before the
 * mirror) answers supported false with no groups; every other error (auth, transport, server) is
 * rethrown so the caller shows it. The client is a parameter so a spec passes a stub.
 */
export async function loadApiKeyGroups (client: { query: Function }, org: string, kind: ApiKeyGroupKind): Promise<{ groups: any[], supported: boolean }> {
    try {
        const resp: any = await client.query({
            query: API_KEY_GROUPS_OF_ORG_QUERY,
            variables: { org, kind },
            fetchPolicy: 'no-cache'
        })
        return { groups: resp?.data?.apiKeyGroupsOfOrg || [], supported: true }
    } catch (e: any) {
        if (isSchemaDriftError(e)) return { groups: [], supported: false }
        throw e
    }
}
