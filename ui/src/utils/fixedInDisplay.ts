// Display rules for a finding's fix version (Vulnerability.fixedIn) and the
// details panel's affected ranges. Pure functions, so the wording, the
// group's bump targets and the panel's grouping are unit-tested without
// mounting the modal.
//
// The UI has no version ordering of its own: the backend compares versions
// with each ecosystem's rules, and nothing here sorts or reformats a version
// or a vers line.

import type { AffectedRange, FixedIn, FixedInVerdict, UpstreamSource, VulnerabilityRecordDetails } from './vulnerabilityRecordService'

/** A findings row as far as the fix version goes. */
export interface FixedInRow {
    fixedIn?: FixedIn | null
}

/** Hover text per verdict; the cell's own text is on the FIXED_IN / FIXED_AFTER / NO_FIX_AVAILABLE rows only. */
export const FIXED_IN_TITLES: Record<FixedInVerdict, string> = {
    FIXED_IN: 'The advisory says this version fixes the finding',
    FIXED_AFTER: 'The advisory says versions up to this one are affected, and names no fixed version',
    NO_FIX_AVAILABLE: 'The advisory knows no fixed version: every later version is affected too',
    NOT_IN_ADVISORY_RANGE: 'Matched by name; this version is outside the advisory\'s affected ranges',
    NO_RANGE_DATA: 'No affected version ranges for this package yet',
    UNCOMPARABLE: 'This version cannot be placed in the advisory\'s affected ranges'
}

// Up to this many bump targets show by name; more show as a count.
const BUMP_TARGETS_SHOWN = 3

/** Whether the rows came from a query that selected fix versions (a CE backend without them leaves the field out). */
export function hasFixedInFields (rows: FixedInRow[]): boolean {
    return rows.some(row => row.fixedIn !== undefined)
}

/** The row's fix version, when the advisory names one. */
export function fixVersionOf (row: FixedInRow): string | null {
    return row.fixedIn?.verdict === 'FIXED_IN' && row.fixedIn.version ? row.fixedIn.version : null
}

/** The advisory knows no fixed version. */
export function isNoFix (fixedIn: FixedIn | null | undefined): boolean {
    return fixedIn?.verdict === 'NO_FIX_AVAILABLE'
}

/** The cell text: the version, "> version" for a last affected one, "no fix", else "-". */
export function fixedInText (fixedIn: FixedIn | null | undefined): string {
    if (!fixedIn) return '-'
    switch (fixedIn.verdict) {
    case 'FIXED_IN': return fixedIn.version || '-'
    case 'FIXED_AFTER': return fixedIn.endIncluding ? `> ${fixedIn.endIncluding}` : '-'
    case 'NO_FIX_AVAILABLE': return 'no fix'
    default: return '-'
    }
}

/** The cell's hover text, with the sources that give the range. */
export function fixedInTitle (fixedIn: FixedIn | null | undefined): string {
    if (!fixedIn) return ''
    const title = FIXED_IN_TITLES[fixedIn.verdict] || fixedIn.verdict
    return fixedIn.sources?.length ? `${title} (${fixedIn.sources.join(', ')})` : title
}

/** The distinct fix versions of a component's findings, in row order. */
export function bumpTargetsOf (rows: FixedInRow[]): string[] {
    const targets: string[] = []
    for (const row of rows) {
        const version = fixVersionOf(row)
        if (version && !targets.includes(version)) targets.push(version)
    }
    return targets
}

/** The group cell: the targets by name, a count past BUMP_TARGETS_SHOWN, '-' for none. */
export function bumpToText (targets: string[]): string {
    if (targets.length === 0) return '-'
    return targets.length > BUMP_TARGETS_SHOWN ? `${targets.length} targets` : targets.join(', ')
}

/** The group cell's hover text: every target. */
export function bumpToTitle (targets: string[]): string {
    return targets.length ? `Fix versions of this component's findings: ${targets.join(', ')}` : ''
}

/** One range's bounds as the source wrote them, for the details panel's expand. */
export function rangeBoundsText (range: AffectedRange): string {
    if (range.rangeType === 'EXACT') return range.exactVersion ? `= ${range.exactVersion}` : '-'
    if (range.rangeType !== 'RANGE') return 'unknown range type'
    const bounds = [
        range.versionStartIncluding ? `>= ${range.versionStartIncluding}` : '',
        range.versionStartExcluding ? `> ${range.versionStartExcluding}` : '',
        range.versionEndIncluding ? `<= ${range.versionEndIncluding}` : '',
        range.versionEndExcluding ? `< ${range.versionEndExcluding}` : ''
    ].filter(Boolean)
    return bounds.length ? bounds.join(', ') : 'every version'
}

export interface AffectedPackage {
    identity: string
    // Null when the identity's bounds do not parse.
    vers: string | null
    sources: UpstreamSource[]
    ranges: AffectedRange[]
}

/**
 * One entry per package identity, in the backend's order: its vers line, the
 * sources of its ranges, and the ranges themselves.
 */
export function affectedPackagesOf (record: Pick<VulnerabilityRecordDetails, 'affectedRanges' | 'affectedVers'> | null | undefined): AffectedPackage[] {
    const versByIdentity = new Map((record?.affectedVers || []).map(v => [v.identity, v.vers]))
    const byIdentity = new Map<string, AffectedPackage>()
    for (const range of record?.affectedRanges || []) {
        let pkg = byIdentity.get(range.identity)
        if (!pkg) {
            pkg = { identity: range.identity, vers: versByIdentity.get(range.identity) ?? null, sources: [], ranges: [] }
            byIdentity.set(range.identity, pkg)
        }
        pkg.ranges.push(range)
        for (const src of range.sources || []) {
            if (!pkg.sources.includes(src)) pkg.sources.push(src)
        }
    }
    return [...byIdentity.values()]
}
