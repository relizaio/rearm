/**
 * GraphQL queries for the new sealed interface changelog API
 */

import { gql } from '@apollo/client/core'
import graphqlClient from './graphql'
import { ComponentChangelog, OrganizationChangelog } from '../types/changelog-sealed'

// Fragments selecting vulnerability findings inline the knownExploited field
// so the KEV flag rides the main changelog query (cheaper than a mirror
// query on top of the already-expensive changelog computation).

// ========== GraphQL Field Fragments ==========

const RELEASE_INFO_FRAGMENT = `
    __typename
    uuid
    version
    lifecycle
`

const CODE_COMMIT_FRAGMENT = `
    commitId
    commitUri
    message
    author
    email
    changeType
`

const RELEASE_SBOM_CHANGES_FRAGMENT = `
    addedArtifacts {
        purl
        name
        version
    }
    removedArtifacts {
        purl
        name
        version
    }
`

const RELEASE_FINDING_CHANGES_FRAGMENT = `
    appearedCount
    resolvedCount
    appearedVulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        analysisState
        knownExploited
    }
    resolvedVulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        analysisState
        knownExploited
    }
    appearedViolations {
        type
        purl
        analysisState
    }
    resolvedViolations {
        type
        purl
        analysisState
    }
    appearedWeaknesses {
        cweId
        severity
        ruleId
        location
        analysisState
    }
    resolvedWeaknesses {
        cweId
        severity
        ruleId
        location
        analysisState
    }
`

// Over-time finding changes: flat list of re-scan-driven MetricsRevisionFindingChange
// records. Exactly one of vulnerability/violation/weakness is non-null per record;
// previousSeverity is set for SEVERITY_INCREASED and SEVERITY_DECREASED. Selects analysisState on each
// nested finding for parity with the per-release finding-change fragments (so
// suppressed/FALSE_POSITIVE findings render correctly in the drill-down drawer).
const OVER_TIME_FINDING_CHANGES_FRAGMENT = `
    changeDate
    changeKind
    releaseUuid
    version
    componentUuid
    componentName
    branchUuid
    branchName
    previousSeverity
    vulnerability {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        analysisState
        knownExploited
    }
    violation {
        type
        purl
        analysisState
    }
    weakness {
        cweId
        severity
        ruleId
        location
        analysisState
    }
`

const NONE_RELEASE_CHANGES_FRAGMENT = `
    releaseUuid
    version
    lifecycle
    createdDate
    commits {
        ${CODE_COMMIT_FRAGMENT}
    }
    sbomChanges {
        ${RELEASE_SBOM_CHANGES_FRAGMENT}
    }
    findingChanges {
        ${RELEASE_FINDING_CHANGES_FRAGMENT}
    }
`

const COMMITS_BY_TYPE_FRAGMENT = `
    changeType
    commits {
        ${CODE_COMMIT_FRAGMENT}
    }
`

const COMPONENT_ATTRIBUTION_FRAGMENT = `
    componentUuid
    componentName
    releaseUuid
    releaseVersion
    branchUuid
    branchName
`

const ORG_LEVEL_CONTEXT_FRAGMENT = `
    isNewToOrganization
    wasPreviouslyReported
    isPartiallyResolved
    isFullyResolved
    isInheritedInAllComponents
    componentCount
    affectedComponentNames
    isNewlyKev
    isSeverityIncreased
    previousSeverity
`

const SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT = `
    totalAdded
    totalRemoved
    artifacts {
        purl
        name
        version
        isNetAdded
        isNetRemoved
        addedInCount
        removedInCount
        addedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        removedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
    }
`

const FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT = `
    totalAppeared
    totalResolved
    totalNewlyKev
    totalSeverityIncreased
    vulnerabilities {
        findingKey
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        knownExploited
        isNetAppeared
        isNetResolved
        isStillPresent
        appearedInCount
        resolvedInCount
        presentInCount
        appearedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        resolvedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        presentIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        orgContext {
            ${ORG_LEVEL_CONTEXT_FRAGMENT}
        }
        analysisState
    }
    violations {
        findingKey
        type
        purl
        isNetAppeared
        isNetResolved
        isStillPresent
        appearedInCount
        resolvedInCount
        presentInCount
        appearedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        resolvedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        presentIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        orgContext {
            ${ORG_LEVEL_CONTEXT_FRAGMENT}
        }
        analysisState
    }
    weaknesses {
        findingKey
        cweId
        severity
        ruleId
        location
        isNetAppeared
        isNetResolved
        isStillPresent
        appearedInCount
        resolvedInCount
        presentInCount
        appearedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        resolvedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        presentIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        orgContext {
            ${ORG_LEVEL_CONTEXT_FRAGMENT}
        }
        analysisState
    }
`

// ========== Shared Component Changelog Fragments ==========

const NONE_BRANCH_CHANGES_FRAGMENT = `
    branchUuid
    branchName
    componentUuid
    componentName
    changeType
    releases {
        ${NONE_RELEASE_CHANGES_FRAGMENT}
    }
`

const NONE_CHANGELOG_FIELDS = `
    componentUuid
    componentName
    orgUuid
    firstRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    lastRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    branches {
        ${NONE_BRANCH_CHANGES_FRAGMENT}
    }
    overTimeFindingChanges {
        ${OVER_TIME_FINDING_CHANGES_FRAGMENT}
    }
`

