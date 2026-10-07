import { describe, it, expect, vi } from 'vitest'

// commonFunctions pulls in the Apollo client and Keycloak; nothing of it is used here.
vi.mock('./commonFunctions', () => ({ default: {} }))

import { ref } from 'vue'
import { buildReleaseScoreVariables, type ReleaseScoreForm } from './sbomScore'
import { useReleaseSbomScore } from './useReleaseSbomScore'
import { FileSwitchUnsupportedError, exportWithMetadataFallback, fileSwitchAvailable } from './exportMetadataFallback'

/**
 * "Leave out file components" in the Export Release BOM dialog (SCORE-11): what the score
 * query sends for it, the score's options-changed line, and the export's drift handling, which
 * never retries a switched export as the document that would bring the files back.
 */
const form: ReleaseScoreForm = {
    release: 'r-1',
    tldOnly: false,
    ignoreDev: false,
    selectedBomStructureType: 'FLAT',
    selectedRebomType: '',
    computedExcludeCoverageTypes: [],
    includeSupportMetadata: false,
    includeInternalMetadata: false,
    orgSupportInjectionEnabled: false,
    exportMetadataArgsUnsupported: false,
    excludeFileComponents: false,
}

const drift = (e: any) => e?.drift === true
const ok = (v: any) => () => Promise.resolve(v)
const boom = (e: any) => () => Promise.reject(e)

describe('the score variables of the file switch (T-8)', () => {
    it('sends true when the switch is on', () => {
        const v = buildReleaseScoreVariables({ ...form, excludeFileComponents: true })
        expect(v.excludeFileComponents).toBe(true)
        expect(v.includeSupportMetadata).toBeNull()
    })

    it('sends null, never false, when the switch is off, beside the support flag the same way', () => {
        const v = buildReleaseScoreVariables(form)
        expect('excludeFileComponents' in v).toBe(true)
        expect(v.excludeFileComponents).toBeNull()
        expect(v.includeSupportMetadata).toBeNull()
    })

    it('says the options changed when only the switch moved since the last score', () => {
        const current = ref<ReleaseScoreForm>({ ...form })
        const score = useReleaseSbomScore({
            variables: () => buildReleaseScoreVariables(current.value),
            formDisabled: () => false,
            sbomForm: () => true
        })
        score.score()
        expect(score.optionsChanged.value).toBe(false)
        current.value = { ...current.value, excludeFileComponents: true }
        expect(score.optionsChanged.value).toBe(true)
        score.score()
        expect(score.scoredVariables().excludeFileComponents).toBe(true)
        expect(score.optionsChanged.value).toBe(false)
        current.value = { ...current.value, excludeFileComponents: false }
        expect(score.optionsChanged.value).toBe(true)
    })
})

describe('exporting with the file switch on a server that may not take it (T-9)', () => {
    it('with the switch off, a drift rejection is still retried as the core document once', async () => {
        const r = await exportWithMetadataFallback({
            runFull: boom({ drift: true }), runCore: ok('CORE'),
            flagsUnsupported: false, isDriftError: drift, excludeFileComponents: false })
        expect(r).toEqual({ data: 'CORE', usedCore: true, justDiscovered: true })
    })

    it('with the switch on, a drift rejection is surfaced and never retried as the core document', async () => {
        const rejection = { drift: true, message: 'Unknown argument excludeFileComponents' }
        const core = vi.fn()
        const err = await exportWithMetadataFallback({
            runFull: boom(rejection), runCore: core,
            flagsUnsupported: false, isDriftError: drift, excludeFileComponents: true }).catch(e => e)
        expect(err).toBeInstanceOf(FileSwitchUnsupportedError)
        expect(err.rejection).toBe(rejection)
        expect(err.message).toMatch(/cannot leave file components out/)
        expect(core).not.toHaveBeenCalled()
    })

    it('with the switch on, a refusal that is not drift propagates untouched', async () => {
        const refusal = { drift: false, message: 'Not authorized' }
        await expect(exportWithMetadataFallback({
            runFull: boom(refusal), runCore: vi.fn(),
            flagsUnsupported: false, isDriftError: drift, excludeFileComponents: true })).rejects.toBe(refusal)
    })

    it('with the switch on and the server already latched flagless, nothing is sent', async () => {
        const full = vi.fn()
        const core = vi.fn()
        await expect(exportWithMetadataFallback({
            runFull: full, runCore: core,
            flagsUnsupported: true, isDriftError: drift, excludeFileComponents: true }))
            .rejects.toBeInstanceOf(FileSwitchUnsupportedError)
        expect(full).not.toHaveBeenCalled()
        expect(core).not.toHaveBeenCalled()
    })

    it('with the switch on and a server that takes it, the switched document is the export', async () => {
        const r = await exportWithMetadataFallback({
            runFull: ok('SWITCHED'), runCore: vi.fn(),
            flagsUnsupported: false, isDriftError: drift, excludeFileComponents: true })
        expect(r).toEqual({ data: 'SWITCHED', usedCore: false, justDiscovered: false })
    })

    it('reads the switch as unavailable once either latch is set', () => {
        expect(fileSwitchAvailable(false, false)).toBe(true)
        expect(fileSwitchAvailable(false, true)).toBe(false)
        expect(fileSwitchAvailable(true, false)).toBe(false)
    })
})
