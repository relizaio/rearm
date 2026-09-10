import { describe, it, expect } from 'vitest'
import { applyShipmentWindowToInput, effectiveWindowLabel } from './componentDeviceWindow'

/**
 * The shipment override's rules, tested as BEHAVIOUR.
 *
 * They lived inline in DistributionOfOrg.vue and were covered only by a spec that
 * regex-matched the .vue source, which can tell you the text moved but not whether the rule is
 * right. That matters most for the clear flag: an empty window object is NOT a retraction on
 * the server, so sending one leaves the old override silently in force while the form shows it
 * gone -- on a section 524B commitment, a wrong answer that looks like a right one.
 */
describe('applyShipmentWindowToInput', () => {
    const w = (eos: string | null, eol: string | null) => ({ eos, eol })

    it('sends nothing at all when neither date changed', () => {
        expect(applyShipmentWindowToInput({ uuid: 's' }, w('2031-01-31', null), w('2031-01-31', null)))
            .toEqual({ uuid: 's' })
    })

    it('sends the window when a date is set', () => {
        expect(applyShipmentWindowToInput({}, w('2031-01-31', '2033-06-30'), w(null, null)))
            .toEqual({ deviceSupportWindow: { eos: '2031-01-31', eol: '2033-06-30' } })
    })

    it('sends the CLEAR FLAG, never an empty window, when both are blanked', () => {
        const out = applyShipmentWindowToInput({}, w(null, null), w('2031-01-31', '2033-06-30'))
        expect(out).toEqual({ clearDeviceSupportWindow: true })
        expect(out.deviceSupportWindow).toBeUndefined()
    })

    it('sends nothing when both are blank and nothing was declared before', () => {
        // Not a retraction: there is nothing to retract, and sending the flag would stamp a
        // write -- and fresh provenance -- on a claim nobody touched.
        expect(applyShipmentWindowToInput({}, w(null, null), w(null, null))).toEqual({})
    })

    it('treats the empty string as blank, the way a cleared picker reports it', () => {
        expect(applyShipmentWindowToInput({}, w('', ''), w('2031-01-31', null)))
            .toEqual({ clearDeviceSupportWindow: true })
    })
})

describe('effectiveWindowLabel', () => {
    it('names the batch when the shipment overrides', () => {
        expect(effectiveWindowLabel({ eos: '2030-01-01', eol: null, source: 'SHIPMENT' }))
            .toContain('from this batch')
    })

    it('names the product component when the window is inherited', () => {
        expect(effectiveWindowLabel({ eos: '2030-01-01', eol: null, source: 'COMPONENT' }))
            .toContain('from the product component')
    })

    it('never says "override": that is our vocabulary, not the reader\'s', () => {
        expect(effectiveWindowLabel({ eos: '2030-01-01', eol: '2031-01-01', source: 'SHIPMENT' }))
            .not.toMatch(/override/i)
    })

    it('names each missing date rather than dropping it', () => {
        expect(effectiveWindowLabel({ eos: '2030-01-01', eol: null, source: 'COMPONENT' }))
            .toContain('EOL not declared')
    })

    it('returns empty for no window, and for a null shipment', () => {
        expect(effectiveWindowLabel({ eos: null, eol: null, source: 'COMPONENT' })).toBe('')
        expect(effectiveWindowLabel(null)).toBe('')
        expect(effectiveWindowLabel(undefined)).toBe('')
    })
})
