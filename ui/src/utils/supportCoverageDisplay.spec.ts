import { describe, expect, it, vi } from 'vitest'
import { coverageDisplay } from './supportCoverageDisplay'
import type { SupportExportState } from './releaseSupportCoverage'

const cov = (attested: number, total: number, exportState: SupportExportState) =>
    ({ attested, total, exportState })

describe('coverageDisplay', () => {
    // THE rule. Full coverage beside an export carrying none of it is a lie by omission, and
    // it is exactly what a default-off export setting produces. Asserted for all three
    // non-ENABLED states, including PARTIAL which is unreachable as anything else today, and
    // DISABLED which is unreachable until the export toggle lands -- written now because the
    // combination is the one this surface exists to prevent, not because it is reachable.
    it.each<[SupportExportState]>([['PARTIAL'], ['DISABLED'], ['UNKNOWN']])(
        '100%% attested with export state %s still warns', (exportState) => {
            const d = coverageDisplay(cov(1240, 1240, exportState))
            expect(d.warn).toBe(true)
            expect(d.tone).toBe('error')
            expect(d.exportNote).toBeTruthy()
        })

    it('only a complete disclosure that actually ships reads as success', () => {
        const d = coverageDisplay(cov(1240, 1240, 'ENABLED'))
        expect(d.warn).toBe(false)
        expect(d.tone).toBe('success')
        expect(d.exportNote).toBeNull()
    })

    it('incomplete coverage warns even when exports are on', () => {
        const d = coverageDisplay(cov(34, 1240, 'ENABLED'))
        expect(d.warn).toBe(true)
        expect(d.tone).toBe('warning')
    })

    // Specificity is what makes a permanent warning tolerable rather than noise. PARTIAL
    // must name the file that does NOT carry it, because that is the one being submitted.
    it('names the release SBOM export in the PARTIAL copy', () => {
        const note = coverageDisplay(cov(5, 10, 'PARTIAL')).exportNote as string
        expect(note).toContain('release')
        expect(note).toContain('NOT')
        expect(note).not.toBe(coverageDisplay(cov(5, 10, 'DISABLED')).exportNote)
        expect(note).not.toBe(coverageDisplay(cov(5, 10, 'UNKNOWN')).exportNote)
    })

    /**
     * A value from a newer server. Without a guard the map lookup returns undefined, the
     * template's v-if hides the note, and the operator gets a red alert with nothing
     * explaining it -- alarming and uninformative at once. The sibling supportStatusTag
     * module already guards its enum this way; review found the two handled oppositely.
     */
    it('never leaves an unrecognised export state without a sentence', () => {
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        const d = coverageDisplay(cov(1240, 1240, 'SOMETHING_NEW' as SupportExportState))
        expect(d.warn).toBe(true)
        expect(d.tone).toBe('error')
        expect(d.exportNote, 'a red alert with no explanation is the worst outcome here')
            .toBeTruthy()
        // The UNKNOWN wording is already correct for "we do not know what the state is".
        expect(d.exportNote).toBe(coverageDisplay(cov(1, 2, 'UNKNOWN')).exportNote)
        // TWICE, once from each guard: the note and the label are independently reachable
        // -- exportStateLabel is exported and can be called without the note -- so each
        // reports rather than relying on the other having already done so.
        expect(err).toHaveBeenCalledTimes(2)
        err.mockRestore()
    })

    it('gives each state its own sentence rather than one generic caveat', () => {
        const notes = (['PARTIAL', 'DISABLED', 'UNKNOWN'] as SupportExportState[])
            .map(s => coverageDisplay(cov(1, 2, s)).exportNote)
        expect(new Set(notes).size).toBe(3)
    })

    // Absent is "not checked", never a default. And it is not an alarm: no claim is being
    // made, and treating a missing number as a warning trains operators to ignore real ones.
    /**
     * No coverage is not a coverage state. It must not render as a number, and it must not
     * render as an alarm either -- an operator who learns to ignore this would ignore the
     * DISABLED-export warning that looks similar and means something.
     */
    it('reports an absent gauge as absent, not as a state and not as a warning', () => {
        const d = coverageDisplay(null)
        expect(d.warn).toBe(false)
        expect(d.tone).toBe('default')
        expect(d.headline).toContain('has not loaded yet')
        expect(d.exportNote).toBeNull()
    })

    it('says so when a release has nothing to disclose', () => {
        expect(coverageDisplay(cov(0, 0, 'ENABLED')).headline).toContain('No components')
        // 0 of 0 with exports off is not a success, but it is not an error either -- there is
        // no disclosure to fail to ship.
        expect(coverageDisplay(cov(0, 0, 'DISABLED')).tone).toBe('warning')
    })
})

describe('the export state is stated beside the gauge', () => {
    // Decision D3 calls this a build requirement. With injection defaulting OFF, a coverage
    // figure beside an export carrying nothing is a lie by omission -- so the state is named
    // rather than left to be inferred from the presence of a warning.
    it('names ON when injection is enabled', () => {
        expect(coverageDisplay(cov(2, 2, 'ENABLED')).stateLabel).toBe('export injection ON')
    })

    it('names OFF when injection is disabled', () => {
        expect(coverageDisplay(cov(2, 2, 'DISABLED')).stateLabel).toBe('export injection OFF')
    })

    // THE ONE THAT MATTERS. DISABLED says the organization chose not to export attestations;
    // UNKNOWN says the setting could not be read. Rendering the second as the first states a
    // choice nobody made.
    it('says UNKNOWN could not be read, and never calls it OFF', () => {
        const label = coverageDisplay(cov(2, 2, 'UNKNOWN')).stateLabel as string
        expect(label).toBe('export injection could not be read')
        expect(label).not.toContain('OFF')
    })

    it('names a state for every value the server can return', () => {
        for (const state of ['ENABLED', 'DISABLED', 'UNKNOWN'] as const) {
            expect(coverageDisplay(cov(1, 2, state)).stateLabel, state).toBeTruthy()
        }
    })

    // An unrecognised value from a newer server must not be reported as OFF either.
    it('labels an unknown value unreadable rather than asserting it is off', () => {
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        const label = coverageDisplay(cov(1, 2, 'SOMETHING_NEW' as any)).stateLabel as string
        expect(label).toBe('export injection could not be read')
        expect(label).not.toContain('OFF')
        err.mockRestore()
    })

    // Nothing has been established before the first load, or after a failed one, so naming a
    // state would be a claim built on no data.
    it('names no state before data and after an error', () => {
        expect(coverageDisplay(null).stateLabel).toBeNull()
        expect(coverageDisplay(null, 'boom').stateLabel).toBeNull()
    })
})
