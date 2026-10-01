import { PackageURL } from 'packageurl-js'
import type { DetailedMetric } from './metrics'
import {
    ROW_SEVERITIES,
    SEVERITY_ORDER,
    emptySeverityCounts,
    findingTypeOf,
    getSeverityIndex,
    severityBucketOf
} from './findingUtils'
import type { RowSeverity } from './findingUtils'
import { FindingType } from '@/constants/findingType'
import { FindingSbomMissReason } from '@/constants/findingSbomMissReason'
import type { VulnScore } from './vulnerabilityRecordService'
import { maxEpssOf, scoreSortValue, worstScoreOf } from './vulnScoreDisplay'

/**
 * Group-by-component view of a findings table: one group per affected
 * component, the findings table itself nested under each group.
 */

/**
 * Whether a row passes the Type and Severity column filters. An empty filter
 * lets everything through, as an unset naive-ui column filter does.
 */
export function matchesFindingFilters (row: DetailedMetric, typeFilter: string[], severityFilter: string[]): boolean {
    if (typeFilter.length > 0 && !typeFilter.includes(row.type)) return false
    if (severityFilter.length > 0 && !severityFilter.includes(severityBucketOf(row))) return false
    return true
}

/** The Type / Severity filters a findings modal opens with, from its initial* props. */
export function initialColumnFilters (initialType: string | string[] | undefined, initialSeverity: string | undefined):
    { type: string[], severity: string[] } {
    return {
        type: !initialType ? [] : (Array.isArray(initialType) ? [...initialType] : [initialType]),
        severity: initialSeverity ? [initialSeverity] : []
    }
}

export interface FindingComponentGroup {
    key: string
    // pkg: purls show as namespace/name@version; other locations (SARIF weakness
    // file paths) show as they are.
    label: string
    // Purl type (npm, deb, maven, ...) for pkg: groups.
    ecosystem?: string
    // A purl of the group, for the dependency-graph link; unset for
    // non-purl locations.
    purl?: string
    rows: DetailedMetric[]
    severityCounts: Record<RowSeverity, number>
    violationCount: number
    kevCount: number
    // Highest headline CVSS / EPSS among the group's findings; null when none
    // is scored or the rows carry no scores.
    worstScore: VulnScore | null
    maxEpss: VulnScore | null
    // The release SBOM component the server matched the group's findings to;
    // unset when the rows carry no match and were grouped here.
    sbomComponentUuid?: string
    // Every vulnerability of the group is on a package the release's SBOM
    // does not hold, e.g. carried forward from a BOM the release replaced.
    notInSbom: boolean
}

const NO_COMPONENT_KEY = '(none)'
const SBOM_COMPONENT_KEY_PREFIX = 'sbom:'

interface ComponentIdentity { key: string, label: string, ecosystem?: string }

/**
 * Component identity of a purl: type/namespace/name@version, parsed by
 * packageurl-js so qualifiers and subpath drop out and '%2B' / '+' spellings of
 * one version land together. An unparseable purl is its own identity.
 */
export function purlComponentIdentity (purl: string): ComponentIdentity {
    try {
        const p = PackageURL.fromString(purl)
        const label = `${p.namespace ? `${p.namespace}/` : ''}${p.name}${p.version ? `@${p.version}` : ''}`
        return { key: `pkg:${p.type}/${label}`, label, ecosystem: p.type }
    } catch {
        return { key: purl, label: purl }
    }
}

type RowIdentity = ComponentIdentity & { purl?: string, sbomComponentUuid?: string }

function identityOf (row: DetailedMetric): RowIdentity {
    const location = (row.purl || '').trim()
    if (!location || location === '-') return { key: NO_COMPONENT_KEY, label: 'No component' }
    if (location.startsWith('pkg:')) return { ...purlComponentIdentity(location), purl: row.purl }
    return { key: location, label: location }
}

/**
 * The component label of a canonical purl: its identity, plus the Debian /
 * Alpine / RPM distribution release when the purl names one, since the server
 * keeps a package's builds for two releases apart.
 */
function canonicalLabel (canonicalPurl: string): string {
    const label = purlComponentIdentity(canonicalPurl).label
    try {
        const distro = PackageURL.fromString(canonicalPurl).qualifiers?.distro
        return distro ? `${label} (${distro})` : label
    } catch {
        return label
    }
}

/** The group identity of each finding row; see componentIdentitiesOf. */
export type ComponentIdentities = Map<DetailedMetric, RowIdentity>

/**
 * The group identity of each row. A vulnerability the server matched to a
 * component of the release's SBOM (sbomMatch) is keyed by that component.
 * A row the server answered for without a match (a missReason) keeps its
 * purl identity: the server said the release's SBOM does not hold it. A row
 * with no answer at all (a violation, a weakness, or every row when the
 * backend does not match) is keyed by its purl identity too, except that a
 * purl identity the matched rows tie to exactly one component joins it, so a
 * package's violations stay with its vulnerabilities.
 *
 * Built over all of a table's rows, not the filtered ones, so a filter never
 * moves a row to another group.
 */
