import { describe, it, expect, vi } from 'vitest'
import { BULK_SET_SUPPORT_BY_SELECTOR, SELECTOR_OVER_CAP_TEXT, SUPPORT_BULK_SELECTOR_COUNT, emptySelectorChoice,
    fetchSelectorCount, selectorCountText, selectorInput, selectorRelease, submitBySelector, validateSelector }
    from './bulkSelectorForm'
import type { BulkSelectorChoice } from './bulkSelectorForm'
import { emptyBulkForm } from './useBulkAttest'
import { NO_IDENTITY_SENTENCE, SET_HASH_JUSTIFICATION, BULK_HASH_JUSTIFICATION_BY_SELECTOR, CLEAR_HASH_JUSTIFICATION,
    RELEASE_SBOM_HASHLESS_SELECTOR_COUNT, clearHashJustification, countHashlessBySelector, skippedKeysText,
    submitHashJustification, validateHashJustification } from './releaseSbomHashJustification'
import { attestationVariables, emptyAttestationForm, validateAttestation } from './supportAttestationInput'

const choice = (over: Partial<BulkSelectorChoice>): BulkSelectorChoice => ({ ...emptySelectorChoice(), ...over })
const DEB = choice({ form: 'NAMESPACE', type: 'deb', namespace: 'debian' })

function client (result: any) {
    const mutate = vi.fn(async () => ({ data: result }))
    const query = vi.fn(async (_opts: any) => ({ data: {
        supportBulkSelectorCount: { matched: 42, overCap: false },
        releaseSbomHashlessSelectorCount: 7 } }))
    return { c: { query, mutate } as any, mutate, query }
}

