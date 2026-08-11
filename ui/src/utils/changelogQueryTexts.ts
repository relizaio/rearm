/**
 * Changelog GraphQL document texts (fragments + full query strings).
 *
 * Deliberately free of any Apollo-client / runtime import so the vitest
 * schema-drift spec (changelogSchemaDrift.spec.ts) can load it in a plain
 * node environment; changelogQueries.ts owns the client-facing fetchers.
 */
// Fields newer than the oldest supported CE-mirror backend are declared on
// single lines tagged with this GraphQL comment. When the deployed schema
// rejects the full document (mirror lag), the tagged lines are stripped and
// the query retried - same FULL->CORE degradation as notificationInboxQuery,
// but derived from one source instead of hand-maintained fragment pairs.
// RULE: a tagged selection must be self-contained on its one line (inline the
// object body, no multi-line fragment interpolation).
// Currently NO line is tagged: the CE mirror caught up with the re-scan
// visibility fields (scanArrivalDate/carriers/baselineRelease), so they were
// untagged and the fallback is dormant. Tag future Pro-first fields the same
// way to re-arm it; changelogSchemaDrift.spec.ts keeps the stripped documents
// valid against the CE mirror either way.
const ADDITIVE_FIELD_MARKER = '#additive-field'

export function stripAdditiveFields(queryText: string): string {
    return queryText.split('\n').filter((line) => !line.includes(ADDITIVE_FIELD_MARKER)).join('\n')
}

// One-line ComponentAttribution selection for single-line carrier fields
// (a tagged line cannot interpolate the multi-line COMPONENT_ATTRIBUTION_FRAGMENT).
const ATTRIBUTION_INLINE = 'componentUuid componentName releaseUuid releaseVersion branchUuid branchName'

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
export const OVER_TIME_FINDING_CHANGES_FRAGMENT = `
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
    baselineRelease
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

export const COMPONENT_ATTRIBUTION_FRAGMENT = `
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
        scanArrivalDate
        earliestCarrier { ${ATTRIBUTION_INLINE} }
        latestCarrier { ${ATTRIBUTION_INLINE} }
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
        scanArrivalDate
        earliestCarrier { ${ATTRIBUTION_INLINE} }
        latestCarrier { ${ATTRIBUTION_INLINE} }
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
        scanArrivalDate
        earliestCarrier { ${ATTRIBUTION_INLINE} }
        latestCarrier { ${ATTRIBUTION_INLINE} }
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

// Query texts are module-level (and exported for the schema-drift spec, which
// asserts the full text validates against Pro and the additive-stripped text
// against the CE mirror). Exported via __changelogQueryTestables below.

export const COMPONENT_CHANGELOG_QUERY = `
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
        `

export const COMPONENT_CHANGELOG_BY_DATE_QUERY = `
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
        `

export const ORGANIZATION_CHANGELOG_BY_DATE_QUERY = `
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
        `

