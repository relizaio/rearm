// The Org Settings perspective members dialog's write (TPF-41).

import gql from 'graphql-tag'
import type { MutationClient } from './setSbomComponentSupport'

/**
 * What the Org Settings members dialog sends (TPF-41): one membership added, the others kept (TPF-40).
 *
 * Never the whole-set replace with a one-element list: that call stores exactly the list it is
 * given, so a component added to one perspective silently left all its others. No capability probe and
 * no fallback either: the dialog is Pro-only, and a backend without this field answers a validation
 * error that the dialog shows, writing nothing, where a fallback would bring the silent replace back.
 */
export const ADD_PERSPECTIVE_TO_COMPONENT = gql`
    mutation addPerspectiveToComponent($componentUuid: ID!, $perspectiveUuid: ID!) {
        addPerspectiveToComponent(componentUuid: $componentUuid, perspectiveUuid: $perspectiveUuid) {
            uuid
            perspectiveDetails {
                uuid
                name
                org
                createdDate
            }
        }
    }`

/** The component as answered, or null when the server answered no component. Rejections propagate. */
export async function addPerspectiveToComponent (client: MutationClient, componentUuid: string, perspectiveUuid: string): Promise<any | null> {
    const resp = await client.mutate({ mutation: ADD_PERSPECTIVE_TO_COMPONENT, variables: { componentUuid, perspectiveUuid } })
    return resp.data?.addPerspectiveToComponent ?? null
}
