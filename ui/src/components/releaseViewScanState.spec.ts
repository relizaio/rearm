import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

/**
 * Wiring guard for the not-scannable marker in ReleaseView (TPF-127, architecture row 8). ReleaseView
 * is never mounted in tests, so this reads the source (comments stripped, functions sliced by name),
 * the releaseViewScanStatusWiring.spec.ts idiom; the behaviour runs in the util specs.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const descriptor = parseSfc(source).descriptor
const script = (descriptor.scriptSetup || descriptor.script)!.content
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const template = descriptor.template!.content.replace(/<!--[\s\S]*?-->/g, '')

function fn (name: string): string {
    return script.match(new RegExp(`(async )?function ${name} \\([^)]*\\)[^{]*\\{[\\s\\S]*?\\n\\}`))?.[0] || ''
}

function count (haystack: string, needle: string): number {
    return haystack.split(needle).length - 1
}

function importLine (module: string): string {
    return script.split('\n').find((l) => l.includes(`from '${module}'`)) || ''
}

describe('ReleaseView reads the not-scannable marker (TPF-127)', () => {
    it('(a) the pill says No scannable components after the failure branches and before Scan done', () => {
        const pill = fn('renderDtrackPill')
        expect(pill).not.toBe('')
        const marker = pill.indexOf('m.notScannable')
        expect(marker).toBeGreaterThan(pill.indexOf("dtrackFetchStatus === 'FAILED'"))
        expect(marker).toBeLessThan(pill.indexOf('if (m.firstScanned)'))
        const branch = pill.slice(marker, pill.indexOf('if (m.firstScanned)'))
        expect(branch).toContain("'data-testid': 'artifact-not-scannable'")
        expect(branch).toContain("type: 'default'")
        expect(branch).toContain('notScannableTitle(m.notScannable)')
        expect(branch).toContain("'No scannable components'")
        for (const label of ["'Scan done'", "'Scan pending'", "'Scanning", "'Scan failed'", "'Release rejected'", "'DTrack: not configured'"]) {
            expect(pill).toContain(label)
        }
    })

    it('(b) the circles are gated off for a marked artifact', () => {
        for (const name of ['renderArtifactVulnerabilitiesColumn', 'renderArtifactViolationsColumn']) {
            expect(count(fn(name), 'row.metrics.firstScanned && !row.metrics.notScannable')).toBe(1)
        }
    })

    it('(c) the facts list names the reason', () => {
        const facts = fn('renderArtifactFactsColumn')
        expect(count(facts, "'artifact-not-scannable-fact'")).toBe(1)
        expect(count(facts, 'notScannableTitle(row.metrics.notScannable)')).toBe(1)
    })

    it('(d) the page stops polling on a marked BOM', () => {
        expect(fn('hasPendingStatuses')).toContain('!a.metrics?.notScannable')
    })

    it('(e) the title helper is imported beside the TPF-122 names', () => {
        const line = importLine('@/utils/artifactScanStatus')
        for (const name of ['notScannableTitle', 'loadArtifactScanStatus', 'scanStatusSupported']) expect(line).toContain(name)
    })

    it('(f) the pill test id occurs once', () => {
        expect(count(script, "'artifact-not-scannable'")).toBe(1)
    })

    it('(g) no new v-html: the file count stays the one TPF-122 pins', () => {
        expect(count(source, 'v-html')).toBe(1)
    })

    it('(h) the header badge takes its colour from scanBadgeBackground', () => {
        const styleLine = template.split('\n').find((l) => l.includes('scanBadgeBackground(releaseScanStatus)')) || ''
        expect(styleLine).toContain(':style')
        expect(styleLine).not.toContain('#ffc107')
    })

    it('(i) the child-row badge takes its colour from scanBadgeBackground', () => {
        const badge = fn('renderChildScanStatusBadge')
        expect(badge).toContain('scanBadgeBackground(status)')
        expect(badge).not.toContain('#ffc107')
    })

    it('(j) the status import line names scanBadgeBackground beside the existing names', () => {
        const line = importLine('@/utils/releaseScanStatus')
        for (const name of ['scanBadgeBackground', 'getReleaseScanStatus', 'collectArtifactsForStatus', 'showsScanBadge']) expect(line).toContain(name)
    })

    it('(k) no badge yellow is left in the file', () => {
        expect(source).not.toContain("'#ffc107'")
    })

    it('(l) the child-row cells still call getReleaseScanStatus with two arguments', () => {
        expect(count(script, 'getReleaseScanStatus(child, dtrackConfigured.value)')).toBe(2)
    })
})
