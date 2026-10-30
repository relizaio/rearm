import { describe, it, expect, vi } from 'vitest'

// releaseScanStatus imports the graphql client (for the org-integration
// lookup), which transitively boots keycloak against window at module load --
// mock it out; these tests only exercise the pure status logic.
vi.mock('./graphql', () => ({ default: { query: vi.fn() } }))

import type { VNode } from 'vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { renderVulnerabilityCells, renderViolationCells, renderPendingBadge, renderNotScannableNote } from './releaseScanCells'
import { getReleaseScanStatus, type ReleaseScanStatus } from './releaseScanStatus'
import { notScannableTitle, reasonLabel } from './artifactScanStatus'

const open = vi.fn()
const metrics = { lastScanned: '2026-09-29T00:00:00Z', critical: 1, high: 0, medium: 0, low: 0, unassigned: 0,
    policyViolationsLicenseTotal: 0, policyViolationsSecurityTotal: 0, policyViolationsOperationalTotal: 0 }
const status = (kind: ReleaseScanStatus['kind']): ReleaseScanStatus => ({ kind, label: kind, title: kind })

describe('release scan cells (RD4-11)', () => {
    it('render nothing for a release that is never scanned: no badge, no circles', () => {
        const row = { componentDetails: { kind: 'BOARD_DOCUMENT' }, metrics }
        expect(renderVulnerabilityCells(row, status('not-applicable'), open)).toEqual([])
        expect(renderViolationCells(row, status('not-applicable'), open)).toEqual([])
    })

    it('still draw the pending badge for a software release awaiting its scan', () => {
        const cells = renderVulnerabilityCells({ metrics: null }, status('scan-pending'), open) as VNode[]
        expect(cells).toHaveLength(1)
        expect(cells[0].type).toBe('span')
        expect(renderViolationCells({ metrics: null }, status('scan-pending'), open)).toHaveLength(1)
    })

    it('still draw the circles for a scanned software release', () => {
        const cells = renderVulnerabilityCells({ metrics }, status('ready'), open) as VNode[]
        expect(cells).toHaveLength(1)
        expect(cells[0].type).not.toBe('span')
        expect(renderViolationCells({ metrics }, status('ready'), open)).toHaveLength(1)
    })
})

// TPF-127: the grey kind, the colours from scanBadgeBackground, and the note beside the circles.
const m = { reason: 'ONLY_ROOT', at: '2026-10-29T08:00:00Z', components: 1, root: 1, terminal: 0, unmatchable: 0 }
const m2 = { reason: 'NO_COMPONENTS', at: null, components: 0, root: 0, terminal: 0, unmatchable: 0 }
const marked = (marker: any) => ({ type: 'BOM', metrics: { notScannable: marker } })
const slotOf = (cell: any): VNode[] => (cell.children as any).default()
const circlesOf = (nodes: VNode[]) => nodes.filter((n: any) => n.props?.class === 'circle')
const notesOf = (nodes: VNode[]) => nodes.filter((n: any) => n.props?.['data-testid'] === 'release-not-scannable-note')

