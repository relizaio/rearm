import { describe, expect, it } from 'vitest'
import { answerPayloadOf, answerableQuestions } from './agentTaskQuestions'

const q = (id: string) => ({ id, priority: 1, status: 'OPEN', title: id }) as any

describe('agentTaskQuestions', () => {
    it('offers answers only while the task is parked or with the coordinator', () => {
        const base = { questionStack: [{}], openQuestions: [q('q1')] }
        expect(answerableQuestions({ ...base, status: 'ON_HOLD' }).map(f => f.id)).toEqual(['q1'])
        expect(answerableQuestions({ ...base, status: 'AWAITING_COORDINATOR' })).toHaveLength(1)
        expect(answerableQuestions({ ...base, status: 'QUEUED' })).toEqual([])
        expect(answerableQuestions({ ...base, status: 'ON_HOLD', questionStack: [] })).toEqual([])
        expect(answerableQuestions(null)).toEqual([])
    })

    it('sends per-id answers, or one answer for all, never both', () => {
        const t = { uuid: 't1' }
        const per = answerPayloadOf(t, [q('q1'), q('q2')], { q1: ' yes ', q2: '' }, { q1: true }, 'ignored')
        expect(per.answers).toEqual([{ id: 'q1', status: 'WITHDRAWN', resolution: 'yes' }])
        expect(per.answerAll).toBeUndefined()
        const all = answerPayloadOf(t, [q('q1')], {}, {}, ' same for all ')
        expect(all.answers).toEqual([])
        expect(all.answerAll).toBe('same for all')
        expect(answerPayloadOf(t, [q('q1')], {}, {}, '  ').answerAll).toBeUndefined()
    })
})
