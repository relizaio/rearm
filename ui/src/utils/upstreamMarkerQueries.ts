// The documents of the ingested markers, the read-only snapshot and the source of supplier VEX
// statements (ReARM Pro). Pro only: they are sent only behind upstreamSourcesAvailable. The
// component, release and VEX proposal documents CE also runs stay as they are; what the Pro
// schema adds to those types is read here, by documents of its own, and nowhere else.
//
// Its own module that imports nothing but gql, so upstreamQueriesSchemaDrift.spec.ts and
// scripts/validate-graphql read these documents as they are sent.

import gql from 'graphql-tag'

// Plain strings, not gql fragments, so that scripts/validate-graphql expands them into every
// document that uses them and validates the whole selection.
export const UPSTREAM_ARTIFACT_ORIGIN_FIELDS = `
        source upstreamRelease collectionVersion number presence`

export const UPSTREAM_COLLECTION_FIELDS = `
        version upstreamCreatedDate updateReasonType updateReasonComment ingestedDate
        files { number outcome reason message artifact conflictArtifact upstreamArtifactName upstreamArtifactType documentClass mediaType sizeBytes sha256 }`

export const GET_COMPONENT_INGESTED_FROM = gql`
    query componentIngestedFrom($uuid: ID!) {
        component(componentUuid: $uuid) {
            uuid
            ingestedFrom { uuid source sourceName kind upstreamUuid upstreamName depth depthOrigin mapping updateState }
        }
    }`

export const GET_RELEASE_INGESTED_FROM = gql`
    query releaseIngestedFrom($uuid: ID!, $org: ID) {
        release(releaseUuid: $uuid, orgUuid: $org) {
            uuid
            ingestedFrom {
                uuid item source sourceName upstreamUuid upstreamVersion upstreamDate state integrity updateState presence goneSince lineKey oneOff
                collections { ${UPSTREAM_COLLECTION_FIELDS} }
            }
        }
    }`

/** The upstream item or release an answer of GET_COMPONENT_INGESTED_FROM or GET_RELEASE_INGESTED_FROM names, or null. The field is named here only. */
export function ingestedOriginOf (data: any): any | null {
    return data?.component?.ingestedFrom ?? data?.release?.ingestedFrom ?? null
}

export const GET_MARKER_FOLLOWS = gql`
    query markerFollows($org: ID!, $source: ID) {
        upstreamFollows(org: $org, source: $source, statuses: [ACTIVE]) {
            uuid
            item { uuid }
            components { uuid }
            lastRun { state stopReason finishedDate }
        }
    }`

export const GET_ARTIFACT_VEX_IMPORT = gql`
    query artifactVexImport($uuid: ID!) {
        artifact(artifactUuid: $uuid) {
            uuid
            vexImport { state reason modeUsed storedDate finishedDate statementsTotal proposalsStaged proposalsAccepted statementsUnmatched statementsErrored }
        }
    }`

export const GET_VEX_PROPOSAL_ORIGINS = gql`
    query vexProposalOrigins($org: ID!, $status: ProposalStatus) {
        getVexStatementProposals(org: $org, status: $status) {
            uuid
            ingested { ${UPSTREAM_ARTIFACT_ORIGIN_FIELDS} }
        }
    }`

export const GET_VEX_PROPOSAL_ORIGIN = gql`
    query vexProposalOrigin($uuid: ID!) {
        getVexStatementProposal(uuid: $uuid) {
            uuid
            ingested { ${UPSTREAM_ARTIFACT_ORIGIN_FIELDS} }
        }
    }`
