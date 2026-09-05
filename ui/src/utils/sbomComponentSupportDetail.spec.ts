import { describe, expect, it, vi } from 'vitest'
import { loadSbomComponentSupportDetail } from './sbomComponentSupportDetail'

describe('loadSbomComponentSupportDetail', () => {
    it('returns the stored attestation', async () => {
        const query = vi.fn().mockResolvedValue({
            data: { sbomComponent: { uuid: 'c-1', attestationState: 'ATTESTED', justification: 'x' } }
        })
        const r = await loadSbomComponentSupportDetail({ query } as any, 'c-1')
        expect(r).toEqual({ kind: 'ok', attestation: { uuid: 'c-1', attestationState: 'ATTESTED', justification: 'x' } })
    })

    // The form seeds from this. Editing a stale copy is how one operator silently overwrites
    // another's attestation, because an omitted field means "keep" under PATCH semantics --
    // so a cached form would re-send values that had already changed underneath it.
    it('always hits the network', async () => {
        const query = vi.fn().mockResolvedValue({ data: { sbomComponent: null } })
        await loadSbomComponentSupportDetail({ query } as any, 'c-1')
        expect(query.mock.calls[0][0].fetchPolicy).toBe('network-only')
    })

    /**
     * "This server cannot answer" and "no attestation yet" look identical if both are null,
     * and they must not: the first has to refuse the form, the second has to open an empty
     * one. Hence a tagged result rather than a nullable attestation.
     */
    it('reports drift as unsupported, distinctly from an unattested component', async () => {
        const drift = vi.fn().mockRejectedValue(
            new Error('Cannot query field "attestationState" on type "SbomComponent"'))
        expect(await loadSbomComponentSupportDetail({ query: drift } as any, 'c-1'))
            .toEqual({ kind: 'unsupported' })

        const empty = vi.fn().mockResolvedValue({ data: { sbomComponent: null } })
        expect(await loadSbomComponentSupportDetail({ query: empty } as any, 'c-1'))
            .toEqual({ kind: 'ok', attestation: null })
    })

    it('propagates real errors rather than reporting them as unsupported', async () => {
        const query = vi.fn().mockRejectedValue(new Error('Not authorized'))
        await expect(loadSbomComponentSupportDetail({ query } as any, 'c-1'))
            .rejects.toThrow('Not authorized')
    })
})