/** FDAW-14 row 17: the selector forms of the bulk dialog. */
describe('the selector', () => {
    it('defaults to Shown components and, for the selector forms, to This release', () => {
        expect(emptySelectorChoice()).toEqual({ form: 'EXPLICIT', type: '', namespace: '', scope: 'RELEASE' })
    })

    it('This release sends the page release; Whole organization sends null and says so', () => {
        expect(selectorRelease(DEB, 'rel-1')).toBe('rel-1')
        expect(selectorRelease({ ...DEB, scope: 'ORGANIZATION' }, 'rel-1')).toBeNull()
        expect(selectorCountText(3, 'RELEASE')).toBe('Matches 3 components in this release')
        expect(selectorCountText(3, 'ORGANIZATION')).toBe('Matches 3 components across the organization')
    })

    it.each(['Deb', 'np%', ''])('refuses the type %j', (type) => {
        expect(validateSelector(choice({ form: 'TYPE', type }))).not.toEqual([])
    })

    it('accepts a valid type, needs a namespace for By namespace, and checks nothing for Shown', () => {
        expect(validateSelector(choice({ form: 'TYPE', type: 'deb' }))).toEqual([])
        expect(validateSelector(choice({ form: 'NAMESPACE', type: 'deb', namespace: ' ' }))).not.toEqual([])
        expect(validateSelector(choice({ form: 'EXPLICIT', type: 'Deb' }))).toEqual([])
        expect(selectorInput(choice({ form: 'TYPE', type: 'deb', namespace: 'x' })))
            .toEqual({ form: 'TYPE', type: 'deb', namespace: null })
        expect(selectorInput(choice({ form: 'EXPLICIT' }))).toBeNull()
    })

    it('the confirm stage sends one count with the same selector and scope', async () => {
        const { c, query } = client({})
        expect(await fetchSelectorCount(c, 'org-1', DEB, 'rel-1')).toEqual({ matched: 42, overCap: false })
        expect(query).toHaveBeenCalledTimes(1)
        expect((query.mock.calls[0] as any)[0].query).toBe(SUPPORT_BULK_SELECTOR_COUNT)
        expect((query.mock.calls[0] as any)[0].variables).toEqual({ orgUuid: 'org-1',
            selector: { form: 'NAMESPACE', type: 'deb', namespace: 'debian' }, releaseUuid: 'rel-1' })
    })

    it('names the narrowing line shown, with the confirm disabled, over the cap', () => {
        expect(SELECTOR_OVER_CAP_TEXT).toBe('Matches more than 1000 components; narrow the selector by namespace or to'
            + ' this release, or use Shown components')
    })

    it('sends one selector call with the typed selector, no walk, and an end-of-support-only sweep', async () => {
        const { c, mutate, query } = client({ bulkSetSbomComponentSupportBySelector: { bulkAct: 'act-9',
            matchedCount: 2, appliedCount: 2, skippedCount: 0, failedCount: 0,
            results: [{ sbomComponentUuid: 'a', outcome: 'APPLIED', message: null },
                { sbomComponentUuid: 'b', outcome: 'APPLIED', message: null }] } })
        const at = '2026-11-06T10:00:00Z'
        const out = await submitBySelector(c, 'org-1', { ...DEB, scope: 'ORGANIZATION' }, 'rel-1',
            { ...emptyBulkForm(), reason: 'deb backlog', endOfSupportMode: 'UNKNOWN',
                endOfSupportUnknownCode: 'NO_COMMITTED_END_OF_SUPPORT', assessedAt: at })
        expect(query).not.toHaveBeenCalled()
        expect(mutate).toHaveBeenCalledTimes(1)
        const opts = (mutate.mock.calls[0] as any)[0]
        expect(opts.mutation).toBe(BULK_SET_SUPPORT_BY_SELECTOR)
        expect(opts.variables.selector).toEqual({ form: 'NAMESPACE', type: 'deb', namespace: 'debian' })
        expect(opts.variables.releaseUuid).toBeNull()
        expect(opts.variables.endOfSupportUnknown).toEqual({ code: 'NO_COMMITTED_END_OF_SUPPORT', text: null })
        expect(opts.variables.assessedAt).toBe(at)
        expect(opts.variables.endOfSupportDate).toBeNull()
        expect(opts.variables.levelOfSupport).toBeNull()
        expect(out.matched).toBe(2)
        expect(out.applied).toBe(2)
        expect(out.bulkActs).toEqual(['act-9'])
    })

    it('blocks OTHER without a text before any call', async () => {
        const { c, mutate } = client({})
        const out = await submitBySelector(c, 'org-1', DEB, 'rel-1', { ...emptyBulkForm(), reason: 'r',
            endOfSupportMode: 'UNKNOWN', endOfSupportUnknownCode: 'OTHER' })
        expect(out.aborted).toBe(true)
        expect(mutate).not.toHaveBeenCalled()
    })
})

