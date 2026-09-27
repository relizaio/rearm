import { describe, expect, it } from 'vitest'
import { agentDirectory, agentNameOf, sessionLabel, sessionOf, sessionOfActor, sessionPath } from './agentSessionLabel'

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
