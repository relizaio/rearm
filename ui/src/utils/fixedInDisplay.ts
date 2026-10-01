// Display rules for a finding's fix version (Vulnerability.fixedIn) and the
// details panel's affected ranges. Pure functions, so the wording, the
// group's bump targets and the panel's grouping are unit-tested without
// mounting the modal.
//
// The UI has no version ordering of its own: the backend compares versions
// with each ecosystem's rules, and nothing here sorts or reformats a version
// or a vers line.

import type { AffectedRange, ComponentFixTargets, FixedIn, FixedInVerdict, UpstreamSource, VulnerabilityRecordDetails } from './vulnerabilityRecordService'
import { FindingType } from '@/constants/findingType'
import { findingTypeOf } from './findingUtils'

/** A findings row as far as the fix version goes. */
export interface FixedInRow {
    // The finding's id (a vulnerability's id on vulnerability rows) and row type.
    id?: string
    type?: string
    fixedIn?: FixedIn | null
    // The fix versions of the row's component, when the backend served them.
    fixTargets?: ComponentFixTargets | null
}

/** The one version a component group could move to, and which of its findings it fixes. */
export interface GroupBump {
    version: string
    // Ids of the group's vulnerability findings the version fixes, one per row.
    fixed: string[]
    // The group's vulnerability findings.
    total: number
    // The component's major version, when it has one.
    major?: string | null
    // Whether version is on another major version than the component's (false when that is unknown).
    leavesMajor: boolean
    // Set when version leaves the component's major version: the best one that stays on it.
    withinMajor?: { version: string, fixed: string[] } | null
}

/** Why a finding the bump does not fix stays affected, by its own verdict. */
const HELD_REASONS: Record<FixedInVerdict, string> = {
    FIXED_IN: 'fixed by another version',
    FIXED_AFTER: 'no fixed version named',
    NO_FIX_AVAILABLE: 'no fix yet',
    NOT_IN_ADVISORY_RANGE: 'matched by name only',
    NO_RANGE_DATA: 'no range data',
    UNCOMPARABLE: 'cannot be compared'
}

/** Hover text per verdict; the cell's own text is on the FIXED_IN / FIXED_AFTER / NO_FIX_AVAILABLE rows only. */
export const FIXED_IN_TITLES: Record<FixedInVerdict, string> = {
    FIXED_IN: 'The advisory says this version fixes the finding',
    FIXED_AFTER: 'The advisory says versions up to this one are affected, and names no fixed version',
    NO_FIX_AVAILABLE: 'The advisory names no fixed version yet for this package',
    NOT_IN_ADVISORY_RANGE: 'Matched by name; this version is outside the advisory\'s affected ranges',
    NO_RANGE_DATA: 'No affected version ranges for this package yet',
    UNCOMPARABLE: 'This version cannot be placed in the advisory\'s affected ranges'
}

// Up to this many bump targets show by name; more show as a count.
const BUMP_TARGETS_SHOWN = 3

/** Whether the rows came from a query that selected fix versions (a backend older than them leaves the field out). */
export function hasFixedInFields (rows: FixedInRow[]): boolean {
    return rows.some(row => row.fixedIn !== undefined)
}

/** The row's fix version, when the advisory names one. */
export function fixVersionOf (row: FixedInRow): string | null {
    return row.fixedIn?.verdict === 'FIXED_IN' && row.fixedIn.version ? row.fixedIn.version : null
}

/** The advisory names no fixed version yet. */
export function isNoFix (fixedIn: FixedIn | null | undefined): boolean {
    return fixedIn?.verdict === 'NO_FIX_AVAILABLE'
}

