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
        expect(err).toHaveBeenCalledOnce()
        err.mockRestore()
    })

    it('gives each state its own sentence rather than one generic caveat', () => {
        const notes = (['PARTIAL', 'DISABLED', 'UNKNOWN'] as SupportExportState[])
            .map(s => coverageDisplay(cov(1, 2, s)).exportNote)
        expect(new Set(notes).size).toBe(3)
    })

    // Absent is "not checked", never a default. And it is not an alarm: no claim is being
    // made, and treating a missing number as a warning trains operators to ignore real ones.
    it('reports an unavailable gauge as unavailable, not as a state', () => {
        const d = coverageDisplay(null)
        expect(d.warn).toBe(false)
        expect(d.tone).toBe('default')
        expect(d.headline).toContain('not available')
        expect(d.exportNote).toBeNull()
    })

    it('says so when a release has nothing to disclose', () => {
        expect(coverageDisplay(cov(0, 0, 'ENABLED')).headline).toContain('No components')
        // 0 of 0 with exports off is not a success, but it is not an error either -- there is
        // no disclosure to fail to ship.
        expect(coverageDisplay(cov(0, 0, 'DISABLED')).tone).toBe('warning')
    })
})
