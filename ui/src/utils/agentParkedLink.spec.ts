// The PRs linked while the seat had a task parked (task RD4-19): from the hold while it waits, from the lift row
// after; a hop's own hold (RD4-5) and an unparked task list none.
import { describe, expect, it } from 'vitest'
import { linkedLine, linkedSinceParked, operatorQuestions } from './agentOperatorQuestion'

const seat = { kind: 'SESSION', uuid: 's-seat', name: null }
const link = { prUrl: 'https://github.com/acme/app/pull/402', by: 'Claude Code (agent 2ffebe1a)', at: '2026-10-01T10:05:00Z' }
const asked = (from: string) => ({ from, to: 'ON_HOLD', at: '2026-10-01T10:00:00Z', trigger: 'HOLD', actor: seat,
    note: 'awaiting the operator: which PR?' })
const hold = (returnTo: string | null, linked: any) => ({ level: 'OPERATOR', kind: 'MANUAL', reason: 'awaiting the operator: which PR?',
    heldBy: seat, heldAt: '2026-10-01T10:00:00Z', returnTo, linked })

describe('linked since parked', () => {
    it('reads the seat\'s hold, and nothing else', () => {
        expect(linkedSinceParked({ status: 'ON_HOLD', hold: hold('DELIVERING', [link]), statusHistory: [asked('DELIVERING')] }))
            .toEqual([link])
        expect(linkedSinceParked({ status: 'ON_HOLD', hold: hold('DELIVERING', null), statusHistory: [] })).toEqual([])
        // a hop's own hold records no returnTo: not the seat's, so no lines
        expect(linkedSinceParked({ status: 'ON_HOLD', hold: hold(null, [link]), statusHistory: [asked('ASSIGNED')] })).toEqual([])
        expect(linkedSinceParked({ status: 'DELIVERING', hold: null })).toEqual([])
    })

    it('words a line, naming a key when no agent was recorded', () => {
        expect(linkedLine(link)).toBe('since parked: PR https://github.com/acme/app/pull/402 linked by Claude Code (agent 2ffebe1a) at')
        expect(linkedLine({ ...link, by: null })).toContain('linked by an API key at')
    })

    it('gives a question its links from the hold while it waits and from the lift row after', () => {
        const waiting = operatorQuestions({ status: 'ON_HOLD', hold: hold('DELIVERING', [link]), statusHistory: [asked('DELIVERING')] })
        expect(waiting[0].answer).toBeNull()
        expect(waiting[0].linked).toEqual([link])
        const lifted = operatorQuestions({ status: 'DELIVERING', hold: null, statusHistory: [asked('DELIVERING'),
            { from: 'ON_HOLD', to: 'DELIVERING', at: '2026-10-01T10:10:00Z', trigger: 'LIFT_HOLD', actor: { kind: 'USER', name: 'Pat' },
                note: 'lifted by Pat: supersede it', linked: [link] }] })
        expect(lifted[0].answer).toBe('supersede it')
        expect(lifted[0].linked).toEqual([link])
        const older = operatorQuestions({ status: 'DELIVERING', hold: null, statusHistory: [asked('DELIVERING'),
            { from: 'ON_HOLD', to: 'DELIVERING', at: '2026-10-01T10:10:00Z', trigger: 'LIFT_HOLD', actor: { kind: 'USER', name: 'Pat' },
                note: 'lifted by Pat: go on' }] })
        expect(older[0].linked, 'a row written before links were kept').toEqual([])
    })
})
