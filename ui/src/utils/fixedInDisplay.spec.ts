import { describe, it, expect } from 'vitest'
import {
    FIXED_IN_TITLES,
    affectedPackagesOf,
    bumpTargetsOf,
    bumpToText,
    bumpToTitle,
    fixVersionOf,
    fixedInText,
    fixedInTitle,
    hasFixedInFields,
    isNoFix,
    rangeBoundsText
} from './fixedInDisplay'
import type { AffectedRange, FixedIn } from './vulnerabilityRecordService'

const fixedIn = (verdict: FixedIn['verdict'], extra: Partial<FixedIn> = {}): FixedIn => ({ verdict, sources: [], ...extra })

describe('the fix version of a finding', () => {
    it('shows the version, the last affected one, no fix, or a dash', () => {
        expect(fixedInText(fixedIn('FIXED_IN', { version: '4.7.7' }))).toBe('4.7.7')
        expect(fixedInText(fixedIn('FIXED_AFTER', { endIncluding: '1.2.1.2-jre17' }))).toBe('> 1.2.1.2-jre17')
        expect(fixedInText(fixedIn('NO_FIX_AVAILABLE'))).toBe('no fix')
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
        expect(fixedInTitle(null)).toBe('')
    })

    it('counts as a fix only when the advisory names the version', () => {
        expect(fixVersionOf({ fixedIn: fixedIn('FIXED_IN', { version: '2.0.3' }) })).toBe('2.0.3')
        expect(fixVersionOf({ fixedIn: fixedIn('FIXED_AFTER', { endIncluding: '2.0.2' }) })).toBeNull()
        expect(fixVersionOf({ fixedIn: fixedIn('NO_FIX_AVAILABLE') })).toBeNull()
        expect(fixVersionOf({})).toBeNull()
    })

    it('tells a missing fix from the others', () => {
        expect(isNoFix(fixedIn('NO_FIX_AVAILABLE'))).toBe(true)
        expect(isNoFix(fixedIn('FIXED_AFTER', { endIncluding: '1' }))).toBe(false)
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
