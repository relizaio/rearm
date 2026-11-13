// The one artifact download URL builder (task TEA-30, design 4.6 row 29): the uuid pattern, the
// integer-only version (no query for a blank one, a throw for anything else), the fail-closed ref
// of a submission part and the sanitised file name.
import { describe, expect, it, vi } from 'vitest'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { artifactDownloadRef, artifactDownloadUrl, downloadFileName, downloadArtifactFile } = await import('./artifactDownload')
const { fetchWithAuth } = await import('./fetchClient') as any

const U = '0f8fad5b-d9cb-469f-a165-70867728950e'

describe('artifactDownloadUrl', () => {
    it('builds the raw and the augmented download, with an integer version only', () => {
        expect(artifactDownloadUrl(U, true, 1)).toBe('/api/manual/v1/artifact/' + U + '/rawdownload?version=1')
        expect(artifactDownloadUrl(U, false)).toBe('/api/manual/v1/artifact/' + U + '/download')
        expect(artifactDownloadUrl(U, true, '2')).toBe('/api/manual/v1/artifact/' + U + '/rawdownload?version=2')
        expect(artifactDownloadUrl(U, true, 0)).toBe('/api/manual/v1/artifact/' + U + '/rawdownload?version=0')
    })

    it('adds no query for a blank version: the row latest', () => {
        for (const v of ['', null, undefined]) {
            expect(artifactDownloadUrl(U, true, v as any)).toBe('/api/manual/v1/artifact/' + U + '/rawdownload')
        }
    })

    it('throws on a version that is not a non-negative integer, never fetching latest instead', () => {
        for (const v of ['1.0', -1, 'x', 'latest', ' 1', 1.5, NaN]) {
            expect(() => artifactDownloadUrl(U, true, v as any), String(v)).toThrow()
        }
    })

    it('throws on anything but a uuid', () => {
        for (const u of ['../x', U + '?x=1', 'javascript:alert(1)', '', U + '/..']) {
            expect(() => artifactDownloadUrl(u, true, 1), u).toThrow()
        }
    })
})

describe('artifactDownloadRef and downloadFileName', () => {
    it('is null for a non-uuid artifact or a non-integer version, else the uuid, the version and the name', () => {
        expect(artifactDownloadRef('not-a-uuid', '1', 'a.json')).toBeNull()
        expect(artifactDownloadRef(null, '1', 'a.json')).toBeNull()
        expect(artifactDownloadRef(U, null, 'a.json')).toBeNull()
        expect(artifactDownloadRef(U, '1.0', 'a.json')).toBeNull()
        expect(artifactDownloadRef(U, 'latest', 'a.json')).toBeNull()
        expect(artifactDownloadRef(U, '1', 'a.json')).toEqual({ uuid: U, version: 1, fileName: 'a.json' })
    })

    it('strips path separators and control characters and falls back to the uuid', () => {
        expect(downloadFileName('a/b\\c.json', U)).toBe('abc.json')
        expect(downloadFileName('a\u0000b\u001fc\n.json', U)).toBe('abc.json')
        expect(downloadFileName('', U)).toBe(U)
        expect(downloadFileName(null, U)).toBe(U)
        expect(artifactDownloadRef(U, '3', '../../etc/passwd')!.fileName).toBe('....etcpasswd')
        expect(artifactDownloadRef(U, '3', '')!.fileName).toBe(U)
    })
})

// FDAW-32 3.6: a draft part has no stored file name, so the server's Content-Disposition names it.
describe('downloadArtifactFile', () => {
    it('returns the bytes with the Content-Disposition and Content-Type headers on an ok response', async () => {
        const headers = new Map([['Content-Disposition', 'attachment; filename="part.json"'], ['Content-Type', 'application/json']])
        const bytes = new ArrayBuffer(4)
        fetchWithAuth.mockResolvedValueOnce({ ok: true, headers: { get: (k: string) => headers.get(k) ?? null },
            arrayBuffer: async () => bytes })
        const file = await downloadArtifactFile('/api/manual/v1/artifact/' + U + '/download')
        expect(file.bytes).toBe(bytes)
        expect(file.disposition).toBe('attachment; filename="part.json"')
        expect(file.mediaType).toBe('application/json')
    })

    it('is null for a header the response did not send', async () => {
        fetchWithAuth.mockResolvedValueOnce({ ok: true, headers: { get: () => null }, arrayBuffer: async () => new ArrayBuffer(0) })
        const file = await downloadArtifactFile('/x')
        expect(file.disposition).toBeNull()
        expect(file.mediaType).toBeNull()
    })

    it('throws, naming the status, on a response that is not ok; never reads a body', async () => {
        const arrayBuffer = vi.fn()
        fetchWithAuth.mockResolvedValueOnce({ ok: false, status: 404, statusText: 'Not Found', headers: { get: () => null }, arrayBuffer })
        await expect(downloadArtifactFile('/x')).rejects.toThrow('404')
        expect(arrayBuffer).not.toHaveBeenCalled()
    })
})
