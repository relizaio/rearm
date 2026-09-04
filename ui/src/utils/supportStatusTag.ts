// Display mapping for the FDA-Readiness-1 per-component support disclosure.
//
// Lives in utils/ rather than inside ReleaseView.vue so it can be tested. The fail-loud
// behaviour below is the whole point of this module, and behaviour that matters is
// behaviour that gets a spec.

export type SupportTagType = 'default' | 'success' | 'warning' | 'error'

/**
 * The support statuses the backend can actually return.
 *
 * THREE, not the six members of the Java enum. ACTIVELY_SUPPORTED, END_OF_LIFE and
 * ABANDONED are @Deprecated there and never produced by SupportStatus.derive(): the first
 * and last are ATTESTED levels rather than derived states (see LevelOfSupport), and
 * end-of-life was redefined as end of SALE, which is not a support state at all. Listing
 * them would claim this UI can render values the server cannot send.
 */
export type LiveSupportStatus = 'SECURITY_ONLY' | 'END_OF_SUPPORT' | 'UNKNOWN'

export const SUPPORT_TAG: Record<LiveSupportStatus, { type: SupportTagType, label: string }> = {
    // Derived, not guaranteed: the dates say guaranteed support has lapsed but full support
    // has not. An expectation about what the window entails, NOT a claim that security fixes
    // will be provided.
    SECURITY_ONLY: { type: 'warning', label: 'Security only' },
    END_OF_SUPPORT: { type: 'error', label: 'End of support' },
    UNKNOWN: { type: 'default', label: 'Unknown' }
}

export const UNRECOGNISED_TAG: { type: SupportTagType, label: string } =
    { type: 'error', label: 'Unrecognised status' }

/**
 * The tag for a support status, or a loud marker when the server sent something this build
 * does not know.
 *
 * The previous version did `SUPPORT_TAG[status] || SUPPORT_TAG.UNKNOWN`, which is wrong in a
 * way that matters here: UNKNOWN is itself a MEANINGFUL status meaning "not assessed".
 * Falling back to it renders an unrecognised value as a confident "nobody has assessed this"
 * -- the UI asserting a disclosure fact the server never stated, on a screen whose entire
 * purpose is regulatory disclosure. The two conditions need different pixels.
 *
 * Loud, not fatal. Throwing would take the whole component table down over one row, and an
 * operator with 1,200 components needs the other 1,199 rendered. So: a visually distinct tag
 * the user cannot mistake for a status, plus a console error for whoever fixes the drift.
 */
export function supportTag (status: unknown): { type: SupportTagType, label: string } {
    if (typeof status === 'string'
            && Object.prototype.hasOwnProperty.call(SUPPORT_TAG, status)) {
        return SUPPORT_TAG[status as LiveSupportStatus]
    }
    console.error('unrecognised SupportStatus from the server:', status,
        `- this UI build knows only ${Object.keys(SUPPORT_TAG).join(', ')}.`
        + ' Rendering it as "Unknown" would assert it was never assessed.')
    return UNRECOGNISED_TAG
}

/**
 * DeviceSupportRisk labels. A SEPARATE axis from the support status: that answers "is this
 * supported TODAY", this answers "does its support end before the DEVICE's own horizon". A
 * component can be supported today and still fail the device check -- support to 2030 inside
 * a device fielded to 2031 -- which is exactly the section-524B case a manufacturer must
 * disclose, and which a status-only column shows in reassuring green.
 *
 * Only the flagged value appears. OK needs no marker, and UNKNOWN must never render as OK:
 * it means "not assessed" (no device horizon, or no dates on the component).
 *
 * EOL_BEFORE_DEVICE was removed from the backend enum with the end-of-life-as-support-state
 * reading (EOL means end of SALE). Deliberately absent -- a label for a verdict the server
 * cannot send is a dead branch that reads as coverage.
 */
export type FlaggedDeviceRisk = 'EOS_BEFORE_DEVICE'

export const DEVICE_RISK_LABEL: Record<FlaggedDeviceRisk, string> = {
    EOS_BEFORE_DEVICE: 'EOS before device'
}

/**
 * Keyed on the SAME union as the label map, so the compiler enforces what a comment used to
 * assert. deviceRiskBadge reads both, and a verdict present in LABEL but missing from DETAIL
 * would render an empty tooltip body under a populated tag.
 */
export const DEVICE_RISK_DETAIL: Record<FlaggedDeviceRisk, string> = {
    EOS_BEFORE_DEVICE: 'This component stops receiving support before this device\'s declared'
        + ' support window ends, so it goes unsupported while the device is still fielded.'
}

/**
 * Derived from the label map rather than re-listing its keys: the map IS the set of flagged
 * verdicts, and a hand-maintained second copy fails silently -- a second flagged value would
 * render a tag while a summary counted zero and a filter hid the row.
 */
export function isDeviceRiskFlagged (risk: unknown): risk is FlaggedDeviceRisk {
    // hasOwnProperty, not `in`. `'toString' in DEVICE_RISK_LABEL` is true, and the caller
    // renders DEVICE_RISK_LABEL[risk] as a tag label -- so `in` would put
    // Object.prototype.toString, a Function, on screen. supportTag above already guards this
    // way; guarding one of the two is how the pair drifts. Not reachable from a conformant
    // server, since deviceSupportRisk is a GraphQL enum, which is why this is consistency and
    // defence rather than a live defect.
    return typeof risk === 'string'
        && Object.prototype.hasOwnProperty.call(DEVICE_RISK_LABEL, risk)
}
