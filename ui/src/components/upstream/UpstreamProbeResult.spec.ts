// @vitest-environment happy-dom
//
// T-510 (TPF-185 design 3.6): the probe of an upstream source in words. The backend's sentence, the
// two hints the UI adds for a timeout and a rejected API key, "Probe in progress" while pending and
// "never probed" for none; a message is shown as text and nothing else an object carries is shown.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('@/utils/commonFunctions', () => ({ default: { dateDisplay: (d: string) => 'D:' + d } }))

const { default: ProbeResult } = await import('./UpstreamProbeResult.vue')
const { probeApiKeyHint, probeResultSentence, probeTimeoutHint } = await import('@/utils/upstreamLabels')

const HOSTILE = '<img src=x onerror=alert(1)>'

function done (result: string, message: string | null, extra: Record<string, any> = {}) {
    return { state: 'DONE', result, message, finishedDate: '2026-11-01T10:00:00Z', ...extra }
}

function render (probe: any, credentialKind?: string) {
    return mount(ProbeResult, { props: { probe, credentialKind }, attachTo: document.body })
}

describe('UpstreamProbeResult', () => {
    it('timedOutShowsTheMessageAndTheTimeoutHint', () => {
        const w = render(done('TIMED_OUT', probeResultSentence.TIMED_OUT))
        expect(w.text()).toContain(probeResultSentence.TIMED_OUT)
        expect(w.text()).toContain(probeTimeoutHint)
        expect(w.text()).not.toContain(probeApiKeyHint)
        w.unmount()
    })

    it('couldNotConnectShowsTheMessageAndNotTheHint', () => {
        const w = render(done('COULD_NOT_CONNECT', probeResultSentence.COULD_NOT_CONNECT))
        expect(w.find('[data-testid="upstream-probe-message"]').text()).toBe(probeResultSentence.COULD_NOT_CONNECT)
        expect(w.text()).not.toContain(probeTimeoutHint)
        expect(w.find('[data-testid="upstream-probe-hint"]').exists()).toBe(false)
        w.unmount()
    })

    it('theApiKeyHintShowsOnlyForAnApiKey', () => {
        const apiKey = render(done('AUTHENTICATION_FAILED', probeResultSentence.AUTHENTICATION_FAILED), 'API_KEY')
        expect(apiKey.text()).toContain(probeResultSentence.AUTHENTICATION_FAILED)
        expect(apiKey.text()).toContain(probeApiKeyHint)
        apiKey.unmount()

        const bearer = render(done('AUTHENTICATION_FAILED', probeResultSentence.AUTHENTICATION_FAILED), 'BEARER_TOKEN')
        expect(bearer.text()).toContain(probeResultSentence.AUTHENTICATION_FAILED)
        expect(bearer.text()).not.toContain(probeApiKeyHint)
        bearer.unmount()
    })

    it('aHostileMessageIsTextAndNotMarkup', () => {
        const w = render(done('REACHABLE', HOSTILE))
        expect(w.text()).toContain(HOSTILE)
        expect(w.find('img').exists()).toBe(false)
        expect(document.body.querySelector('img')).toBeNull()
        w.unmount()
    })

    it('strayPropertiesOfTheObjectAreNotShown', () => {
        const w = render(done('REACHABLE', probeResultSentence.REACHABLE, { address: '10.1.2.3', status: 502 }))
        expect(w.text()).toContain(probeResultSentence.REACHABLE)
        expect(w.text()).not.toContain('10.1.2.3')
        expect(w.text()).not.toContain('502')
        w.unmount()
    })

    it('aPendingProbeSaysProbeInProgress', () => {
        const w = render({ state: 'PENDING', result: null, message: null })
        expect(w.text()).toContain('Probe in progress')
        expect(w.find('[data-testid="upstream-probe-done"]').exists()).toBe(false)
        w.unmount()
    })

    it('noProbeSaysNeverProbed', () => {
        const w = render(null)
        expect(w.text()).toContain('never probed')
        w.unmount()
    })
})
