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
    query apiKeyGroupsOfOrg($org: ID!) {
        apiKeyGroupsOfOrg(org: $org) {
            uuid name description org status apiKeys
            apiKeyDetails { uuid keyOrder type status notes declaredName }
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`

/**
 * Loads the groups. A schema-drift error (a server without API key groups, e.g. CE) answers
 * supported false with no groups; every other error (auth, transport, server) is rethrown so the
 * caller shows it. The client is a parameter so a spec passes a stub.
 */
export async function loadApiKeyGroups (client: { query: Function }, org: string): Promise<{ groups: any[], supported: boolean }> {
    try {
        const resp: any = await client.query({
            query: API_KEY_GROUPS_OF_ORG_QUERY,
            variables: { org },
            fetchPolicy: 'no-cache'
        })
        return { groups: resp?.data?.apiKeyGroupsOfOrg || [], supported: true }
    } catch (e: any) {
        if (isSchemaDriftError(e)) return { groups: [], supported: false }
        throw e
    }
}
