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
