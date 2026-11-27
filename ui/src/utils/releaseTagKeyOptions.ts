import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'

/**
 * The organization's release tag keys, offered as suggestions by the add-tag select of the
 * release page. That select renders only for users who can write the release, and the query
 * needs an organization-wide read grant, so a reader whose grants are scoped to a release or a
 * component would be refused. The loader therefore asks only for writers and never rejects: a
 * refusal or a failure leaves the list empty, and the select still accepts a typed key.
 */
export const RELEASE_TAG_KEYS_QUERY: DocumentNode = gql`
            query releaseTagKeys($orgId: ID!) {
                releaseTagKeys(orgUuid: $orgId)
            }`

export type TagKeyOption = { label: string, value: string }

/** The part of the GraphQL client the loader uses, so tests can pass a stub. */
export interface ReleaseTagKeysClient {
    query (opts: { query: DocumentNode, variables: Record<string, any> }): Promise<{ data?: any }>
}

export async function loadReleaseTagKeyOptions (
    client: ReleaseTagKeysClient,
    orgUuid: string | null | undefined,
    writable: boolean
): Promise<TagKeyOption[]> {
    if (!writable || !orgUuid) return []
    try {
        const response = await client.query({
            query: RELEASE_TAG_KEYS_QUERY,
            variables: { orgId: orgUuid }
        })
        const keys = response?.data?.releaseTagKeys
        if (!Array.isArray(keys)) return []
        return keys.map((k: string) => ({ label: k, value: k }))
    } catch (err) {
        console.warn('Could not load release tag keys', err)
        return []
    }
}
