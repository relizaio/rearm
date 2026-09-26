import { describe, expect, it } from 'vitest'
import { closeAttribution, forceCloseReason, idleCloseHoursOf, isIdleWarned, IDLE_CLOSE_HOURS_DEFAULT } from './agentSessionIdle'

describe('idleCloseHoursOf', () => {
    it('is the org setting, else the server default', () => {
        expect(idleCloseHoursOf({ agentSessionIdleCloseHours: 6 })).toBe(6)
        expect(idleCloseHoursOf({ agentSessionIdleCloseHours: null })).toBe(IDLE_CLOSE_HOURS_DEFAULT)
        expect(idleCloseHoursOf(undefined)).toBe(24)
    })
})

describe('closeAttribution', () => {
    it('names who closed the session and why', () => {
        expect(closeAttribution({ status: 'CLOSED', closedBy: { kind: 'SYSTEM', name: 'idle-sweep' },
            closeReason: 'idle since 2026-09-25T21:17Z, window 24 h' }))
            .toBe('idle-sweep — idle since 2026-09-25T21:17Z, window 24 h')
        expect(closeAttribution({ status: 'CLOSED', closedBy: { kind: 'USER', uuid: 'u1', name: 'pm@example.com' },
            closeReason: 'force-closed by pm@example.com' })).toBe('pm@example.com — force-closed by pm@example.com')
    })

    it('shows what it has on older rows and nothing on an open session', () => {
        expect(closeAttribution({ status: 'CLOSED', closedBy: null, closeReason: 'closed by the agent' })).toBe('closed by the agent')
        expect(closeAttribution({ status: 'CLOSED', closedBy: { kind: 'SESSION', uuid: '2f9c1b4a-0000' } })).toBe('session 2f9c1b4a')
        expect(closeAttribution({ status: 'CLOSED' })).toBe('')
        expect(closeAttribution({ status: 'OPEN', closeReason: 'stale' })).toBe('')
    })
})

describe('isIdleWarned', () => {
    it('is an open session the sweep has warned', () => {
        expect(isIdleWarned({ status: 'OPEN', idleWarnedAt: '2026-09-26T10:00:00Z' })).toBe(true)
        expect(isIdleWarned({ status: 'OPEN', idleWarnedAt: null })).toBe(false)
        expect(isIdleWarned({ status: 'CLOSED', idleWarnedAt: '2026-09-26T10:00:00Z' })).toBe(false)
    })
})

describe('forceCloseReason', () => {
    it('sends a trimmed reason or none', () => {
        expect(forceCloseReason('  agent crashed ')).toBe('agent crashed')
        expect(forceCloseReason('   ')).toBeNull()
        expect(forceCloseReason(undefined)).toBeNull()
    })
})
