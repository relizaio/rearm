import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { supportTag, WITHDRAWN_TAG, isWithdrawnAttestation } from './supportStatusTag'

/**
 * A withdrawn attestation must read as WITHDRAWN, not as a live status and not as
 * "never assessed".
 *
 * The operator walkthrough found the list rendering a live "End of support" chip and its EOS
 * date for a claim the manufacturer had taken back, while the Not-disclosed filter on the same
 * screen correctly counted that component as unattested (board t20260909-061338-23148). The
 * backend half is fixed in SbomComponentSupportStatusTest; this covers the presentation.
 */
describe('withdrawn attestations are presented as withdrawn', () => {
    it('is a distinct tag from UNKNOWN', () => {
        expect(WITHDRAWN_TAG.label).toBe('Withdrawn')
        // "Unknown" means nobody assessed it. "Withdrawn" means somebody did and retracted it.
        // Rendering the second as the first erases a diligence record from an auditor's screen.
        expect(WITHDRAWN_TAG.label).not.toBe(supportTag('UNKNOWN').label)
    })

    it('recognises only the WITHDRAWN state', () => {
        expect(isWithdrawnAttestation('WITHDRAWN')).toBe(true)
        expect(isWithdrawnAttestation('ATTESTED')).toBe(false)
        expect(isWithdrawnAttestation(null)).toBe(false)
        expect(isWithdrawnAttestation(undefined)).toBe(false)
    })
})

const releaseView = readFileSync(
    fileURLToPath(new URL('../components/ReleaseView.vue', import.meta.url)), 'utf8')
const query = readFileSync(
    fileURLToPath(new URL('./sbomComponentsQuery.ts', import.meta.url)), 'utf8')

describe('the list is wired to render that', () => {
    // Without this field the UI cannot tell a withdrawal from an unassessed component at all.
    it('asks the server for attestationState', () => {
        expect(query).toMatch(/attestationState/)
    })

    it('chooses the withdrawn tag over the derived status', () => {
        expect(releaseView).toMatch(
            /const withdrawn = isWithdrawnAttestation\(c\.attestationState\)/)
        expect(releaseView).toMatch(/withdrawn \? WITHDRAWN_TAG : supportTag\(c\.supportStatus\)/)
    })

    /**
     * The dates stay on the row, so the suffix has to be suppressed WITH the status. Printing
     * "EOS 2025-12-31" beside "Withdrawn" would put the retracted date back on screen as
     * though it still stood -- which is most of what the original defect looked like.
     */
    it('suppresses the EOS suffix on a withdrawn row', () => {
        expect(releaseView).toMatch(/if \(c\.endOfSupportDate && !withdrawn\)/)
    })
})

describe('the attest dialog says why Save is disabled', () => {
    // Two gates fired at once in the walkthrough -- the mandatory re-assert reason and the
    // unacknowledged basis prompt -- and nothing at the button named either.
    it('renders errors() in the modal footer, beside the button they gate', () => {
        const footer = releaseView.slice(releaseView.indexOf('WHY SAVE IS DISABLED'))
        expect(footer).toMatch(/v-for="e in attestForm\.errors\(\)"/)
        expect(footer.indexOf('attestForm.errors()'))
            .toBeLessThan(footer.indexOf('@click="saveAttestation"'))
    })

    it('hides them while the form is still loading or saving', () => {
        expect(releaseView).toMatch(
            /v-if="!attestLoading && !attestSaving && attestForm\.errors\(\)\.length"/)
    })
})
