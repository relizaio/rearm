// Release SBOM-components query.

import gql from 'graphql-tag'

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
            }
            artifactParticipations {
                artifact
                exactPurls
            }
        }
    }`

export interface SbomComponentsLoad {
    rows: any[]
}

/**
 * Load a release's SBOM components.
 */
export async function loadSbomComponentsForRelease (
    client: { query: (options: any) => Promise<any> },
    releaseUuid: string,
): Promise<SbomComponentsLoad> {
    const resp = await client.query({
        query: SBOM_COMPONENTS_QUERY,
        variables: { releaseUuid },
        fetchPolicy: 'cache-first'
    })
    return { rows: (resp.data as any)?.getReleaseSbomComponents || [] }
}
