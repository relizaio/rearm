import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

/**
 * Wiring guard for the two components that load releases without the store (TPF-127, architecture
 * row 8): the Latest tab and the dashboard widget send their documents through the latched fallback
 * with the artifact scan state latch from the util (never from the store module), and the branch list
 * is untouched (D18). The widget's behaviour runs mounted in MostRecentReleasesWidgetScanState.spec.ts.
 * TPF-133: both components send their documents through the two-tier sender (sendWithLatchedTiers), the
 * release BOM scan latch gating the full document and the artifact scan state latch the document
 * without bomScan; the branch list reads the tally through the store's rows, so BranchView stays untouched.
 */
function sfc (file: string) {
    const source = readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
    const descriptor = parseSfc(source).descriptor
    const script = (descriptor.scriptSetup || descriptor.script)!.content
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
    const template = descriptor.template!.content.replace(/<!--[\s\S]*?-->/g, '')
    const fn = (name: string): string =>
        script.match(new RegExp(`(async )?function ${name} ?\\([^)]*\\)[^{]*\\{[\\s\\S]*?\\n\\}`))?.[0] || ''
    const importLine = (module: string): string => script.split('\n').find((l) => l.includes(`from '${module}'`)) || ''
    return { source, script, template, fn, importLine }
}

function count (haystack: string, needle: string): number {
    return haystack.split(needle).length - 1
}

const latest = sfc('./LatestReleasesOfComponent.vue')
const widget = sfc('./MostRecentReleasesWidget.vue')
const branch = sfc('./BranchView.vue')

/** The tiers of one sendWithLatchedTiers call, in order: full document, document without bomScan, core. */
function expectTiers (text: string, full: string) {
    const parts = [`doc: GqlQueries.${full}, latch: releaseBomScanLatch(store)`,
        `doc: GqlQueries.${full}WithoutBomScan, latch: artifactScanStateLatch(store)`,
        `core: GqlQueries.${full}Core`]
    const at = parts.map((p) => text.indexOf(p))
    for (const i of at) expect(i).toBeGreaterThanOrEqual(0)
    expect(at[0]).toBeLessThan(at[1])
    expect(at[1]).toBeLessThan(at[2])
}

describe('LatestReleasesOfComponent loads through the latched fallback (TPF-127)', () => {
    it('imports useStore from vuex and the helper and the latch from the util, never the store module', () => {
        expect(latest.importLine('vuex')).toContain('useStore')
        const line = latest.importLine('@/utils/graphqlDriftFallback')
        expect(line).toContain('artifactScanStateLatch')
        expect(line).toContain('releaseBomScanLatch')
        expect(line).toContain('sendWithLatchedTiers')
        expect(latest.source).not.toContain("from '@/store'")
    })

    it('sends the three tiers of its document, the bomScan latch first, no-cache (TPF-133)', () => {
        const fetchLatest = latest.fn('fetchLatest')
        expect(fetchLatest).toContain('sendWithLatchedTiers')
        expectTiers(fetchLatest, 'LatestReleasesOfComponentGql')
        expect(fetchLatest).toContain("fetchPolicy: 'no-cache'")
    })

    it('keeps its two cell lines and draws no note of its own (the cells do)', () => {
        expect(count(latest.script, 'getReleaseScanStatus(row, dtrackConfigured.value)')).toBe(2)
        expect(latest.source).not.toContain('ownNotScannableNote')
    })
})

describe('MostRecentReleasesWidget loads through the latched fallback and draws the note (TPF-127)', () => {
    it('imports the helper and the latch from the util, never the store module', () => {
        const line = widget.importLine('@/utils/graphqlDriftFallback')
        expect(line).toContain('artifactScanStateLatch')
        expect(line).toContain('releaseBomScanLatch')
        expect(line).toContain('sendWithLatchedTiers')
        expect(widget.source).not.toContain("from '@/store'")
    })

    it('sends the three tiers of both documents, the bomScan latch first, no-cache (TPF-133)', () => {
        const fetchReleases = widget.fn('fetchReleases')
        expect(count(fetchReleases, 'sendWithLatchedTiers')).toBe(2)
        expectTiers(fetchReleases, 'ReleasesByDateRangeAndPerspectiveGql')
        expectTiers(fetchReleases, 'ReleasesByDateRangeGql')
        expect(count(fetchReleases, "fetchPolicy: 'no-cache'")).toBe(2)
    })

    it('takes the badge colour from scanBadgeBackground and draws the note once', () => {
        expect(widget.template).toContain('scanBadgeBackground(getPendingStatus(rel))')
        expect(widget.template).not.toContain('#ffc107')
        expect(count(widget.template, 'release-not-scannable-note')).toBe(1)
        expect(widget.template).toContain('notScannableNote(rel)')
        expect(widget.script).toContain('ownNotScannableNote')
        const line = widget.importLine('@/utils/releaseScanStatus')
        for (const name of ['scanBadgeBackground', 'ownNotScannableNote', 'NOT_SCANNABLE_GREY']) expect(line).toContain(name)
    })
})

describe('BranchView is untouched (TPF-127, D18)', () => {
    it('keeps its two cell lines and reads nothing new', () => {
        expect(count(branch.script, 'getReleaseScanStatus(row, dtrackConfigured.value)')).toBe(2)
        for (const name of ['scanBadgeBackground', 'notScannable', 'ownNotScannableNote', 'artifactScanStateLatch', 'releaseBomScanLatch', 'bomScan']) {
            expect(branch.source).not.toContain(name)
        }
    })
})
