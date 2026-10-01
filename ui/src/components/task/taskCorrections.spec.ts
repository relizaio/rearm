// @vitest-environment happy-dom
//
// A correction (task cac71351): an item a person filed while accepting at a gate. Its row carries a
// chip, and it stays listed with the open review items.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskReviewItems from './TaskReviewItems.vue'
import { isCorrection } from '@/utils/agentDocuments'

function task (reviewItems: any[]) {
    return {
        uuid: 't1', status: 'AWAITING_COORDINATOR',
        documents: [{ uuid: 'r1', lifecycle: 'READY_TO_SHIP', document: { specification: 'BOARD_REVIEW_ITEMS', round: 2,
            reviewItems: { kind: 'BOARD_REVIEW_ITEMS', verdict: 'PASSED', reviewItems } } }],
    }
}

describe('corrections', () => {
    it('isCorrection reads the flag, and only true', () => {
        expect(isCorrection({ correction: true })).toBe(true)
        expect(isCorrection({ correction: null })).toBe(false)
        expect(isCorrection({})).toBe(false)
        expect(isCorrection(null)).toBe(false)
    })

    it('marks a correction row with a chip and keeps it among the open review items', () => {
        const w = mount(TaskReviewItems, { props: { task: task([
            { id: 'F-1', priority: 2, status: 'OPEN', title: 'an agent review item' },
            { id: 'P-1', priority: 1, status: 'OPEN', title: 'rename the flag', correction: true,
                decidedBy: { kind: 'USER', name: 'pm@example.com' } },
        ]) } as any })
        const rows = w.findAll('.frow')
        expect(rows.map(r => r.find('.frow__id').text())).toEqual(['P-1', 'F-1'])
        expect(rows[0].find('.frow__corr').exists()).toBe(true)
        expect(rows[0].find('.frow__corr').text()).toBe('correction')
        expect(rows[1].find('.frow__corr').exists()).toBe(false)
        expect(rows[0].classes()).not.toContain('frow--closed')
    })
})
