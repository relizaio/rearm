import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import {
    SUPPORT_HINT_CHECKING, SUPPORT_HINT_DISABLED, SUPPORT_HINT_UNKNOWN,
    releaseExportState, supportSwitchHint, supportSwitchOffered, useExportSupportSwitch
} from './useExportSupportSwitch'
import type { ReleaseSupportCoverage, SupportExportState } from './releaseSupportCoverage'

/**
 * The export dialog's support switch, run (SCORE-21 round 2 ADR-11, tests T-18 to T-20): the
 * release organization's supportExportState decides whether it moves and which hint shows, and
 * opening the dialog asks for that state when nothing has yet. Where ReleaseView wires it is
 * pinned in components/releaseViewExportWiring.spec.ts.
 */

const STATES: SupportExportState[] = ['ENABLED', 'DISABLED', 'UNKNOWN', 'PARTIAL']
const coverageOf = (exportState: SupportExportState): ReleaseSupportCoverage => ({ total: 4, attested: 1, exportState })

function harness (initial: { supported?: boolean, coverage?: ReleaseSupportCoverage | null, loading?: boolean } = {}) {
    const supported = ref(initial.supported ?? true)
    const coverage = ref<ReleaseSupportCoverage | null>(initial.coverage ?? null)
    const loading = ref(initial.loading ?? false)
    const load = vi.fn(async () => {})
    const sw = useExportSupportSwitch({
        supported: () => supported.value,
        coverage: () => coverage.value,
        loading: () => loading.value,
        load
    })
    return { supported, coverage, loading, load, sw }
}

describe('the support switch gate (T-18)', () => {
    it('offers the switch only when the server supports it and the release organization is ENABLED', () => {
        expect(supportSwitchOffered(true, 'ENABLED')).toBe(true)
        for (const state of ['DISABLED', 'UNKNOWN', 'PARTIAL'] as SupportExportState[]) {
            expect(supportSwitchOffered(true, state)).toBe(false)
        }
        for (const state of STATES) expect(supportSwitchOffered(false, state)).toBe(false)
    })

    it('reads no coverage as UNKNOWN, and a server answer as itself', () => {
        expect(releaseExportState(null)).toBe('UNKNOWN')
        expect(releaseExportState(undefined)).toBe('UNKNOWN')
        for (const state of STATES) expect(releaseExportState(coverageOf(state))).toBe(state)
    })

    it('follows the release coverage as it loads, fails and changes', () => {
        const { coverage, supported, sw } = harness()
        expect(sw.exportState.value).toBe('UNKNOWN')
        expect(sw.offered.value).toBe(false)
        coverage.value = coverageOf('ENABLED')
        expect(sw.exportState.value).toBe('ENABLED')
        expect(sw.offered.value).toBe(true)
        supported.value = false
        expect(sw.offered.value).toBe(false)
        supported.value = true
        coverage.value = coverageOf('PARTIAL')
        expect(sw.offered.value).toBe(false)
        coverage.value = coverageOf('DISABLED')
        expect(sw.offered.value).toBe(false)
        // A failed request or a release change leaves no coverage: back to unknown, not offered.
        coverage.value = null
        expect(sw.exportState.value).toBe('UNKNOWN')
        expect(sw.offered.value).toBe(false)
    })
})

describe('the hint under the support switch (T-19)', () => {
    it('says the setting is off only for DISABLED, loading or not', () => {
        expect(supportSwitchHint(true, 'DISABLED', false)).toBe(SUPPORT_HINT_DISABLED)
        expect(supportSwitchHint(true, 'DISABLED', true)).toBe(SUPPORT_HINT_DISABLED)
    })

    it('says it is checking only for an unknown state while a load is in flight', () => {
        expect(supportSwitchHint(true, 'UNKNOWN', true)).toBe(SUPPORT_HINT_CHECKING)
        expect(supportSwitchHint(true, 'PARTIAL', true)).toBe(SUPPORT_HINT_CHECKING)
    })

    it('says it could not determine the setting for an unknown state with nothing in flight', () => {
        expect(supportSwitchHint(true, 'UNKNOWN', false)).toBe(SUPPORT_HINT_UNKNOWN)
        expect(supportSwitchHint(true, 'PARTIAL', false)).toBe(SUPPORT_HINT_UNKNOWN)
    })

    it('gives no hint for ENABLED, and none at all when the server does not offer the option', () => {
        expect(supportSwitchHint(true, 'ENABLED', false)).toBeNull()
        expect(supportSwitchHint(true, 'ENABLED', true)).toBeNull()
        for (const state of STATES) {
            expect(supportSwitchHint(false, state, false)).toBeNull()
            expect(supportSwitchHint(false, state, true)).toBeNull()
        }
    })

    it('words each hint as the design does', () => {
        expect(SUPPORT_HINT_DISABLED).toBe('Support disclosure is off for this organization. An organization admin turns it on in Organization Settings → Support disclosure export.')
        expect(SUPPORT_HINT_CHECKING).toBe('Checking whether this organization discloses support metadata…')
        expect(SUPPORT_HINT_UNKNOWN).toBe('ReARM could not determine whether this organization discloses support metadata. The export follows the organization setting.')
    })

    it('moves from checking to the answer as the load lands', () => {
        const { coverage, loading, sw } = harness({ loading: true })
        expect(sw.hint.value).toBe(SUPPORT_HINT_CHECKING)
        coverage.value = coverageOf('DISABLED')
        loading.value = false
        expect(sw.hint.value).toBe(SUPPORT_HINT_DISABLED)
        coverage.value = coverageOf('ENABLED')
        expect(sw.hint.value).toBeNull()
        coverage.value = null
        expect(sw.hint.value).toBe(SUPPORT_HINT_UNKNOWN)
    })
})

describe('opening the dialog loads the export state when nothing has (T-20)', () => {
    it('loads when there is no coverage and no load in flight', () => {
        const { load, sw } = harness()
        sw.loadIfUnknown()
        expect(load).toHaveBeenCalledTimes(1)
    })

    it('does not load again when the coverage is there, whatever it says', () => {
        for (const state of STATES) {
            const { load, sw } = harness({ coverage: coverageOf(state) })
            sw.loadIfUnknown()
            expect(load).not.toHaveBeenCalled()
        }
    })

    it('does not start a second load while one is in flight', () => {
        const { load, sw } = harness({ loading: true })
        sw.loadIfUnknown()
        expect(load).not.toHaveBeenCalled()
    })

    it('tries again on the next opening after a failed load', () => {
        const { load, loading, sw } = harness()
        sw.loadIfUnknown()
        loading.value = true
        sw.loadIfUnknown()
        loading.value = false
        sw.loadIfUnknown()
        expect(load).toHaveBeenCalledTimes(2)
    })

    it('does not wait for the answer', () => {
        const supported = ref(true)
        const load = vi.fn(() => new Promise<void>(() => {}))
        const sw = useExportSupportSwitch({ supported: () => supported.value, coverage: () => null, loading: () => false, load })
        expect(sw.loadIfUnknown()).toBeUndefined()
        expect(load).toHaveBeenCalledTimes(1)
    })
})
