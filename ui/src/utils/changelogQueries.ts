/**
 * GraphQL queries for the new sealed interface changelog API
 */

import { gql } from '@apollo/client/core'
import graphqlClient from './graphql'
import { ComponentChangelog, OrganizationChangelog } from '../types/changelog-sealed'
import { kevFieldSelection } from './kevService'

// Fragments selecting vulnerability findings interpolate kevFieldSelection so
// the knownExploited flag rides the main changelog query (cheaper than a
// mirror query on top of the already-expensive changelog computation).

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

const releaseFindingChangesFragment = (kevField: string) => `
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
        ${kevField}
    }
    resolvedVulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        analysisState
        ${kevField}
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

const noneReleaseChangesFragment = (kevField: string) => `
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
        ${releaseFindingChangesFragment(kevField)}
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
        addedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        removedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
    }
`

const findingChangesWithAttributionFragment = (kevField: string) => `
    totalAppeared
    totalResolved
    vulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        ${kevField}
        isNetAppeared
        isNetResolved
        isStillPresent
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
        type
        purl
        isNetAppeared
        isNetResolved
        isStillPresent
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
        cweId
        severity
        ruleId
        location
        isNetAppeared
        isNetResolved
        isStillPresent
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

const noneBranchChangesFragment = (kevField: string) => `
    branchUuid
    branchName
    componentUuid
    componentName
    changeType
    releases {
        ${noneReleaseChangesFragment(kevField)}
    }
`

const noneChangelogFields = (kevField: string) => `
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
        ${noneBranchChangesFragment(kevField)}
    }
`

const noneProductChangelogFields = (kevField: string) => `
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
            ${noneBranchChangesFragment(kevField)}
        }
    }
`

const aggregatedChangelogFields = (kevField: string) => `
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
        ${findingChangesWithAttributionFragment(kevField)}
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
    installationType?: string
}): Promise<ComponentChangelog> {
    const kevField = kevFieldSelection(params.installationType)
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
                        ${noneChangelogFields(kevField)}
                    }
                    ... on NoneProductChangelog {
                        ${noneProductChangelogFields(kevField)}
                    }
                    ... on AggregatedChangelog {
                        ${aggregatedChangelogFields(kevField)}
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
    installationType?: string
}): Promise<ComponentChangelog> {
    const kevField = kevFieldSelection(params.installationType)
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
                        ${noneChangelogFields(kevField)}
                    }
                    ... on NoneProductChangelog {
                        ${noneProductChangelogFields(kevField)}
                    }
                    ... on AggregatedChangelog {
                        ${aggregatedChangelogFields(kevField)}
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
    installationType?: string
}): Promise<OrganizationChangelog> {
    const kevField = kevFieldSelection(params.installationType)
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
                                ${noneChangelogFields(kevField)}
                            }
                        }
                    }
                    ... on AggregatedOrganizationChangelog {
                        orgUuid
                        dateFrom
                        dateTo
                        components {
                            __typename
                            ... on AggregatedChangelog {
                                ${aggregatedChangelogFields(kevField)}
                            }
                        }
                        sbomChanges {
                            ${SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                        }
                        findingChanges {
                            ${findingChangesWithAttributionFragment(kevField)}
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
