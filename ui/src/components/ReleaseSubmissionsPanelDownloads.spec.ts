// @vitest-environment happy-dom
//
// FDAW-122 row 4: on every version of the package page, Download zip and the record of review as HTML and as
// JSON; on a generated HTML part, Print or save as PDF through the sandboxed print frame. A reader sees them
// too; a refusal is text under the buttons, and for every one of the four requests it is the page's sentence
// for that answer, never a thrown message or a status text.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
const fetchWithAuth = vi.fn()
const fetchPackageFile = vi.fn()
const fetchPartBytes = vi.fn()
const saveFile = vi.fn()
const downloadArtifactBytes = vi.fn()
const printHtml = vi.fn()
const removePrintFrame = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: (...a: any[]) => fetchWithAuth(...a), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('@/utils/artifactDownload', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/utils/artifactDownload')>()),
    downloadArtifactBytes: (...a: any[]) => downloadArtifactBytes(...a),
    saveBlob: vi.fn(),
}))
vi.mock('@/utils/packageDownloads', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/utils/packageDownloads')>()),
    fetchPackageFile: (...a: any[]) => fetchPackageFile(...a),
    fetchPartBytes: (...a: any[]) => fetchPartBytes(...a),
    saveFile: (...a: any[]) => saveFile(...a),
}))
vi.mock('@/utils/documentPrint', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/utils/documentPrint')>()),
    printHtml: (...a: any[]) => printHtml(...a),
    removePrintFrame: (...a: any[]) => removePrintFrame(...a),
}))

const { default: Panel } = await import('./ReleaseSubmissionsPanel.vue')
const { GENERATED_HEAD_OPEN, NOT_PRINTABLE, PRINT_FAILED } = await import('@/utils/documentPrint')
const realDownloads = await vi.importActual<typeof import('@/utils/packageDownloads')>('@/utils/packageDownloads')
const NOT_ASKED = 'This page could not make the request. Reload the page and try again.'
const FORBIDDEN = 'ReARM refused this download. It needs the Artifact Download permission on the component of this release.'
const SIGNED_OUT = 'Your session is not valid any more. Sign in again, then try again.'
const UNAVAILABLE = 'ReARM is not serving downloads at the moment. Try again later; if this stays, tell the administrator of this ReARM instance.'
const FAULT = (n: number) => 'The download failed on the server (HTTP status ' + n + '). Try again; if this stays, tell the administrator of this ReARM instance the time of the attempt.'
const { expectSwept } = await import('@/utils/__fixtures__/fdaSweep')

const HEAD = '44444444-4444-4444-8444-444444444444'
const A1 = '11111111-1111-4111-8111-111111111111'
const A2 = '22222222-2222-4222-8222-222222222222'
const A3 = '55555555-5555-4555-8555-555555555555'
const M = '33333333-3333-4333-8333-333333333333'
const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const STATEMENT = GENERATED_HEAD_OPEN + 'Device software support statement</title>\n</head>\n<body>\n<h1>Caf\u00e9</h1>\n</body>\n</html>\n'

const v = (n: number, parts: any[] = []) => ({
    submission: HEAD, version: n, kind: 'FDA_PREMARKET', createdDate: '2026-10-0' + n + 'T00:00:00Z', createdBy: 'u1', reason: null,
    settings: { row: null, revision: 0, source: 'DEFAULT' }, sbomProfile: { name: 'default', row: null, revision: 0, source: 'DEFAULT' },
    manifestArtifact: M, manifestArtifactVersion: '1', manifestSha256: 'c'.repeat(64), manifest: '{}', parts, confirmations: [],
})
const PARTS = [
    { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', outcome: 'REFERENCED', artifact: A1, artifactVersion: '4',
        fileName: 'widget-sbom.cdx.json', mediaType: 'application/json', size: 10, sha256: 'a'.repeat(64) },
    { part: 'DEVICE_SUPPORT_STATEMENT', requirement: 'OPTIONAL', outcome: 'PRODUCED', artifact: A2, artifactVersion: '1',
        fileName: 'widget-1.0-fda-premarket-v2-device-support-statement.html', mediaType: 'text/html', size: 5, sha256: 'b'.repeat(64) },
    { part: 'RAW_ARTIFACTS', requirement: 'OPTIONAL', outcome: 'REFERENCED', artifact: A3, artifactVersion: '1',
        fileName: 'uploaded.html', mediaType: 'text/html', size: 5, sha256: 'd'.repeat(64) },
]
const HEADS = [{ uuid: HEAD, release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2,
    lastAct: { kind: 'FINALIZE', actedAt: '2026-10-02T00:00:00Z', actorName: 'Ann' }, lastActDate: '2026-10-02T00:00:00Z', lastActBy: 'u1',
    versions: [v(1), v(2, PARTS)] }]

