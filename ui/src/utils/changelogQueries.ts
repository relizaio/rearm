/**
 * GraphQL queries for the new sealed interface changelog API
 */

import { gql } from '@apollo/client/core'
import graphqlClient from './graphql'
import { ComponentChangelog, OrganizationChangelog } from '../types/changelog-sealed'
import { isSchemaDriftError } from './graphqlDriftFallback'
import {
    stripAdditiveFields,
    COMPONENT_CHANGELOG_QUERY,
    COMPONENT_CHANGELOG_BY_DATE_QUERY,
    ORGANIZATION_CHANGELOG_BY_DATE_QUERY,
    COMPONENT_ATTRIBUTION_FRAGMENT,
    OVER_TIME_FINDING_CHANGES_FRAGMENT
} from './changelogQueryTexts'

// Try the full document first; if the deployed schema rejects it (CE mirror
// lag), strip the #additive-field-tagged lines and retry. See
// changelogQueryTexts.ts for the tagging rule and the drift spec pinning it.
async function queryWithAdditiveFallback(queryText: string, variables: Record<string, any>): Promise<any> {
    try {
        return await graphqlClient.query({ query: gql(queryText), variables, fetchPolicy: 'no-cache' })
    } catch (err) {
        if (!isSchemaDriftError(err)) throw err
        return await graphqlClient.query({ query: gql(stripAdditiveFields(queryText)), variables, fetchPolicy: 'no-cache' })
    }
}

/**
 * Fetch component changelog between two releases
 */
export async function fetchComponentChangelog(params: {
    release1: string
    release2: string
    org: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
}): Promise<ComponentChangelog> {
    const response = await queryWithAdditiveFallback(COMPONENT_CHANGELOG_QUERY, {
        release1: params.release1,
        release2: params.release2,
        org: params.org,
        aggregated: params.aggregated,
        timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
    })

    return (response.data as any).componentChangelog as ComponentChangelog
}

/**
 * Fetch component changelog by date range
 */
export async function fetchComponentChangelogByDate(params: {
    componentUuid: string
    branchUuid?: string
    org: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
    dateFrom: string
    dateTo: string
}): Promise<ComponentChangelog> {
    const response = await queryWithAdditiveFallback(COMPONENT_CHANGELOG_BY_DATE_QUERY, {
        componentUuid: params.componentUuid,
        branchUuid: params.branchUuid || null,
        org: params.org,
        aggregated: params.aggregated,
        timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone,
        dateFrom: params.dateFrom,
        dateTo: params.dateTo
    })

    return (response.data as any).componentChangelogByDate as ComponentChangelog
}

/**
 * Fetch organization changelog by date range
 */
export async function fetchOrganizationChangelogByDate(params: {
    orgUuid: string
    perspectiveUuid?: string
    dateFrom: string
    dateTo: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
}): Promise<OrganizationChangelog> {
    const response = await queryWithAdditiveFallback(ORGANIZATION_CHANGELOG_BY_DATE_QUERY, {
        orgUuid: params.orgUuid,
        perspectiveUuid: params.perspectiveUuid || null,
        dateFrom: params.dateFrom,
        dateTo: params.dateTo,
        aggregated: params.aggregated,
        timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
    })

    return (response.data as any).organizationChangelogByDate as OrganizationChangelog
}

// ========== Drill-down: full attribution / timeline behind the capped inline previews ==========

export interface ComponentAttributionEntry {
    componentUuid: string
    componentName: string
    releaseUuid: string
    releaseVersion: string
    branchUuid?: string
    branchName?: string
}

export interface ComponentAttributionPage {
    items: ComponentAttributionEntry[]
    total: number
    page: number
    pageSize: number
}

/**
 * Pages one finding's FULL attribution for a bucket (the "+N more" behind the capped inline list).
 * `total` equals the *InCount shown inline. Scope: org (only orgUuid) / component (+componentUuid) /
 * branch (+componentUuid+branchUuid).
 */
