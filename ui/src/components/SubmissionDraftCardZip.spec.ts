// @vitest-environment happy-dom
//
// FDAW-122 row 3: the draft card's Download draft zip and the line under the contents table. The button is
// there at job BUILT for everyone who sees the card; a refusal is text on the card; a 409 reads the draft again,
// and the message stays on the card of that draft through that read.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const fetchPackageFile = vi.fn()
const saveFile = vi.fn()
const fetchWithAuth = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: vi.fn(), mutate: vi.fn() } }))
vi.mock('@/utils/fetchClient', () => ({ fetchWithAuth: (...a: any[]) => fetchWithAuth(...a), fetchArrayBufferWithAuth: vi.fn() }))
vi.mock('@/utils/packageDownloads', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/utils/packageDownloads')>()),
    fetchPackageFile: (...a: any[]) => fetchPackageFile(...a),
    saveFile: (...a: any[]) => saveFile(...a),
}))

const { default: Card } = await import('./SubmissionDraftCard.vue')
const { expectSwept } = await import('@/utils/__fixtures__/fdaSweep')
const realDownloads = await vi.importActual<typeof import('@/utils/packageDownloads')>('@/utils/packageDownloads')

const DRAFT = '22222222-2222-4222-8222-222222222222'
const OTHER_DRAFT = '66666666-6666-4666-8666-666666666666'
const PLANNED = '77777777-7777-4777-8777-777777777777'
const U1 = '11111111-1111-4111-8111-111111111111'
const NO_BUILD = 'The draft has no finished build to download. When a build is running, wait for it; otherwise rebuild the draft. Then download again.'
const CHANGED = 'The draft changed while its zip was being made. Download again.'
const NEEDS_WRITE = ' A rebuild needs write access; ask a member who has it.'
const NOT_ASKED = 'This page could not make the request. Reload the page and try again.'
const HINT = 'A file downloaded from this table is the stored file, without a draft mark. The draft zip carries _DRAFT in every file name, '
    + 'and marks each HTML document ReARM generated as a draft in its title and at the top of its page.'
const D: any = {
    uuid: DRAFT, org: 'o1', release: 'r1', component: 'c1', kind: 'FDA_PREMARKET', submission: null, state: 'EDITING', revision: 7,
    plannedSubmission: PLANNED, plannedVersion: 2, startedAt: '2026-11-13T05:00:00Z', startedBy: { actor: 'u1', actorName: 'Ann' }, reasonCategory: 'CORRECTION',
    reasonText: 'fix', replaces: null, job: { state: 'BUILT', attempt: 1 },
    build: { builtAt: '2026-11-13T06:07:08.123Z', releaseSbom: { version: 3 }, parts: [
        { part: 'DEVICE_SUPPORT_STATEMENT', artifact: U1, sha256: 'a'.repeat(64), sizeBytes: 10, documentVersion: 3 }], diff: null },
    confirmation: null, changesSinceBuild: { count: 0, fingerprintEqual: true, movedLines: [] },
}
const with_ = (...over: any[]) => Object.assign({}, D, ...over)

async function mountCard (props: Record<string, any> = {}) {
    // the panel gives the card a new draft object at every read: `given` is what it gives now
    const given = reactive<Record<string, any>>({ ...props })
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Card, { draft: D, secondConfirmer: false, isWritable: true, busy: false,
            stillRunning: false, names: {}, currentUser: null, lifecycle: 'ASSEMBLED', head: null, ...given })),
    }), { attachTo: document.body })
    await flushPromises()
    return { w, card: () => w.findComponent(Card), give: async (more: Record<string, any>) => { Object.assign(given, more); await flushPromises() } }
}
const testid = (w: any, id: string) => w.find('[data-testid="' + id + '"]')

