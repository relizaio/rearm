import { describe, expect, it } from 'vitest'
import { agentDirectory, agentNameOf, agentOfSession, sessionLabel, sessionOf, sessionOfActor, sessionOnTask, sessionPath } from './agentSessionLabel'

// Sessions and agents told apart (RD2-11).
describe('a session\'s label', () => {
    it('reads role first, then the name, then the short id', () => {
        expect(sessionLabel({ uuid: '3b6fa5e8-1111-2222-3333-444444444444', role: 'coder', name: 'claude-code' }))
            .toBe('coder · claude-code · 3b6fa5e8')
        expect(sessionLabel({ uuid: '3b6fa5e8-1111', name: 'claude-code' })).toBe('claude-code · 3b6fa5e8')
    })

    it('puts the key\'s note last, and only when nothing else names the session', () => {
        expect(sessionLabel({ uuid: '3b6fa5e8-1', role: 'coder', notes: 'agent board budget key' }))
            .toBe('coder · 3b6fa5e8 · agent board budget key')
        expect(sessionLabel({ uuid: '3b6fa5e8-1', name: 'claude-code', notes: 'agent board budget key' }))
            .toBe('claude-code · 3b6fa5e8')
        expect(sessionLabel({ uuid: null })).toBe('session')
    })

    it('keeps an agent\'s own name apart from the key\'s note', () => {
        expect(agentNameOf({ name: 'claude-code', effectiveDisplayName: 'agent board budget key' }))
            .toEqual({ name: 'claude-code', notes: 'agent board budget key' })
        expect(agentNameOf({ name: 'x', displayName: 'Coder bot', effectiveDisplayName: 'Coder bot' }))
            .toEqual({ name: 'Coder bot', notes: null })
        expect(agentNameOf({ effectiveDisplayName: 'agent board budget key' })).toEqual({ name: null, notes: 'agent board budget key' })
        const dir = agentDirectory([{ uuid: 'a1', name: 'claude-code', effectiveDisplayName: 'budget key' }, { name: 'no uuid' }])
        expect(Object.keys(dir)).toEqual(['a1'])
        expect(sessionLabel(sessionOf('3b6fa5e8-1', 'a1', dir, 'designer'))).toBe('designer · claude-code · 3b6fa5e8')
        expect(sessionLabel(sessionOf('3b6fa5e8-1', 'unknown', dir))).toBe('3b6fa5e8')
    })

    it('reads a SESSION actor, and nothing else', () => {
        expect(sessionOfActor({ kind: 'SESSION', uuid: 's1', name: 'budget key' }, 'tester'))
            .toEqual({ uuid: 's1', role: 'tester', name: null, notes: 'budget key' })
        expect(sessionOfActor({ kind: 'USER', uuid: 'u1', name: 'pavel' })).toBeNull()
        expect(sessionPath('s1')).toBe('/aiAgentSession/s1')
    })
})

describe('a session named by the task it appears on (RD2-11 run 1, T-2)', () => {
    const task = {
        assignment: { session: 's-open', agent: 'a2', role: 'tester' },
        signOffs: [{ session: 's-done', agent: 'a1', role: 'coder', signedOffAt: '2026-09-27T09:00:00Z' }],
        returns: [{ session: 's-ret', agent: 'a1', role: 'reviewer', returnedAt: '2026-09-27T10:00:00Z' }],
    }
    const dir = agentDirectory([{ uuid: 'a1', name: 'claude-code' }, { uuid: 'a2', displayName: 'codex' }])

    it('finds the agent from the assignment, sign-offs or returns', () => {
        expect(agentOfSession(task, 's-open')).toBe('a2')
        expect(agentOfSession(task, 's-done')).toBe('a1')
        expect(agentOfSession(task, 's-ret')).toBe('a1')
        expect(agentOfSession(task, 'other')).toBeNull()
    })

    it('labels it role first, a known role overriding the task\'s', () => {
        expect(sessionLabel(sessionOnTask(task, 's-open', dir))).toBe('tester · codex · s-open')
        expect(sessionLabel(sessionOnTask(task, 's-done', dir))).toBe('coder · claude-code · s-done')
        expect(sessionLabel(sessionOnTask(task, 's-done', dir, 'designer'))).toBe('designer · claude-code · s-done')
        expect(sessionLabel(sessionOnTask(task, 'unknown1', dir))).toBe('unknown1', 'a session the task does not know')
    })
})
