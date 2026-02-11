/**
 * Changelog Attribution Types
 * These types match the new backend GraphQL schema with release-level attribution
 */

/**
 * Component attribution - shows which specific release contributed a change
 */
export interface ComponentAttribution {
    componentUuid: string
    componentName: string
    releaseUuid: string
    releaseVersion: string
    branchUuid: string
    branchName: string | null
}

/**
 * Organization-level context for findings in multi-component changelogs
 * Provides semantic information about how a finding relates to the organization as a whole
 */
export interface OrgLevelContext {
    isNewToOrganization: boolean        // First occurrence across all components in the org
    wasPreviouslyReported: boolean      // Existed in other components when it appeared in new ones
    isPartiallyResolved: boolean        // Resolved in some components, still present in others
    isFullyResolved: boolean            // Resolved everywhere, not in any latest release
    isInheritedInAllComponents: boolean // Present in first AND last release of ALL components (technical debt)
    componentCount: number              // Number of components currently affected
    affectedComponentNames: string[]    // Names of components currently affected
}

/**
 * SBOM artifact with attribution showing which releases added/removed it
 */
export interface ArtifactWithAttribution {
    purl: string
    name: string
    version: string
    addedIn: ComponentAttribution[]
    removedIn: ComponentAttribution[]
    isNetAdded: boolean      // Org-wide: net added across all components
    isNetRemoved: boolean    // Org-wide: net removed across all components
}

/**
 * SBOM changes with attribution (replaces old ArtifactChangelog)
 */
export interface SbomChangesWithAttribution {
    artifacts: ArtifactWithAttribution[]
    totalAdded: number
    totalRemoved: number
}

/**
 * Vulnerability with attribution showing which releases introduced/resolved it
 */
export interface VulnerabilityWithAttribution {
    vulnId: string
    purl: string
    severity: string
    aliases: Array<{ aliasId: string }>
    resolvedIn: ComponentAttribution[]
    appearedIn: ComponentAttribution[]
    presentIn: ComponentAttribution[]
    isNetResolved: boolean   // Org-wide: net resolved across all components
    isNetAppeared: boolean   // Org-wide: net appeared across all components
    isStillPresent: boolean  // Org-wide: still present in some releases
    orgContext?: OrgLevelContext  // Optional org-level semantic context (only for multi-component views)
}

/**
 * Violation with attribution
 */
export interface ViolationWithAttribution {
    type: string
    purl: string
    resolvedIn: ComponentAttribution[]
    appearedIn: ComponentAttribution[]
    presentIn: ComponentAttribution[]
    isNetResolved: boolean
    isNetAppeared: boolean
    isStillPresent: boolean
    orgContext?: OrgLevelContext  // Optional org-level semantic context (only for multi-component views)
}

/**
 * Weakness with attribution
 */
export interface WeaknessWithAttribution {
    cweId: string
    severity: string | null
    ruleId: string | null
    location: string
    resolvedIn: ComponentAttribution[]
    appearedIn: ComponentAttribution[]
    presentIn: ComponentAttribution[]
    isNetResolved: boolean
    isNetAppeared: boolean
    isStillPresent: boolean
    orgContext?: OrgLevelContext  // Optional org-level semantic context (only for multi-component views)
}

/**
 * Finding changes with attribution (replaces old FindingChanges)
 */
export interface FindingChangesWithAttribution {
    vulnerabilities: VulnerabilityWithAttribution[]
    violations: ViolationWithAttribution[]
    weaknesses: WeaknessWithAttribution[]
    totalAppeared: number
    totalResolved: number
}

