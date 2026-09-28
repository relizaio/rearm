import { describe, expect, it } from 'vitest'
import { approvalLifetimeProblem, describeMinutes, hardEndCell, MAX_SESSION_MINUTES, sessionEnd, sessionEndSentence,
    sessionMinutes, sessionMinutesProblem } from './cliSessionLifetime'

const iso = (d: Date) => d.toISOString()
const APPROVED = new Date('2026-09-28T10:00:00Z')

describe('cliSessionLifetime', () => {
    it('takes a key limit of 1 to 90 days in whole minutes, or none', () => {
        expect(MAX_SESSION_MINUTES).toBe(129600)
        for (const ok of [null, undefined, 1, 30, 60, 90, 129600]) expect(sessionMinutesProblem(ok)).toBe('')
        for (const bad of [0, -5, 129601, 1.5]) {
            expect(sessionMinutesProblem(bad)).toBe('sessionMaxMinutes is 1 to 129600 minutes, or empty for no bound')
        }
    })

    it('lets the approver shorten the key limit, never lengthen it', () => {
        expect(approvalLifetimeProblem(90, null)).toBe('')
        expect(approvalLifetimeProblem(90, 30)).toBe('')
        expect(approvalLifetimeProblem(90, 90)).toBe('')
        expect(approvalLifetimeProblem(90, 91)).toBe('This key bounds its sessions to 90 minutes; choose 90 or fewer')
        expect(approvalLifetimeProblem(null, 600)).toBe('')
        expect(approvalLifetimeProblem(null, 129601)).toBe('A session lasts 1 to 129600 minutes')
        expect(approvalLifetimeProblem(90, 0)).toBe('A session lasts 1 to 129600 minutes')
    })

    it('works out the hard end the approval gives', () => {
        expect(sessionMinutes(90, null)).toBe(90)
        expect(sessionMinutes(90, 30)).toBe(30)
        expect(sessionMinutes(null, null)).toBeNull()
        expect(sessionEnd(APPROVED, null, null)).toBeNull()
        expect(iso(sessionEnd(APPROVED, 90, null) as Date)).toBe('2026-09-28T11:30:00.000Z')
        expect(iso(sessionEnd(APPROVED, 90, 30) as Date)).toBe('2026-09-28T10:30:00.000Z')
        expect(iso(sessionEnd(APPROVED, null, 45) as Date)).toBe('2026-09-28T10:45:00.000Z')
    })

    it('says what the approval does, refresh included', () => {
        expect(sessionEndSentence(APPROVED, null, null, iso)).toBe('No hard end: the session slides 30 days with each use, never past 90 days after approval.')
        expect(sessionEndSentence(APPROVED, 30, null, iso))
            .toBe('The session ends at 2026-09-28T10:30:00.000Z (30 minutes), refreshes included. The CLI gets one access token and no refresh.')
        expect(sessionEndSentence(APPROVED, 90, 60, iso)).toContain('no refresh')
        expect(sessionEndSentence(APPROVED, 90, null, iso)).toBe('The session ends at 2026-09-28T11:30:00.000Z (1 hour 30 minutes), refreshes included.')
    })

    it('names a lifetime in days, hours and minutes', () => {
        expect(describeMinutes(1)).toBe('1 minute')
        expect(describeMinutes(30)).toBe('30 minutes')
        expect(describeMinutes(60)).toBe('1 hour')
        expect(describeMinutes(90)).toBe('1 hour 30 minutes')
        expect(describeMinutes(1440)).toBe('1 day')
        expect(describeMinutes(129600)).toBe('90 days')
    })

    it('shows a dash for a session with no hard end', () => {
        expect(hardEndCell(null, iso)).toBe('—')
        expect(hardEndCell(undefined, iso)).toBe('—')
        expect(hardEndCell('2026-09-28T11:30:00Z', iso)).toBe('2026-09-28T11:30:00.000Z')
    })
})
