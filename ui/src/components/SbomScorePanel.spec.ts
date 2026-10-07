// @vitest-environment happy-dom
//
// The SBOM score panel (SCORE-6, design 3.6-3.8): runs the caller's load, counts the seconds,
// gives up at 185 s, shows the report or the one line that says why there is none, and hands
// out the raw report byte for byte. Nothing global is mocked but the notification hook: the
// query arrives as the load prop.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const notify = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => notify }))
vi.mock('@/utils/commonFunctions', () => ({
    default: { extractGraphQLErrorMessage: (e: any) => e?.message || 'Unknown error' }
}))

import SbomScorePanel from './SbomScorePanel.vue'
import { fixtureText } from '@/utils/__fixtures__/sbomScore/fixtures'
import { SBOM_SCORE_TIMEOUT_MESSAGE } from '@/utils/sbomScore'

function mountPanel (load: (signal: AbortSignal) => Promise<string>) {
    return mount(SbomScorePanel, { props: { load, fileKind: 'release', fileId: '0123456789abcdef' }, attachTo: document.body })
}

function scoreError (reason: string, message = 'server says why'): any {
    const err: any = new Error(message)
    err.errors = [{ message, extensions: { code: 'SBOM_SCORE_ERROR', reason } }]
    return err
}
function withErrors (message: string, extensions: any = {}): any {
    const err: any = new Error(message)
    err.errors = [{ message, extensions }]
    return err
}
function httpError (status: number): any {
    const err: any = new Error(`Response not successful: Received status code ${status}`)
    err.statusCode = status
    return err
}

beforeEach(() => {
    vi.useFakeTimers()
    notify.success.mockReset()
})
afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
})

