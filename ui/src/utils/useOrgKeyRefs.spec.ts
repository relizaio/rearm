import { describe, expect, it, vi } from 'vitest'
import { useOrgKeyRefs } from './useOrgKeyRefs'

/** The org key refs a history view labels key actors with (task t20261013-191133-18220). */
const KEYS = [{ uuid: 'k-1', type: 'FREEFORM', declaredName: 'CI pipeline', notes: null, status: 'ACTIVE' }]

describe('useOrgKeyRefs', () => {
    it('asks for an admin only, and once per org', async () => {
        const fetchKeys = vi.fn(async () => KEYS)
        const { orgKeys, load } = useOrgKeyRefs(fetchKeys)
        await load('org-1', false)
        expect(fetchKeys).not.toHaveBeenCalled()
        expect(orgKeys.value).toEqual([])
        await load('org-1', true)
        await load('org-1', true)
        expect(fetchKeys).toHaveBeenCalledTimes(1)
        expect(fetchKeys).toHaveBeenCalledWith('org-1')
        expect(orgKeys.value).toEqual(KEYS)
    })

    it('does nothing without an org', async () => {
        const fetchKeys = vi.fn(async () => KEYS)
        const { orgKeys, load } = useOrgKeyRefs(fetchKeys)
        await load(undefined, true)
        await load('', true)
        expect(fetchKeys).not.toHaveBeenCalled()
        expect(orgKeys.value).toEqual([])
    })

    it('asks again after an empty or failed answer', async () => {
        const fetchKeys = vi.fn().mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('down')).mockResolvedValueOnce(KEYS)
        const { orgKeys, load } = useOrgKeyRefs(fetchKeys)
        vi.spyOn(console, 'error').mockImplementation(() => {})
        await load('org-1', true)
        expect(orgKeys.value).toEqual([])
        await load('org-1', true)
        expect(orgKeys.value).toEqual([])
        await load('org-1', true)
        expect(orgKeys.value).toEqual(KEYS)
        expect(fetchKeys).toHaveBeenCalledTimes(3)
    })

    it('drops the keys of an org it has left and the late answer for it', async () => {
        let resolveFirst: (keys: any[]) => void = () => {}
        const fetchKeys = vi.fn()
            .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
            .mockResolvedValueOnce([{ uuid: 'k-2' }])
        const { orgKeys, load } = useOrgKeyRefs(fetchKeys)
        const first = load('org-1', true)
        await load('org-2', true)
        resolveFirst(KEYS)
        await first
        expect(orgKeys.value).toEqual([{ uuid: 'k-2' }])
    })
})
