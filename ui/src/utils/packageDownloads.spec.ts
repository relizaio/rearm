// @vitest-environment happy-dom
//
// FDAW-122 row 1: the three URLs, the file name kept from Content-Disposition, the sentence for every
// answer that is not the file (every request crossed with every answer), one request per click, and
// the save through an octet-stream blob.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const fetchWithAuth = vi.fn()
vi.mock('./fetchClient', () => ({ fetchWithAuth: (...a: any[]) => fetchWithAuth(...a), fetchArrayBufferWithAuth: vi.fn() }))

const P = await import('./packageDownloads')
const { expectSwept } = await import('./__fixtures__/fdaSweep')

const S1 = '11111111-1111-4111-8111-111111111111'
const D1 = '22222222-2222-4222-8222-222222222222'
const NO_BUILD = 'The draft has no finished build to download. When a build is running, wait for it; otherwise rebuild the draft. Then download again.'
const CHANGED = 'The draft changed while its zip was being made. Download again.'
const NOT_MARKED = 'draft file fda/Pump-1.2.0-fda-premarket-v2_DRAFT-device-support-statement.html could not be marked DRAFT: it is not UTF-8 text'
    + '. ReARM marks only the HTML it generates and never delivers such a file of a draft unmarked. Rebuild the draft and download again;'
    + ' if this message returns, give it to the administrator of this ReARM instance.'
const PINNED = 'pinned file fda/Pump-1.2.0-fda-premarket-v2-vdr.cdx.json does not match its recorded sha256'
// every 409 text the server has at rearm-saas 715bf899: PackageZipLayout :92, :95, :105, PinnedArtifactBytes :29;
// DraftZipContents :44-52, :164, :169, :172, :181 with the six reasons of DraftMark.Refusal :73-78
const FINAL_TEXTS = ['pinned file fda/Pump-1.2.0-fda-premarket-v2-sbom.cdx.json has no recorded sha256', PINNED,
    'the manifest does not match its recorded sha256', 'a pinned file could not be read from the artifact store']
const ADVICE = '. ReARM marks only the HTML it generates and never delivers such a file of a draft unmarked. Rebuild the draft and download again;'
    + ' if this message returns, give it to the administrator of this ReARM instance.'
const MARK_REASONS = ['it is not UTF-8 text', 'it does not start as the generator starts a document', 'its body does not open as the generator writes it',
    'it does not have exactly one body tag', 'it already has a header tag', 'it does not have exactly two meta tags']
const STATEMENT_ENTRY = 'fda/Pump-1.2.0-fda-premarket-v2_DRAFT-device-support-statement.html'
// a component may be named anything in [A-Za-z0-9._-]: these names hold the words a search would look for
const REBUILD_ENTRY = 'fda/Rebuild-the-draft-and-download-again-1.0-fda-premarket-v2_DRAFT-sbom.cdx.json'
const ASKS_REBUILD = [NO_BUILD, ...MARK_REASONS.map(r => 'draft file ' + STATEMENT_ENTRY + ' could not be marked DRAFT: ' + r + ADVICE)]
const ASKS_NO_REBUILD = [CHANGED,
    'draft file ' + REBUILD_ENTRY + ' has no recorded sha256',
    'draft file ' + REBUILD_ENTRY + ' could not be read from the artifact store',
    'draft file ' + REBUILD_ENTRY + ' does not match the sha256 its build recorded',
    'draft file fda/otherwise-rebuild-the-draft.-Then-download-again. does not match the sha256 its build recorded',
    NO_BUILD + ' ', ' ' + NO_BUILD, NO_BUILD.toLowerCase(), NO_BUILD.slice(0, -1), 'Rebuild the draft.', 'rebuild', ADVICE.slice(2),
    ADVICE + ' More.', 'A text this page does not know.']
const FAULT = (n: number) => 'The download failed on the server (HTTP status ' + n + '). Try again; if this stays, tell the administrator of this ReARM instance the time of the attempt.'
const KINDS = ['zip', 'record-json', 'record-html', 'draft-zip', 'part'] as const
const PLAIN = 'text/plain;charset=UTF-8'
// the Java source of the backend, beside the Pro schema folder when the whole checkout is there
const proSchema = process.env.REARM_PRO_SCHEMA
const SAAS_JAVA = proSchema ? resolve(proSchema, '../../java/io/reliza') : ''
const draftZipJava = SAAS_JAVA ? resolve(SAAS_JAVA, 'service/saas/DraftZipContents.java') : ''

