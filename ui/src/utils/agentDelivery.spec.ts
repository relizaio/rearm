import { describe, it, expect } from 'vitest'
import { prChips, shortPr } from './agentDelivery'

describe('prChips', () => {
    it('colours each linked PR by what CI reported', () => {
        const chips = prChips({ pullRequests: [
            { url: 'https://github.com/acme/app/pull/1', state: 'MERGED', targetBranch: 'main', mergedDate: '2026-09-25', registered: true },
            { url: 'https://github.com/acme/app/pull/2', state: 'OPEN', targetBranch: 'main', registered: true },
            { url: 'https://github.com/acme/app/pull/3', state: 'CLOSED', registered: true },
            { url: 'https://github.com/acme/app/pull/4', state: null, registered: false }
        ] })
        expect(chips.map(c => [c.state, c.type])).toEqual([
            ['merged', 'success'], ['open', 'warning'], ['closed', 'error'], ['unregistered', 'default']])
        expect(chips[0].title).toContain('into main')
        expect(chips[3].title).toContain('CI has not reported')
        expect(chips[1].label).toBe('acme/app/pull/2')
    })

    it('falls back to the linked URLs when the read has no resolved PRs', () => {
        expect(prChips({ prUrls: ['https://github.com/acme/app/pull/9'] }).map(c => c.state)).toEqual(['linked'])
        expect(prChips({})).toEqual([])
    })
})

describe('shortPr', () => {
    it('keeps owner/repo/pull/N and ignores a trailing slash', () => {
        expect(shortPr('https://github.com/acme/app/pull/12/')).toBe('acme/app/pull/12')
    })
})
