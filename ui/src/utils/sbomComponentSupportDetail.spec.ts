import { describe, expect, it, vi } from 'vitest'
import { loadSbomComponentSupportDetail } from './sbomComponentSupportDetail'

describe('loadSbomComponentSupportDetail', () => {
    it('returns the stored attestation', async () => {
        const query = vi.fn().mockResolvedValue({
            data: { getReleaseSbomComponentGraph: { component: { uuid: 'c-1', attestationState: 'ATTESTED', justification: 'x' } } }
        })
        const r = await loadSbomComponentSupportDetail({ query } as any, 'rel-1', 'c-1')
        expect(r).toEqual({ kind: 'ok', attestation: { uuid: 'c-1', attestationState: 'ATTESTED', justification: 'x' } })
    })

    // The form seeds from this. Editing a stale copy is how one operator silently overwrites
    // another's attestation, because an omitted field means "keep" under PATCH semantics --
    // so a cached form would re-send values that had already changed underneath it.
    it('always hits the network', async () => {
        const query = vi.fn().mockResolvedValue({ data: { getReleaseSbomComponentGraph: null } })
        await loadSbomComponentSupportDetail({ query } as any, 'rel-1', 'c-1')
        expect(query.mock.calls[0][0].fetchPolicy).toBe('network-only')
    })

    it('propagates real errors rather than swallowing them', async () => {
        const query = vi.fn().mockRejectedValue(new Error('Not authorized'))
        await expect(loadSbomComponentSupportDetail({ query } as any, 'rel-1', 'c-1'))
            .rejects.toThrow('Not authorized')
    })
})
