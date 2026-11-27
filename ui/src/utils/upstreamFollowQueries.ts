// The documents of following an upstream item (ReARM Pro, TPF-185 design 3.7 to 3.9): the follows
// of the organization, the start of a follow, the confirmation page, the read of a follow and the
// operation of a follow (its runs, its releases, sync now, reconfigure and unfollow). Pro
// only: they are sent only behind upstreamSourcesAvailable, and no document CE runs selects any of
// this.
//
// Its own module that imports nothing but gql, so upstreamQueriesSchemaDrift.spec.ts and
// scripts/validate-graphql read these documents as they are sent. Inputs always travel as
// variables of the declared input types.
//
// CONFIRM_UPSTREAM_FOLLOW is imported by the confirmation page only (design 3.8): no other screen
// follows anything.

import gql from 'graphql-tag'

// The selections are plain strings, not gql fragments, so that scripts/validate-graphql expands
// them into every document that uses them and validates the whole selection (the
// SINGLE_RELEASE_GQL_DATA pattern of graphqlQueries.ts).
export const UPSTREAM_LINE_PROPOSAL_FIELDS = `
        lineKey branchName releases lowestVersion highestVersion newestDate`

export const UPSTREAM_ITEM_PROPOSAL_FIELDS = `
        kind upstreamUuid upstreamName rearmName nameCollides
        existingItem { uuid component depth depthOrigin updateState }
        adoptionCandidates { component name releases releasesWithUpstreamVersion }
        releases finalReleases preReleases releasesNotStatingPreRelease releasesWithDate releasesWithUnusableVersion
        preReleaseRecognition proposedDepth depthBasis
        depths { depth fit lineCount unplaceable linesTruncated lines { ${UPSTREAM_LINE_PROPOSAL_FIELDS} } }
        failure`

export const UPSTREAM_ITEM_FIELDS = `
        uuid kind upstreamUuid upstreamName component depth depthOrigin mapping updateState
        lines { lineKey branch latestVersion latestRearmRelease }`

export const UPSTREAM_RUN_FIELDS = `
        uuid kind origin state stopReason sourceFailure message createdDate startedDate finishedDate sync
        counters {
            releasesInScope created adopted alreadyIngested skippedPreReleases failed failedComponentRefs documentsPending
            filesStored filesNotKept bytes checked newCollectionVersions gone repaired held
        }`

// ---- The Follows tab (design 3.7) ----

export const GET_UPSTREAM_FOLLOWS = gql`
    query upstreamFollows($org: ID!, $source: ID, $statuses: [UpstreamFollowStatus!]) {
        upstreamFollows(org: $org, source: $source, statuses: $statuses) {
            uuid source status onDrift createdDate unfollowedDate pendingCount
            item { uuid kind upstreamUuid upstreamName component depth depthOrigin updateState }
            lastRun { uuid kind origin state stopReason finishedDate }
        }
    }`

export const PREPARE_UPSTREAM_FOLLOW = gql`
    mutation prepareUpstreamFollow($source: ID!, $kind: UpstreamItemKind!, $upstreamUuid: ID!) {
        prepareUpstreamFollow(source: $source, kind: $kind, upstreamUuid: $upstreamUuid) { uuid state }
    }`

// ---- The confirmation page (design 3.8) ----

export const GET_UPSTREAM_FOLLOW_PREPARATION = gql`
    query upstreamFollowPreparation($uuid: ID!) {
        upstreamFollowPreparation(uuid: $uuid) {
            uuid org source state failure sourceFailure message createdDate finishedDate expires
            componentsTruncated maxBackfillReleases
            item { ${UPSTREAM_ITEM_PROPOSAL_FIELDS} }
            components { ${UPSTREAM_ITEM_PROPOSAL_FIELDS} }
        }
    }`

export const GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES = gql`
    query upstreamFollowPreparationReleases($preparation: ID!, $after: String) {
        upstreamFollowPreparationReleases(preparation: $preparation, first: 50, after: $after) {
            total endCursor
            releases { upstreamUuid version date preRelease usable }
        }
    }`

export const GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE = gql`
    query upstreamFollowPreparationScope($preparation: ID!, $depth: Int!, $backfill: UpstreamBackfillInput!) {
        upstreamFollowPreparationScope(preparation: $preparation, depth: $depth, backfill: $backfill) {
            releases withinLimit onBaseBranch skippedPreReleases linesTruncated
            lines { ${UPSTREAM_LINE_PROPOSAL_FIELDS} }
        }
    }`

export const CONFIRM_UPSTREAM_FOLLOW = gql`
    mutation confirmUpstreamFollow($preparation: ID!, $follow: UpstreamFollowConfirmInput!) {
        confirmUpstreamFollow(preparation: $preparation, follow: $follow) { uuid }
    }`

/** The uuid of the follow a confirm answered with, or null. The answer's field is named here only. */
export function confirmedFollowUuid (data: any): string | null {
    const uuid = data?.confirmUpstreamFollow?.uuid
    return typeof uuid === 'string' ? uuid : null
}

// ---- The follow page (design 3.9) ----

export const GET_UPSTREAM_FOLLOW = gql`
    query upstreamFollow($uuid: ID!) {
        upstreamFollow(uuid: $uuid) {
            uuid org source status onDrift createdDate unfollowedDate pendingCount
            backfill { kind count date release releaseVersion }
            item { ${UPSTREAM_ITEM_FIELDS} }
            components { ${UPSTREAM_ITEM_FIELDS} }
            lastRun { ${UPSTREAM_RUN_FIELDS} }
            pending(limit: 50) { uuid item upstreamUuid upstreamVersion upstreamDate drift }
        }
    }`

// ---- Operating a follow (design 3.9, sections 4 to 8 and the reconfigure dialog) ----

/** The newest 20 runs of a follow; the backend serves the newest runs only. */
export const GET_UPSTREAM_INGEST_RUNS = gql`
    query upstreamIngestRuns($follow: ID!) {
        upstreamIngestRuns(follow: $follow, first: 20) { ${UPSTREAM_RUN_FIELDS} }
    }`

/**
 * The upstream release rows of one item, 50 at a time, in the order they were made. after is the
 * number of rows already loaded, as a decimal string. release is the ReARM release of a row and the
 * only field of a row that may be a link target.
 */
export const GET_UPSTREAM_RELEASES = gql`
    query upstreamReleases($item: ID!, $states: [UpstreamReleaseState!], $after: String) {
        upstreamReleases(item: $item, states: $states, first: 50, after: $after) {
            uuid upstreamUuid upstreamVersion upstreamDate state failure message integrity presence goneSince drift lineKey release
        }
    }`

export const START_UPSTREAM_FOLLOW_RUN = gql`
    mutation startUpstreamFollowRun($follow: ID!) {
        startUpstreamFollowRun(follow: $follow) { uuid }
    }`

export const RECONFIGURE_UPSTREAM_FOLLOW = gql`
    mutation reconfigureUpstreamFollow($follow: ID!, $input: UpstreamFollowReconfigureInput!) {
        reconfigureUpstreamFollow(follow: $follow, input: $input) { uuid }
    }`

export const UNFOLLOW_UPSTREAM_ITEM = gql`
    mutation unfollowUpstreamItem($follow: ID!) {
        unfollowUpstreamItem(follow: $follow) { uuid }
    }`
