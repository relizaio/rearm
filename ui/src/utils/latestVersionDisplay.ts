// The latest version of a finding's SBOM component, as the findings views show
// it (rearm-saas PR G). Counts are over the rows shown, as Bump to counts. Dependency-Track's repository metadata: the newest
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
    // vulnerability rows of the group the latest version fixes, leaves affected, and all of them
    fixes: number
    stillAffected: number
    of: number
}

/** What every latest-version surface says the value is, and is not. */
export const LATEST_VERSION_NOTE = 'Latest version in the repositories Dependency-Track is configured with. '
    + 'It says how current the component is, not whether it is supported.'

/** A finding's latest-version verdict as a table cell: yes / no, or why neither. */
export function latestFixCell (verdict: LatestFixVerdict | null | undefined): { text: string, title: string } {
    switch (verdict) {
    case LatestFixVerdict.FIXES: return { text: 'yes', title: 'The latest version is outside this finding\'s affected ranges' }
    case LatestFixVerdict.DOES_NOT_FIX: return { text: 'no', title: 'The latest version is still inside this finding\'s affected ranges' }
    case LatestFixVerdict.NOT_ABOVE_CURRENT: return { text: 'at or past latest', title: 'The component is already on the latest version, or on a newer build' }
    case LatestFixVerdict.NOT_IN_ADVISORY_RANGE: return { text: '-', title: 'The advisory\'s ranges do not contain the component\'s version' }
    case LatestFixVerdict.NO_RANGE_DATA: return { text: '-', title: 'The advisory gives no version ranges for this package' }
    case LatestFixVerdict.UNCOMPARABLE: return { text: '-', title: 'The versions cannot be compared under the package ecosystem\'s rules' }
    default: return { text: '', title: '' }
    }
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
        stillAffected: vulnerabilities.filter(row => row.latestFix === LatestFixVerdict.DOES_NOT_FIX).length,
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
    const checked = latest.checked ? ` Checked ${checkedDay(latest.checked)}.` : ''
    let placed: string
    if (latest.fixes > 0) {
        placed = `It is outside the affected ranges of ${latest.fixes} of these ${latest.of} findings, by their advisories.`
    } else if (latest.stillAffected > 0) {
        placed = `It is still inside the affected ranges of ${latest.stillAffected} of these ${latest.of} findings.`
    } else {
        placed = 'The advisories give no ranges it can be placed in for these findings.'
    }
    return `${LATEST_VERSION_NOTE}${checked} ${placed}`
}
