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
     * The load-bearing one. CE's sbomComponentSupportCoverage takes ONLY orgUuid -- it has
     * no releaseUuid argument -- so the single thing a CE server could answer is the
     * ORG-WIDE number. That is a different question, not a degraded answer to this one:
     * "34 of 1,240 across your whole organisation" rendered against one release is
     * confidently wrong in a way an operator cannot detect. Null, and say so.
     */
    it('returns null on schema drift instead of falling back to the org-wide number', async () => {
        const query = vi.fn().mockRejectedValue(
            new Error('Unknown argument "releaseUuid" on field "Query.sbomComponentSupportCoverage"'))
        expect(await loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1')).toBeNull()
        // Exactly one attempt. A second call would be a fallback query, which is what this
        // deliberately does not do.
        expect(query).toHaveBeenCalledOnce()
    })

    it('returns null when the field is absent rather than inventing zeroes', async () => {
        const query = vi.fn().mockResolvedValue({ data: {} })
        expect(await loadReleaseSupportCoverage({ query } as any, 'org-1', 'rel-1')).toBeNull()
    })

    // Auth, transport, a rejected org: real errors, and the caller must see them. Swallowing
    // them as "unavailable" would render a missing-data state over a broken request.
    it('propagates non-drift errors', async () => {
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
