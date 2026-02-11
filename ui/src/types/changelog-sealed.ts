/**
 * Sealed Interface Changelog Types
 * These types match the new backend GraphQL sealed interface (union type)
 */

import { SbomChangesWithAttribution, FindingChangesWithAttribution } from './changelog-attribution'

/**
 * Release metadata
 */
export interface ReleaseInfo {
    uuid: string
    version: string
    lifecycle: string
}

/**
 * Individual code commit
 */
export interface CodeCommit {
    commitId: string | null
    commitUri: string | null
    message: string
    author: string | null
    email: string | null
    changeType: string
}

/**
 * Structured SBOM artifact info for NONE mode display.
 */
export interface ReleaseSbomArtifact {
    purl: string
    name?: string
    version?: string
}

/**
 * SBOM changes for a single release (NONE mode)
 */
export interface ReleaseSbomChanges {
    addedArtifacts: ReleaseSbomArtifact[]
    removedArtifacts: ReleaseSbomArtifact[]
}

/**
 * Lightweight vulnerability info for per-release NONE mode display.
 */
export interface ReleaseVulnerabilityInfo {
    vulnId: string
    purl?: string
    severity?: string
    aliases?: { aliasId: string }[]
}

/**
 * Lightweight violation info for per-release NONE mode display.
 */
export interface ReleaseViolationInfo {
    type: string
    purl?: string
}

/**
 * Lightweight weakness info for per-release NONE mode display.
 */
export interface ReleaseWeaknessInfo {
    cweId: string
    severity?: string
    ruleId?: string
    location?: string
}

/**
 * Finding changes for a single release (NONE mode)
 */
export interface ReleaseFindingChanges {
    appearedCount: number
    resolvedCount: number
    appearedVulnerabilities: ReleaseVulnerabilityInfo[]
    resolvedVulnerabilities: ReleaseVulnerabilityInfo[]
    appearedViolations: ReleaseViolationInfo[]
    resolvedViolations: ReleaseViolationInfo[]
    appearedWeaknesses: ReleaseWeaknessInfo[]
    resolvedWeaknesses: ReleaseWeaknessInfo[]
}

/**
 * All changes for a single release (NONE mode).
 * Embeds code, SBOM, and finding changes in one self-contained record.
 */
export interface NoneReleaseChanges {
    releaseUuid: string
    version: string
    lifecycle: string
    commits: CodeCommit[]
    sbomChanges: ReleaseSbomChanges
    findingChanges: ReleaseFindingChanges
}

/**
 * Branch changes for NONE mode (per-release breakdown)
 */
export interface NoneBranchChanges {
    branchUuid: string
    branchName: string
    releases: NoneReleaseChanges[]
}

/**
 * Commits grouped by type (AGGREGATED mode)
 */
export interface CommitsByType {
    changeType: string
    commits: CodeCommit[]
}

/**
 * Branch changes for AGGREGATED mode (commits grouped by type)
 */
export interface AggregatedBranchChanges {
    branchUuid: string
    branchName: string
    commitsByType: CommitsByType[]
}

/**
 * NONE mode changelog (per-release breakdown)
 */
export interface NoneChangelog {
    __typename: 'NoneChangelog'
    componentUuid: string
    componentName: string
    orgUuid: string
    firstRelease: ReleaseInfo
    lastRelease: ReleaseInfo
    branches: NoneBranchChanges[]
}

/**
 * AGGREGATED mode changelog (component-level summary)
 */
export interface AggregatedChangelog {
    __typename: 'AggregatedChangelog'
    componentUuid: string
    componentName: string
    orgUuid: string
    firstRelease: ReleaseInfo
    lastRelease: ReleaseInfo
    branches: AggregatedBranchChanges[]
    sbomChanges: SbomChangesWithAttribution
    findingChanges: FindingChangesWithAttribution
}

/**
 * Union type for component changelog
 */
export type ComponentChangelog = NoneChangelog | AggregatedChangelog

/**
 * NONE mode organization changelog (per-component, per-release breakdown)
 */
export interface NoneOrganizationChangelog {
    __typename: 'NoneOrganizationChangelog'
    orgUuid: string
    dateFrom: string
    dateTo: string
    components: ComponentChangelog[]
}

/**
 * AGGREGATED mode organization changelog (org-wide summary with attribution)
 */
export interface AggregatedOrganizationChangelog {
    __typename: 'AggregatedOrganizationChangelog'
    orgUuid: string
    dateFrom: string
    dateTo: string
    components: ComponentChangelog[]
    sbomChanges: SbomChangesWithAttribution
    findingChanges: FindingChangesWithAttribution
}

/**
 * Union type for organization changelog
 */
export type OrganizationChangelog = NoneOrganizationChangelog | AggregatedOrganizationChangelog

/**
 * Type guard to check if changelog is NONE mode
 */
export function isNoneChangelog(changelog: ComponentChangelog): changelog is NoneChangelog {
    return changelog.__typename === 'NoneChangelog'
}

/**
 * Type guard to check if changelog is AGGREGATED mode
 */
export function isAggregatedChangelog(changelog: ComponentChangelog): changelog is AggregatedChangelog {
    return changelog.__typename === 'AggregatedChangelog'
}

/**
 * Type guard to check if organization changelog is NONE mode
 */
export function isNoneOrganizationChangelog(changelog: OrganizationChangelog): changelog is NoneOrganizationChangelog {
    return changelog.__typename === 'NoneOrganizationChangelog'
}

/**
 * Type guard to check if organization changelog is AGGREGATED mode
 */
export function isAggregatedOrganizationChangelog(changelog: OrganizationChangelog): changelog is AggregatedOrganizationChangelog {
    return changelog.__typename === 'AggregatedOrganizationChangelog'
}

