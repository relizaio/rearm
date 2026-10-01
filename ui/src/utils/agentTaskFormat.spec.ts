import { describe, expect, it } from 'vitest'
import { agentName, dur, hopHistory, isTerminal, missingRequiredRoles, requiredSignOffsLine, roleName, shortId, statusRowWords,
    statusTone, taskLabel, taskPagePath, ts, tsDate, tsFull } from './agentTaskFormat'

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
        expect(taskLabel({ key: 'RD-42', externalRef: 'github:relizaio/rearm#42', title: 'x' })).toBe('RD-42')
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

    // RD2-23 (sweep UI-16): one format on every board surface, "2026-09-27 15:36", local time.
    it('writes a time as ISO date and local time, seconds only within a minute of now', () => {
        const at = new Date(2026, 8, 27, 15, 36, 12).toISOString()
        const later = new Date(2026, 8, 27, 18, 0, 0).getTime()
        expect(ts(at, later)).toBe('2026-09-27 15:36')
        expect(ts(at, new Date(2026, 8, 27, 15, 36, 40).getTime())).toBe('2026-09-27 15:36:12')
        expect(ts(new Date(2026, 0, 5, 9, 7).toISOString(), later)).toBe('2026-01-05 09:07')
        expect(tsFull(at)).toMatch(/^2026-09-27 15:36:12 UTC[+-]\d\d:\d\d$/)
        expect(tsFull(null)).toBe('')
        expect(tsDate(at)).toBe('2026-09-27')
        expect(tsDate(undefined)).toBe('—')
    })

    // RD2-23 (sweep UI-50): routing's note in place of the bare trigger; other rows as before.
    it('words a status-history row, with routing\'s reason when it wrote one', () => {
        const routing = { kind: 'SYSTEM', name: 'routing' }
        expect(statusRowWords({ from: 'QUEUED', to: 'QUEUED', trigger: 'AUTHORIZE', actor: routing,
            note: 'review items decided; back to designer' }))
            .toEqual({ arrow: 'queued → queued', routing: 'routing: review items decided; back to designer', trigger: 'authorize' })
        expect(statusRowWords({ from: 'QUEUED', to: 'QUEUED', trigger: 'AUTHORIZE', actor: routing, note: null }).routing)
            .toBeNull()
        expect(statusRowWords({ from: 'PENDING_INTAKE', to: 'QUEUED', trigger: 'AUTHORIZE',
            actor: { kind: 'USER', name: 'pavel' }, note: 'go' }).routing).toBeNull()
        expect(statusRowWords({ from: null, to: 'PENDING_INTAKE', trigger: 'REGISTER' }).arrow).toBe('· → pending intake')
    })

    // RD2-23 (sweep UI-32): the roles still to pass, as their own line.
    it('lines up the required sign-offs still missing', () => {
        expect(requiredSignOffsLine(['reviewer', 'coder'])).toBe('Required sign-offs: reviewer ✗ · coder ✗')
        expect(requiredSignOffsLine([])).toBeNull()
    })
})
