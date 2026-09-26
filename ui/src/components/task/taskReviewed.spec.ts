// @vitest-environment happy-dom
//
// Which review promoted a document (task fda2c9f1): the hop row lists what a sign-off reviewed.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHops from './TaskHops.vue'

describe('hop row reviewed chips', () => {
    it('shows what a review promoted and what a guard kept back', () => {
        const task = {
            uuid: 't1', documents: [],
            signOffs: [
                { role: 'designer', outcome: 'PASSED', signedOffAt: '2026-09-26T01:00:00Z', outputs: [] },
                { role: 'reviewer', outcome: 'PASSED', signedOffAt: '2026-09-26T02:00:00Z', outputs: [],
                    reviewedInputs: [
                        { release: 'a1', specification: 'ARCHITECTURE', round: null, promotedTo: 'READY_TO_SHIP' },
                        { release: 'b2', specification: 'DETAILED_DESIGN', round: 2, promotedTo: null },
                    ],
                    refusedPromotions: [{ release: 'b2', specification: 'DETAILED_DESIGN', round: 2, reason: 'ships only by a person' }] },
            ],
            returns: [],
        }
        const w = mount(TaskHops, { props: { task, agentNames: {} } })
        const chips = w.findAll('.hist__rev').map(c => c.text())
        expect(chips).toEqual(['reviewed: ARCHITECTURE → READY_TO_SHIP', 'reviewed: DETAILED_DESIGN round 2 — not promoted'])
        expect(w.findAll('.hist__reviewed')).toHaveLength(1)
    })
})