function answer (status: number, headers: Record<string, string> = {}, body: any = ''): any {
    const h = new Map(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]))
    return { status, ok: status >= 200 && status < 300, headers: { get: (k: string) => h.get(k.toLowerCase()) ?? null },
        blob: async () => (body instanceof Blob ? body : new Blob([body])), text: async () => String(body) }
}

describe('packageDownloads (FDAW-122)', () => {
    beforeEach(() => { fetchWithAuth.mockReset() })
    afterEach(() => { vi.useRealTimers() })

    it('(a) the three URLs are built from a uuid and an integer only', () => {
        expect(P.packageZipUrl(S1, 3)).toBe('/api/manual/v1/submission/' + S1 + '/version/3/zip')
        expect(P.recordOfReviewUrl(S1, 3, 'json')).toBe('/api/manual/v1/submission/' + S1 + '/version/3/record-of-review.json')
        expect(P.recordOfReviewUrl(S1, 12, 'html')).toBe('/api/manual/v1/submission/' + S1 + '/version/12/record-of-review.html')
        expect(P.draftZipUrl(D1)).toBe('/api/manual/v1/submission-draft/' + D1 + '/zip')
        for (const bad of ['', 'd1', S1 + '/zip', '../' + S1, S1 + '?x=1', ' ' + S1, null, undefined, 7]) {
            expect(() => P.packageZipUrl(bad as any, 1), String(bad)).toThrow(/Not a submission uuid/)
            expect(() => P.draftZipUrl(bad as any), String(bad)).toThrow(/Not a draft uuid/)
        }
        for (const bad of [0, -1, 1.5, NaN, Infinity, '1', '1/../2', null, undefined, 2147483648]) {
            expect(() => P.packageZipUrl(S1, bad as any), String(bad)).toThrow(/Not a package version/)
            expect(() => P.recordOfReviewUrl(S1, bad as any, 'json'), String(bad)).toThrow(/Not a package version/)
        }
        expect(() => P.recordOfReviewUrl(S1, 1, 'pdf' as any)).toThrow(/Not a record of review form/)
        expect(P.packageZipUrl(S1, 2147483647)).toContain('/version/2147483647/zip')
    })

    it('(b) the file name of the header is kept only in the form the server writes for that download', () => {
        const cd = (n: string) => 'attachment; filename="' + n + '"'
        expect(P.packageFileName('zip', cd('Widget-Pro-1.2.0-rc1-fda-premarket-v3.zip'), 'f')).toBe('Widget-Pro-1.2.0-rc1-fda-premarket-v3.zip')
        expect(P.packageFileName('draft-zip', cd('Pump-1.0-fda-premarket-v2_DRAFT.zip'), 'f')).toBe('Pump-1.0-fda-premarket-v2_DRAFT.zip')
        expect(P.packageFileName('record-json', cd('Pump-1.0-fda-premarket-v2-record-of-review.json'), 'f')).toBe('Pump-1.0-fda-premarket-v2-record-of-review.json')
        expect(P.packageFileName('record-html', cd('Pump-1.0-fda-premarket-v2-record-of-review.html'), 'f')).toBe('Pump-1.0-fda-premarket-v2-record-of-review.html')
        // a Final name may hold the mark's text (FDAW-120 R4); a draft name without it is never kept
        expect(P.packageFileName('zip', cd('a_DRAFT-1-fda-premarket-v1.zip'), 'f')).toBe('a_DRAFT-1-fda-premarket-v1.zip')
        expect(P.packageFileName('draft-zip', cd('Pump-1.0-fda-premarket-v2.zip'), P.packageFallbackName('draft-zip', 2))).toBe('package-draft-v2_DRAFT.zip')
        const FALLBACK = 'fallback.zip'
        for (const bad of [null, undefined, '', 'attachment', 'inline; filename="a.zip"', cd(''), cd('a b.zip'), cd('../a.zip'), cd('a/b.zip'),
            cd('a\\b.zip'), cd('a.zip.exe'), cd('a.ZIP'), cd('\u00e9.zip'), cd('a.zip') + '; filename*=UTF-8\'\'b.zip', cd('a\r\nb.zip'),
            'attachment; filename=a.zip', 'attachment; filename="a.zip', cd('x'.repeat(252) + '.zip'), cd('<img src=x onerror=alert(1)>.zip')]) {
            expect(P.packageFileName('zip', bad as any, FALLBACK), String(bad)).toBe(FALLBACK)
        }
        expect(P.packageFileName('zip', cd('x'.repeat(251) + '.zip'), FALLBACK)).toBe('x'.repeat(251) + '.zip')
        expect(P.packageFallbackName('zip', 3)).toBe('package-v3.zip')
        expect(P.packageFallbackName('record-html', 3)).toBe('package-v3-record-of-review.html')
        expect(P.packageFallbackName('record-json', null)).toBe('package-v-record-of-review.json')
        expect(P.packageFallbackName('draft-zip', undefined)).toBe('package-draft-v_DRAFT.zip')
    })

    it('(c) every request crossed with every answer has one sentence, and a cause is named only where the route has it', () => {
        const say = (kind: any, f: any) => P.packageDownloadProblem(kind, f)
        const busy = (n: string) => P.DOWNLOAD_BUSY + n
        const limited = (n: string) => P.DOWNLOAD_RATE_LIMITED + n
        // [the answer, then the sentence for zip, record-json, record-html, draft-zip, part]
        const ROWS: [any, string, string, string, string, string][] = [
            [{ status: 401 }, P.DOWNLOAD_SIGNED_OUT, P.DOWNLOAD_SIGNED_OUT, P.DOWNLOAD_SIGNED_OUT, P.DOWNLOAD_SIGNED_OUT, P.DOWNLOAD_SIGNED_OUT],
            [{ status: 403 }, P.DOWNLOAD_FORBIDDEN, P.DOWNLOAD_FORBIDDEN, P.DOWNLOAD_FORBIDDEN, P.DOWNLOAD_DRAFT_FORBIDDEN, P.DOWNLOAD_FORBIDDEN],
            [{ status: 404 }, P.DOWNLOAD_VERSION_NOT_FOUND, P.DOWNLOAD_VERSION_NOT_FOUND, P.DOWNLOAD_VERSION_NOT_FOUND, FAULT(404), FAULT(404)],
            [{ status: 409, contentType: PLAIN, body: PINNED }, P.DOWNLOAD_NOT_AS_RECORDED + PINNED, FAULT(409), FAULT(409), PINNED, FAULT(409)],
            [{ status: 409, contentType: PLAIN, body: CHANGED }, P.DOWNLOAD_NOT_AS_RECORDED + CHANGED, FAULT(409), FAULT(409), CHANGED, FAULT(409)],
            [{ status: 409, contentType: 'text/html', body: CHANGED }, P.DOWNLOAD_CONFLICT_UNREAD, FAULT(409), FAULT(409), P.DOWNLOAD_CONFLICT_UNREAD, FAULT(409)],
            [{ status: 429, retryAfter: '7' }, limited('7 seconds.'), limited('7 seconds.'), limited('7 seconds.'), limited('7 seconds.'), limited('7 seconds.')],
            [{ status: 429 }, limited('half a minute.'), limited('half a minute.'), limited('half a minute.'), limited('half a minute.'), limited('half a minute.')],
            [{ status: 503, retryAfter: '30' }, busy('30 seconds.'), P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, busy('30 seconds.'), P.DOWNLOAD_UNAVAILABLE],
            [{ status: 503, retryAfter: 'soon' }, busy('half a minute.'), P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, busy('half a minute.'), P.DOWNLOAD_UNAVAILABLE],
            [{ status: 503, retryAfter: null }, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE],
            [{ status: 503 }, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE, P.DOWNLOAD_UNAVAILABLE],
            ...[400, 405, 500, 502, 504, 206, 204, 302].map(n => [{ status: n, retryAfter: '30', contentType: PLAIN, body: NO_BUILD },
                FAULT(n), FAULT(n), FAULT(n), FAULT(n), FAULT(n)] as [any, string, string, string, string, string]),
            [{ status: NaN }, FAULT(0), FAULT(0), FAULT(0), FAULT(0), FAULT(0)],
        ]
        for (const [facts, ...want] of ROWS) {
            KINDS.forEach((kind, i) => {
                for (const writable of [undefined, true, false]) {
                    expect(say(kind, { ...facts, writable }), kind + ' ' + JSON.stringify(facts) + ' ' + writable).toBe(want[i])
                }
            })
        }
        expect(Object.keys(P.ROUTE_ANSWERS).sort()).toEqual([...KINDS].sort())
        expect(P.ROUTE_ANSWERS).toEqual({
            zip: { versionNotFound: true, conflict: 'final', busy: true },
            'record-json': { versionNotFound: true, conflict: null, busy: false },
            'record-html': { versionNotFound: true, conflict: null, busy: false },
            'draft-zip': { versionNotFound: false, conflict: 'draft', busy: true },
            part: { versionNotFound: false, conflict: null, busy: false },
        })
        // the number of a Retry-After, never its text
        for (const kind of ['zip', 'draft-zip']) {
            expect(say(kind, { status: 503, retryAfter: '5' })).toBe(busy('5 seconds.'))
            for (const odd of ['', '0', 'Wed, 21 Oct 2026 07:28:00 GMT', '-1', '30<b>', '99999', ' 30', '3e1']) {
                expect(say(kind, { status: 503, retryAfter: odd }), odd).toBe(busy('half a minute.'))
                expect(say(kind, { status: 429, retryAfter: odd }), odd).toBe(limited('half a minute.'))
            }
        }
        // 409 of the two zips: the server's text, as it is, only when it is text/plain and printable ASCII
        for (const text of FINAL_TEXTS) {
            for (const writable of [undefined, true, false]) {
                expect(say('zip', { status: 409, contentType: PLAIN, body: text, writable }), text).toBe(P.DOWNLOAD_NOT_AS_RECORDED + text)
            }
        }
        const markup = '<img src=x onerror="window.__hit=1">'
        expect(say('draft-zip', { status: 409, contentType: PLAIN, body: markup })).toBe(markup)
        for (const [contentType, body] of [[PLAIN, ''], [PLAIN, null], [PLAIN, 'x'.repeat(1001)], [PLAIN, 'line one\nline two'], [PLAIN, 'caf\u00e9'],
            [PLAIN, 'a\u202eb'], [PLAIN, 'a\u0000b'], ['text/html', NO_BUILD], [null, NO_BUILD], ['application/json', '{"a":1}'],
            [PLAIN, NO_BUILD + '\n'], [PLAIN, '\t' + NO_BUILD]] as any[]) {
            for (const writable of [undefined, true, false]) {
                const what = String(contentType) + ' ' + String(body).slice(0, 12) + ' ' + writable
                expect(say('draft-zip', { status: 409, contentType, body, writable }), what).toBe(P.DOWNLOAD_CONFLICT_UNREAD)
                expect(say('zip', { status: 409, contentType, body, writable }), what).toBe(P.DOWNLOAD_CONFLICT_UNREAD)
            }
        }
        expect(say('zip', { status: 409, contentType: 'Text/Plain', body: 'x'.repeat(1000) })).toBe(P.DOWNLOAD_NOT_AS_RECORDED + 'x'.repeat(1000))
    })

    it('(c2) the write-access sentence follows a draft text only when that text says to rebuild, and only for a reader', () => {
        const say = (body: string, writable?: boolean) => P.packageDownloadProblem('draft-zip', { status: 409, contentType: PLAIN, body, writable })
        expect(ASKS_REBUILD).toHaveLength(7)
        for (const text of ASKS_REBUILD) {
            expect(P.draftTextAsksRebuild(text), text).toBe(true)
            expect(say(text, false), text).toBe(text + ' A rebuild needs write access; ask a member who has it.')
            expect(say(text, true), text).toBe(text)
            expect(say(text), text).toBe(text)
        }
        for (const text of ASKS_NO_REBUILD) {
            expect(P.draftTextAsksRebuild(text), text).toBe(false)
            for (const writable of [false, true, undefined]) expect(say(text, writable), text).toBe(text)
        }
        // the reviewer's case (F-1), whole: a reader whose click raced another act is told to click again, and nothing more
        expect(say(CHANGED, false)).toBe('The draft changed while its zip was being made. Download again.')
        expect(say(NO_BUILD, false)).toBe('The draft has no finished build to download. When a build is running, wait for it; otherwise rebuild the draft.'
            + ' Then download again. A rebuild needs write access; ask a member who has it.')
        // never on another request, whatever its text
        for (const kind of ['zip', 'record-json', 'record-html', 'part']) {
            expect(P.packageDownloadProblem(kind as any, { status: 409, contentType: PLAIN, body: NO_BUILD, writable: false })).not.toContain('write access')
        }
        for (const status of [401, 403, 404, 429, 500, 503]) {
            expect(P.packageDownloadProblem('draft-zip', { status, contentType: PLAIN, body: NO_BUILD, writable: false, retryAfter: '30' }), String(status))
                .not.toContain('write access')
        }
    })

    it.runIf(draftZipJava && existsSync(draftZipJava))('(g) the two rebuild texts are the backend\'s, and the backend has no other 409 text (Pro source beside the schema)', () => {
        const java = readFileSync(draftZipJava, 'utf8')
        const constant = (name: string): string => {
            const m = new RegExp('String ' + name + ' = ((?:"[^"]*"|[^;"])+);').exec(java)
            expect(m, name).not.toBeNull()
            return [...m![1].matchAll(/"([^"\\]*)"/g)].map(x => x[1]).join('')
        }
        expect(constant('NO_BUILD')).toBe(P.DRAFT_NO_BUILD_TEXT)
        expect(constant('NOT_MARKED_ADVICE')).toBe(P.DRAFT_NOT_MARKED_ADVICE)
        expect(constant('CHANGED')).toBe(CHANGED)
        expect(constant('NOT_MARKED')).toBe(' could not be marked DRAFT: ')
        // four places make a draft file text and three calls answer a draft 409: a fifth or a fourth is a new text to read
        const made = [...java.matchAll(/new DraftFileException\("draft file " \+ name \+ (?:"([^"]*)"|NOT_MARKED \+ e\.reason\(\)\.text\(\) \+ NOT_MARKED_ADVICE)/g)]
        expect(made.map(m => m[1] ?? 'marked')).toEqual([' has no recorded sha256', ' could not be read from the artifact store',
            ' does not match the sha256 its build recorded', 'marked'])
        expect((java.match(/new DraftFileException\(/g) ?? []).length).toBe(4)
        const ws = readFileSync(resolve(SAAS_JAVA, 'ws/saas/SubmissionZipWs.java'), 'utf8')
        expect([...ws.matchAll(/conflict\(response, ([A-Za-z_.()]+)\);/g)].map(m => m[1])).toEqual(['DraftZipContents.NO_BUILD', 'e.getMessage()',
            'DraftZipContents.CHANGED', 'e.getMessage()'])
        const reasons = readFileSync(resolve(SAAS_JAVA, 'service/saas/DraftMark.java'), 'utf8')
        expect([...reasons.matchAll(/^\t\t[A-Z_0-9]+\("([^"]+)"\)[,;]$/gm)].map(m => m[1])).toEqual(MARK_REASONS)
        const layout = readFileSync(resolve(SAAS_JAVA, 'service/saas/PackageZipLayout.java'), 'utf8')
            + readFileSync(resolve(SAAS_JAVA, 'service/saas/PinnedArtifactBytes.java'), 'utf8')
        for (const text of [' has no recorded sha256', ' does not match its recorded sha256', 'the manifest does not match its recorded sha256',
            'a pinned file could not be read from the artifact store']) expect(layout, text).toContain('"' + text + '"')
    })

    it('(d) the page own sentences hold no enum word, uuid or instant', () => {
        const own = Object.entries(P).filter(([k, v]) => k.startsWith('DOWNLOAD_') && typeof v === 'string').map(([, v]) => v as string)
        expect(own.length).toBe(16)
        expectSwept(own.join('\n'))
    })

    it('(d2) the sentences, word for word', () => {
        expect(Object.fromEntries(Object.entries(P).filter(([k, v]) => k.startsWith('DOWNLOAD_') && typeof v === 'string'))).toEqual({
            DOWNLOAD_SIGNED_OUT: 'Your session is not valid any more. Sign in again, then try again.',
            DOWNLOAD_FORBIDDEN: 'ReARM refused this download. It needs the Artifact Download permission on the component of this release.',
            DOWNLOAD_DRAFT_FORBIDDEN: 'ReARM refused this download. It needs the Artifact Download permission on the component of this release.'
                + ' A draft that was finalized or discarded can no longer be downloaded.',
            DOWNLOAD_VERSION_NOT_FOUND: 'This version of the package was not found. Reload the page.',
            DOWNLOAD_BUSY: 'ReARM is making other package zips right now, or another zip of this organization. Nothing was downloaded. Try again in about ',
            DOWNLOAD_BUSY_SHORTLY: 'half a minute',
            DOWNLOAD_RATE_LIMITED: 'ReARM got too many requests from your account in a short time and refused this one. Try again in about ',
            DOWNLOAD_UNAVAILABLE: 'ReARM is not serving downloads at the moment. Try again later; if this stays, tell the administrator of this ReARM instance.',
            DOWNLOAD_NOT_AS_RECORDED: 'This version cannot be delivered as recorded. Nothing was downloaded. Give this message to the administrator of this ReARM instance: ',
            DOWNLOAD_CONFLICT_UNREAD: 'ReARM could not make this download, and its reason could not be read. Reload the page and try again.',
            DOWNLOAD_CANNOT_REBUILD: ' A rebuild needs write access; ask a member who has it.',
            DOWNLOAD_SERVER_FAULT: 'The download failed on the server (HTTP status ',
            DOWNLOAD_SERVER_FAULT_END: '). Try again; if this stays, tell the administrator of this ReARM instance the time of the attempt.',
            DOWNLOAD_NOT_THE_FILE: 'The server answered with something that is not the expected file. Nothing was saved. Reload the page and try again.',
            DOWNLOAD_INTERRUPTED: 'The download did not complete. Check the connection and try again; if this stays, reload the page.',
            DOWNLOAD_NOT_ASKED: 'This page could not make the request. Reload the page and try again.',
        })
    })

    it('(e) one request; only 200 with the media type of the download is the file', async () => {
        const zip = new Blob([new Uint8Array([0x50, 0x4b, 5, 6])], { type: 'application/zip' })
        fetchWithAuth.mockResolvedValueOnce(answer(200, { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="Pump-1.0-fda-premarket-v2.zip"' }, zip))
        let r: any = await P.fetchPackageFile('zip', P.packageZipUrl(S1, 2), 'package-v2.zip')
        expect(r).toEqual({ ok: true, blob: zip, fileName: 'Pump-1.0-fda-premarket-v2.zip' })
        expect(fetchWithAuth.mock.calls).toEqual([['/api/manual/v1/submission/' + S1 + '/version/2/zip']])

        fetchWithAuth.mockReset()
        fetchWithAuth.mockResolvedValueOnce(answer(200, { 'Content-Type': 'text/html;charset=UTF-8', 'Content-Disposition': 'attachment; filename="x-record-of-review.html"' }, '<p>'))
        r = await P.fetchPackageFile('record-html', P.recordOfReviewUrl(S1, 2, 'html'), 'f.html')
        expect(r.ok).toBe(true)
        expect(r.fileName).toBe('x-record-of-review.html')

        // the app shell or a proxy page answered 200: nothing is saved as a zip
        fetchWithAuth.mockResolvedValueOnce(answer(200, { 'Content-Type': 'text/html' }, '<html>'))
        expect(await P.fetchPackageFile('zip', P.packageZipUrl(S1, 2), 'f')).toEqual({ ok: false, status: 200, message: P.DOWNLOAD_NOT_THE_FILE })
        fetchWithAuth.mockResolvedValueOnce(answer(200, {}, 'x'))
        expect(await P.fetchPackageFile('draft-zip', P.draftZipUrl(D1), 'f')).toEqual({ ok: false, status: 200, message: P.DOWNLOAD_NOT_THE_FILE })
        fetchWithAuth.mockResolvedValueOnce(answer(206, { 'Content-Type': 'application/zip' }, zip))
        expect((await P.fetchPackageFile('zip', P.packageZipUrl(S1, 2), 'f') as any).message).toContain('(HTTP status 206)')

        fetchWithAuth.mockReset()
        fetchWithAuth.mockResolvedValueOnce(answer(503, { 'Retry-After': '30', 'Cache-Control': 'no-store' }))
        expect(await P.fetchPackageFile('draft-zip', P.draftZipUrl(D1), 'f')).toEqual({ ok: false, status: 503, message: P.DOWNLOAD_BUSY + '30 seconds.' })
        expect(fetchWithAuth).toHaveBeenCalledTimes(1)

        // F-1 through the request: a reader gets the write-access sentence after the text that says rebuild, not after CHANGED
        fetchWithAuth.mockResolvedValueOnce(answer(409, { 'Content-Type': 'text/plain;charset=UTF-8' }, CHANGED))
        expect(await P.fetchPackageFile('draft-zip', P.draftZipUrl(D1), 'f', { writable: false }))
            .toEqual({ ok: false, status: 409, message: CHANGED })
        fetchWithAuth.mockResolvedValueOnce(answer(409, { 'Content-Type': 'text/plain;charset=UTF-8' }, NO_BUILD))
        expect(await P.fetchPackageFile('draft-zip', P.draftZipUrl(D1), 'f', { writable: false }))
            .toEqual({ ok: false, status: 409, message: NO_BUILD + P.DOWNLOAD_CANNOT_REBUILD })
        fetchWithAuth.mockResolvedValueOnce(answer(409, { 'Content-Type': 'text/plain;charset=UTF-8' }, NO_BUILD))
        expect(await P.fetchPackageFile('draft-zip', P.draftZipUrl(D1), 'f', { writable: true })).toEqual({ ok: false, status: 409, message: NO_BUILD })
        fetchWithAuth.mockResolvedValueOnce(answer(409, { 'Content-Type': 'text/plain;charset=UTF-8' }, NOT_MARKED))
        expect(await P.fetchPackageFile('draft-zip', P.draftZipUrl(D1), 'f', { writable: false }))
            .toEqual({ ok: false, status: 409, message: NOT_MARKED + P.DOWNLOAD_CANNOT_REBUILD })
        // a record of review has no 409 and no busy answer: the body of one is not read, and a Retry-After is not a zip queue
        const odd = answer(409, { 'Content-Type': 'text/plain;charset=UTF-8' }, NO_BUILD)
        odd.text = vi.fn(async () => NO_BUILD)
        fetchWithAuth.mockResolvedValueOnce(odd)
        expect(await P.fetchPackageFile('record-html', P.recordOfReviewUrl(S1, 2, 'html'), 'f', { writable: false })).toEqual({ ok: false, status: 409, message: FAULT(409) })
        expect(odd.text).not.toHaveBeenCalled()
        fetchWithAuth.mockResolvedValueOnce(answer(503, { 'Retry-After': '30' }))
        expect(await P.fetchPackageFile('record-json', P.recordOfReviewUrl(S1, 2, 'json'), 'f')).toEqual({ ok: false, status: 503, message: P.DOWNLOAD_UNAVAILABLE })
        fetchWithAuth.mockResolvedValueOnce(answer(429, { 'Retry-After': '12', 'Content-Type': 'application/json' }, '{"error":"Rate limit exceeded"}'))
        expect(await P.fetchPackageFile('zip', P.packageZipUrl(S1, 2), 'f')).toEqual({ ok: false, status: 429, message: P.DOWNLOAD_RATE_LIMITED + '12 seconds.' })

        fetchWithAuth.mockReset()
        fetchWithAuth.mockRejectedValueOnce(new TypeError('Failed to fetch <b>'))
        expect(await P.fetchPackageFile('zip', P.packageZipUrl(S1, 2), 'f')).toEqual({ ok: false, status: 0, message: P.DOWNLOAD_INTERRUPTED })
        expect(fetchWithAuth).toHaveBeenCalledTimes(1)
        const cut = answer(200, { 'Content-Type': 'application/zip' })
        cut.blob = async () => { throw new TypeError('network error') }
        fetchWithAuth.mockResolvedValueOnce(cut)
        expect(await P.fetchPackageFile('zip', P.packageZipUrl(S1, 2), 'f')).toEqual({ ok: false, status: 200, message: P.DOWNLOAD_INTERRUPTED })
    })

    it('(h) the print fetch: one request, only 200 is the bytes, and every other answer is the sentence of a part request', async () => {
        const url = '/api/manual/v1/artifact/' + S1 + '/rawdownload?version=1'
        const bytes = new TextEncoder().encode('<!doctype html>').buffer
        const ok = answer(200, { 'Content-Type': 'text/html' })
        ok.arrayBuffer = async () => bytes
        fetchWithAuth.mockResolvedValueOnce(ok)
        expect(await P.fetchPartBytes(url)).toEqual({ ok: true, bytes })
        expect(fetchWithAuth.mock.calls).toEqual([[url]])
        const cases: [number, Record<string, string>, string][] = [
            [401, { 'Content-Type': 'application/json' }, P.DOWNLOAD_SIGNED_OUT],
            [403, {}, P.DOWNLOAD_FORBIDDEN],
            [404, {}, FAULT(404)],
            [409, { 'Content-Type': 'text/plain' }, FAULT(409)],
            [429, { 'Retry-After': '9' }, P.DOWNLOAD_RATE_LIMITED + '9 seconds.'],
            [503, {}, P.DOWNLOAD_UNAVAILABLE],
            [503, { 'Retry-After': '30' }, P.DOWNLOAD_UNAVAILABLE],
            [500, { 'Content-Type': 'application/json' }, FAULT(500)],
            [206, { 'Content-Type': 'text/html' }, FAULT(206)],
        ]
        for (const [status, headers, message] of cases) {
            fetchWithAuth.mockReset()
            const refused = answer(status, headers, '<b>HTTP ' + status + '</b> ' + NO_BUILD)
            refused.statusText = '<b>Forbidden</b>'
            refused.text = vi.fn(async () => NO_BUILD)
            refused.arrayBuffer = vi.fn(async () => bytes)
            fetchWithAuth.mockResolvedValueOnce(refused)
            expect(await P.fetchPartBytes(url), String(status)).toEqual({ ok: false, status, message })
            expect(fetchWithAuth).toHaveBeenCalledTimes(1)
            expect(refused.text).not.toHaveBeenCalled()
            expect(refused.arrayBuffer).not.toHaveBeenCalled()
        }
        fetchWithAuth.mockReset()
        fetchWithAuth.mockRejectedValueOnce(new Error('Unable to update token, no refresh token available. <b>'))
        expect(await P.fetchPartBytes(url)).toEqual({ ok: false, status: 0, message: P.DOWNLOAD_INTERRUPTED })
        expect(fetchWithAuth).toHaveBeenCalledTimes(1)
        const cut = answer(200, { 'Content-Type': 'text/html' })
        cut.arrayBuffer = async () => { throw new TypeError('network error') }
        fetchWithAuth.mockResolvedValueOnce(cut)
        expect(await P.fetchPartBytes(url)).toEqual({ ok: false, status: 200, message: P.DOWNLOAD_INTERRUPTED })
    })

    it('(f) a saved file goes through an octet-stream blob, its name through the DOM property, and the URL is revoked', () => {
        vi.useFakeTimers()
        const made: Blob[] = []
        const create = vi.spyOn(window.URL, 'createObjectURL').mockImplementation((b: any) => { made.push(b); return 'blob:test/1' })
        const revoke = vi.spyOn(window.URL, 'revokeObjectURL').mockImplementation(() => {})
        const clicked: any[] = []
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
            clicked.push({ href: this.getAttribute('href'), download: this.download, inPage: this.isConnected })
        })
        const html = new Blob(['<!doctype html><script>window.__hit=1</script>'], { type: 'text/html;charset=UTF-8' })
        P.saveFile(html, 'Pump-1.0-fda-premarket-v2-record-of-review.html')
        expect(made).toHaveLength(1)
        expect(made[0].type).toBe('application/octet-stream')
        expect(made[0].size).toBe(html.size)
        expect(clicked).toEqual([{ href: 'blob:test/1', download: 'Pump-1.0-fda-premarket-v2-record-of-review.html', inPage: false }])
        expect(revoke).not.toHaveBeenCalled()
        vi.advanceTimersByTime(P.SAVE_URL_LIFETIME_MS - 1)
        expect(revoke).not.toHaveBeenCalled()
        vi.advanceTimersByTime(1)
        expect(revoke.mock.calls).toEqual([['blob:test/1']])
        expect((window as any).__hit).toBeUndefined()
        create.mockRestore(); revoke.mockRestore(); click.mockRestore()
    })
})
