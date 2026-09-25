import { describe, expect, it } from 'vitest'
import { agentName, dur, hopHistory, isTerminal, missingRequiredRoles, roleName, shortId, statusTone,
    taskLabel, taskPagePath, ts } from './agentTaskFormat'

describe('agentTaskFormat', () => {
    it('formats times and durations', () => {
        expect(ts(null)).toBe('—')
        expect(ts('not a date')).toBe('—')
        expect(dur('2026-09-24T10:00:00Z', '2026-09-24T10:45:00Z')).toBe('45m')
        expect(dur('2026-09-24T10:00:00Z', '2026-09-24T13:05:00Z')).toBe('3h 5m')
        expect(dur('2026-09-20T10:00:00Z', '2026-09-24T10:00:00Z')).toBe('4d')
        expect(dur('2026-09-24T10:00:00Z', '2026-09-24T09:00:00Z')).toBe('')
        expect(dur(null, null)).toBe('')
    })

    it('labels a task by its tracker number, else a cut title', () => {
        expect(taskLabel({ externalRef: 'github:relizaio/rearm#42', title: 'x' })).toBe('#42')
        expect(taskLabel({ title: 'short' })).toBe('short')
        expect(taskLabel({ title: 'a title well over twenty characters' })).toBe('a title well over t…')
        expect(taskLabel({})).toBe('task')
    })

    it('resolves names with short-uuid fallbacks', () => {
        expect(shortId('0123456789abcdef')).toBe('01234567')
        expect(agentName({ a1: 'Arch' }, 'a1')).toBe('Arch')
        expect(agentName({}, '0123456789abcdef')).toBe('01234567')
        expect(agentName({}, null)).toBe('—')
        expect(roleName([{ uuid: 'r1', name: 'coder' }], 'r1')).toBe('coder')
        expect(roleName([], '0123456789abcdef')).toBe('01234567')
        expect(roleName([], null)).toBe('')
    })

    it('tones statuses', () => {
        expect(statusTone('COMPLETED')).toBe('success')
        expect(statusTone('DELIVERING')).toBe('info')
        expect(statusTone('ON_HOLD')).toBe('error')
        expect(statusTone('CANCELLED')).toBe('error')
        expect(statusTone('ASSIGNED')).toBe('warning')
        expect(statusTone('QUEUED')).toBe('default')
    })

    it('mirrors the completion gate for required roles', () => {
        const roles = [
            { name: 'coder', active: true, necessity: 'REQUIRED' },
            { name: 'reviewer', active: true, necessity: 'REQUIRED' },
            { name: 'extra', active: true, necessity: 'OPTIONAL' },
            { name: 'old', active: false, necessity: 'REQUIRED' },
        ]
        const task = { status: 'QUEUED', signOffs: [
            { role: 'Coder', outcome: 'PASSED' }, { role: 'reviewer', outcome: 'PASSED' }, { role: 'reviewer', outcome: 'REJECTED' },
        ] }
        expect(missingRequiredRoles(task, roles)).toEqual(['reviewer'])
        expect(missingRequiredRoles({ ...task, status: 'COMPLETED' }, roles)).toEqual([])
        expect(missingRequiredRoles({ ...task, childTasks: ['c'] }, roles)).toEqual([])
        expect(missingRequiredRoles(null, roles)).toEqual([])
        expect(isTerminal({ status: 'CANCELLED' })).toBe(true)
    })

    it('interleaves sign-offs and returns by time', () => {
        const h = hopHistory({
            signOffs: [{ role: 'b', signedOffAt: '2026-09-23T00:00:00Z' }, { role: 'a', signedOffAt: '2026-09-21T00:00:00Z' }],
            returns: [{ role: 'r', returnedAt: '2026-09-22T00:00:00Z' }],
        })
        expect(h.map(e => `${e.kind}:${e.rec.role}`)).toEqual(['signoff:a', 'return:r', 'signoff:b'])
        expect(hopHistory(null)).toEqual([])
    })

    it('builds the task page path', () => {
        expect(taskPagePath('t1')).toBe('/aiAgentTask/t1')
    })
})