describe('release scan cells: not scannable (TPF-127)', () => {
    it('draw one grey span and no circle for the not-scannable kind, in both columns', () => {
        for (const render of [renderVulnerabilityCells, renderViolationCells]) {
            const cells = render({ metrics }, status('not-scannable'), open) as VNode[]
            expect(cells).toHaveLength(1)
            expect(cells[0].type).toBe('span')
            expect(String((cells[0].props as any).style)).toContain('#9e9e9e')
            expect(cells[0].children).toBe('not-scannable')
        }
    })

    it('take the badge colours from scanBadgeBackground', () => {
        expect(String((renderPendingBadge(status('rejected')).props as any).style)).toContain('#d03050')
        expect(String((renderPendingBadge(status('enrichment-pending')).props as any).style)).toContain('#fd8c00')
        expect(String((renderPendingBadge(status('scan-pending')).props as any).style)).toContain('#ffc107')
        const text = readFileSync(fileURLToPath(new URL('./releaseScanCells.ts', import.meta.url)), 'utf8')
        expect(text).not.toContain('#ffc107')
        const importLine = text.split('\n').find((l) => l.includes("from '@/utils/releaseScanStatus'")) || ''
        expect(importLine).toContain('scanBadgeBackground')
    })

    it('draw the note beside the five circles for a marked own BOM', () => {
        const cells = renderVulnerabilityCells({ metrics, artifactDetails: [marked(m)] }, status('ready'), open) as VNode[]
        expect(cells).toHaveLength(1)
        const nodes = slotOf(cells[0])
        expect(circlesOf(nodes)).toHaveLength(5)
        expect(nodes).toHaveLength(6)
        const note = nodes[5] as any
        expect(note.type).toBe('span')
        expect(note.props['data-testid']).toBe('release-not-scannable-note')
        expect(note.children).toBe('1 BOM not scannable')
        expect(note.props.title).toBe(notScannableTitle(m))
        expect(note.props.style).toContain('color')
        expect(note.props.style).toContain('#9e9e9e')
    })

    it('count two marked own BOMs, and keep a scanned own BOM in the circles beside the note', () => {
        const two = slotOf((renderVulnerabilityCells({ metrics, artifactDetails: [marked(m), marked(m2)] }, status('ready'), open) as VNode[])[0])
        expect((notesOf(two)[0] as any).children).toBe('2 BOMs not scannable')
        expect((notesOf(two)[0] as any).props.title.startsWith('Nothing to scan in 2 BOMs: ')).toBe(true)
        const mixed = slotOf((renderVulnerabilityCells({ metrics, artifactDetails: [marked(m), { type: 'BOM', metrics: { firstScanned: '2026-09-29T00:00:00Z' } }] }, status('ready'), open) as VNode[])[0])
        expect(circlesOf(mixed)).toHaveLength(5)
        expect(String((circlesOf(mixed)[0] as any).children)).toBe('1')
        expect(notesOf(mixed)).toHaveLength(1)
    })

    it('draw no note in the violations cell: one note per row', () => {
        for (const artifactDetails of [[marked(m)], [marked(m), marked(m2)], [marked(m), { type: 'BOM', metrics: { firstScanned: '2026-09-29T00:00:00Z' } }]]) {
            const nodes = slotOf((renderViolationCells({ metrics, artifactDetails }, status('ready'), open) as VNode[])[0])
            expect(circlesOf(nodes)).toHaveLength(3)
            expect(notesOf(nodes)).toHaveLength(0)
        }
    })

    it('draw no note for an untyped (branch-shaped) row or a row without artifacts', () => {
        const untyped = slotOf((renderVulnerabilityCells({ metrics, artifactDetails: [{ metrics: { notScannable: m } }] }, status('ready'), open) as VNode[])[0])
        expect(circlesOf(untyped)).toHaveLength(5)
        expect(notesOf(untyped)).toHaveLength(0)
        const bare = slotOf((renderVulnerabilityCells({ metrics }, status('ready'), open) as VNode[])[0])
        expect(circlesOf(bare)).toHaveLength(5)
        expect(notesOf(bare)).toHaveLength(0)
    })

    it('draw no note in a badge branch', () => {
        const row = { metrics, artifactDetails: [marked(m)] }
        const pending = renderVulnerabilityCells(row, status('scan-pending'), open) as VNode[]
        expect(pending).toHaveLength(1)
        expect(pending[0].type).toBe('span')
        expect(String((pending[0].props as any).style)).toContain('#ffc107')
        const grey = renderVulnerabilityCells(row, status('not-scannable'), open) as VNode[]
        expect(grey).toHaveLength(1)
        expect(String((grey[0].props as any).style)).toContain('#9e9e9e')
        expect((grey[0].props as any)['data-testid']).toBeUndefined()
    })

    it('renderNotScannableNote is a span with the label as text and the title as an attribute', () => {
        const note = renderNotScannableNote({ count: 1, label: 'x', title: 'y' }) as any
        expect(note.type).toBe('span')
        expect(note.children).toBe('x')
        expect(note.props.title).toBe('y')
    })
})

// TPF-133 (test 7): the branch list and the Latest tab cells, with the status from the real reader over
// rows that carry the served BOM scan tally (metrics.bomScan) the list documents now select.
const ANCHORED = { firstScanned: '2026-10-29T08:00:00Z', lastScanned: '2026-10-29T08:00:00Z', critical: 0, high: 0, medium: 0, low: 0,
    unassigned: 0, policyViolationsLicenseTotal: 0, policyViolationsSecurityTotal: 0, policyViolationsOperationalTotal: 0 }
