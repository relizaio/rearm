import { describe, it, expect } from 'vitest'
import {
    FIXED_IN_TITLES,
    affectedPackagesOf,
    appliesToFinding,
    bumpTargetsOf,
    bumpToText,
    bumpToTitle,
    fixVersionOf,
    fixedByBump,
    fixedInText,
    fixedInTitle,
    groupBumpOf,
    groupBumpText,
    groupBumpTitle,
    groupBumpWithinMajorText,
    hasFixedInFields,
    isNoFix,
    rangeBoundsText
} from './fixedInDisplay'
import type { FixedInRow } from './fixedInDisplay'
import type { AffectedRange, ComponentFixTargets, FixedIn } from './vulnerabilityRecordService'

const fixedIn = (verdict: FixedIn['verdict'], extra: Partial<FixedIn> = {}): FixedIn => ({ verdict, sources: [], ...extra })

describe('the fix version of a finding', () => {
    it('shows the version, the last affected one, no fix yet, or a dash', () => {
        expect(fixedInText(fixedIn('FIXED_IN', { version: '4.7.7' }))).toBe('4.7.7')
        expect(fixedInText(fixedIn('FIXED_AFTER', { versionEndIncluding: '1.2.1.2-jre17' }))).toBe('> 1.2.1.2-jre17')
        expect(fixedInText(fixedIn('NO_FIX_AVAILABLE'))).toBe('no fix yet')
        for (const verdict of ['NOT_IN_ADVISORY_RANGE', 'NO_RANGE_DATA', 'UNCOMPARABLE'] as const) {
            expect(fixedInText(fixedIn(verdict))).toBe('-')
        }
        expect(fixedInText(null)).toBe('-')
        expect(fixedInText(undefined)).toBe('-')
    })

    it('says why in the hover text, with the sources', () => {
        expect(fixedInTitle(fixedIn('FIXED_IN', { version: '4.7.7', sources: ['GITHUB', 'OSV'] })))
            .toBe(`${FIXED_IN_TITLES.FIXED_IN} (GITHUB, OSV)`)
        expect(fixedInTitle(fixedIn('NOT_IN_ADVISORY_RANGE'))).toMatch(/matched by name/i)
        // "not fixed yet", never "unfixable": most of these are Debian releases still waiting for a fix
        expect(FIXED_IN_TITLES.NO_FIX_AVAILABLE).toBe('The advisory names no fixed version yet for this package')
        expect(fixedInTitle(null)).toBe('')
    })

    it('counts as a fix only when the advisory names the version', () => {
        expect(fixVersionOf({ fixedIn: fixedIn('FIXED_IN', { version: '2.0.3' }) })).toBe('2.0.3')
        expect(fixVersionOf({ fixedIn: fixedIn('FIXED_AFTER', { versionEndIncluding: '2.0.2' }) })).toBeNull()
        expect(fixVersionOf({ fixedIn: fixedIn('NO_FIX_AVAILABLE') })).toBeNull()
        expect(fixVersionOf({})).toBeNull()
    })

    it('tells a missing fix from the others', () => {
        expect(isNoFix(fixedIn('NO_FIX_AVAILABLE'))).toBe(true)
        expect(isNoFix(fixedIn('FIXED_AFTER', { versionEndIncluding: '1' }))).toBe(false)
        expect(isNoFix(null)).toBe(false)
    })

    it('tells rows that selected the field from rows that did not', () => {
        expect(hasFixedInFields([{}, { fixedIn: null }])).toBe(true)
        expect(hasFixedInFields([{}, {}])).toBe(false)
    })
})

describe('a component\'s bump targets', () => {
    it('are its findings\' distinct fix versions in row order, unsorted', () => {
        const rows = [
            { fixedIn: fixedIn('FIXED_IN', { version: '1.11.11' }) },
            { fixedIn: fixedIn('NO_FIX_AVAILABLE') },
            { fixedIn: fixedIn('FIXED_IN', { version: '1.8.19' }) },
            { fixedIn: fixedIn('FIXED_IN', { version: '1.11.11' }) },
            {}
        ]
        expect(bumpTargetsOf(rows)).toEqual(['1.11.11', '1.8.19'])
        expect(bumpTargetsOf([])).toEqual([])
    })

    it('show by name up to three, as a count past that, and all in the hover text', () => {
        expect(bumpToText([])).toBe('-')
        expect(bumpToText(['1.11.11', '1.8.19', '2.0.3'])).toBe('1.11.11, 1.8.19, 2.0.3')
        expect(bumpToText(['1', '2', '3', '4'])).toBe('4 targets')
        expect(bumpToTitle(['1', '2', '3', '4'])).toBe('Fix versions of this component\'s findings: 1, 2, 3, 4')
        expect(bumpToTitle([])).toBe('')
    })
})

