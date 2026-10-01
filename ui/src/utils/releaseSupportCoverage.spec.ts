import { describe, expect, it, vi } from 'vitest'
import { loadReleaseSupportCoverage } from './releaseSupportCoverage'

const ok = (cov: any) => ({ data: { sbomComponentSupportCoverage: cov } })

describe('loadReleaseSupportCoverage', () => {
    it('asks for the release-scoped gauge, and always over the network', async () => {
        const query = vi.fn().mockResolvedValue(ok({ total: 10, attested: 3, supportExportState: 'PARTIAL' }))
        await loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1')
        expect(query.mock.calls[0][0].variables).toEqual({ orgUuid: 'org-1', releaseUuid: 'rel-1' })
        expect(query.mock.calls[0][0].fetchPolicy).toBe('network-only')
    })

    it('surfaces counts and export state together', async () => {
        const query = vi.fn().mockResolvedValue(ok({ total: 10, attested: 3, supportExportState: 'DISABLED' }))
        expect(await loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1'))
            .toEqual({ total: 10, attested: 3, exportState: 'DISABLED' })
    })

    /**
     * THROWS on an absent field -- it does not invent zeroes, and it no longer returns null
     * either. The resolver always builds a row (an org with no components answers 0/0, and a
     * bad org or a foreign release is an error), so nothing here means a malformed response,
     * which is a failure. Returning null would route it to the display's "has not loaded yet"
     * branch: a failure rendered as a benign state.
     */
    it('throws when the field is absent rather than inventing zeroes or a null', async () => {
        const query = vi.fn().mockResolvedValue({ data: {} })
        await expect(loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1'))
            .rejects.toThrow(/no support coverage/)
    })

    // Auth, transport, a rejected org: real errors, and the caller must see them. Swallowing
    // them as "unavailable" would render a missing-data state over a broken request.
    it('propagates errors', async () => {
        const query = vi.fn().mockRejectedValue(new Error('Not authorized'))
        await expect(loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1'))
            .rejects.toThrow('Not authorized')
    })

    // Non-null on the wire, but if it ever arrives missing, "we do not know" is the only
    // answer that cannot mislead -- never a state that would reassure.
    it('degrades a missing export state to UNKNOWN, never to ENABLED', async () => {
        const query = vi.fn().mockResolvedValue(ok({ total: 5, attested: 5 }))
        const cov = await loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1')
        expect(cov?.exportState).toBe('UNKNOWN')
    })
})