const SERVED_ALL = { state: 'ALL_NOT_SCANNABLE', scannableBoms: 0, notScannableBoms: 2, reasons: ['ONLY_ROOT', 'NO_COMPONENTS'] }
/** The FetchReleases row shape: own artifacts without type, componentDetails { type }. */
const branchRow = (bomScan: any, opts: { type?: string, firstScanned?: string | null, omit?: boolean } = {}) => {
    const metrics: any = { ...ANCHORED, firstScanned: opts.firstScanned === undefined ? ANCHORED.firstScanned : opts.firstScanned }
    if (!opts.omit) metrics.bomScan = bomScan
    return { uuid: 'r1', lifecycle: 'ASSEMBLED', componentDetails: { type: opts.type ?? 'COMPONENT' }, metrics,
        artifactDetails: [{ enrichmentStatus: 'COMPLETED', metrics: { dtrackSubmissionFailed: false, dependencyTrackFullUri: null, firstScanned: null } }] }
}
const cellsOf = (row: any) => {
    const s = getReleaseScanStatus(row, true)
    return { vuln: renderVulnerabilityCells(row, s, open) as VNode[], viol: renderViolationCells(row, s, open) as VNode[] }
}
function expectGreyBadge (cells: VNode[], notScannableBoms: number) {
    expect(cells).toHaveLength(1)
    const span = cells[0] as any
    expect(span.type).toBe('span')
    expect(String(span.props.style)).toContain('#9e9e9e')
    expect(span.children).toBe('No scannable components')
    expect(String(span.props.title).startsWith(`Nothing to scan in ${notScannableBoms} BOMs: `)).toBe(true)
    expect(String(span.props.title)).toContain(reasonLabel('ONLY_ROOT'))
    expect(span.props['data-testid']).toBeUndefined()
}
function expectCircles (cells: { vuln: VNode[], viol: VNode[] }) {
    const v = slotOf(cells.vuln[0])
    expect(circlesOf(v)).toHaveLength(5)
    expect(circlesOf(slotOf(cells.viol[0]))).toHaveLength(3)
    return v
}

describe('release scan cells over a served BOM scan tally (TPF-133)', () => {
    it('(a) a branch row served ALL_NOT_SCANNABLE: one grey badge per column, no circle, no note', () => {
        const { vuln, viol } = cellsOf(branchRow(SERVED_ALL))
        expectGreyBadge(vuln, 2)
        expectGreyBadge(viol, 2)
    })

    it('(b) a product branch row served ALL_NOT_SCANNABLE keeps its circles (the guard reads componentDetails.type)', () => {
        const v = expectCircles(cellsOf(branchRow(SERVED_ALL, { type: 'PRODUCT' })))
        expect(notesOf(v)).toHaveLength(0)
    })

    it('(c) a branch row served SOME_NOT_SCANNABLE: circles, no badge, no note (untyped artifacts)', () => {
        const v = expectCircles(cellsOf(branchRow({ ...SERVED_ALL, state: 'SOME_NOT_SCANNABLE', scannableBoms: 1 })))
        expect(notesOf(v)).toHaveLength(0)
    })

    it.each([['NO_BOMS'], ['ALL_SCANNABLE']])('(d) a branch row served %s: circles', (state) => {
        expectCircles(cellsOf(branchRow({ state, scannableBoms: state === 'NO_BOMS' ? 0 : 1, notScannableBoms: 0, reasons: [] })))
    })

    it('(e) a null tally and an absent key: circles as today; unanchored besides: the yellow Scan pending badge', () => {
        expectCircles(cellsOf(branchRow(null)))
        expectCircles(cellsOf(branchRow(undefined, { omit: true })))
        for (const row of [branchRow(null, { firstScanned: null }), branchRow(undefined, { omit: true, firstScanned: null })]) {
            const { vuln } = cellsOf(row)
            expect(vuln).toHaveLength(1)
            expect((vuln[0] as any).type).toBe('span')
            expect(String((vuln[0] as any).props.style)).toContain('#ffc107')
            expect((vuln[0] as any).children).toBe('Scan pending')
        }
    })

    it('(f) a Latest-tab row with one marked own BOM: served ALL_NOT_SCANNABLE gives the grey badge, no note; SOME_NOT_SCANNABLE five circles and the note', () => {
        const latestRow = (bomScan: any) => ({ uuid: 'r1', lifecycle: 'ASSEMBLED', componentDetails: { type: 'COMPONENT', kind: 'GENERIC' },
            metrics: { ...ANCHORED, bomScan },
            artifactDetails: [{ type: 'BOM', enrichmentStatus: 'COMPLETED', metrics: { dtrackSubmissionFailed: false, dependencyTrackFullUri: null, firstScanned: null, notScannable: m } }] })
        const all = cellsOf(latestRow({ ...SERVED_ALL, notScannableBoms: 1, reasons: ['ONLY_ROOT'] }))
        expect(all.vuln).toHaveLength(1)
        expect((all.vuln[0] as any).type).toBe('span')
        expect(String((all.vuln[0] as any).props.style)).toContain('#9e9e9e')
        expect((all.vuln[0] as any).children).toBe('No scannable components')
        const some = cellsOf(latestRow({ state: 'SOME_NOT_SCANNABLE', scannableBoms: 1, notScannableBoms: 1, reasons: ['ONLY_ROOT'] }))
        const v = expectCircles(some)
        expect(notesOf(v)).toHaveLength(1)
        expect((notesOf(v)[0] as any).children).toBe('1 BOM not scannable')
    })
})