describe('a component group\'s one bump', () => {
    // django@2.0.1 as the sandbox has it, cut down: 2.0.3 fixes A, 2.2.24 A and B, 5.2.17 all three
    const django: ComponentFixTargets = {
        purl: 'pkg:pypi/django@2.0.1',
        major: '2',
        targets: [
            { version: '2.0.3', sameMajor: true, fixes: ['A'] },
            { version: '2.2.24', sameMajor: true, fixes: ['A', 'B'] },
            { version: '5.2.17', sameMajor: false, fixes: ['A', 'B', 'C'] }
        ]
    }
    const row = (id: string, targets: ComponentFixTargets | null = django, extra: Partial<FixedInRow> = {}): FixedInRow =>
        ({ id, type: 'Vulnerability', fixedIn: fixedIn('FIXED_IN', { version: '2.0.3' }), fixTargets: targets, ...extra })

    it('is the version that fixes the most, with the best on the major version when it leaves it', () => {
        const bump = groupBumpOf([row('A'), row('B'), row('C')])!
        expect(bump).toEqual({
            version: '5.2.17', fixed: ['A', 'B', 'C'], total: 3, major: '2', leavesMajor: true, withinMajor: { version: '2.2.24', fixed: ['A', 'B'] }
        })
        expect(groupBumpText(bump)).toBe('5.2.17 fixes 3 of 3')
        expect(groupBumpWithinMajorText(bump)).toBe('within 2.x: 2.2.24 fixes 2')
    })

    it('counts the rows as filtered, and takes the lowest version on a tie', () => {
        // with C filtered out, 2.2.24 fixes as many as 5.2.17 and is lower
        const bump = groupBumpOf([row('A'), row('B')])!
        expect(bump.version).toBe('2.2.24')
        expect(bump.withinMajor).toBeNull()
        expect(groupBumpWithinMajorText(bump)).toBeNull()
    })

    it('counts vulnerability rows only, and says what stays affected and why', () => {
        const noFix = row('D', django, { fixedIn: fixedIn('NO_FIX_AVAILABLE') })
        const violation: FixedInRow = { id: 'LICENSE', type: 'Violation' }
        const rows = [row('A'), row('B'), noFix, violation]
        const bump = groupBumpOf(rows)!
        expect(bump.total).toBe(3)
        // 2.2.24 and 5.2.17 both fix A and B: the lower one
        expect(groupBumpText(bump)).toBe('2.2.24 fixes 2 of 3')
        const title = groupBumpTitle(bump, rows)
        expect(title).toBe('Bumping to 2.2.24 fixes 2 of this component\'s 3 vulnerability findings, as their advisories say.'
            + ' Still affected: 1 no fix yet (D).')
        const withC = [...rows, row('C')]
        expect(groupBumpTitle(groupBumpOf(withC)!, withC)).toContain('5.2.17 is on another major version; within 2.x: 2.2.24 fixes 2.')
        expect(fixedByBump(rows[0], bump)).toBe(true)
        expect(fixedByBump(noFix, bump)).toBe(false)
        expect(fixedByBump(violation, bump)).toBe(false)
        expect(fixedByBump(rows[0], null)).toBe(false)
    })

    it('groups what stays affected by reason, naming a few', () => {
        const noRange = (id: string) => row(id, django, { fixedIn: fixedIn('NO_RANGE_DATA') })
        const rows = [row('A'), noRange('N1'), noRange('N2'), noRange('N3'), noRange('N4'), row('D', django, { fixedIn: fixedIn('NO_FIX_AVAILABLE') })]
        expect(groupBumpTitle(groupBumpOf(rows)!, rows))
            .toContain('Still affected: 4 no range data (N1, N2, N3, and 1 more); 1 no fix yet (D).')
    })

    it('says when the only bump leaves the major version', () => {
        const pacote: ComponentFixTargets = { purl: 'pkg:npm/pacote@19.0.2', major: '19', targets: [{ version: '21.5.1', sameMajor: false, fixes: ['P'] }] }
        const rows = [row('P', pacote)]
        const bump = groupBumpOf(rows)!
        expect(groupBumpWithinMajorText(bump)).toBe('no fix on 19.x')
        expect(groupBumpTitle(bump, rows)).toContain('21.5.1 is on another major version; no fix on 19.x.')
    })

    it('answers each row from its own package URL in a group of several', () => {
        // zlib under two Debian releases groups together; the fix is bookworm's only
        const bookworm: ComponentFixTargets = { purl: 'pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12', major: '1', targets: [{ version: '1:1.2.13.dfsg-1+deb12u1', sameMajor: true, fixes: ['CVE-X'] }] }
        const trixie: ComponentFixTargets = { purl: 'pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-13', major: '1', targets: [] }
        const rows = [row('CVE-X', bookworm), row('CVE-X', trixie), row('CVE-Y', null)]
        const bump = groupBumpOf(rows)!
        expect(groupBumpText(bump)).toBe('1:1.2.13.dfsg-1+deb12u1 fixes 1 of 3')
        expect(rows.map(r => fixedByBump(r, bump))).toEqual([true, false, false])
    })

    it('does not say a bump leaves a major version it does not know', () => {
        const guava: ComponentFixTargets = { purl: 'pkg:maven/com.google.guava/guava@r09', major: null, targets: [{ version: '24.1.1', sameMajor: false, fixes: ['G'] }] }
        const bump = groupBumpOf([row('G', guava)])!
        expect(bump.leavesMajor).toBe(false)
        expect(groupBumpWithinMajorText(bump)).toBeNull()
    })

    it('is none without fix targets, or when none fixes a visible row', () => {
        expect(groupBumpOf([row('A', null), row('B', null)])).toBeNull()
        expect(groupBumpOf([row('Z')])).toBeNull()
        expect(groupBumpOf([])).toBeNull()
    })
})

