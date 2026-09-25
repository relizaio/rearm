import { describe, it, expect } from 'vitest'
import { DELIVERY_MODE_OPTIONS, MERGE_BY_OPTIONS, MERGE_METHOD_OPTIONS, MERGE_ORDER_OPTIONS, deliveryPolicyPatch, headLine,
    mergeDraftOf, mergeOf, prChips, prKey, shortPr } from './agentDelivery'

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

describe('tested heads (task 3b97ccfd)', () => {
    const A = 'aaaaaaa1111111111111111111111111111111aa'
    const B = 'bbbbbbb2222222222222222222222222222222bb'

    it('shows the tested head against the PR head, and flags a PR past it', () => {
        const chips = prChips({
            testedHeads: [{ pr: 'https://github.com/acme/app/pull/1/', head: 'aaaaaaa1' },
                { pr: 'https://GitHub.com/acme/app/pull/2', head: 'aaaaaaa1' }],
            pullRequests: [
                { url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true, head: A },
                { url: 'https://github.com/acme/app/pull/2', state: 'OPEN', registered: true, head: B },
                { url: 'https://github.com/acme/app/pull/3', state: 'OPEN', registered: true, head: B }
            ]
        })
        expect(chips[0]).toMatchObject({ heads: 'tested aaaaaaa · the PR is at it', moved: false })
        expect(chips[1]).toMatchObject({ heads: 'tested aaaaaaa · now bbbbbbb: moved past the tested head', moved: true })
        expect(chips[2].heads).toBe('head bbbbbbb · no passing review or test names a head')
        expect(chips[0].state).toBe('open')
    })

    it('says nothing about heads on a read that carries none', () => {
        const chip = prChips({ pullRequests: [{ url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true }] })[0]
        expect(chip.heads).toBeUndefined()
        expect(headLine(undefined, undefined)).toEqual({})
        expect(headLine('aaaaaaa1', undefined)).toEqual({ heads: 'tested aaaaaaa' })
    })

    it('matches a PR URL as the board does', () => {
        expect(prKey('https://GitHub.com/acme/app.git/')).toBe(prKey('https://github.com/acme/app'))
        expect(prKey('https://github.com/acme/app/pull/1?x=1#y')).toBe('https://github.com/acme/app/pull/1')
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

    it('keeps the tested-head line on an attested chip (with task 3b97ccfd)', () => {
        const A = 'aaaaaaa1111111111111111111111111111111aa'
        const B = 'bbbbbbb2222222222222222222222222222222bb'
        const chips = prChips({
            testedHeads: [{ pr: 'https://github.com/acme/app/pull/1', head: A }, { pr: 'https://github.com/acme/app/pull/2', head: A }],
            pullRequests: [
                { url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true, head: B,
                    attestation: { outcome: 'DELIVERED', commit: 'ccccccc3', by: { kind: 'USER', name: 'op' }, note: null } },
                { url: 'https://github.com/acme/app/pull/2', state: null, registered: false,
                    attestation: { outcome: 'DELIVERED', commit: A, by: { kind: 'USER', name: 'op' }, note: null } }
            ]
        })
        expect(chips[0]).toMatchObject({ state: 'merged', title: 'attested by op at ccccccc',
            heads: 'tested aaaaaaa · now bbbbbbb: moved past the tested head', moved: true })
        expect(chips[1]).toMatchObject({ state: 'merged', title: 'attested by op at aaaaaaa', heads: 'tested aaaaaaa' })
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

describe('the merge procedure (task 71a3dd22)', () => {
    it('offers who merges, the method and the order, with help for who', () => {
        expect(MERGE_BY_OPTIONS.map(o => o.value)).toEqual(['COORDINATOR', 'ROLE', 'PERSON'])
        expect(MERGE_BY_OPTIONS.every(o => o.help.length > 20)).toBe(true)
        expect(MERGE_BY_OPTIONS[0].help).toContain('PR_MERGE')
        expect(MERGE_METHOD_OPTIONS.map(o => o.value)).toEqual(['MERGE', 'SQUASH', 'REBASE', 'FAST_FORWARD'])
        expect(MERGE_ORDER_OPTIONS.map(o => o.value)).toEqual(['NOTE_ORDER', 'OLDEST_PASS_FIRST'])
    })

    it('reads a declared procedure into the form and sends only what differs from the defaults', () => {
        const defaults = mergeDraftOf(null)
        expect(defaults).toEqual({ by: null, byRole: '', method: null, atTestedHead: true, requireAttestation: false, order: null })
        expect(mergeOf(defaults, 'PR_ROWS')).toBeNull()
        const declared = mergeDraftOf({ merge: { by: 'ROLE:releaser', method: 'SQUASH', atTestedHead: false,
            requireAttestation: true, order: 'OLDEST_PASS_FIRST' } })
        expect(declared).toEqual({ by: 'ROLE', byRole: 'releaser', method: 'SQUASH', atTestedHead: false,
            requireAttestation: true, order: 'OLDEST_PASS_FIRST' })
        expect(mergeOf(declared, 'PR_ROWS')).toEqual({ by: 'ROLE:releaser', method: 'SQUASH', atTestedHead: false,
            requireAttestation: true, order: 'OLDEST_PASS_FIRST' })
        expect(mergeOf(declared, 'ATTESTED')!.requireAttestation).toBeNull()
        expect(mergeOf({ ...defaults, by: 'ROLE', byRole: '  ' }, null)).toBeNull()
        expect(mergeOf({ ...defaults, by: 'PERSON' }, null)).toEqual({ by: 'PERSON', method: null, atTestedHead: null,
            requireAttestation: null, order: null })
    })

    it('patches deliveryPolicy with the procedure, and keeps the board\'s when the form has none', () => {
        const squash = { ...mergeDraftOf(null), by: 'COORDINATOR', method: 'SQUASH' }
        expect(deliveryPolicyPatch({ deliveryPolicy: null }, null, false, squash)).toEqual({ changed: true,
            value: { mode: null, attest: false, merge: { by: 'COORDINATOR', method: 'SQUASH', atTestedHead: null,
                requireAttestation: null, order: null } } })
        const board = { deliveryPolicy: { mode: 'PR_ROWS', attest: false,
            merge: { by: 'COORDINATOR', method: 'SQUASH', atTestedHead: true, requireAttestation: false, order: null } } }
        expect(deliveryPolicyPatch(board, 'PR_ROWS', false, mergeDraftOf(board.deliveryPolicy)).changed).toBe(false)
        expect(deliveryPolicyPatch(board, 'PR_ROWS', false, mergeDraftOf(null))).toEqual({ changed: true,
            value: { mode: 'PR_ROWS', attest: false } })
        expect(deliveryPolicyPatch(board, 'ATTESTED', false).value!.merge).toEqual({ by: 'COORDINATOR', method: 'SQUASH',
            atTestedHead: null, requireAttestation: null, order: null })
        expect(deliveryPolicyPatch(board, null, false, mergeDraftOf(null))).toEqual({ changed: true, value: null })
    })
})
