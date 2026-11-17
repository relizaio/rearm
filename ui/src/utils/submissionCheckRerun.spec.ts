// FDAW-16 3.9a: the re-run of a version's check. Which versions offer it, the two headings, and the one
// request both panels share (a failed answer is dropped so a Retry asks again).
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
const { canRerunCheck, checkRerunLoader, freshHeading, storedHeading } = await import('./submissionCheckRerun')

const A = '11111111-1111-4111-8111-111111111111'
const part = (over: Record<string, any> = {}) => ({ part: 'CHECK_REPORT', outcome: 'PRODUCED', artifact: A, artifactVersion: '1',
    fileName: 'widget-check-report.json', ...over })
const version = (over: Record<string, any> = {}) => ({ checkSummary: { checkerVersion: 'rearm-cli 26.10.15' },
    parts: [{ part: 'AGGREGATE_SBOM', outcome: 'REFERENCED', artifact: A, artifactVersion: '2' }, part()], ...over })
const ANSWER = { storedChecker: 'rearm-cli 26.10.15', stored: '{"s":1}', freshChecker: 'rearm-cli 26.11.7', fresh: '{"f":1}' }
const signal = new AbortController().signal

describe('canRerunCheck', () => {
    it('offers the re-run on a version with a stored check and a downloadable Check report part', () => {
        expect(canRerunCheck(version())).toBe(true)
    })

    it('not without a stored check, nor without the part, nor on a part the page cannot download', () => {
        expect(canRerunCheck(version({ checkSummary: null }))).toBe(false)
        expect(canRerunCheck(version({ parts: [] }))).toBe(false)
        expect(canRerunCheck(version({ parts: [part({ outcome: 'SKIPPED', artifact: null, artifactVersion: null })] }))).toBe(false)
        expect(canRerunCheck(version({ parts: [part({ artifactVersion: null })] }))).toBe(false)
        expect(canRerunCheck(null)).toBe(false)
    })
})

describe('the headings', () => {
    it('name the checker once the answer is in', () => {
        expect(storedHeading('rearm-cli 26.10.15')).toBe('Stored (rearm-cli 26.10.15)')
        expect(freshHeading('rearm-cli 26.11.7')).toBe('Now (rearm-cli 26.11.7)')
        expect([storedHeading(null), freshHeading('')]).toEqual(['Stored', 'Now'])
    })
})

describe('checkRerunLoader', () => {
    it('sends one request for both panels and gives each its report and the checkers', async () => {
        const fetch = vi.fn().mockResolvedValue(ANSWER)
        const l = checkRerunLoader(fetch)
        expect(await Promise.all([l.loadStored(signal), l.loadFresh(signal)])).toEqual(['{"s":1}', '{"f":1}'])
        expect(fetch).toHaveBeenCalledTimes(1)
        expect({ ...l.checkers }).toEqual({ stored: 'rearm-cli 26.10.15', fresh: 'rearm-cli 26.11.7' })
    })

    it('a refused answer fails the panel that asked with its error, and the next ask sends the request again', async () => {
        const refused = new Error('Not authorized')
        const fetch = vi.fn().mockRejectedValueOnce(refused).mockResolvedValueOnce(ANSWER)
        const l = checkRerunLoader(fetch)
        await expect(l.loadStored(signal)).rejects.toBe(refused)
        await expect(l.loadFresh(signal)).resolves.toBe('{"f":1}')
        expect(fetch).toHaveBeenCalledTimes(2)
    })

    it('an empty answer is an error, not an empty report', async () => {
        const l = checkRerunLoader(vi.fn().mockResolvedValue(null))
        await expect(l.loadStored(signal)).rejects.toThrow('the server returned no report')
    })
})
