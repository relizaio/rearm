// Text of the release Safety Context tab's device view: summary lines, completeness banners,
// withheld-source cells, and the count behind the "most stringent" tag.
import { describe, expect, it } from 'vitest'
import {
    INCOMPLETE_REASONS, REDACTED_REVIEWER_TEXT, REDACTED_SOURCE_TEXT, acceptedDetail, acceptedWho, banners,
    distinctActiveLevels, emptySummaryText, headerText, issuerOverrideTooltip, releaseCellText, rowKey, showingText,
    summaryLine
} from './safetyContextView'

describe('summaryLine (v1)', () => {
    it('names the level and one release in the singular', () => {
        expect(summaryLine({ schemeKey: 'IEC-62304', level: 'Class C', levelRank: 3, releaseCount: 1, unrankedLevels: [] }))
            .toBe('IEC-62304: Class C (1 release)')
    })

    it('uses the plural for any other count', () => {
        expect(summaryLine({ schemeKey: 'IEC-62304', level: 'Class C', levelRank: 3, releaseCount: 2, unrankedLevels: [] }))
            .toBe('IEC-62304: Class C (2 releases)')
        expect(summaryLine({ schemeKey: 'IEC-62304', level: 'Class C', levelRank: 3, releaseCount: 0 }))
            .toBe('IEC-62304: Class C (0 releases)')
    })

    it('says when the top level comes only from carried rows', () => {
        expect(summaryLine({ schemeKey: 'ISO-26262', level: 'ASIL D', releaseCount: 1, fromCarriedOnly: true, unrankedLevels: [] }))
            .toBe('ISO-26262: ASIL D (1 release) - carried from a previous package version')
    })

    it('lists levels that cannot be ranked beside the ranked one', () => {
        expect(summaryLine({ schemeKey: 'IEC-62304', level: 'Class B', releaseCount: 3, unrankedLevels: ['Class X', 'custom'] }))
            .toBe('IEC-62304: Class B (3 releases); also cannot be ranked: Class X, custom')
    })

    it('puts the carried note before the unranked levels', () => {
        expect(summaryLine({ schemeKey: 'IEC-62304', level: 'Class B', releaseCount: 1, fromCarriedOnly: true, unrankedLevels: ['Y'] }))
            .toBe('IEC-62304: Class B (1 release) - carried from a previous package version; also cannot be ranked: Y')
    })

    it('says "cannot be ranked" when no level is ranked', () => {
        expect(summaryLine({ schemeKey: 'acme-internal', level: null, levelRank: null, releaseCount: 0, unrankedLevels: ['Gold', 'Silver'] }))
            .toBe('acme-internal: cannot be ranked (Gold, Silver)')
    })
})

describe('emptySummaryText (v1)', () => {
    it('complete', () => {
        expect(emptySummaryText(true)).toBe('No safety integrity levels apply to this product release or its component releases.')
    })

    it('not complete', () => {
        expect(emptySummaryText(false)).toBe('No safety integrity levels are visible to you.')
    })
})

describe('banners (v1)', () => {
    const view = {
        hiddenReleaseCount: 2, hiddenLevelCount: 3, evaluatedReleaseCount: 200, constituentReleaseCount: 240,
        pendingInventoryReleaseCount: 1
    }

    it('gives each reason its exact text', () => {
        const all = banners({ ...view, incompleteReasons: [...INCOMPLETE_REASONS] })
        expect(all.map(b => b.text)).toEqual([
            '2 component release(s) are not visible to you; 3 level(s) on them are not shown. The most stringent level of'
                + ' this product may be higher than shown.',
            'Only 200 of 240 releases were evaluated. The most stringent level of this product may be higher than shown.',
            'The SBOM of 1 release(s) is still being processed; levels for packages not yet indexed are missing.',
            'Some releases have more levels than can be listed; the rows and the summary may be incomplete.'
        ])
    })

    it('shows only the listed reasons, in enum order', () => {
        expect(banners({ ...view, incompleteReasons: ['READ_CAP', 'HIDDEN_RELEASES'] }).map(b => b.reason))
            .toEqual(['HIDDEN_RELEASES', 'READ_CAP'])
    })

    it('shows none for a complete view or no view', () => {
        expect(banners({ ...view, incompleteReasons: [] })).toEqual([])
        expect(banners(null)).toEqual([])
    })
})

describe('headerText, showingText, releaseCellText (v1)', () => {
    it('keeps the sources sentence on a non-product view and replaces it on a product view', () => {
        const plain = headerText(false)
        const product = headerText(true)
        expect(plain).toContain('Levels come from SAFETY_CONTEXT (CycloneDX 2.0) artifacts uploaded to this release, or to'
            + ' other releases of the same component, branch or organization, for packages this release contains.')
        expect(product).not.toContain('Levels come from')
        expect(product).toContain('This is a product release. The table lists the levels that apply to this release and to'
            + ' each component release it contains, resolved the way each component release\'s own Safety Context tab'
            + ' resolves them; each row names the release it applies to.')
        for (const t of [plain, product]) {
            expect(t.startsWith('Declared by the issuer; not verified by ReARM. ')).toBe(true)
            expect(t).toContain('Where assets declare different levels for one component and scheme, the most stringent is marked.')
        }
    })

    it('says how many rows were received when the server holds more', () => {
        expect(showingText(2, 5)).toBe('Showing the first 2 of 5 rows.')
        expect(showingText(5, 5)).toBeNull()
        expect(showingText(0, null)).toBeNull()
    })

    it('names the viewed release "This release" and others by component name and version', () => {
        expect(releaseCellText({ appliesToRelease: 'p1' }, 'p1')).toBe('This release')
        expect(releaseCellText({ appliesToRelease: 'c1', appliesToComponentName: 'pump-fw', appliesToReleaseVersion: '1.2.0' }, 'p1'))
            .toBe('pump-fw 1.2.0')
    })
})