export function componentIdentitiesOf (rows: DetailedMetric[]): ComponentIdentities {
    const byPurl = rows.map(identityOf)
    const componentsOfPurl = new Map<string, Set<string>>()
    const labelOf = new Map<string, string>()
    rows.forEach((row, i) => {
        const component = row.sbomMatch?.sbomComponentUuid
        if (!component) return
        const components = componentsOfPurl.get(byPurl[i].key) ?? new Set<string>()
        components.add(component)
        componentsOfPurl.set(byPurl[i].key, components)
        const canonical = row.sbomMatch?.canonicalPurl
        if (canonical && !labelOf.has(component)) labelOf.set(component, canonicalLabel(canonical))
    })
    const identities: ComponentIdentities = new Map()
    rows.forEach((row, i) => {
        const tied = row.sbomMatch ? undefined : componentsOfPurl.get(byPurl[i].key)
        const component = row.sbomMatch?.sbomComponentUuid || (tied?.size === 1 ? [...tied][0] : undefined)
        identities.set(row, component
            ? {
                ...byPurl[i],
                key: SBOM_COMPONENT_KEY_PREFIX + component,
                label: labelOf.get(component) ?? byPurl[i].label,
                sbomComponentUuid: component
            }
            : byPurl[i])
    })
    return identities
}

/**
 * Groups rows by affected component, worst first: by worst severity, then
 * worst CVSS score, then known-exploited count, then finding count, then
 * label. Pass the identities of all the table's rows when grouping a
 * filtered subset of them.
 */
export function groupFindingsByComponent (rows: DetailedMetric[],
    identities: ComponentIdentities = componentIdentitiesOf(rows)): FindingComponentGroup[] {
    const groups = new Map<string, FindingComponentGroup>()
    for (const row of rows) {
        const identity = identities.get(row) ?? identityOf(row)
        let group = groups.get(identity.key)
        if (!group) {
            group = {
                key: identity.key,
                label: identity.label,
                ecosystem: identity.ecosystem,
                purl: identity.purl,
                rows: [],
                severityCounts: emptySeverityCounts(),
                violationCount: 0,
                kevCount: 0,
                worstScore: null,
                maxEpss: null,
                sbomComponentUuid: identity.sbomComponentUuid,
                notInSbom: false
            }
            groups.set(identity.key, group)
        }
        group.rows.push(row)
        if (findingTypeOf(row.type) === FindingType.VIOLATION) group.violationCount++
        else group.severityCounts[severityBucketOf(row)]++
        if (row.knownExploited) group.kevCount++
    }
    for (const group of groups.values()) {
        group.worstScore = worstScoreOf(group.rows)
        group.maxEpss = maxEpssOf(group.rows)
        const vulnerabilities = group.rows.filter(row => findingTypeOf(row.type) === FindingType.VULNERABILITY)
        group.notInSbom = vulnerabilities.length > 0
            && vulnerabilities.every(row => row.sbomMatch?.missReason === FindingSbomMissReason.NOT_IN_INVENTORY)
    }
    return [...groups.values()].sort((a, b) =>
        worstSeverityIndex(a) - worstSeverityIndex(b)
        || scoreSortValue(b.worstScore) - scoreSortValue(a.worstScore)
        || b.kevCount - a.kevCount
        || b.rows.length - a.rows.length
        || a.label.localeCompare(b.label))
}

/** Number of distinct components the rows touch; rows without one are not a component. */
export function componentCountOf (rows: DetailedMetric[], identities: ComponentIdentities = componentIdentitiesOf(rows)): number {
    return new Set(rows.map(row => (identities.get(row) ?? identityOf(row)).key).filter(key => key !== NO_COMPONENT_KEY)).size
}

// Index into SEVERITY_ORDER of the group's worst severity; violation-only groups sort last.
function worstSeverityIndex (group: FindingComponentGroup): number {
    const worst = ROW_SEVERITIES.find(s => group.severityCounts[s] > 0)
    return worst ? getSeverityIndex(worst) : SEVERITY_ORDER.length
}

const GROUP_BY_COMPONENT_STORAGE_KEY = 'rearmFindingsGroupByComponent'

/** The user's last choice of grouped vs flat findings view; flat by default. */
export function storedGroupByComponent (): boolean {
    try {
        return window.localStorage.getItem(GROUP_BY_COMPONENT_STORAGE_KEY) === 'true'
    } catch { return false }
}

export function storeGroupByComponent (grouped: boolean): void {
    try { window.localStorage.setItem(GROUP_BY_COMPONENT_STORAGE_KEY, String(grouped)) } catch { /* storage unavailable */ }
}
