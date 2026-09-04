import { describe, expect, it, vi, afterEach } from 'vitest'
import {
    DEVICE_RISK_LABEL,
    SUPPORT_TAG,
    UNRECOGNISED_TAG,
    isDeviceRiskFlagged,
    supportTag
} from './supportStatusTag'

afterEach(() => { vi.restoreAllMocks() })

describe('supportTag', () => {
    it('maps each status the backend can actually return', () => {
        expect(supportTag('SECURITY_ONLY')).toEqual({ type: 'warning', label: 'Security only' })
        expect(supportTag('END_OF_SUPPORT')).toEqual({ type: 'error', label: 'End of support' })
        expect(supportTag('UNKNOWN')).toEqual({ type: 'default', label: 'Unknown' })
    })

    // The bug this module exists to prevent. UNKNOWN is a MEANINGFUL status -- "nobody has
    // assessed this" -- so falling back to it renders a value we do not understand as a
    // confident disclosure the server never made. On a regulatory screen that is worse than
    // an obvious error.
    it('does NOT render an unrecognised status as Unknown', () => {
        const err = vi.spyOn(console, 'error').mockImplementation(() => {})
        const tag = supportTag('SOME_FUTURE_STATUS')
        expect(tag).toEqual(UNRECOGNISED_TAG)
        expect(tag.label).not.toBe(SUPPORT_TAG.UNKNOWN.label)
        expect(err).toHaveBeenCalledOnce()
    })

    // These three are @Deprecated on the backend and never returned by derive(). If one
    // shows up, something is wrong and the operator should see that rather than a plausible
    // green badge.
    it.each(['ACTIVELY_SUPPORTED', 'END_OF_LIFE', 'ABANDONED'])(
        'treats the deprecated, never-derived %s as unrecognised', (status) => {
            vi.spyOn(console, 'error').mockImplementation(() => {})
            expect(supportTag(status)).toEqual(UNRECOGNISED_TAG)
        })

    it('treats null and undefined as unrecognised rather than assessed', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {})
        expect(supportTag(null)).toEqual(UNRECOGNISED_TAG)
        expect(supportTag(undefined)).toEqual(UNRECOGNISED_TAG)
    })

    // Object.prototype.hasOwnProperty, not `in` or a bare index: `'toString' in SUPPORT_TAG`
    // is true, and a bare lookup would return a Function where the caller expects a tag.
    it('does not accept inherited Object properties as statuses', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {})
        expect(supportTag('toString')).toEqual(UNRECOGNISED_TAG)
        expect(supportTag('constructor')).toEqual(UNRECOGNISED_TAG)
    })

    // Pins the set itself. Adding a value here without adding it on the backend, or vice
    // versa, should be a deliberate edit rather than something noticed in production.
    it('knows exactly the three statuses derive() can produce', () => {
        expect(Object.keys(SUPPORT_TAG).sort())
            .toEqual(['END_OF_SUPPORT', 'SECURITY_ONLY', 'UNKNOWN'])
    })
})

describe('isDeviceRiskFlagged', () => {
    it('flags only the verdict the backend can send', () => {
        expect(isDeviceRiskFlagged('EOS_BEFORE_DEVICE')).toBe(true)
    })

    // UNKNOWN must not render as OK -- but it is also not a flag. It means the check could
    // not be made, which the column shows by simply having no marker, same as OK.
    it.each(['OK', 'UNKNOWN', null, undefined, ''])('does not flag %s', (risk) => {
        expect(isDeviceRiskFlagged(risk)).toBe(false)
    })

    // Removed from the backend enum when end-of-life was redefined as end of SALE. A label
    // for a verdict the server cannot send is a dead branch that reads as coverage.
    it('no longer knows EOL_BEFORE_DEVICE', () => {
        expect(isDeviceRiskFlagged('EOL_BEFORE_DEVICE')).toBe(false)
        expect(Object.keys(DEVICE_RISK_LABEL)).toEqual(['EOS_BEFORE_DEVICE'])
    })
})
