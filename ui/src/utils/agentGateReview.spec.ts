import { describe, expect, it } from 'vitest'
import { acceptConfirm, acceptLabel, gateBlockingReviewItems, gateDecisions, gatedSignOff, gateRejected, rejectLabel,
    undecided } from './agentGateReview'

// A rejected gate (task RD2-25): what blocks, what the person must decide, and the labels.
const reviewItem = (id: string, priority: number | null, extra: any = {}) => ({ id, priority, status: 'OPEN', title: `about ${id}`, ...extra })
const hold = { kind: 'HUMAN_GATE', gateRole: 'reviewer' }
function gated (outcome: string, reviewItems: any[], other: any[] = []) {
    return {
        hold,
        signOffs: [
            { role: 'reviewer', outcome: 'PASSED', outputs: ['old'] },
            { role: 'coder', outcome: 'PASSED', outputs: ['c1'] },
            { role: 'reviewer', outcome, outputs: ['r2'] },
        ],
        documents: [
            { uuid: 'r2', document: { reviewItems: { reviewItems } } },
            { uuid: 'old', document: { reviewItems: { reviewItems: other } } },
        ],
    }
}

describe('the gate review rule', () => {
    it('reads the gated role\'s last sign-off and whether it rejected', () => {
        expect(gatedSignOff(gated('REJECTED', []))?.outputs).toEqual(['r2'])
        expect(gateRejected(gated('REJECTED', []))).toBe(true)
        expect(gateRejected(gated('PASSED', []))).toBe(false)
        expect(gateRejected({ ...gated('REJECTED', []), hold: { kind: 'MANUAL' } })).toBe(false)
        expect(gatedSignOff({ hold })).toBeNull()
    })

    it('finds what blocks in the gated hop\'s own index, as the server does', () => {
        const t = gated('REJECTED', [reviewItem('F-1', 1), reviewItem('F-2', 3), reviewItem('F-3', 1, { status: 'RESOLVED' }),
            reviewItem('F-4', 1, { correction: true }), reviewItem('F-5', null)], [reviewItem('X-1', 1)])
        const strict = gateBlockingReviewItems(t, { blockingPriority: null })
        expect(strict.map(f => f.id)).toEqual(['F-1', 'F-2', 'F-5'])
        const lax = gateBlockingReviewItems(t, { blockingPriority: 1 })
        expect(lax.map(f => f.id), 'P3 is below the line; unprioritised blocks').toEqual(['F-1', 'F-5'])
        expect(gateBlockingReviewItems(t, undefined).map(f => f.id)).toEqual(['F-1', 'F-2', 'F-5'])
    })

    it('needs an action and words on every blocking item, and sends one decision each', () => {
        const blocking = [reviewItem('F-1', 1), reviewItem('F-2', 1)]
        expect(undecided(blocking, {}).map(f => f.id)).toEqual(['F-1', 'F-2'])
        const partly = { 'F-1': { action: 'ACCEPT' as const, reason: 'ship it' }, 'F-2': { action: 'DISMISS' as const, reason: '  ' } }
        expect(undecided(blocking, partly).map(f => f.id), 'blank words are no decision').toEqual(['F-2'])
        const all = { ...partly, 'F-2': { action: 'DISMISS' as const, reason: ' not a bug ' } }
        expect(undecided(blocking, all)).toEqual([])
        expect(gateDecisions(blocking, all)).toEqual([
            { action: 'ACCEPT', reviewItemId: 'F-1', resolution: 'ship it' },
            { action: 'DISMISS', reviewItemId: 'F-2', resolution: 'not a bug' },
        ])
        expect(acceptConfirm(blocking, all)).toBe('Accepting past F-1 (P1): accepted · F-2 (P1): dismissed')
    })

    it('never says pass on a rejection, and leads with sending it back', () => {
        const t = gated('REJECTED', [])
        expect(acceptLabel(t, true, [reviewItem('F-1', 1)], false)).toBe('Accept past the review items')
        expect(acceptLabel(t, true, [], false)).toBe('Accept anyway')
        expect(acceptLabel(t, false, [], false)).toBe('Accept reviewer pass')
        expect(acceptLabel(t, false, [], true)).toBe('Accept with correction')
        for (const l of [acceptLabel(t, true, [], true), acceptLabel(t, true, [reviewItem('F-1', 1)], true)]) expect(l).not.toMatch(/pass/)
        expect(rejectLabel(true, false)).toBe('Reject (send back)')
        expect(rejectLabel(true, true)).toBe('Reject (send back) with review item')
        expect(rejectLabel(false, false)).toBe('Reject')
    })
})
