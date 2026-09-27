import { describe, expect, it } from 'vitest'
import { approveConfirm, approveLabel, gateBlockingFindings, gateDecisions, gatedSignOff, gateRejected, rejectLabel,
    undecided } from './agentGateReview'

// A rejected gate (task RD2-25): what blocks, what the person must decide, and the labels.
const finding = (id: string, priority: number | null, extra: any = {}) => ({ id, priority, status: 'OPEN', title: `about ${id}`, ...extra })
const hold = { kind: 'HUMAN_GATE', gateRole: 'reviewer' }
function gated (outcome: string, findings: any[], other: any[] = []) {
    return {
        hold,
        signOffs: [
            { role: 'reviewer', outcome: 'PASSED', outputs: ['old'] },
            { role: 'coder', outcome: 'PASSED', outputs: ['c1'] },
            { role: 'reviewer', outcome, outputs: ['r2'] },
        ],
        documents: [
            { uuid: 'r2', document: { findings: { findings } } },
            { uuid: 'old', document: { findings: { findings: other } } },
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
        const t = gated('REJECTED', [finding('F-1', 1), finding('F-2', 3), finding('F-3', 1, { status: 'RESOLVED' }),
            finding('F-4', 1, { correction: true }), finding('F-5', null)], [finding('X-1', 1)])
        const strict = gateBlockingFindings(t, { blockingPriority: null })
        expect(strict.map(f => f.id)).toEqual(['F-1', 'F-2', 'F-5'])
        const lax = gateBlockingFindings(t, { blockingPriority: 1 })
        expect(lax.map(f => f.id), 'P3 is below the line; unprioritised blocks').toEqual(['F-1', 'F-5'])
        expect(gateBlockingFindings(t, undefined).map(f => f.id)).toEqual(['F-1', 'F-2', 'F-5'])
    })

    it('needs an action and words on every blocking item, and sends one decision each', () => {
        const blocking = [finding('F-1', 1), finding('F-2', 1)]
        expect(undecided(blocking, {}).map(f => f.id)).toEqual(['F-1', 'F-2'])
        const partly = { 'F-1': { action: 'ACCEPT' as const, reason: 'ship it' }, 'F-2': { action: 'DISMISS' as const, reason: '  ' } }
        expect(undecided(blocking, partly).map(f => f.id), 'blank words are no decision').toEqual(['F-2'])
        const all = { ...partly, 'F-2': { action: 'DISMISS' as const, reason: ' not a bug ' } }
        expect(undecided(blocking, all)).toEqual([])
        expect(gateDecisions(blocking, all)).toEqual([
            { action: 'ACCEPT', findingId: 'F-1', resolution: 'ship it' },
            { action: 'DISMISS', findingId: 'F-2', resolution: 'not a bug' },
        ])
        expect(approveConfirm(blocking, all)).toBe('Approving past F-1 (P1): accepted · F-2 (P1): dismissed')
    })

    it('never says pass on a rejection, and leads with sending it back', () => {
        const t = gated('REJECTED', [])
        expect(approveLabel(t, true, [finding('F-1', 1)], false)).toBe('Approve past the findings')
        expect(approveLabel(t, true, [], false)).toBe('Approve anyway')
        expect(approveLabel(t, false, [], false)).toBe('Approve reviewer pass')
        expect(approveLabel(t, false, [], true)).toBe('Approve with correction')
        for (const l of [approveLabel(t, true, [], true), approveLabel(t, true, [finding('F-1', 1)], true)]) expect(l).not.toMatch(/pass/)
        expect(rejectLabel(true, false)).toBe('Reject (send back)')
        expect(rejectLabel(true, true)).toBe('Reject (send back) with finding')
        expect(rejectLabel(false, false)).toBe('Reject')
    })
})