const NONE_PRODUCT_CHANGELOG_FIELDS = `
    componentUuid
    componentName
    orgUuid
    firstRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    lastRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    productReleases {
        releaseUuid
        version
        lifecycle
        createdDate
        branches {
            ${NONE_BRANCH_CHANGES_FRAGMENT}
        }
    }
`

const AGGREGATED_CHANGELOG_FIELDS = `
    componentUuid
    componentName
    orgUuid
    firstRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    lastRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    branches {
        branchUuid
        branchName
        componentUuid
        componentName
        firstReleaseUuid
        firstVersion
        lastReleaseUuid
        lastVersion
        changeType
        commitsByType {
            ${COMMITS_BY_TYPE_FRAGMENT}
        }
    }
    sbomChanges {
        ${SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
    }
    findingChanges {
        ${FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
    }
    postureFindingChanges {
        ${FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
    }
    overTimeFindingChanges {
        ${OVER_TIME_FINDING_CHANGES_FRAGMENT}
    }
`

// ========== Query Functions ==========

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
    const response = await graphqlClient.query({
        query: gql`
            query FetchComponentChangelog(
                $release1: ID!
                $release2: ID!
                $org: ID!
                $aggregated: AggregationType!
                $timeZone: String
            ) {
                componentChangelog(
                    release1: $release1
                    release2: $release2
                    orgUuid: $org
                    aggregated: $aggregated
                    timeZone: $timeZone
                ) {
                    __typename
                    ... on NoneChangelog {
                        ${NONE_CHANGELOG_FIELDS}
                    }
                    ... on NoneProductChangelog {
                        ${NONE_PRODUCT_CHANGELOG_FIELDS}
                    }
                    ... on AggregatedChangelog {
                        ${AGGREGATED_CHANGELOG_FIELDS}
                    }
                }
            }
        `,
        variables: {
            release1: params.release1,
            release2: params.release2,
            org: params.org,
            aggregated: params.aggregated,
            timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
        },
        fetchPolicy: 'no-cache'
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
    const response = await graphqlClient.query({
        query: gql`
            query FetchComponentChangelogByDate(
                $componentUuid: ID!
                $branchUuid: ID
                $org: ID!
                $aggregated: AggregationType!
                $timeZone: String
                $dateFrom: DateTime!
                $dateTo: DateTime!
            ) {
                componentChangelogByDate(
                    componentUuid: $componentUuid
                    branchUuid: $branchUuid
                    orgUuid: $org
                    aggregated: $aggregated
                    timeZone: $timeZone
                    dateFrom: $dateFrom
                    dateTo: $dateTo
                ) {
                    __typename
                    ... on NoneChangelog {
                        ${NONE_CHANGELOG_FIELDS}
                    }
                    ... on NoneProductChangelog {
                        ${NONE_PRODUCT_CHANGELOG_FIELDS}
                    }
                    ... on AggregatedChangelog {
                        ${AGGREGATED_CHANGELOG_FIELDS}
                    }
                }
            }
        `,
        variables: {
            componentUuid: params.componentUuid,
            branchUuid: params.branchUuid || null,
            org: params.org,
            aggregated: params.aggregated,
            timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone,
            dateFrom: params.dateFrom,
            dateTo: params.dateTo
        },
        fetchPolicy: 'no-cache'
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
    const response = await graphqlClient.query({
        query: gql`
            query FetchOrganizationChangelogByDate(
                $orgUuid: ID!
                $perspectiveUuid: ID
                $dateFrom: DateTime!
                $dateTo: DateTime!
                $aggregated: AggregationType!
                $timeZone: String
            ) {
                organizationChangelogByDate(
                    orgUuid: $orgUuid
                    perspectiveUuid: $perspectiveUuid
                    dateFrom: $dateFrom
                    dateTo: $dateTo
                    aggregated: $aggregated
                    timeZone: $timeZone
                ) {
                    __typename
                    ... on NoneOrganizationChangelog {
                        orgUuid
                        dateFrom
                        dateTo
                        components {
                            __typename
                            ... on NoneChangelog {
                                ${NONE_CHANGELOG_FIELDS}
                            }
                        }
                        overTimeFindingChanges {
                            ${OVER_TIME_FINDING_CHANGES_FRAGMENT}
                        }
                    }
                    ... on AggregatedOrganizationChangelog {
                        orgUuid
                        dateFrom
                        dateTo
                        components {
                            __typename
                            ... on AggregatedChangelog {
                                ${AGGREGATED_CHANGELOG_FIELDS}
                            }
                        }
                        sbomChanges {
                            ${SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                        }
                        findingChanges {
                            ${FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                        }
                        overTimeFindingChanges {
                            ${OVER_TIME_FINDING_CHANGES_FRAGMENT}
                        }
                    }
                }
            }
        `,
        variables: {
            orgUuid: params.orgUuid,
            perspectiveUuid: params.perspectiveUuid || null,
            dateFrom: params.dateFrom,
            dateTo: params.dateTo,
            aggregated: params.aggregated,
            timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
        },
        fetchPolicy: 'no-cache'
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
}

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
                ) {
                    total
                    page
                    pageSize
                    since
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
            pageSize: params.pageSize ?? 50
        },
        fetchPolicy: 'no-cache'
    })
    return (response.data as any).findingChangeTimelineByDate as MetricsRevisionFindingChangePage
}