describe('the details panel for a clicked finding', () => {
    it('marks the packages the finding\'s verdict was taken from', () => {
        const bookworm = 'pkg:deb/debian/openssl?arch=source&distro=bookworm'
        const f = fixedIn('FIXED_IN', { version: '3.0.22-1~deb12u1', identities: [bookworm] })
        expect(appliesToFinding(bookworm, f)).toBe(true)
        expect(appliesToFinding('pkg:deb/debian/openssl?arch=source&distro=forky', f)).toBe(false)
        expect(appliesToFinding(bookworm, fixedIn('FIXED_IN'))).toBe(false)
        expect(appliesToFinding(bookworm, undefined)).toBe(false)
        // checked against these ranges, but the version is outside them: not where a fix came from
        expect(appliesToFinding(bookworm, fixedIn('NOT_IN_ADVISORY_RANGE', { identities: [bookworm] }))).toBe(false)
    })
})

describe('the raw bounds of a range', () => {
    const range = (extra: Partial<AffectedRange>): AffectedRange => ({ identity: 'pkg:npm/a', sources: [], ...extra })

    it('reads as the source wrote them', () => {
        expect(rangeBoundsText(range({ rangeType: 'RANGE', versionStartIncluding: '1.8', versionEndExcluding: '1.8.19' })))
            .toBe('>= 1.8, < 1.8.19')
        expect(rangeBoundsText(range({ rangeType: 'RANGE', versionStartExcluding: '1.0', versionEndIncluding: '2.0' })))
            .toBe('> 1.0, <= 2.0')
        expect(rangeBoundsText(range({ rangeType: 'RANGE' }))).toBe('every version')
        expect(rangeBoundsText(range({ rangeType: 'EXACT', exactVersion: '6.3.2.1' }))).toBe('= 6.3.2.1')
        expect(rangeBoundsText(range({ rangeType: null }))).toBe('unknown range type')
    })
})

describe('the details panel\'s affected packages', () => {
    const range = (identity: string, extra: Partial<AffectedRange> = {}): AffectedRange =>
        ({ identity, rangeType: 'RANGE', sources: ['OSV'], ...extra })

    it('are one entry per identity in the backend\'s order, with its vers line and sources', () => {
        const packages = affectedPackagesOf({
            affectedRanges: [
                range('pkg:deb/debian/openssl?distro=bookworm', { versionEndExcluding: '3.0.22-1~deb12u1' }),
                range('pkg:deb/debian/openssl?distro=trixie', { versionEndExcluding: '3.5.7-1~deb13u2', sources: ['OSV', 'GITHUB'] }),
                range('pkg:deb/debian/openssl?distro=bookworm', { versionEndExcluding: '3.0.9', sources: ['GITHUB'] }),
                range('pkg:npm/broken', { versionEndExcluding: 'not a version' })
            ],
            affectedVers: [
                { identity: 'pkg:deb/debian/openssl?distro=bookworm', vers: 'vers:deb/<3.0.22-1~deb12u1' },
                { identity: 'pkg:deb/debian/openssl?distro=trixie', vers: 'vers:deb/<3.5.7-1~deb13u2' }
            ]
        })
        expect(packages.map(p => [p.identity, p.vers, p.sources, p.ranges.length])).toEqual([
            ['pkg:deb/debian/openssl?distro=bookworm', 'vers:deb/<3.0.22-1~deb12u1', ['OSV', 'GITHUB'], 2],
            ['pkg:deb/debian/openssl?distro=trixie', 'vers:deb/<3.5.7-1~deb13u2', ['OSV', 'GITHUB'], 1],
            ['pkg:npm/broken', null, ['OSV'], 1]
        ])
    })

    it('are none without ranges or a record', () => {
        expect(affectedPackagesOf({ affectedRanges: [], affectedVers: [] })).toEqual([])
        expect(affectedPackagesOf({})).toEqual([])
        expect(affectedPackagesOf(null)).toEqual([])
    })
})
