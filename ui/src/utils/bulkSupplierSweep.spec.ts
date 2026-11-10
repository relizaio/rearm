import { describe, it, expect, vi } from 'vitest'
import { emptySupplierForm, emptySupplierSelectorChoice, submitSupplierBySelector, validateSupplierSweep }
    from './bulkSelectorForm'

/**
 * FDAW-15 row 16 (REQ-15), the supplier sweep's bounds and failures. The happy path and the
 * blank supplier are in bulkSelectorForm.spec.ts ('the supplier sweep').
 */
const SUP = { ...emptySupplierSelectorChoice(), form: 'NAMESPACE' as const, type: 'deb', namespace: 'debian' }

function client (mutate: (...args: any[]) => Promise<any>) {
    const m = vi.fn(mutate)
    return { c: { query: vi.fn(), mutate: m } as any, mutate: m }
}

describe('the supplier sweep bounds', () => {
    it('refuses an invalid selector before any call', async () => {
        const input = { supplier: 'Debian Project', reason: '' }
        expect(validateSupplierSweep(emptySupplierSelectorChoice(), input)).not.toEqual([])
        const { c, mutate } = client(async () => ({ data: {} }))
        const out = await submitSupplierBySelector(c, 'org-1', emptySupplierSelectorChoice(), 'rel-1', input)
        expect(out.aborted).toBe(true)
        expect(mutate).not.toHaveBeenCalled()
    })

    it('takes a supplier of 512 characters and a reason of 2048', () => {
        expect(validateSupplierSweep(SUP, { supplier: 's'.repeat(512), reason: 'r'.repeat(2048) })).toEqual([])
    })

    it('refuses a reason over 2048 characters', () => {
        expect(validateSupplierSweep(SUP, { supplier: 'Debian Project', reason: 'r'.repeat(2049) }))
            .toEqual(['reason is longer than 2048 characters'])
    })

    it('refuses a control character in the supplier', () => {
        expect(validateSupplierSweep(SUP, { ...emptySupplierForm(), supplier: 'Debian\tProject' }))
            .toEqual(['supplier holds a control character'])
    })

    it('sends a blank reason as null', async () => {
        const { c, mutate } = client(async () => ({ data: { bulkSetSbomComponentSupplierBySelector: {
            bulkAct: 'act-s', matchedCount: 0, results: [] } } }))
        await submitSupplierBySelector(c, 'org-1', SUP, 'rel-1', { supplier: 'Debian Project', reason: '   ' })
        expect((mutate.mock.calls[0] as any)[0].variables.reason).toBeNull()
    })
})

describe('the supplier sweep failures', () => {
    it('an answer without a result aborts with the reason and may be re-run', async () => {
        const { c } = client(async () => ({ data: {} }))
        const out = await submitSupplierBySelector(c, 'org-1', SUP, 'rel-1', { supplier: 'Debian Project', reason: '' })
        expect(out.aborted).toBe(true)
        expect(out.error).toBe('the server returned no result for this sweep')
        expect(out.retryable).toBe(true)
    })

    it('a refusal aborts with the server\'s message and may be re-run', async () => {
        const { c } = client(async () => { throw new Error('supplier holds a control character') })
        const out = await submitSupplierBySelector(c, 'org-1', SUP, 'rel-1', { supplier: 'Debian Project', reason: '' })
        expect(out.aborted).toBe(true)
        expect(out.error).toBe('supplier holds a control character')
        expect(out.retryable).toBe(true)
        expect(out.applied).toBe(0)
    })
})
