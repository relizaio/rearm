import { describe, it, expect } from 'vitest'
import { latestRound, replacedByLabel } from './agentDocuments'

// Task RD4-7: a hop that publishes the same path twice makes a new version of the round; the earlier
// version names its replacement in supersededBy and stays listed.
const v7 = { uuid: 'r7', version: '7', lifecycle: 'DRAFT',
    document: { specification: 'BOARD_TEST_REPORT', round: 2, reviewItems: { verdict: 'REJECTED', reviewItems: [] } } }
const v6 = { uuid: 'r6', version: '6', lifecycle: 'DRAFT',
    document: { specification: 'BOARD_TEST_REPORT', round: 2, supersededBy: 'r7', reviewItems: { verdict: 'PASSED', reviewItems: [] } } }

describe('replacedByLabel', () => {
    it('names the replacing version as the list shows it', () => {
        expect(replacedByLabel(v6, [v7, v6])).toBe('replaced by v7')
    })
    it('says replaced when the replacing release is not in the list', () => {
        expect(replacedByLabel(v6, [v6])).toBe('replaced')
    })
    it('is null on a current version', () => {
        expect(replacedByLabel(v7, [v7, v6])).toBeNull()
        expect(replacedByLabel(null, null)).toBeNull()
    })
})

describe('latestRound with a replaced version', () => {
    it('never reads a replaced version, wherever the list puts it', () => {
        expect(latestRound([v7, v6], 'BOARD_TEST_REPORT')?.uuid).toBe('r7')
        expect(latestRound([v6, v7], 'BOARD_TEST_REPORT')?.uuid).toBe('r7')
    })
})
