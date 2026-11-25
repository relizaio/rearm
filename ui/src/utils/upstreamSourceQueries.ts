// The documents of the Sources tab (ReARM Pro, TPF-185 design 3.6). Pro only: they are sent only
// behind upstreamSourcesAvailable, and no document CE runs selects any of this.
//
// Its own module that imports nothing but gql, so upstreamQueriesSchemaDrift.spec.ts and
// scripts/validate-graphql read these documents as they are sent. Inputs always travel as
// variables of the declared input types. No selection here can carry a stored credential: the
// schema serves its kind, its set date and, to administrators, the key id only.

import gql from 'graphql-tag'

// The selections are plain strings, not gql fragments, so that scripts/validate-graphql expands
// them into every document that uses them and validates the whole selection (the
// SINGLE_RELEASE_GQL_DATA pattern of graphqlQueries.ts).
export const UPSTREAM_SYNC_FIELDS = `
        uuid origin state stopReason sourceFailure createdDate finished
        counters { follows runsCompleted runsWithFailures runsStopped ingested newCollectionVersions held failed }`

export const UPSTREAM_SOURCE_FIELDS = `
        uuid org kind name status documentClasses vexImportMode autoIntegrate
        tea { root }
        credential { kind keyId setDate }
        probe { state result message requestedDate finishedDate }
        createdDate lastUpdatedDate archivedDate syncIntervalMinutes nextSync syncFailures
        lastSync { ${UPSTREAM_SYNC_FIELDS} }
        documentReServing`

export const GET_UPSTREAM_SOURCES = gql`
    query upstreamSources($org: ID!, $statuses: [UpstreamSourceStatus!]) {
        upstreamSources(org: $org, statuses: $statuses) { ${UPSTREAM_SOURCE_FIELDS} }
    }`

export const GET_UPSTREAM_SOURCE = gql`
    query upstreamSource($uuid: ID!) {
        upstreamSource(uuid: $uuid) { ${UPSTREAM_SOURCE_FIELDS} }
    }`

export const GET_UPSTREAM_SYNCS = gql`
    query upstreamSyncs($source: ID!) {
        upstreamSyncs(source: $source, limit: 10) { ${UPSTREAM_SYNC_FIELDS} }
    }`

export const CREATE_UPSTREAM_SOURCE = gql`
    mutation createUpstreamSource($org: ID!, $source: UpstreamSourceInput!) {
        createUpstreamSource(org: $org, source: $source) { ${UPSTREAM_SOURCE_FIELDS} }
    }`

export const UPDATE_UPSTREAM_SOURCE = gql`
    mutation updateUpstreamSource($uuid: ID!, $source: UpstreamSourceUpdateInput!) {
        updateUpstreamSource(uuid: $uuid, source: $source) { ${UPSTREAM_SOURCE_FIELDS} }
    }`

export const PROBE_UPSTREAM_SOURCE = gql`
    mutation probeUpstreamSource($uuid: ID!) {
        probeUpstreamSource(uuid: $uuid) { ${UPSTREAM_SOURCE_FIELDS} }
    }`

export const SYNC_UPSTREAM_SOURCE_NOW = gql`
    mutation syncUpstreamSourceNow($source: ID!) {
        syncUpstreamSourceNow(source: $source) { ${UPSTREAM_SOURCE_FIELDS} }
    }`

export const ARCHIVE_UPSTREAM_SOURCE = gql`
    mutation archiveUpstreamSource($uuid: ID!) {
        archiveUpstreamSource(uuid: $uuid) { ${UPSTREAM_SOURCE_FIELDS} }
    }`
