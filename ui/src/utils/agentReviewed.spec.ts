import { describe, expect, it } from 'vitest'
import { reviewedChips } from './agentReviewed'

describe('reviewedChips', () => {
    it('names each reviewed document and what the review did to it, in words (RD2-23)', () => {
        expect(reviewedChips({
            reviewedInputs: [
                { release: 'a1', specification: 'DETAILED_DESIGN', round: 2, promotedTo: 'READY_TO_SHIP' },
                { release: 'b2', specification: 'ARCHITECTURE', round: null, promotedTo: null },
                { release: 'c3', specification: 'TEST_REPORT', round: 1, promotedTo: null },
            ],
            refusedPromotions: [{ release: 'b2', specification: 'ARCHITECTURE', reason: 'ships only by a person' }],
        })).toEqual([
            { release: 'a1', label: 'reviewed: detailed design · round 2 → ready to ship', type: 'success', title: null },
            { release: 'b2', label: 'reviewed: architecture — not promoted', type: 'warning', title: 'ships only by a person' },
            { release: 'c3', label: 'reviewed: test report · round 1', type: 'default', title: null },
        ])
    })

    it('shows nothing for a hop that reviewed nothing, or an older read without the fields', () => {
        expect(reviewedChips({ reviewedInputs: null })).toEqual([])
        expect(reviewedChips({})).toEqual([])
        expect(reviewedChips(null)).toEqual([])
    })

    it('names a document it cannot resolve by its release', () => {
        expect(reviewedChips({ reviewedInputs: [{ release: '0123456789ab', promotedTo: null }] })[0].label)
            .toBe('reviewed: release 01234567')
    })
})
