import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * The export state is actually RENDERED beside the gauge.
 *
 * Layer 1 deleted the entire `<strong v-if="sbomCoverageDisplay.stateLabel">` block from
 * ReleaseView and the full suite stayed green, 719/719 -- the only user-visible half of this
 * feature shipped unguarded. The display function was thoroughly tested; nothing asserted the
 * template used it.
 *
 * That is the same failure `useReleaseSupportCoverage.ts` records about source-scan guards
 * being "theatre", so this file is deliberately narrow: it asserts the binding exists, is
 * conditioned on the label, and sits inside the coverage alert -- the things whose absence
 * makes the feature invisible while every unit test passes.
 */
const source = readFileSync(
    fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')

describe('the export state is rendered beside the coverage gauge', () => {
    it('binds stateLabel in the template', () => {
        expect(source).toMatch(/\{\{\s*sbomCoverageDisplay\.stateLabel\s*\}\}/)
    })

    // v-if, not v-show and not unconditional: the label is null before data and after an
    // error, and rendering a bare dash there would be a claim about a state nobody read.
    it('renders it only when there is a state to name', () => {
        expect(source).toMatch(/v-if="sbomCoverageDisplay\.stateLabel"/)
    })

    /**
     * Inside the coverage alert, beside the headline.
     *
     * Decision D3 is specifically that the state must appear WITH the coverage number -- a
     * gauge reporting full attestation coverage next to an export carrying nothing is the
     * lie by omission. The same label rendered somewhere else on the page would satisfy every
     * other assertion here and none of the requirement.
     */
    it('sits with the coverage headline, not merely somewhere on the page', () => {
        const headline = source.indexOf('sbomCoverageDisplay.headline')
        const label = source.indexOf('sbomCoverageDisplay.stateLabel')
        const note = source.indexOf('sbomCoverageDisplay.exportNote')
        expect(headline).toBeGreaterThan(-1)
        expect(label).toBeGreaterThan(headline)
        expect(label).toBeLessThan(note)
    })
})
