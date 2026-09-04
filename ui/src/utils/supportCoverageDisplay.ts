// How the release support-coverage gauge presents itself. Pure, so the rules that matter
// most here can be tested without a browser.

import type { ReleaseSupportCoverage, SupportExportState } from './releaseSupportCoverage'

export type CoverageTone = 'success' | 'warning' | 'error' | 'default'

export interface CoverageDisplay {
    tone: CoverageTone
    /** "34 of 1,240 components disclosed", or why there is no number. */
    headline: string
    /** The export-state sentence. Null only when exports carry everything. */
    exportNote: string | null
    /** True when the operator must not read this as "ready to submit". */
    warn: boolean
}

/**
 * Export-state copy, one sentence each, deliberately specific.
 *
 * A single generic "exports may not carry this" would be easier to write and worse to act
 * on. PARTIAL in particular gets the sharpest wording, because it is the state where a
 * manufacturer is MOST likely to hand over the wrong file: something does carry the
 * disclosure, so the reassuring reading is available, and the file they actually attach to a
 * submission is the one that does not.
 */
const EXPORT_NOTE: Record<Exclude<SupportExportState, 'ENABLED'>, string> = {
    PARTIAL: 'Support disclosure ships on artifact downloads only \u2014 NOT on the release'
        + ' SBOM export, which is the file usually attached to a submission.',
    DISABLED: 'Exports carry no support disclosure.',
    UNKNOWN: 'Export state could not be determined \u2014 do not assume exports carry the'
        + ' disclosure.'
}

/**
 * The gauge's presentation for a release.
 *
 * ANY state other than ENABLED warns, including at 100% attested. That combination is the
 * whole point: full coverage beside an export that carries none of it is a lie by omission,
 * and it is precisely what a default-off export setting produces. A green gauge with a small
 * grey label beside it would be the failure rendered as success.
 *
 * @param coverage null when the server cannot answer -- see loadReleaseSupportCoverage.
 */
export function coverageDisplay (coverage: ReleaseSupportCoverage | null): CoverageDisplay {
    if (!coverage) {
        return {
            tone: 'default',
            headline: 'Support coverage is not available from this server.',
            exportNote: null,
            // Not a warning: nothing is being claimed, correctly or otherwise. An operator
            // seeing this knows they have no number, which is different from having a bad
            // one, and treating it as an alarm would train them to ignore real ones.
            warn: false
        }
    }
    const { total, attested, exportState } = coverage
    const headline = total === 0
        ? 'No components in this release require a support disclosure.'
        : `${attested} of ${total} components have a support disclosure.`
    if (exportState === 'ENABLED') {
        return {
            tone: attested === total ? 'success' : 'warning',
            headline,
            exportNote: null,
            warn: attested !== total
        }
    }
    return {
        // error, not warning, when the disclosure is complete but unshipped: the operator is
        // most likely to act on the gauge precisely when it reads 100%.
        tone: attested === total && total > 0 ? 'error' : 'warning',
        headline,
        exportNote: EXPORT_NOTE[exportState],
        warn: true
    }
}
