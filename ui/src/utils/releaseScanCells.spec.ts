import { describe, it, expect, vi } from 'vitest'
import type { VNode } from 'vue'

import { renderVulnerabilityCells, renderViolationCells } from './releaseScanCells'
import type { ReleaseScanStatus } from './releaseScanStatus'

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