export async function fetchFindingAttribution(params: {
    orgUuid: string
    componentUuid?: string
    branchUuid?: string
    perspectiveUuid?: string
    dateFrom: string
    dateTo: string
    findingKind: 'VULNERABILITY' | 'VIOLATION' | 'WEAKNESS'
    findingKey: string
    bucket: 'APPEARED' | 'PRESENT' | 'RESOLVED'
    page?: number
    pageSize?: number
}): Promise<ComponentAttributionPage> {
    const response = await graphqlClient.query({
        query: gql`
            query FetchFindingAttribution(
                $orgUuid: ID!
                $componentUuid: ID
                $branchUuid: ID
                $perspectiveUuid: ID
                $dateFrom: DateTime!
                $dateTo: DateTime!
                $findingKind: ChangelogFindingKind!
                $findingKey: String!
                $bucket: FindingAttributionBucket!
                $page: Int
                $pageSize: Int
            ) {
                findingAttributionByDate(
                    orgUuid: $orgUuid
                    componentUuid: $componentUuid
                    branchUuid: $branchUuid
                    perspectiveUuid: $perspectiveUuid
                    dateFrom: $dateFrom
                    dateTo: $dateTo
                    findingKind: $findingKind
                    findingKey: $findingKey
                    bucket: $bucket
                    page: $page
                    pageSize: $pageSize
                ) {
                    total
                    page
                    pageSize
                    items {
                        ${COMPONENT_ATTRIBUTION_FRAGMENT}
                    }
                }
            }
        `,
        variables: {
            orgUuid: params.orgUuid,
            componentUuid: params.componentUuid || null,
            branchUuid: params.branchUuid || null,
            perspectiveUuid: params.perspectiveUuid || null,
            dateFrom: params.dateFrom,
            dateTo: params.dateTo,
            findingKind: params.findingKind,
            findingKey: params.findingKey,
            bucket: params.bucket,
            page: params.page ?? 0,
            pageSize: params.pageSize ?? 50
        },
        fetchPolicy: 'no-cache'
    })
    return (response.data as any).findingAttributionByDate as ComponentAttributionPage
}

export interface MetricsRevisionFindingChangePage {
    items: any[]
    total: number
    page: number
    pageSize: number
    since?: string
    // F4: true when total is a floor (org ALL_POSTURE scan hit its cap). Render total as 'N+'.
    // Optional so a backend that doesn't yet return it degrades gracefully (treated as false).
    truncated?: boolean
}

// Read scope for the over-time timeline. RELEASE_ANCHORED (default) = events on releases produced in the
// window; ALL_POSTURE = also re-scan-driven changes on releases shipped before the window (org scope only).
export type FindingChangeScope = 'RELEASE_ANCHORED' | 'ALL_POSTURE'

/**
 * Pages the over-time finding-change timeline behind the capped inline overTimeFindingChanges. Optional
 * findingKey narrows to one finding's timeline (drawer). Newest-first.
 */
export async function fetchFindingChangeTimeline(params: {
    orgUuid: string
    componentUuid?: string
    branchUuid?: string
    perspectiveUuid?: string
    dateFrom: string
    dateTo: string
    findingKey?: string
    page?: number
    pageSize?: number
    scope?: FindingChangeScope
}): Promise<MetricsRevisionFindingChangePage> {
    const response = await graphqlClient.query({
        query: gql`
            query FetchFindingChangeTimeline(
                $orgUuid: ID!
                $componentUuid: ID
                $branchUuid: ID
                $perspectiveUuid: ID
                $dateFrom: DateTime!
                $dateTo: DateTime!
                $findingKey: String
                $page: Int
                $pageSize: Int
                $scope: FindingChangeScope
            ) {
                findingChangeTimelineByDate(
                    orgUuid: $orgUuid
                    componentUuid: $componentUuid
                    branchUuid: $branchUuid
                    perspectiveUuid: $perspectiveUuid
                    dateFrom: $dateFrom
                    dateTo: $dateTo
                    findingKey: $findingKey
                    page: $page
                    pageSize: $pageSize
                    scope: $scope
                ) {
                    total
                    page
                    pageSize
                    since
                    truncated
                    items {
                        ${OVER_TIME_FINDING_CHANGES_FRAGMENT}
                    }
                }
            }
        `,
        variables: {
            orgUuid: params.orgUuid,
            componentUuid: params.componentUuid || null,
            branchUuid: params.branchUuid || null,
            perspectiveUuid: params.perspectiveUuid || null,
            dateFrom: params.dateFrom,
            dateTo: params.dateTo,
            findingKey: params.findingKey || null,
            page: params.page ?? 0,
            pageSize: params.pageSize ?? 50,
            scope: params.scope ?? null
        },
        fetchPolicy: 'no-cache'
    })
    return (response.data as any).findingChangeTimelineByDate as MetricsRevisionFindingChangePage
}

