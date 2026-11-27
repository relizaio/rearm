// The list of what a saved upstream source offers, read for the start of a follow (ReARM Pro).
// Pro only: it is sent only from the start of a follow, which its hosts mount for an administrator
// of the organization behind the edition gate.
//
// Its own module that imports nothing but gql, so upstreamQueriesSchemaDrift.spec.ts and
// scripts/validate-graphql read the document as it is sent. Every argument travels as a variable.
// The page token is an opaque value of the source: the screen keeps it in the set it came with and
// sends it back unchanged.

import gql from 'graphql-tag'

export const GET_UPSTREAM_OFFERED_ITEMS = gql`
    query upstreamOfferedItems($source: ID!, $kind: UpstreamItemKind!, $identifier: UpstreamIdentifierInput, $pageSize: Int, $pageToken: String) {
        upstreamOfferedItems(source: $source, kind: $kind, identifier: $identifier, pageSize: $pageSize, pageToken: $pageToken) {
            source kind failure sourceFailure message
            items { upstreamUuid name identifiers { idType idValue } identifiersDropped activeFollow }
            next nextPageToken rowsReceived rowsDropped cut
        }
    }`

/** The page of an answer to GET_UPSTREAM_OFFERED_ITEMS, or null; the field is named in this module only. */
export function offeredPageOf (data: any): any | null {
    return data?.upstreamOfferedItems ?? null
}
