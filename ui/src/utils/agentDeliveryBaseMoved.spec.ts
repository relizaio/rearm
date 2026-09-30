import { describe, it, expect } from 'vitest'
import { baseMovedLine, prChips } from './agentDelivery'

// Registered PRs are consulted (task RD4-2): the task page's PR row says how far the PR's base moved since the
// task's newest round, from baseMovedBy on the task read.
describe('base moved since your round (task RD4-2)', () => {
    it('words the count, and says nothing for null or zero', () => {
        expect(baseMovedLine(3)).toEqual({ baseMoved: 'base moved: 3 commits since your round' })
        expect(baseMovedLine(1)).toEqual({ baseMoved: 'base moved: 1 commit since your round' })
        expect(baseMovedLine(0)).toEqual({})
        expect(baseMovedLine(null)).toEqual({})
        expect(baseMovedLine(undefined)).toEqual({})
    })

    it('puts the line on the chip of each PR whose base moved', () => {
        const chips = prChips({
            prUrls: ['https://github.com/acme/app/pull/1', 'https://github.com/acme/app/pull/2', 'https://github.com/acme/app/pull/3'],
            pullRequests: [
                { url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true, baseMovedBy: 2 },
                { url: 'https://github.com/acme/app/pull/2', state: 'OPEN', registered: true, baseMovedBy: 0 },
                { url: 'https://github.com/acme/app/pull/3', state: null, registered: false, baseMovedBy: null }
            ]
        })
        expect(chips.map(c => c.baseMoved)).toEqual(['base moved: 2 commits since your round', undefined, undefined])
        expect(chips[0].state).toBe('open')
    })

    it('keeps the line on an attested chip', () => {
        const [chip] = prChips({
            prUrls: ['https://github.com/acme/app/pull/1'],
            pullRequests: [{ url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true, baseMovedBy: 4,
                attestation: { outcome: 'DELIVERED', commit: 'abcdef1234', by: { name: 'ops' } } }]
        })
        expect(chip.state).toBe('merged')
        expect(chip.baseMoved).toBe('base moved: 4 commits since your round')
    })
})
