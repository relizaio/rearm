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
            ingestedFrom { uuid source kind upstreamUuid upstreamName depth depthOrigin mapping updateState activeFollow coveringFollows }
        }
    }`

export const GET_RELEASE_INGESTED_FROM = gql`
    query releaseIngestedFrom($uuid: ID!, $org: ID) {
        release(releaseUuid: $uuid, orgUuid: $org) {
            uuid
            ingestedFrom {
                uuid item source upstreamUuid upstreamVersion upstreamDate state integrity updateState presence goneSince lineKey oneOff
                collections { ${UPSTREAM_COLLECTION_FIELDS} }
            }
            componentDetails { ingestedFrom { uuid activeFollow coveringFollows } }
        }
    }`

/** The upstream item or release an answer of GET_COMPONENT_INGESTED_FROM or GET_RELEASE_INGESTED_FROM names, or null. The field is named here only. */
export function ingestedOriginOf (data: any): any | null {
    return data?.component?.ingestedFrom ?? data?.release?.ingestedFrom ?? null
}

/** The upstream item whose follow the marker shows: the item itself, or the item of the release's component, or null. */
export function ingestedItemOf (data: any): any | null {
    return data?.component?.ingestedFrom ?? data?.release?.componentDetails?.ingestedFrom ?? null
}

/** The one follow a field of the item names, by its uuid: the same read the follow page makes first. */
export const GET_MARKER_FOLLOW = gql`
    query markerFollow($uuid: ID!) {
        upstreamFollow(uuid: $uuid) {
            uuid
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
