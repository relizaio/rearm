// Release SBOM-components query.

import gql from 'graphql-tag'
import type { DriftFallbackClient } from './graphqlDriftFallback'

export const SBOM_COMPONENTS_QUERY = gql`
    query getReleaseSbomComponentsList($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {
            uuid
            sbomComponentUuid
            component {
                uuid
                canonicalPurl
                type
                group
                name
                version
                isRoot
                supportStatus
                supportSource
                endOfSupportDate
                deviceSupportRisk
            }
            artifactParticipations {
                artifact
                exactPurls
            }
        }
    }`

/**
 * Load a release's SBOM components. Always hits the network -- callers
 * (e.g. the post-reconcile refresh) rely on this never serving a stale
 * Apollo-cached response.
 */
export async function loadSbomComponentsForRelease (
    client: DriftFallbackClient,
    releaseUuid: string,
): Promise<any[]> {
    const resp = await client.query({
        query: SBOM_COMPONENTS_QUERY,
        variables: { releaseUuid },
        fetchPolicy: 'network-only'
    })
    return (resp.data as any)?.getReleaseSbomComponents || []
}