describe('SubmissionDraftCard: the draft zip (FDAW-122 row 6)', () => {
    beforeEach(() => { fetchPackageFile.mockReset(); saveFile.mockReset(); fetchWithAuth.mockReset() })

    it('(a) the button is there at job BUILT only, also for a reader; the line under the table whenever a build is shown', async () => {
        const shown = async (props: any) => {
            const { w } = await mountCard(props)
            const r = [testid(w, 'draft-zip-download').exists(), testid(w, 'draft-files-hint').exists()]
            w.unmount()
            return r
        }
        expect(await shown({})).toEqual([true, true])
        expect(await shown({ isWritable: false })).toEqual([true, true])
        expect(await shown({ busy: true })).toEqual([true, true])
        expect(await shown({ draft: with_({ state: 'AWAITING_CONFIRMATION' }) })).toEqual([true, true])
        expect(await shown({ draft: with_({ changesSinceBuild: { count: 3, fingerprintEqual: false, movedLines: [] } }) })).toEqual([true, true])
        for (const state of ['NONE', 'REQUESTED', 'RUNNING', 'FAILED']) {
            expect(await shown({ draft: with_({ job: { state, attempt: 1 } }) }), state).toEqual([false, true])
        }
        expect(await shown({ draft: with_({ job: { state: 'NONE', attempt: 0 }, build: null }) })).toEqual([false, false])
        expect(await shown({ draft: with_({ build: null }) })).toEqual([false, false])
        expect(await shown({ draft: with_({ uuid: 'd1' }) })).toEqual([false, true])
        const { w } = await mountCard()
        expect(testid(w, 'draft-zip-download').text()).toBe('Download draft zip')
        expect(testid(w, 'draft-files-hint').text()).toBe(HINT)
        expectSwept(testid(w, 'draft-card').text())
        // no print on a draft: the stored statement of a draft has no mark (FDAW-120 R12)
        expect(testid(w, 'draft-part-download').exists()).toBe(true)
        expect(testid(w, 'submission-print').exists()).toBe(false)
        expect(testid(w, 'draft-card').text()).not.toContain('Print')
        w.unmount()
    })

    it('(b) one request with the draft uuid; the file is saved under the name the helper kept; busy until it ends', async () => {
        let release: (v: any) => void = () => {}
        fetchPackageFile.mockImplementation(() => new Promise(r => { release = r }))
        const { w, card } = await mountCard()
        const button = () => testid(w, 'draft-zip-download')
        await button().trigger('click')
        await flushPromises()
        expect((button().element as HTMLButtonElement).disabled).toBe(true)
        await button().trigger('click')
        expect(fetchPackageFile.mock.calls).toEqual([['draft-zip', '/api/manual/v1/submission-draft/' + DRAFT + '/zip', 'package-draft-v2_DRAFT.zip', { writable: true }]])
        const blob = new Blob(['PK'])
        release({ ok: true, blob, fileName: 'Pump-1.2.0-fda-premarket-v2_DRAFT.zip' })
        await flushPromises()
        expect(saveFile.mock.calls).toEqual([[blob, 'Pump-1.2.0-fda-premarket-v2_DRAFT.zip']])
        expect((button().element as HTMLButtonElement).disabled).toBe(false)
        expect(testid(w, 'draft-zip-message').exists()).toBe(false)
        expect(card().emitted('refresh')).toBeUndefined()
        w.unmount()
    })

    it('(c) a refusal is text on the card; a 409 has the panel read again; the next click clears the message', async () => {
        const HOSTILE = '<img src=x onerror="window.__hit=1"> could not be marked DRAFT'
        const { w, card } = await mountCard({ isWritable: false })
        const click = async (result: any) => {
            fetchPackageFile.mockResolvedValueOnce(result)
            await testid(w, 'draft-zip-download').trigger('click')
            await flushPromises()
        }
        await click({ ok: false, status: 409, message: HOSTILE })
        expect(fetchPackageFile.mock.calls[0][3]).toEqual({ writable: false })
        const message = testid(w, 'draft-zip-message')
        expect(message.text()).toBe(HOSTILE)
        expect(testid(w, 'draft-card').find('img').exists()).toBe(false)
        expect((window as any).__hit).toBeUndefined()
        expect(card().emitted('refresh')).toHaveLength(1)
        expect(saveFile).not.toHaveBeenCalled()

        await click({ ok: false, status: 403, message: 'refused' })
        expect(testid(w, 'draft-zip-message').text()).toBe('refused')
        await click({ ok: false, status: 503, message: 'busy' })
        expect(testid(w, 'draft-zip-message').text()).toBe('busy')
        expect(card().emitted('refresh')).toHaveLength(1)

        let release: (v: any) => void = () => {}
        fetchPackageFile.mockImplementationOnce(() => new Promise(r => { release = r }))
        await testid(w, 'draft-zip-download').trigger('click')
        await flushPromises()
        expect(testid(w, 'draft-zip-message').exists()).toBe(false)
        release({ ok: true, blob: new Blob(['PK']), fileName: 'x_DRAFT.zip' })
        await flushPromises()
        expect(saveFile).toHaveBeenCalledTimes(1)
        w.unmount()
    })

    it('(d) the message of a 409 stays through the read it asks for, on the card of that draft only', async () => {
        const { w, card, give } = await mountCard({ isWritable: false })
        fetchPackageFile.mockResolvedValueOnce({ ok: false, status: 409, message: NO_BUILD + NEEDS_WRITE })
        await testid(w, 'draft-zip-download').trigger('click')
        await flushPromises()
        expect(card().emitted('refresh')).toHaveLength(1)
        // the panel reads again and gives the same draft as a new object, its job running now: no button, the message still there
        await give({ draft: with_({ revision: 8, job: { state: 'RUNNING', attempt: 2 } }) })
        expect(testid(w, 'draft-zip-download').exists()).toBe(false)
        expect(testid(w, 'draft-zip-message').text()).toBe(NO_BUILD + NEEDS_WRITE)
        await give({ draft: with_({ revision: 9 }), busy: true })
        expect(testid(w, 'draft-zip-message').text()).toBe(NO_BUILD + NEEDS_WRITE)
        // another draft of the kind (this one was discarded and a new one started elsewhere): its card says nothing yet
        await give({ draft: with_({ uuid: OTHER_DRAFT, revision: 1 }) })
        expect(testid(w, 'draft-zip-download').exists()).toBe(true)
        expect(testid(w, 'draft-zip-message').exists()).toBe(false)
        // an answer that arrives for a draft the card no longer shows is not said on the other draft's card, and asks no read
        let release: (v: any) => void = () => {}
        fetchPackageFile.mockImplementationOnce(() => new Promise(r => { release = r }))
        await testid(w, 'draft-zip-download').trigger('click')
        await flushPromises()
        expect(fetchPackageFile.mock.calls[1][1]).toBe('/api/manual/v1/submission-draft/' + OTHER_DRAFT + '/zip')
        await give({ draft: with_({ uuid: DRAFT, revision: 10 }) })
        release({ ok: false, status: 409, message: CHANGED })
        await flushPromises()
        expect(testid(w, 'draft-zip-message').exists()).toBe(false)
        expect(card().emitted('refresh')).toHaveLength(1)
        expect((testid(w, 'draft-zip-download').element as HTMLButtonElement).disabled).toBe(false)
        w.unmount()
    })

    it('(e) through the real helper: a reader reads the write-access sentence after the text that says rebuild, and not after the others', async () => {
        fetchPackageFile.mockImplementation(realDownloads.fetchPackageFile)
        const answer = (status: number, body = '', headers: Record<string, string> = {}) => ({ status, ok: false, statusText: '',
            headers: { get: (k: string) => headers[k] ?? null }, text: async () => body, blob: async () => new Blob([body]) })
        const plain = { 'Content-Type': 'text/plain;charset=UTF-8' }
        const FILE = 'draft file fda/Rebuild-1.0-fda-premarket-v2_DRAFT-sbom.cdx.json does not match the sha256 its build recorded'
        const FORBIDDEN = 'ReARM refused this download. It needs the Artifact Download permission on the component of this release.'
            + ' A draft that was finalized or discarded can no longer be downloaded.'
        const CASES: [boolean, any, string, number][] = [
            [false, answer(409, NO_BUILD, plain), NO_BUILD + NEEDS_WRITE, 1],
            [true, answer(409, NO_BUILD, plain), NO_BUILD, 1],
            [false, answer(409, CHANGED, plain), CHANGED, 1],
            [false, answer(409, FILE, plain), FILE, 1],
            [false, answer(409, '<html>', { 'Content-Type': 'text/html' }), 'ReARM could not make this download, and its reason could not be read. Reload the page and try again.', 1],
            [false, answer(403), FORBIDDEN, 0],
            [false, answer(404), 'The download failed on the server (HTTP status 404). Try again; if this stays, tell the administrator of this ReARM instance the time of the attempt.', 0],
            [false, answer(503, '', { 'Retry-After': '30' }), 'ReARM is making other package zips right now, or another zip of this organization. Nothing was downloaded. Try again in about 30 seconds.', 0],
            [false, answer(503), 'ReARM is not serving downloads at the moment. Try again later; if this stays, tell the administrator of this ReARM instance.', 0],
            [false, answer(401), 'Your session is not valid any more. Sign in again, then try again.', 0],
        ]
        for (const [isWritable, response, want, refreshes] of CASES) {
            const { w, card } = await mountCard({ isWritable })
            fetchWithAuth.mockReset()
            fetchWithAuth.mockResolvedValueOnce(response)
            await testid(w, 'draft-zip-download').trigger('click')
            await flushPromises()
            expect(fetchWithAuth.mock.calls, want).toEqual([['/api/manual/v1/submission-draft/' + DRAFT + '/zip']])
            expect(testid(w, 'draft-zip-message').text(), want).toBe(want)
            expect(card().emitted('refresh') ?? [], want).toHaveLength(refreshes)
            w.unmount()
        }
        // the browser refuses the save: a constant sentence, never the thrown text, and no read
        vi.spyOn(console, 'error').mockImplementation(() => {})
        const { w, card } = await mountCard()
        fetchPackageFile.mockReset()
        fetchPackageFile.mockResolvedValueOnce({ ok: true, blob: new Blob(['PK']), fileName: 'x_DRAFT.zip' })
        saveFile.mockImplementationOnce(() => { throw new Error('quota <b>exceeded</b>') })
        await testid(w, 'draft-zip-download').trigger('click')
        await flushPromises()
        expect(testid(w, 'draft-zip-message').text()).toBe(NOT_ASKED)
        expect(card().emitted('refresh')).toBeUndefined()
        expect((testid(w, 'draft-zip-download').element as HTMLButtonElement).disabled).toBe(false)
        w.unmount()
    })
})