/** The cell text: the version, "> version" for a last affected one, "no fix yet", else "-". */
export function fixedInText (fixedIn: FixedIn | null | undefined): string {
    if (!fixedIn) return '-'
    switch (fixedIn.verdict) {
    case 'FIXED_IN': return fixedIn.version || '-'
    case 'FIXED_AFTER': return fixedIn.versionEndIncluding ? `> ${fixedIn.versionEndIncluding}` : '-'
    case 'NO_FIX_AVAILABLE': return 'no fix yet'
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

// Ids named per reason in the group cell's hover; more show as a count.
const HELD_IDS_SHOWN = 3

function isVulnerabilityRow (row: FixedInRow): boolean {
    return findingTypeOf(row.type) === FindingType.VULNERABILITY && !!row.id
}

// Whether the row's own component lists version as a fix for the row. Each row
// answers from its own package URL's targets: a group can hold qualifier
// spellings of one component, whose findings a version may fix for one and
// not the other (a Debian release and the next).
function fixesRow (row: FixedInRow, version: string): boolean {
    return !!row.fixTargets?.targets.some(t => t.version === version && t.fixes.includes(row.id!))
}

/**
 * The fix version that fixes the most of the group's vulnerability findings,
 * the lowest on a tie, counted over the rows as filtered; null when the rows
 * carry no fix targets (a backend without them, or none named). The backend
 * lists each package URL's versions lowest first; a group of several lists
 * the others' after the first's, so there a tie goes to the first listed.
 */
export function groupBumpOf (rows: FixedInRow[]): GroupBump | null {
    const vulnRows = rows.filter(isVulnerabilityRow)
    const versions: string[] = []
    const sameMajorOf = new Map<string, boolean>()
    let major: string | null = null
    for (const row of vulnRows) {
        if (!row.fixTargets) continue
        major = major ?? row.fixTargets.major ?? null
        for (const target of row.fixTargets.targets) {
            if (sameMajorOf.has(target.version)) continue
            versions.push(target.version)
            sameMajorOf.set(target.version, target.sameMajor)
        }
    }
    let best: { version: string, fixed: string[], sameMajor: boolean } | null = null
    let bestSame: { version: string, fixed: string[] } | null = null
    for (const version of versions) {
        const fixed = vulnRows.filter(row => fixesRow(row, version)).map(row => row.id!)
        const sameMajor = sameMajorOf.get(version)!
        if (fixed.length > (best?.fixed.length ?? 0)) best = { version, fixed, sameMajor }
        if (sameMajor && fixed.length > (bestSame?.fixed.length ?? 0)) bestSame = { version, fixed }
    }
    if (!best) return null
    // an unknown major version (r09) is no reason to say the bump leaves it
    const leavesMajor = !!major && !best.sameMajor
    return {
        version: best.version,
        fixed: best.fixed,
        total: vulnRows.length,
        major,
        leavesMajor,
        withinMajor: leavesMajor && bestSame ? bestSame : null
    }
}

/** The group cell's first line: "2.25.4 fixes 7 of 7". */
export function groupBumpText (bump: GroupBump): string {
    return `${bump.version} fixes ${bump.fixed.length} of ${bump.total}`
}

/**
 * The group cell's second line when the best bump leaves the component's
 * major version: the best one that stays on it ("within 2.x: 2.2.24 fixes
 * 18"), or that none does ("no fix on 19.x").
 */
export function groupBumpWithinMajorText (bump: GroupBump): string | null {
    if (!bump.leavesMajor) return null
    const on = bump.major ? `${bump.major}.x` : 'this major version'
    return bump.withinMajor ? `within ${on}: ${bump.withinMajor.version} fixes ${bump.withinMajor.fixed.length}` : `no fix on ${on}`
}

/** The group cell's hover: what the bump fixes, what it leaves and why, and the major-version alternative. */
export function groupBumpTitle (bump: GroupBump, rows: FixedInRow[]): string {
    const parts = [`Bumping to ${bump.version} fixes ${bump.fixed.length} of this component's ${bump.total} vulnerability findings, as their advisories say.`]
    const heldByReason = new Map<string, string[]>()
    for (const row of rows) {
        if (!isVulnerabilityRow(row) || fixesRow(row, bump.version)) continue
        const reason = row.fixedIn ? HELD_REASONS[row.fixedIn.verdict] : 'no fix version read'
        heldByReason.set(reason, [...(heldByReason.get(reason) || []), row.id!])
    }
    if (heldByReason.size > 0) {
        const groups = [...heldByReason].map(([reason, ids]) => {
            const more = ids.length > HELD_IDS_SHOWN ? `, and ${ids.length - HELD_IDS_SHOWN} more` : ''
            return `${ids.length} ${reason} (${ids.slice(0, HELD_IDS_SHOWN).join(', ')}${more})`
        })
        parts.push(`Still affected: ${groups.join('; ')}.`)
    }
    const within = groupBumpWithinMajorText(bump)
    if (within) parts.push(`${bump.version} is on another major version; ${within}.`)
    return parts.join(' ')
}

/** Whether the group's bump fixes this row. */
export function fixedByBump (row: FixedInRow, bump: GroupBump | null): boolean {
    return !!bump && isVulnerabilityRow(row) && fixesRow(row, bump.version)
}

// Verdicts taken from ranges that contain the finding's version.
const FROM_CONTAINING_RANGES: FixedInVerdict[] = ['FIXED_IN', 'FIXED_AFTER', 'NO_FIX_AVAILABLE']

/** Whether a package of the details panel is one the finding's fix version was taken from. */
export function appliesToFinding (identity: string, fixedIn: FixedIn | null | undefined): boolean {
    return !!fixedIn && FROM_CONTAINING_RANGES.includes(fixedIn.verdict) && !!fixedIn.identities?.includes(identity)
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