function answers () {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'ReleaseDrafts': return { data: { release: { uuid: 'r1', drafts: [] } } }
        case 'OrgSecondConfirmer': return { data: { organizations: [{ uuid: 'o1', settings: { submissionSecondConfirmer: false } }] } }
        case 'PackageKinds': return { data: { packageKinds: [{ kind: 'FDA_PREMARKET', parts: [] }] } }
        case 'SubmissionsOfRelease': return { data: { submissionsOfRelease: HEADS } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

async function mountPanel (props: Record<string, any> = {}) {
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Panel, { orgUuid: 'o1', releaseUuid: 'r1', releaseVersion: '1.0.0',
            lifecycle: 'ASSEMBLED', componentUuid: 'c1', componentName: 'widget', isWritable: true, installationType: 'SAAS', ...props })),
    }), { attachTo: document.body })
    await flushPromises()
    return w
}
const latest = (w: any) => w.find('[data-testid="submission-latest-FDA_PREMARKET"]')
const block = (w: any) => latest(w).find('[data-testid="submission-package-FDA_PREMARKET-2"]')
const disabled = (b: any) => (b.element as HTMLButtonElement).disabled

describe('ReleaseSubmissionsPanel: package downloads (FDAW-122 row 7)', () => {
    beforeEach(() => {
        for (const m of [query, fetchWithAuth, fetchPackageFile, fetchPartBytes, saveFile, downloadArtifactBytes, printHtml, removePrintFrame]) m.mockReset()
        answers()
    })

    it('(a) the three buttons are the first thing of a version, for a reader too, in this order', async () => {
        for (const isWritable of [true, false]) {
            const w = await mountPanel({ isWritable })
            const detail = latest(w).find('.submission-version-detail')
            expect(detail.element.firstElementChild).toBe(block(w).element)
            expect(block(w).findAll('button').map((b: any) => [b.attributes('data-testid'), b.text()])).toEqual([
                ['submission-zip-download', 'Download zip'], ['submission-record-html', 'Record of review (HTML)'],
                ['submission-record-json', 'Record of review (JSON)']])
            expect(block(w).find('[data-testid="submission-package-message"]').exists()).toBe(false)
            expectSwept(block(w).text())
            w.unmount()
        }
    })

    it('(b) each button asks its URL of the head uuid and the version number, once, and saves what the helper answers', async () => {
        const w = await mountPanel()
        const blob = new Blob(['PK'])
        const cases: [string, string, string, string][] = [
            ['submission-zip-download', 'zip', '/api/manual/v1/submission/' + HEAD + '/version/2/zip', 'package-v2.zip'],
            ['submission-record-html', 'record-html', '/api/manual/v1/submission/' + HEAD + '/version/2/record-of-review.html', 'package-v2-record-of-review.html'],
            ['submission-record-json', 'record-json', '/api/manual/v1/submission/' + HEAD + '/version/2/record-of-review.json', 'package-v2-record-of-review.json'],
        ]
        for (const [id, what, url, fallback] of cases) {
            fetchPackageFile.mockReset(); saveFile.mockReset()
            fetchPackageFile.mockResolvedValueOnce({ ok: true, blob, fileName: 'served-name' })
            await block(w).find('[data-testid="' + id + '"]').trigger('click')
            await flushPromises()
            expect(fetchPackageFile.mock.calls, id).toEqual([[what, url, fallback]])
            expect(saveFile.mock.calls, id).toEqual([[blob, 'served-name']])
        }
        w.unmount()
    })

    it('(c) while one request runs every button of that version is disabled; a refusal is text under them and goes at the next click', async () => {
        const w = await mountPanel()
        let release: (v: any) => void = () => {}
        fetchPackageFile.mockImplementationOnce(() => new Promise(r => { release = r }))
        // two clicks in one tick, before the page has drawn the buttons disabled: still one request
        const zip = block(w).find('[data-testid="submission-zip-download"]').element as HTMLButtonElement
        zip.click()
        zip.click()
        await flushPromises()
        expect(fetchPackageFile).toHaveBeenCalledTimes(1)
        expect(block(w).findAll('button').map(disabled)).toEqual([true, true, true])
        expect(disabled(latest(w).find('[data-testid="submission-print"]'))).toBe(true)
        await block(w).find('[data-testid="submission-record-json"]').trigger('click')
        expect(fetchPackageFile).toHaveBeenCalledTimes(1)
        const HOSTILE = 'pinned file fda/x <img src=x onerror="window.__hit=1"> does not match'
        release({ ok: false, status: 409, message: HOSTILE })
        await flushPromises()
        expect(block(w).findAll('button').map(disabled)).toEqual([false, false, false])
        expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe(HOSTILE)
        expect(w.find('.release-submissions-panel img').exists()).toBe(false)
        expect((window as any).__hit).toBeUndefined()
        expect(saveFile).not.toHaveBeenCalled()
        fetchPackageFile.mockResolvedValueOnce({ ok: true, blob: new Blob(['x']), fileName: 'n' })
        await block(w).find('[data-testid="submission-record-json"]').trigger('click')
        await flushPromises()
        expect(block(w).find('[data-testid="submission-package-message"]').exists()).toBe(false)
        // the browser refuses the save, or the helper throws: a constant sentence, never the thrown text
        vi.spyOn(console, 'error').mockImplementation(() => {})
        fetchPackageFile.mockResolvedValueOnce({ ok: true, blob: new Blob(['x']), fileName: 'n' })
        saveFile.mockImplementationOnce(() => { throw new Error('quota <b>exceeded</b>') })
        await block(w).find('[data-testid="submission-zip-download"]').trigger('click')
        await flushPromises()
        expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe(NOT_ASKED)
        fetchPackageFile.mockRejectedValueOnce(new Error('Not a submission uuid: undefined'))
        await block(w).find('[data-testid="submission-record-html"]').trigger('click')
        await flushPromises()
        expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe(NOT_ASKED)
        expect(block(w).findAll('button').map(disabled)).toEqual([false, false, false])
        w.unmount()
    })

    it('(d) an older version has its own buttons and its own message', async () => {
        const w = await mountPanel()
        const row = w.findAll('[data-testid="submission-versions-FDA_PREMARKET"] tbody tr').find((r: any) => r.findAll('td')[1]?.text() === 'v1')!
        await row.find('.n-data-table-expand-trigger').trigger('click')
        await flushPromises()
        const old = w.find('[data-testid="submission-versions-FDA_PREMARKET"] [data-testid="submission-package-FDA_PREMARKET-1"]')
        fetchPackageFile.mockResolvedValueOnce({ ok: false, status: 404, message: 'not found' })
        await old.find('[data-testid="submission-zip-download"]').trigger('click')
        await flushPromises()
        expect(fetchPackageFile.mock.calls).toEqual([['zip', '/api/manual/v1/submission/' + HEAD + '/version/1/zip', 'package-v1.zip']])
        expect(old.find('[data-testid="submission-package-message"]').text()).toBe('not found')
        expect(block(w).find('[data-testid="submission-package-message"]').exists()).toBe(false)
        w.unmount()
    })

    it('(e) Print or save as PDF is on the generated HTML part only, and prints its stored bytes in the frame', async () => {
        const w = await mountPanel()
        const rows = latest(w).findAll('[data-testid="submission-parts-FDA_PREMARKET-2"] tbody tr')
        expect(rows.map((r: any) => r.find('[data-testid="submission-print"]').exists())).toEqual([false, true, false])
        expect(rows.map((r: any) => r.find('[data-testid="submission-download"]').exists())).toEqual([true, true, true])
        const print = rows[1].find('[data-testid="submission-print"]')
        expect(print.text()).toBe('Print or save as PDF')
        fetchPartBytes.mockResolvedValueOnce({ ok: true, bytes: new TextEncoder().encode(STATEMENT).buffer })
        await print.trigger('click')
        await flushPromises()
        expect(fetchPartBytes.mock.calls).toEqual([['/api/manual/v1/artifact/' + A2 + '/rawdownload?version=1']])
        expect(downloadArtifactBytes).not.toHaveBeenCalled()
        expect(printHtml).toHaveBeenCalledTimes(1)
        expect(printHtml.mock.calls[0][0]).toBe(STATEMENT)
        expect(block(w).find('[data-testid="submission-package-message"]').exists()).toBe(false)
        // the browser gives no dialog: the callback puts the sentence under the buttons
        printHtml.mock.calls[0][1]()
        await flushPromises()
        expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe(PRINT_FAILED)
        w.unmount()
        expect(removePrintFrame).toHaveBeenCalledTimes(1)
    })

    it('(f) a file that does not start as a generated document is not given to the frame', async () => {
        const w = await mountPanel()
        const print = () => latest(w).find('[data-testid="submission-print"]')
        for (const bytes of [new TextEncoder().encode('<html><body><script>parent.__hit=1</script></body></html>').buffer,
            new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode(STATEMENT)]).buffer]) {
            fetchPartBytes.mockResolvedValueOnce({ ok: true, bytes })
            await print().trigger('click')
            await flushPromises()
            expect(printHtml).not.toHaveBeenCalled()
            expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe(NOT_PRINTABLE)
        }
        // a refused fetch: the helper's sentence, as text
        fetchPartBytes.mockResolvedValueOnce({ ok: false, status: 403, message: 'refused <b>here</b>' })
        await print().trigger('click')
        await flushPromises()
        expect(printHtml).not.toHaveBeenCalled()
        expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe('refused <b>here</b>')
        expect(w.find('.release-submissions-panel b').exists()).toBe(false)
        // a helper that throws: a constant sentence, never the thrown text
        vi.spyOn(console, 'error').mockImplementation(() => {})
        fetchPartBytes.mockRejectedValueOnce(new Error('HTTP 403: <b>Forbidden</b>'))
        await print().trigger('click')
        await flushPromises()
        expect(printHtml).not.toHaveBeenCalled()
        expect(block(w).find('[data-testid="submission-package-message"]').text()).toBe(NOT_ASKED)
        expect(disabled(print())).toBe(false)
        expect(document.querySelectorAll('iframe')).toHaveLength(0)
        w.unmount()
    })

    it('(g) refused by the server, each of the four requests of a version says the page\'s sentence for that answer (check P3)', async () => {
        // the real helpers over a refused request: what a member without the function reads under the buttons
        fetchPackageFile.mockImplementation(realDownloads.fetchPackageFile)
        fetchPartBytes.mockImplementation(realDownloads.fetchPartBytes)
        const w = await mountPanel({ isWritable: false })
        const refused = (status: number, headers: Record<string, string> = {}) => ({ status, ok: false, statusText: '<b>Forbidden</b>',
            headers: { get: (k: string) => headers[k] ?? null }, text: async () => '<b>body</b>', blob: async () => new Blob(['x']),
            arrayBuffer: async () => new ArrayBuffer(0) })
        const BUTTONS: [string, string][] = [
            ['submission-zip-download', '/api/manual/v1/submission/' + HEAD + '/version/2/zip'],
            ['submission-record-html', '/api/manual/v1/submission/' + HEAD + '/version/2/record-of-review.html'],
            ['submission-record-json', '/api/manual/v1/submission/' + HEAD + '/version/2/record-of-review.json'],
            ['submission-print', '/api/manual/v1/artifact/' + A2 + '/rawdownload?version=1'],
        ]
        const NOT_FOUND = 'This version of the package was not found. Reload the page.'
        const ANSWERS: [number, Record<string, string>, string[]][] = [
            [403, {}, [FORBIDDEN, FORBIDDEN, FORBIDDEN, FORBIDDEN]],
            [401, {}, [SIGNED_OUT, SIGNED_OUT, SIGNED_OUT, SIGNED_OUT]],
            [404, {}, [NOT_FOUND, NOT_FOUND, NOT_FOUND, FAULT(404)]],
            [503, {}, [UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE]],
            [503, { 'Retry-After': '30' }, ['ReARM is making other package zips right now, or another zip of this organization. Nothing was downloaded.'
                + ' Try again in about 30 seconds.', UNAVAILABLE, UNAVAILABLE, UNAVAILABLE]],
            [500, {}, [FAULT(500), FAULT(500), FAULT(500), FAULT(500)]],
        ]
        for (const [status, headers, want] of ANSWERS) {
            for (const [i, [id, url]] of BUTTONS.entries()) {
                fetchWithAuth.mockReset()
                fetchWithAuth.mockResolvedValueOnce(refused(status, headers))
                await latest(w).find('[data-testid="' + id + '"]').trigger('click')
                await flushPromises()
                expect(fetchWithAuth.mock.calls, id + ' ' + status).toEqual([[url]])
                expect(block(w).find('[data-testid="submission-package-message"]').text(), id + ' ' + status).toBe(want[i])
            }
        }
        expect(saveFile).not.toHaveBeenCalled()
        expect(printHtml).not.toHaveBeenCalled()
        expect(w.find('.release-submissions-panel b').exists()).toBe(false)
        w.unmount()
    })
})