/** FDAW-14 row 17: the Missing hashes half. */
describe('the hash justification half', () => {
    const result = (skippedKeys: string[]) => ({ releaseSbom: 'h-1', revision: 2, hashes: [], skippedKeys })

    it('has the no-identity sentence', () => {
        expect(NO_IDENTITY_SENTENCE).toBe('No release SBOM identity exists for this release yet: publish it to TEA'
            + ' or build a package first')
    })

    it('Shown sends the checked keys to setReleaseSbomHashJustification', async () => {
        const { c, mutate } = client({ setReleaseSbomHashJustification: result([]) })
        const input = { code: 'DISTRIBUTION_PUBLISHES_NO_HASH', text: '', reason: 'r' }
        expect(validateHashJustification(choice({}), ['k1', 'k2'], input)).toEqual([])
        await submitHashJustification(c, 'h-1', choice({}), ['k1', 'k2'], input)
        const opts = (mutate.mock.calls[0] as any)[0]
        expect(opts.mutation).toBe(SET_HASH_JUSTIFICATION)
        expect(opts.variables).toEqual({ releaseSbom: 'h-1', keys: ['k1', 'k2'], code: 'DISTRIBUTION_PUBLISHES_NO_HASH',
            text: null, reason: 'r' })
    })

    it('By namespace previews the count, then sends the selector, and lists the skipped keys', async () => {
        const { c, mutate, query } = client({ bulkSetReleaseSbomHashJustificationBySelector: result(['k3']) })
        expect(await countHashlessBySelector(c, 'h-1', DEB)).toBe(7)
        expect((query.mock.calls[0] as any)[0].query).toBe(RELEASE_SBOM_HASHLESS_SELECTOR_COUNT)
        const r = await submitHashJustification(c, 'h-1', DEB, [], { code: 'OTHER', text: 'vendored', reason: '' })
        const opts = (mutate.mock.calls[0] as any)[0]
        expect(opts.mutation).toBe(BULK_HASH_JUSTIFICATION_BY_SELECTOR)
        expect(opts.variables.selector).toEqual({ form: 'NAMESPACE', type: 'deb', namespace: 'debian' })
        expect(skippedKeysText(r)).toEqual(['k3: left as justified'])
    })

    it('Shown with nothing checked, a missing code, and OTHER without a text are refused', () => {
        expect(validateHashJustification(choice({}), [], { code: 'OTHER', text: 'x', reason: '' })).not.toEqual([])
        expect(validateHashJustification(choice({}), ['k'], { code: null, text: '', reason: '' })).not.toEqual([])
        expect(validateHashJustification(choice({}), ['k'], { code: 'OTHER', text: '', reason: '' })).not.toEqual([])
    })

    it('Clear needs a reason', async () => {
        const { c, mutate } = client({ clearReleaseSbomHashJustification: result([]) })
        await expect(clearHashJustification(c, 'h-1', ['k1'], ' ')).rejects.toThrow('needs a reason')
        await clearHashJustification(c, 'h-1', ['k1'], 'wrong code')
        expect((mutate.mock.calls[0] as any)[0].mutation).toBe(CLEAR_HASH_JUSTIFICATION)
    })
})

/** FDAW-14 row 17: the per-component editor. */
describe('the per-component editor', () => {
    it('sends clearEndOfSupportUnknown with a reason, and needs the reason', () => {
        const form = { ...emptyAttestationForm(), clearEndOfSupportUnknown: true, reason: 'wrong component' }
        const vars = attestationVariables('c-1', form, { endOfSupportUnknown: { code: 'OTHER', text: 'x' } })
        expect(vars).toEqual({ sbomComponentUuid: 'c-1', clearEndOfSupportUnknown: true, reason: 'wrong component' })
        expect(validateAttestation({ ...form, reason: '' }).join(' ')).toContain('Clearing the justified unknown')
    })

    it('on a row with a date sends clearMilestones END_OF_SUPPORT, the unknown and the reason in one call', () => {
        const form = { ...emptyAttestationForm(), endOfSupportDate: '2030-01-01', endOfSupportMode: 'UNKNOWN' as const,
            endOfSupportUnknownCode: 'SUPPLIER_PUBLISHES_NO_DATE', reason: 'the date was a guess' }
        const vars = attestationVariables('c-1', form, { endOfSupportDate: '2030-01-01' })
        expect(vars).toEqual({ sbomComponentUuid: 'c-1', reason: 'the date was a guess',
            endOfSupportUnknown: { code: 'SUPPLIER_PUBLISHES_NO_DATE', text: null }, clearMilestones: ['END_OF_SUPPORT'] })
    })

    it('a new choice replaces a stored unknown; the same one sends nothing', () => {
        const stored = { endOfSupportUnknown: { code: 'OTHER', text: 'old' } }
        const same = { ...emptyAttestationForm(), endOfSupportMode: 'UNKNOWN' as const, endOfSupportUnknownCode: 'OTHER',
            endOfSupportUnknownText: 'old' }
        expect(attestationVariables('c-1', same, stored)).toEqual({ sbomComponentUuid: 'c-1' })
        const changed = { ...same, endOfSupportUnknownCode: 'COMMUNITY_PROJECT' }
        expect(attestationVariables('c-1', changed, stored).endOfSupportUnknown)
            .toEqual({ code: 'COMMUNITY_PROJECT', text: 'old' })
    })
})
