// The latest version of a finding's SBOM component, as the findings views show
// it (rearm-saas#708): the newest version of the package in the repositories
// Dependency-Track is configured with, from its repository metadata. A
// freshness signal only; the copy never calls it end of support or
// "outdated". Counts are over the rows shown, as Bump to counts.

import type { DetailedMetric } from './metrics'
import { findingTypeOf } from './findingUtils'
import { FindingType } from '@/constants/findingType'
import { LatestFixVerdict } from '@/constants/latestFixVerdict'

/** Whether the rows carry the latest-version fields: the backend served the LATEST document. */
export function hasLatestFields (rows: DetailedMetric[]): boolean {
    return rows.some(row => row.latestFix !== undefined)
}

/** A component's latest version and what it does for a set of its findings. */
export interface GroupLatest {
    /** The latest version Dependency-Track reports. */
    version: string
    /** When ReARM last asked, a UTC RFC-3339 instant; null when unknown. */
    checked: string | null
    /** Findings the latest version is outside the affected ranges of. */
    fixes: number
    /** Findings the latest version is still inside the affected ranges of. */
    stillAffected: number
    /** All the findings counted. */
    total: number
}

/** What every latest-version surface says the value is, and is not. */
export const LATEST_VERSION_NOTE = 'Latest version in the repositories Dependency-Track is configured with. '
    + 'It says how current the component is, not whether it is supported.'

/** Each verdict as a findings-table cell: yes / no, or why neither. */
export const LATEST_FIX_CELLS: Record<LatestFixVerdict, { text: string, title: string }> = {
    FIXES: { text: 'yes', title: 'The latest version is outside this finding\'s affected ranges' },
    DOES_NOT_FIX: { text: 'no', title: 'The latest version is still inside this finding\'s affected ranges' },
    NOT_ABOVE_CURRENT: { text: 'at or past latest', title: 'The component is already on the latest version, or on a newer build' },
    NOT_IN_ADVISORY_RANGE: { text: '-', title: 'The advisory\'s ranges do not contain the component\'s version' },
    NO_RANGE_DATA: { text: '-', title: 'The advisory gives no version ranges for this package' },
    UNCOMPARABLE: { text: '-', title: 'The versions cannot be compared under the package ecosystem\'s rules' }
}

/** A finding's verdict as a table cell; empty when there is none. */
export function latestFixCell (verdict: LatestFixVerdict | null | undefined): { text: string, title: string } {
    return verdict ? LATEST_FIX_CELLS[verdict] : { text: '', title: '' }
}

/** The latest version with its verdicts on a set of findings counted. */
export function latestOf (version: string, checked: string | null, verdicts: Array<LatestFixVerdict | null | undefined>): GroupLatest {
    return {
        version,
        checked,
        fixes: verdicts.filter(v => v === LatestFixVerdict.FIXES).length,
        stillAffected: verdicts.filter(v => v === LatestFixVerdict.DOES_NOT_FIX).length,
        total: verdicts.length
    }
}

/** The latest version of a component group, over its vulnerability rows; null when none is known. */
export function groupLatestOf (rows: DetailedMetric[]): GroupLatest | null {
    const known = rows.find(row => row.sbomMatch?.latestVersion)
    if (!known?.sbomMatch?.latestVersion) return null
    const vulnerabilities = rows.filter(row => findingTypeOf(row.type) === FindingType.VULNERABILITY)
    return latestOf(known.sbomMatch.latestVersion, known.sbomMatch.latestVersionChecked ?? null,
        vulnerabilities.map(row => row.latestFix))
}

/** The day of a UTC RFC-3339 instant, e.g. 2026-09-29; empty for none. */
export function checkedDay (checked: string | null | undefined): string {
    return checked ? checked.slice(0, 10) : ''
}

/** The group column's text: the version, and what it fixes when it fixes any. */
export function groupLatestText (latest: GroupLatest): string {
    return latest.fixes > 0 ? `${latest.version} fixes ${latest.fixes} of ${latest.total}` : latest.version
}

/** The dependency-graph page's text beside the version; empty when it fixes none. */
export function latestFixesText (latest: GroupLatest): string {
    return latest.fixes > 0 ? `(fixes ${latest.fixes} of ${latest.total} findings)` : ''
}

/** The tooltip: what the value is, when it was checked, and what it does for these findings. */
export function groupLatestTitle (latest: GroupLatest): string {
    const checked = latest.checked ? ` Checked ${checkedDay(latest.checked)}.` : ''
    let placed: string
    if (latest.fixes > 0) {
        placed = `It is outside the affected ranges of ${latest.fixes} of these ${latest.total} findings, by their advisories.`
    } else if (latest.stillAffected > 0) {
        placed = `It is still inside the affected ranges of ${latest.stillAffected} of these ${latest.total} findings.`
    } else {
        placed = 'The advisories give no ranges it can be placed in for these findings.'
    }
    return `${LATEST_VERSION_NOTE}${checked} ${placed}`
}
