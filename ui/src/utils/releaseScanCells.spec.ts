import { describe, it, expect, vi } from 'vitest'

// releaseScanStatus imports the graphql client (for the org-integration
// lookup), which transitively boots keycloak against window at module load --
// mock it out; these tests only exercise the pure status logic.
vi.mock('./graphql', () => ({ default: { query: vi.fn() } }))

import type { VNode } from 'vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { renderVulnerabilityCells, renderViolationCells, renderPendingBadge, renderNotScannableNote } from './releaseScanCells'
import type { ReleaseScanStatus } from './releaseScanStatus'
import { notScannableTitle } from './artifactScanStatus'

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
