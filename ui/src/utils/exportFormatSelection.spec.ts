import { describe, it, expect } from 'vitest'
import {
    BOM_MEDIA_TYPES, supportExportFormats, mediaTypeForBomType
} from './exportFormatSelection'

/**
 * RUN, not scanned. The wiring specs next door can only read ReleaseView.vue's source text,
 * and this change proved what that is worth: all 66 of their assertions were green while the
 * component threw during setup and rendered nothing, because the watch that drives this rule
 * was declared above the ref it watches.
 */
describe('which support documents a release can produce', () => {
    it('offers both addendum encodings on a component release', () => {
        const v = supportExportFormats(false).map(f => f.value)
        expect(v).toEqual(['FDA_ADDENDUM', 'FDA_ADDENDUM_PDF'])
    })

    // The statement is about a DEVICE. Offering it on a component release is an invitation to
    // press a button that refuses -- the walkthrough finding that put the PRODUCT rule here.
    it('adds the device statement only on a product release', () => {
        const v = supportExportFormats(true).map(f => f.value)
        expect(v).toContain('DEVICE_STATEMENT')
        expect(supportExportFormats(false).map(f => f.value)).not.toContain('DEVICE_STATEMENT')
    })

    // An INVERTED gate is the mutation a source scan cannot see: the identifiers are all still
    // present and in the same order. This one fails.
    it('is never empty, so the Support type is always offered today', () => {
        expect(supportExportFormats(true).length).toBeGreaterThan(0)
        expect(supportExportFormats(false).length).toBeGreaterThan(0)
    })

    it('labels the addendum without the agency name', () => {
        for (const f of supportExportFormats(true)) {
            expect(f.label).not.toMatch(/FDA/)
        }
        expect(supportExportFormats(true).map(f => f.label)).toEqual([
            'Support addendum (CSV)', 'Support addendum (PDF)', 'Device support statement (PDF)'
        ])
    })
})

describe('the media type reset when the bom type changes', () => {
    // THE DEFECT THIS EXISTS FOR: Support -> SBOM leaving FDA_ADDENDUM selected, so the form
    // shows an unselected CycloneDX radio group and Export builds the addendum.
    it('falls back to JSON when leaving Support with a support document selected', () => {
        expect(mediaTypeForBomType('SBOM', 'FDA_ADDENDUM', true)).toBe('JSON')
        expect(mediaTypeForBomType('SBOM', 'FDA_ADDENDUM_PDF', true)).toBe('JSON')
        expect(mediaTypeForBomType('SBOM', 'DEVICE_STATEMENT', true)).toBe('JSON')
    })

    it('falls back to the first support document when entering Support from the BOM', () => {
        for (const m of BOM_MEDIA_TYPES) {
            expect(mediaTypeForBomType('SUPPORT', m, true)).toBe('FDA_ADDENDUM')
        }
    })

    // A component release cannot hold DEVICE_STATEMENT even coming back to Support: the radio
    // is not rendered, and a selection the form does not show is a pair Export would still send.
    it('drops a device statement carried into Support on a component release', () => {
        expect(mediaTypeForBomType('SUPPORT', 'DEVICE_STATEMENT', false)).toBe('FDA_ADDENDUM')
        expect(mediaTypeForBomType('SUPPORT', 'DEVICE_STATEMENT', true)).toBe('DEVICE_STATEMENT')
    })

    it('keeps a selection that is already valid for the type', () => {
        expect(mediaTypeForBomType('SBOM', 'CSV', true)).toBe('CSV')
        expect(mediaTypeForBomType('SBOM', 'EXCEL', true)).toBe('EXCEL')
        expect(mediaTypeForBomType('SUPPORT', 'FDA_ADDENDUM_PDF', true)).toBe('FDA_ADDENDUM_PDF')
    })

    // Every non-SUPPORT type shares the BOM encodings, so none of them may be left holding a
    // support document either.
    it('normalises for every other bom type too', () => {
        for (const t of ['OBOM', 'VDR', 'VEX', 'CLE']) {
            expect(mediaTypeForBomType(t, 'FDA_ADDENDUM', true)).toBe('JSON')
            expect(mediaTypeForBomType(t, 'CSV', true)).toBe('CSV')
        }
    })
})
