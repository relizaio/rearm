import { describe, expect, it } from 'vitest'
import { boardWarningShown, coverageLines } from './agentBoardCoverage'

// The board warning (task 5c70990d): capability gaps and what no key can do, in one alert.
describe('the board warning', () => {
    const gaps = [
        { function: 'BOARD_AGENT', message: 'no key with BOARD_AGENT covers this board; its agent roles cannot take work' },
        { function: 'BOARD_WRITE', message: 'no key with BOARD_WRITE can coordinate this board' },
    ]

    it('shows each coverage gap with where it is fixed', () => {
        expect(coverageLines(gaps)).toEqual([
            'no key with BOARD_AGENT covers this board; its agent roles cannot take work — grant it on the Permissions page.',
            'no key with BOARD_WRITE can coordinate this board — grant it on the Permissions page.',
        ])
        expect(coverageLines(null)).toEqual([])
        expect(coverageLines([{ function: 'BOARD_AGENT', message: null }])).toEqual([])
    })

    it('shows for a missing capability, a coverage gap, or both, and not for neither', () => {
        expect(boardWarningShown(['CODE_PUSH'], [])).toBe(true)
        expect(boardWarningShown([], gaps)).toBe(true)
        expect(boardWarningShown(['PR_MERGE'], gaps)).toBe(true)
        expect(boardWarningShown([], [])).toBe(false)
        expect(boardWarningShown(null, null)).toBe(false)
    })
})
