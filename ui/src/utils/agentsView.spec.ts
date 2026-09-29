import { describe, expect, it } from 'vitest'
import { agentLabel, agentLinks, agentsErrorText, cacheSharePercent, cacheShareTitle, staleTitle, stateTagType, stateWords } from './agentsView'

// The Agents tab's words and figures (task RD3-5).
describe('agentsView', () => {
    it('says each state', () => {
        expect(stateWords({ kind: 'WORKING', taskKey: 'RD3-5', since: null })).toBe('working RD3-5')
        expect(stateWords({ kind: 'WORKING', taskKey: 'RD3-5', since: '2026-09-29T08:00:00Z' })).toMatch(/^working RD3-5 since \d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
        expect(stateWords({ kind: 'WAITING', since: '2026-09-29T08:00:00Z' })).toMatch(/^waiting for work since /)
        expect(stateWords({ kind: 'IDLE' })).toBe('idle')
        expect(stateWords({ kind: 'CLOSED', closedBy: { kind: 'USER', name: 'ops@acme.example' }, since: null })).toBe('closed by ops@acme.example')
        expect(stateWords(null)).toBe('—')
        expect(['WORKING', 'WAITING', 'IDLE', 'CLOSED'].map(stateTagType)).toEqual(['success', 'info', 'warning', 'default'])
    })

    it('writes the cache share and its counts', () => {
        expect(cacheSharePercent(0.6)).toBe('60%')
        expect(cacheSharePercent(0.004)).toBe('0%')
        expect(cacheSharePercent(1)).toBe('100%')
        expect(cacheSharePercent(null)).toBe('—')
        expect(cacheSharePercent(undefined)).toBe('—')
        expect(cacheShareTitle({ inputTokens: 1000, cacheReadTokens: 3000, cacheWriteTokens: 1000 }))
            .toBe('3,000 cache read of 5,000 (input 1,000, cache write 1,000)')
        expect(cacheShareTitle(null)).toBeNull()
    })

    it('names the stale rules, the links and the agent', () => {
        expect(staleTitle([{ rule: 'hopNoProgress', message: 'stalled' }, { rule: 'seatSilent', message: 'quiet' }]))
            .toBe('hopNoProgress: stalled\nseatSilent: quiet')
        expect(staleTitle([])).toBeNull()
        expect(agentLinks({ session: 's1', state: { kind: 'WORKING', taskUuid: 't1' } })).toEqual({ session: '/aiAgentSession/s1', task: '/aiAgentTask/t1' })
        expect(agentLinks({ session: 's1', state: { kind: 'WAITING', taskUuid: 't1' } })).toEqual({ session: '/aiAgentSession/s1', task: null })
        expect(agentLabel({ agentName: 'scully', session: 'abcdefgh-1' })).toBe('scully')
        expect(agentLabel({ agentName: null, session: 'abcdefgh-1' })).toBe('abcdefgh')
        expect(agentsErrorText(new Error('GraphQL error: boom'))).toBe('Could not read the agents: boom')
    })
})
