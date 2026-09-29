// The latest version of a finding's SBOM component, as the findings views show
// it (rearm-saas PR G). Dependency-Track's repository metadata: the newest
// version of the package in the repositories it is configured with. A freshness
// signal only; the copy never calls it end of support or "outdated".

import type { DetailedMetric } from './metrics'
import { findingTypeOf } from './findingUtils'
import { FindingType } from '@/constants/findingType'
import { LatestFixVerdict } from '@/constants/latestFixVerdict'

/** Whether the rows carry the latest-version fields: the backend served the LATEST document. */
export function hasLatestFields (rows: DetailedMetric[]): boolean {
    return rows.some(row => row.latestFix !== undefined)
}

export interface GroupLatest {
    version: string
    // UTC RFC-3339 instant of the last check, null when unknown
    checked: string | null
    // vulnerability rows of the group the latest version fixes, and all of them
    fixes: number
    of: number
}

/** The latest version of a component group and how many of its vulnerability rows it fixes; null when none is known. */
export function groupLatestOf (rows: DetailedMetric[]): GroupLatest | null {
    const known = rows.find(row => row.sbomMatch?.latestVersion)
    if (!known?.sbomMatch?.latestVersion) return null
    const vulnerabilities = rows.filter(row => findingTypeOf(row.type) === FindingType.VULNERABILITY)
    return {
        version: known.sbomMatch.latestVersion,
        checked: known.sbomMatch.latestVersionChecked ?? null,
        fixes: vulnerabilities.filter(row => row.latestFix === LatestFixVerdict.FIXES).length,
        of: vulnerabilities.length
    }
}

/** The day of a UTC RFC-3339 instant, e.g. 2026-09-29; empty for none. */
export function checkedDay (checked: string | null | undefined): string {
    return checked ? checked.slice(0, 10) : ''
}

export function groupLatestText (latest: GroupLatest): string {
    return latest.fixes > 0 ? `${latest.version} fixes ${latest.fixes} of ${latest.of}` : latest.version
}

export function groupLatestTitle (latest: GroupLatest): string {
    const checked = latest.checked ? `, checked ${checkedDay(latest.checked)}` : ''
    const fixes = latest.fixes > 0
        ? `It is outside the affected ranges of ${latest.fixes} of these ${latest.of} findings, by their advisories. `
        : 'The advisories name none of these findings as fixed by it. '
    return `Latest version in the repositories Dependency-Track is configured with${checked}. ${fixes}`
        + 'It says how current the component is, not whether it is supported.'
}