describe('SbomScorePanel', () => {
    // T-12
    it('shows the spinner, then the report, and downloads and copies the raw string', async () => {
        const raw = fixtureText('fda.cdx')
        let resolve!: (s: string) => void
        const load = vi.fn(() => new Promise<string>(r => { resolve = r }))
        const w = mountPanel(load)
        await flushPromises()
        expect(load).toHaveBeenCalledTimes(1)
        expect(w.find('[data-testid="sbom-score-loading"]').exists()).toBe(true)
        expect(w.emitted('update:pending')?.[0]).toEqual([true])

        resolve(raw)
        await flushPromises()
        expect(w.find('[data-testid="sbom-score-loading"]').exists()).toBe(false)
        expect(w.findComponent({ name: 'SbomScoreReport' }).exists()).toBe(true)
        expect(w.find('[data-testid="sbom-score-verdict"]').text()).toBe('READY')
        expect(w.emitted('update:pending')?.at(-1)).toEqual([false])

        const blobs: Blob[] = []
        const create = vi.spyOn(URL, 'createObjectURL').mockImplementation((b: any) => { blobs.push(b); return 'blob:x' })
        vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
        await w.find('[data-testid="sbom-score-download"]').trigger('click')
        expect(create).toHaveBeenCalledTimes(1)
        expect(await blobs[0].text()).toBe(raw)
        expect(blobs[0].type).toBe('application/json')
        expect(click).toHaveBeenCalledTimes(1)

        const writeText = vi.fn().mockResolvedValue(undefined)
        Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
        await w.find('[data-testid="sbom-score-copy"]').trigger('click')
        await flushPromises()
        expect(writeText).toHaveBeenCalledWith(raw)
        expect(notify.success).toHaveBeenCalledWith(expect.objectContaining({ title: 'Report copied' }))
        w.unmount()
    })

    // T-13
    it('counts the seconds, hints after 30 s, aborts at 185 s with Retry, and Retry loads again', async () => {
        const signals: AbortSignal[] = []
        const load = vi.fn((signal: AbortSignal) => { signals.push(signal); return new Promise<string>(() => {}) })
        const w = mountPanel(load)
        await flushPromises()
        await vi.advanceTimersByTimeAsync(31_000)
        const loading = w.find('[data-testid="sbom-score-loading"]').text()
        expect(loading).toContain('31s')
        expect(loading).toContain('Large SBOMs can take up to 3 minutes')
        expect(signals[0].aborted).toBe(false)

        await vi.advanceTimersByTimeAsync(185_000 - 31_000)
        expect(signals[0].aborted).toBe(true)
        expect(w.find('[data-testid="sbom-score-error"]').text()).toContain(SBOM_SCORE_TIMEOUT_MESSAGE)
        expect(w.emitted('update:pending')?.at(-1)).toEqual([false])

        await w.find('[data-testid="sbom-score-retry"]').trigger('click')
        await flushPromises()
        expect(load).toHaveBeenCalledTimes(2)
        expect(signals[1].aborted).toBe(false)
        expect(w.find('[data-testid="sbom-score-loading"]').text()).toContain('0s')
        w.unmount()
    })

    // T-14: every condition of design 3.6, Retry only where it says, never a stack.
    it.each([
        ['UNKNOWN_PROFILE', () => scoreError('UNKNOWN_PROFILE'), 'The server does not know the profiles this UI asked for (cisa-2026, fda). Update the server. server says why', false],
        ['INPUT_TOO_LARGE', () => scoreError('INPUT_TOO_LARGE'), 'This SBOM is too large to score on this server. server says why', false],
        ['HTTP 413', () => httpError(413), 'This SBOM is too large to score on this server.', false],
        ['INPUT_REFUSED', () => scoreError('INPUT_REFUSED'), 'The scoring engine cannot score this document: server says why', false],
        ['NOT_SCORABLE', () => scoreError('NOT_SCORABLE'), 'This artifact is not a scorable SBOM: server says why', false],
        ['TIMEOUT', () => scoreError('TIMEOUT'), SBOM_SCORE_TIMEOUT_MESSAGE, true],
        ['HTTP 504', () => httpError(504), SBOM_SCORE_TIMEOUT_MESSAGE, true],
        ['SCORING_UNAVAILABLE', () => scoreError('SCORING_UNAVAILABLE'), 'SBOM scoring is not available on this server right now', true],
        ['SCORING_FAILED', () => scoreError('SCORING_FAILED'), 'The scoring engine failed: server says why', true],
        ['Not authorized', () => withErrors('Not authorized'), 'You are not authorized to score this SBOM (it needs the permission to download it).', false],
        ['schema drift', () => withErrors("Validation error of type FieldUndefined: Field 'artifactSbomScore' in type 'Query' is undefined", { classification: 'ValidationError' }), 'SBOM scoring is not available on this server.', false],
        ['anything else', () => httpError(500), 'Scoring failed: Response not successful: Received status code 500', true],
    ])('%s', async (_name, makeError, text, retryable) => {
        const err = makeError()
        err.stack = 'Error: STACK-MARKER at load (file.js:1:1)'
        const w = mountPanel(() => Promise.reject(err))
        await flushPromises()
        const alert = w.find('[data-testid="sbom-score-error"]')
        expect(alert.text()).toContain(text)
        expect(w.find('[data-testid="sbom-score-retry"]').exists()).toBe(retryable)
        expect(w.text()).not.toContain('STACK-MARKER')
        expect(w.find('[data-testid="sbom-score-download"]').exists()).toBe(false)
        w.unmount()
    })

    // T-15
    it('says when the report is of a version this UI does not know, and still offers the raw JSON', async () => {
        const raw = fixtureText('version-2')
        const w = mountPanel(() => Promise.resolve(raw))
        await flushPromises()
        expect(w.find('[data-testid="sbom-score-error"]').text()).toContain('unsupported report version 2; update ReARM UI')
        expect(w.find('[data-testid="sbom-score-retry"]').exists()).toBe(false)
        expect(w.findComponent({ name: 'SbomScoreReport' }).exists()).toBe(false)
        const blobs: Blob[] = []
        vi.spyOn(URL, 'createObjectURL').mockImplementation((b: any) => { blobs.push(b); return 'blob:x' })
        vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
        vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
        await w.find('[data-testid="sbom-score-download"]').trigger('click')
        expect(await blobs[0].text()).toBe(raw)
        w.unmount()
    })

    // T-16
    it('aborts the request and stops the clock when unmounted while pending', async () => {
        const clear = vi.spyOn(globalThis, 'clearInterval')
        let signal!: AbortSignal
        let resolve!: (s: string) => void
        const w = mountPanel(s => { signal = s; return new Promise<string>(r => { resolve = r }) })
        await flushPromises()
        expect(signal.aborted).toBe(false)
        w.unmount()
        expect(signal.aborted).toBe(true)
        expect(clear).toHaveBeenCalled()
        expect(vi.getTimerCount()).toBe(0)
        // A late answer after unmount changes nothing and throws nothing.
        resolve(fixtureText('fda.cdx'))
        await flushPromises()
    })
})
