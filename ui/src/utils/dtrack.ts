import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'

export async function searchDtrackComponentByPurl(
  orgUuid: string,
  purl: string,
  dtrackProjects: string[]
): Promise<string | undefined> {
  let dtrackComponent: string | undefined = undefined
  try {
    const resp = await graphqlClient.query({
      query: gql`
        query searchDtrackComponentByPurlAndProjects($orgUuid: ID!, $purl: String!, $dtrackProjects: [ID]!) {
          searchDtrackComponentByPurlAndProjects(orgUuid: $orgUuid, purl: $purl, dtrackProjects: $dtrackProjects)
        }
      `,
      variables: { orgUuid, purl, dtrackProjects },
      fetchPolicy: 'no-cache'
    })
    dtrackComponent = resp.data?.searchDtrackComponentByPurlAndProjects
  } catch (error) {
    console.error('Error searching Dependency-Track component:', error)
  }
  return dtrackComponent
}

// Native canonical-purl lookup. Returns rearm.sbom_components.uuid (the same
// identity used by releasesBySbomComponents and the sbomComponentUuid field on
// ReleaseSbomComponent). PURL canonicalization is server-side, so callers can
// pass the raw purl as ingested.
export async function searchSbomComponentByPurl(
  orgUuid: string,
  purl: string
): Promise<string | undefined> {
  try {
    const resp = await graphqlClient.query({
      query: gql`
        query searchSbomComponentByPurl($orgUuid: ID!, $purl: String!) {
          searchSbomComponentByPurl(orgUuid: $orgUuid, purl: $purl)
        }
      `,
      variables: { orgUuid, purl },
      // Canonical purl→uuid is a stable mapping; cache-first lets repeated
      // deep-link clicks for the same purl skip the round-trip.
      fetchPolicy: 'cache-first'
    })
    return (resp.data as any)?.searchSbomComponentByPurl ?? undefined
  } catch (error) {
    console.error('Error searching SBOM component by purl:', error)
    return undefined
  }
}