describe('withheld source cells (v2)', () => {
    it('"Applies via" text for a redacted row', () => {
        expect(REDACTED_SOURCE_TEXT).toBe('uploaded on a release not visible to you')
    })

    it('"Accepted": a redacted manual accept names no reviewer', () => {
        expect(REDACTED_REVIEWER_TEXT).toBe('reviewer (not visible to you)')
        expect(acceptedWho({ acceptedVia: 'MANUAL', sourceRedacted: true, acceptedByName: null })).toBe('reviewer (not visible to you)')
        expect(acceptedWho({ acceptedVia: 'AUTO_SELF', sourceRedacted: true })).toBe('auto')
    })

    it('"Accepted": a non-redacted row keeps today\'s text', () => {
        expect(acceptedWho({ acceptedVia: 'MANUAL', sourceRedacted: false, acceptedByName: 'Ann' })).toBe('Ann')
        expect(acceptedWho({ acceptedVia: 'MANUAL', sourceRedacted: false })).toBe('reviewer')
        expect(acceptedWho({ acceptedVia: 'AUTO_VENDOR' })).toBe('auto')
    })

    it('"Accepted" tooltip: no comment on a redacted row, the comment otherwise', () => {
        const r = { gateRule: 'DOWNGRADE', acceptComment: 'checked', statusReason: null }
        expect(acceptedDetail({ ...r, sourceRedacted: true })).toBe('Lower than the current level')
        expect(acceptedDetail({ ...r, sourceRedacted: false })).toBe('Lower than the current level - checked')
    })

    it('"Issuer" tooltip of an elevated level', () => {
        expect(issuerOverrideTooltip({ issuerClassDerived: 'THIRD_PARTY', sourceRedacted: true }))
            .toBe('Raised from THIRD_PARTY by an override (not visible to you)')
        expect(issuerOverrideTooltip({ issuerClassDerived: 'THIRD_PARTY', sourceRedacted: false, overrideActorName: 'Bo' }))
            .toBe('Raised from THIRD_PARTY by Bo')
        expect(issuerOverrideTooltip({ issuerClassDerived: 'THIRD_PARTY', sourceRedacted: false }))
            .toBe('Raised from THIRD_PARTY by an override')
    })
})

describe('distinctActiveLevels and rowKey (v6)', () => {
    const purl = 'pkg:generic/acme/pump@1.1.0'
    const rec = (over: any) => ({ recordStatus: 'ACTIVE', componentPurl: purl, schemeKey: 'IEC-62304', ...over })

    it('one record listed under two constituents is one level for each', () => {
        const rows = [rec({ uuid: 'org-b', appliesToRelease: 'c1' }), rec({ uuid: 'org-b', appliesToRelease: 'a2' })]
        expect(distinctActiveLevels(rows, rows[0])).toBe(1)
        expect(distinctActiveLevels(rows, rows[1])).toBe(1)
    })

    it('two records under one constituent are two levels, counted per constituent', () => {
        const rows = [
            rec({ uuid: 'c', appliesToRelease: 'c1' }), rec({ uuid: 'a', appliesToRelease: 'c1' }),
            rec({ uuid: 'c', appliesToRelease: 'c2' }), rec({ uuid: 'a', appliesToRelease: 'c2' })
        ]
        expect(distinctActiveLevels(rows, rows[0])).toBe(2)
        expect(distinctActiveLevels(rows, rows[2])).toBe(2)
        expect(distinctActiveLevels(rows.slice(0, 3), rows[2])).toBe(1)
    })

    it('ignores carried and SUPERSEDED rows, and rows of another purl or scheme', () => {
        const rows = [
            rec({ uuid: 'r1', appliesToRelease: 'c1' }),
            rec({ uuid: 'r2', appliesToRelease: 'c1', carried: true }),
            rec({ uuid: 'r3', appliesToRelease: 'c1', recordStatus: 'SUPERSEDED' }),
            rec({ uuid: 'r4', appliesToRelease: 'c1', componentPurl: 'pkg:generic/acme/other@1' }),
            rec({ uuid: 'r5', appliesToRelease: 'c1', schemeKey: 'ISO-26262' })
        ]
        expect(distinctActiveLevels(rows, rows[0])).toBe(1)
    })

    it('row key names the constituent, the record and the purl it is shown for', () => {
        expect(rowKey({ appliesToRelease: 'c1', uuid: 'r1', shownForPurl: purl })).toBe(`c1|r1|${purl}`)
        expect(rowKey({ appliesToRelease: 'c1', uuid: 'r1' })).not.toBe(rowKey({ appliesToRelease: 'a2', uuid: 'r1' }))
    })
})
