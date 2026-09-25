import { describe, it, expect } from 'vitest'
import { DELIVERY_MODE_OPTIONS, deliveryPolicyPatch, prChips, shortPr } from './agentDelivery'

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

describe('delivery modes and attestations (task 18c5c293)', () => {
    it('reads an attested PR as merged or abandoned, whatever its row says', () => {
        const chips = prChips({ pullRequests: [
            { url: 'https://github.com/acme/app/pull/1', state: null, registered: false,
                attestation: { outcome: 'DELIVERED', commit: 'abcdef0123456', by: { kind: 'SESSION', uuid: '83922fa1-307e', name: null }, note: 'merged on reliza' } },
            { url: 'https://github.com/acme/app/pull/2', state: 'OPEN', registered: true,
                attestation: { outcome: 'ABANDONED', by: { kind: 'USER', name: 'pavel@reliza.io' }, note: 'superseded' } },
            { url: 'https://github.com/acme/app/pull/3', state: 'MERGED', targetBranch: 'main', registered: true }
        ] })
        expect(chips[0]).toMatchObject({ state: 'merged', type: 'success', title: 'attested by session 83922fa1 at abcdef0: merged on reliza' })
        expect(chips[1]).toMatchObject({ state: 'abandoned', type: 'error', title: 'attested abandoned by pavel@reliza.io: superseded' })
        expect(chips[2].title).toBe('merged (CI) into main')
    })

    it('offers the three modes with help, and sends the policy only when changed', () => {
        expect(DELIVERY_MODE_OPTIONS.map(o => o.value)).toEqual(['PR_ROWS', 'ATTESTED', 'NONE'])
        expect(DELIVERY_MODE_OPTIONS.every(o => o.help.length > 20)).toBe(true)
        expect(deliveryPolicyPatch({ deliveryPolicy: null }, null, false)).toEqual({ changed: false, value: null })
        expect(deliveryPolicyPatch({ deliveryPolicy: null }, 'ATTESTED', true)).toEqual({ changed: true, value: { mode: 'ATTESTED', attest: false } })
        expect(deliveryPolicyPatch({ deliveryPolicy: { mode: 'NONE', attest: true } }, 'NONE', true).changed).toBe(false)
        expect(deliveryPolicyPatch({ deliveryPolicy: { mode: 'NONE', attest: true } }, 'NONE', false))
            .toEqual({ changed: true, value: { mode: 'NONE', attest: false } })
        expect(deliveryPolicyPatch({ deliveryPolicy: { mode: 'ATTESTED' } }, null, false))
            .toEqual({ changed: true, value: null })
        expect(deliveryPolicyPatch(null, 'PR_ROWS', false)).toEqual({ changed: true, value: { mode: 'PR_ROWS', attest: false } })
    })
})
