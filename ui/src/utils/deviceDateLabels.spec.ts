import { describe, it, expect } from 'vitest'
import { DEVICE_EOS_LABEL, DEVICE_EOL_LABEL, EOS_FIELD_LABEL, EOL_FIELD_LABEL } from './deviceDateLabels'

/**
 * FDAW-3, B11: the decided copy, pinned exactly. The two header strings are also the backend's
 * FdaDocumentStrings.DEVICE_EOS_LABEL / DEVICE_EOL_LABEL, which reads the addendum CSV back by
 * label, so a one-character drift on either side breaks the document round trip.
 */
describe('the device date labels', () => {
    it('name the document header rows by their IMDRF N70 meaning', () => {
        expect(DEVICE_EOS_LABEL).toBe('Device end of support (last security update)')
        expect(DEVICE_EOL_LABEL).toBe('Device end of life (last sale date)')
    })

    it('name the date pickers the same way', () => {
        expect(EOS_FIELD_LABEL).toBe('End of support (last security update)')
        expect(EOL_FIELD_LABEL).toBe('End of life (last sale date)')
    })

    it('never use the old "end of sale" or "(EOL)" wording', () => {
        for (const l of [DEVICE_EOS_LABEL, DEVICE_EOL_LABEL, EOS_FIELD_LABEL, EOL_FIELD_LABEL]) {
            expect(l).not.toMatch(/end of sale/i)
            expect(l).not.toMatch(/\((EOL|EOS)\)/)
        }
    })
})
