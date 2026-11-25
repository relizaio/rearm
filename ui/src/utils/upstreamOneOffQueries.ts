// The documents of ingesting one release by its TEI (ReARM Pro, TPF-185 design 3.10): the
// one-offs of the organization, one one-off, the resolve of a TEI, the choice among candidates and
// the refresh. Pro only: they are sent only behind upstreamSourcesAvailable, and no document CE
// runs selects any of this.
//
// Its own module that imports nothing but gql, so upstreamQueriesSchemaDrift.spec.ts and
// scripts/validate-graphql read these documents as they are sent. The TEI travels as a variable
// to the backend and nowhere else.

import gql from 'graphql-tag'

// The selection is a plain string, not a gql fragment, so that scripts/validate-graphql expands
// it into every document that uses it and validates the whole selection.
export const UPSTREAM_ONE_OFF_FIELDS = `
        uuid tei state failure sourceFailure source unmatchedRoots sourceCanBeAdded createdDate
        candidates { upstreamRelease source root }
        release { uuid item upstreamUuid upstreamVersion state failure message release oneOff }
        lastRun { uuid state stopReason message finishedDate }`

export const GET_UPSTREAM_ONE_OFFS = gql`
    query upstreamOneOffs($org: ID!) {
        upstreamOneOffs(org: $org, limit: 50) { ${UPSTREAM_ONE_OFF_FIELDS} }
    }`

export const GET_UPSTREAM_ONE_OFF = gql`
    query upstreamOneOff($uuid: ID!) {
        upstreamOneOff(uuid: $uuid) { ${UPSTREAM_ONE_OFF_FIELDS} }
    }`

export const RESOLVE_UPSTREAM_TEI = gql`
    mutation resolveUpstreamTei($org: ID!, $tei: String!, $source: ID) {
        resolveUpstreamTei(org: $org, tei: $tei, source: $source) { ${UPSTREAM_ONE_OFF_FIELDS} }
    }`

export const CHOOSE_UPSTREAM_ONE_OFF = gql`
    mutation chooseUpstreamOneOff($oneOff: ID!, $upstreamRelease: ID!, $source: ID!) {
        chooseUpstreamOneOff(oneOff: $oneOff, upstreamRelease: $upstreamRelease, source: $source) { ${UPSTREAM_ONE_OFF_FIELDS} }
    }`

export const REFRESH_UPSTREAM_ONE_OFF = gql`
    mutation refreshUpstreamOneOff($oneOff: ID!) {
        refreshUpstreamOneOff(oneOff: $oneOff) { ${UPSTREAM_ONE_OFF_FIELDS} }
    }`
