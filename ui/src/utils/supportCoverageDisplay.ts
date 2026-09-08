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
    /**
     * The state in plain words, ALWAYS present -- "export injection ON" / "OFF" / "could not
     * be read". Required beside the gauge by decision D3: the export state has to be stated,
     * not inferred from whether a warning happens to be showing.
     *
     * Null only in the two pre-data branches (error, not-yet-loaded), where there is no state
     * to name yet.
     */
    stateLabel: string | null
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
/**
 * The state named in plain words beside the gauge, for EVERY state including ENABLED.
 *
 * Decision D3 calls this a build requirement, not a suggestion: with injection defaulting
 * OFF, a gauge reporting full attestation coverage beside an export carrying nothing is a lie
 * by omission, and the fix is to say the export state out loud rather than leave it to be
 * inferred from the absence of a warning.
 *
 * UNKNOWN IS NOT "OFF". DISABLED says the organization chose not to export attestations;
 * UNKNOWN says the setting could not be read. Rendering the second as the first states a
 * choice nobody made -- so it reads "could not be read", and the operator is told to find out
 * rather than reassured.
 */
export const EXPORT_STATE_LABEL: Record<SupportExportState, string> = {
    ENABLED: 'export injection ON',
    DISABLED: 'export injection OFF',
    PARTIAL: 'export injection PARTIAL',
    UNKNOWN: 'export injection could not be read'
}

/**
 * The label for a state, falling back to the unreadable wording for a value this build does
 * not know.
 *
 * Same reasoning as exportNoteFor: a newer server can introduce a state, and the honest thing
 * to say about a value we cannot interpret is that we could not read it -- never "OFF", which
 * would be a claim about the organization's configuration.
 */
export function exportStateLabel (exportState: SupportExportState): string {
    if (Object.prototype.hasOwnProperty.call(EXPORT_STATE_LABEL, exportState)) {
        return EXPORT_STATE_LABEL[exportState]
    }
    console.error('unrecognised SupportExportState from the server:', exportState,
        `- this UI build knows only ${Object.keys(EXPORT_STATE_LABEL).join(', ')}.`
        + ' Labelling it unreadable rather than asserting the export is off.')
    return EXPORT_STATE_LABEL.UNKNOWN
}

const EXPORT_NOTE: Record<Exclude<SupportExportState, 'ENABLED'>, string> = {
    // RETIRED: the server no longer returns it now that one setting gates every egress. Kept
    // because a UI build can meet an older server, and an unrecognised state falls back to the
    // UNKNOWN copy -- which would be less accurate than this sentence for a server that
    // genuinely is in the old split state.
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
 * @param coverage null ONLY before the first load resolves. The loader throws rather than
 *                 returning null for a malformed answer, so this branch can no longer be
 *                 reached by a failure -- which is what lets it read as benign.
 * @param error    set when the request FAILED, which is a different thing and must stay
 *                 separate: folding them together would report "no number available" for a
 *                 request that merely 502'd, hiding that a retry works.
 */
export function coverageDisplay (
    coverage: ReleaseSupportCoverage | null,
    error?: string | null
): CoverageDisplay {
    if (error) {
        return {
            tone: 'warning',
            headline: 'Could not load support coverage. Retry, or reload the release.',
            exportNote: null,
            // No state to name: the request failed, so we know nothing about the setting.
            // Naming one here would be a claim built on a failed request.
            stateLabel: null,
            // A warning: something IS wrong, and unlike an unanswerable server it is
            // actionable.
            warn: true
        }
    }
    if (!coverage) {
        return {
            tone: 'default',
            headline: 'Support coverage has not loaded yet.',
            exportNote: null,
            // No state to name yet -- naming one here would assert something about the org's
            // configuration on the strength of a request that has not returned.
            stateLabel: null,
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
            stateLabel: exportStateLabel(exportState),
            warn: attested !== total
        }
    }
    return {
        // error, not warning, when the disclosure is complete but unshipped: the operator is
        // most likely to act on the gauge precisely when it reads 100%.
        tone: attested === total && total > 0 ? 'error' : 'warning',
        headline,
        exportNote: exportNoteFor(exportState),
        stateLabel: exportStateLabel(exportState),
        warn: true
    }
}

/**
 * The sentence for a non-ENABLED export state, or the UNKNOWN copy when this build does not
 * recognise the value.
 *
 * A bare `EXPORT_NOTE[state]` returns undefined for a state a newer server introduces, and
 * the template hides an absent note -- leaving a RED ALERT WITH NO SENTENCE EXPLAINING IT.
 * That is the worst of both: alarming and uninformative, on the screen whose whole job is to
 * say what is and is not being disclosed. The Pro schema explicitly anticipates this field
 * growing a third state, so it is a matter of when.
 *
 * Falls back to the UNKNOWN copy rather than inventing one, because that copy is already
 * exactly right for the situation: we do not know what the export state is, so do not assume
 * exports carry the disclosure. Logged so whoever added the value finds out.
 *
 * This is the same guard supportStatusTag.ts applies to SupportStatus, and it is here
 * because review pointed out the two were handled oppositely in the same feature.
 */
function exportNoteFor (exportState: Exclude<SupportExportState, 'ENABLED'>): string {
    if (Object.prototype.hasOwnProperty.call(EXPORT_NOTE, exportState)) {
        return EXPORT_NOTE[exportState]
    }
    console.error('unrecognised SupportExportState from the server:', exportState,
        `- this UI build knows only ${Object.keys(EXPORT_NOTE).join(', ')}.`
        + ' Falling back to the UNKNOWN wording rather than showing an unexplained alert.')
    return EXPORT_NOTE.UNKNOWN
}
