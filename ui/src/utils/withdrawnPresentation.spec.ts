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
const read = (name: string): string => readFileSync(fileURLToPath(new URL(name, import.meta.url)), 'utf8')
/** Comments stripped, as componentViewLicenseWiring.spec.ts does, so a comment cannot satisfy or fail a grep. */
const stripComments = (s: string): string => s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const query = stripComments(read('./sbomComponentsQuery.ts'))

/**
 * FDAW-68 (architecture-2 row 4, REQ-2). The withdrawn rules themselves are behavioural cells
 * now: the model's in supportCell.spec.ts (row 3) and the rendered column's in
 * supportCellRender.spec.ts (row 10). What stays here is that the list is wired to them.
 */
describe('the list is wired to render that', () => {
    // Without attestationState the UI cannot tell a withdrawal from an unassessed component at
    // all, and without the level text an undated attested row reads only "Unknown".
    it('selects attestationState and attestedLevelOfSupportText, and not supportSource', () => {
        const core = query.slice(query.indexOf('COMPONENT_CORE_SELECTION = `'))
        const selection = core.slice(0, core.indexOf('`', core.indexOf('`') + 1))
        expect(selection).toMatch(/\battestationState\b/)
        expect(selection).toMatch(/\battestedLevelOfSupportText\b/)
        expect(selection).not.toMatch(/supportSource/)
    })

    // supportSource is the END_OF_SUPPORT milestone's source, null on every undated record:
    // gating the cell on it is the FDAW-65 defect.
    it('renders the support column through renderSupportCell, and never reads supportSource', () => {
        const code = stripComments(releaseView)
        const start = code.indexOf("key: 'support'")
        const column = code.slice(start, code.indexOf("key: 'artifacts'", start))
        expect(start).toBeGreaterThan(-1)
        expect(column).toMatch(/renderSupportCell\(\s*c\s*,\s*deviceRiskBadge\(\s*c\s*\)\s*\)/)
        expect(column).not.toMatch(/supportSource/)
        expect(stripComments(read('./supportCell.ts'))).not.toMatch(/supportSource/)
        expect(stripComments(read('./supportCellRender.ts'))).not.toMatch(/supportSource/)
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
