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
}

const NO_COMPONENT_KEY = '(none)'

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

function identityOf (row: DetailedMetric): ComponentIdentity & { purl?: string } {
    const location = (row.purl || '').trim()
    if (!location || location === '-') return { key: NO_COMPONENT_KEY, label: 'No component' }
    if (location.startsWith('pkg:')) return { ...purlComponentIdentity(location), purl: row.purl }
    return { key: location, label: location }
}

/**
 * Groups rows by affected component, worst first: by worst severity, then
 * known-exploited count, then finding count, then label.
 */
export function groupFindingsByComponent (rows: DetailedMetric[]): FindingComponentGroup[] {
    const groups = new Map<string, FindingComponentGroup>()
    for (const row of rows) {
        const identity = identityOf(row)
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
                kevCount: 0
            }
            groups.set(identity.key, group)
        }
        group.rows.push(row)
        if (findingTypeOf(row.type) === FindingType.VIOLATION) group.violationCount++
        else group.severityCounts[severityBucketOf(row)]++
        if (row.knownExploited) group.kevCount++
    }
    return [...groups.values()].sort((a, b) =>
        worstSeverityIndex(a) - worstSeverityIndex(b)
        || b.kevCount - a.kevCount
        || b.rows.length - a.rows.length
        || a.label.localeCompare(b.label))
}

/** Number of distinct components the rows touch; rows without one are not a component. */
export function componentCountOf (rows: DetailedMetric[]): number {
    return new Set(rows.map(row => identityOf(row).key).filter(key => key !== NO_COMPONENT_KEY)).size
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
