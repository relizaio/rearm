// SBOM readiness score (SCORE-6): everything about the score that can be tested without a
// browser. The backend (releaseSbomScore / artifactSbomScore) returns the scoring engine's
// report verbatim as a JSON string; this module parses it, orders it for display, builds the
// release query's variables and turns a failed call into the one line the panel shows.
// SbomScoreReport.vue renders a parsed report, SbomScorePanel.vue runs the call.

import { graphqlErrorsOf, httpStatusOf, isSchemaDriftError } from './graphqlDriftFallback'
import commonFunctions from './commonFunctions'

// Types mirror rearm-cli internal/bomscore/report.go and check.go, names as in the JSON.
export type SbomScoreStatus = 'PASS' | 'FAIL' | 'NOT_ASSESSED' | 'ERROR'
export type SbomScoreVerdict = 'READY' | 'NOT_READY' | 'UNKNOWN'
export type SbomScoreLevel = 'REQUIRED' | 'INFO'

export interface SbomScoreCheck {
    id: string
    title: string
    level: SbomScoreLevel
    scope: string
    status: SbomScoreStatus
    passed: number
    total: number
    failing: string[]
    failingTruncated: boolean
    ref: string
    remedy: string
    note: string
}

export interface SbomScoreProfile {
    key: string
    title: string
    source: { title: string, version: string, date: string, url: string }
    verdict: SbomScoreVerdict
    // *int in Go: null when the engine could not compute one.
    score: number | null
    summary: { pass: number, fail: number, notAssessed: number, errors: number }
    checks: SbomScoreCheck[]
}

export interface SbomScoreReport {
    reportVersion: 1
    engine: { name: string, version: string }
    // componentsSkipped and options arrive with SCORE-10; optional so older reports parse.
    input: { format: string, specVersion: string, serialization: string, components: number, componentsSkipped?: number, sha256: string }
    options?: { skipFiles?: boolean }
    profiles: SbomScoreProfile[]
    structure: { checks: SbomScoreCheck[] }
    errors: { check: string, message: string }[]
}

// Both profiles on every call: one call scores both, the view separates them (design D-7).
export const SBOM_SCORE_PROFILES = ['cisa-2026', 'fda']

// The client gives up after this; the backend and nginx stop at 180 s (design D-9).
export const SBOM_SCORE_ABORT_MS = 185_000

export type SbomScoreParseErrorKind = 'NOT_JSON' | 'UNSUPPORTED_VERSION' | 'MALFORMED'

export class SbomScoreParseError extends Error {
    readonly kind: SbomScoreParseErrorKind
    constructor (kind: SbomScoreParseErrorKind, message: string) {
        super(message)
        this.name = 'SbomScoreParseError'
        this.kind = kind
    }
}

/**
 * The report in `raw`. Strict on the version (a shape this UI does not know is refused rather
 * than half-rendered), tolerant of extra fields (they are kept and ignored), so the UI reads
 * reports from before and after SCORE-10 alike.
 */
export function parseSbomScoreReport (raw: string): SbomScoreReport {
    let parsed: any
    try {
        parsed = JSON.parse(raw)
    } catch {
        throw new SbomScoreParseError('NOT_JSON', 'The server returned a score report that is not JSON.')
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new SbomScoreParseError('NOT_JSON', 'The server returned a score report that is not a JSON object.')
    }
    if (parsed.reportVersion !== 1) {
        throw new SbomScoreParseError('UNSUPPORTED_VERSION',
            `unsupported report version ${parsed.reportVersion}; update ReARM UI`)
    }
    if (!Array.isArray(parsed.profiles) || parsed.input === null || typeof parsed.input !== 'object') {
        throw new SbomScoreParseError('MALFORMED', 'The score report has no profiles or no input section.')
    }
    const report = parsed as SbomScoreReport
    report.structure = { checks: Array.isArray(parsed.structure?.checks) ? parsed.structure.checks : [] }
    report.errors = Array.isArray(parsed.errors) ? parsed.errors : []
    for (const p of report.profiles) {
        if (!Array.isArray(p.checks)) p.checks = []
    }
    return report
}

const STATUS_ORDER: Record<SbomScoreStatus, number> = { FAIL: 0, ERROR: 1, NOT_ASSESSED: 2, PASS: 3 }
const LEVEL_ORDER: Record<SbomScoreLevel, number> = { REQUIRED: 0, INFO: 1 }

/** Failed first: FAIL, ERROR, NOT_ASSESSED, PASS; REQUIRED before INFO; else the report's order. */
export function orderChecks (checks: SbomScoreCheck[]): SbomScoreCheck[] {
    // A status or level this UI does not know sorts last.
    const rank = (m: Record<string, number>, k: string) => m[k] ?? Object.keys(m).length
    return checks
        .map((check, index) => ({ check, index }))
        .sort((a, b) =>
            rank(STATUS_ORDER, a.check.status) - rank(STATUS_ORDER, b.check.status)
            || rank(LEVEL_ORDER, a.check.level) - rank(LEVEL_ORDER, b.check.level)
            || a.index - b.index)
        .map(e => e.check)
}

/** "9 passed, 1 failed, 1 not assessed": groups at zero are left out, except passed. */
export function summaryLine (profile: SbomScoreProfile): string {
    const s = profile.summary
    const parts = [`${s.pass} passed`]
    if (s.fail) parts.push(`${s.fail} failed`)
    if (s.notAssessed) parts.push(`${s.notAssessed} not assessed`)
    if (s.errors) parts.push(`${s.errors} errors`)
    return parts.join(', ')
}

/** The Export Release BOM form values the Export button passes, plus what decides the metadata flags. */
export interface ReleaseScoreForm {
    release: string
    tldOnly: boolean
    ignoreDev: boolean
    selectedBomStructureType: string
    selectedRebomType: string
    computedExcludeCoverageTypes: string[]
    // This server rejected the metadata arguments on an export: the score omits them too.
    exportMetadataArgsUnsupported: boolean
    // "Leave out file components" (SCORE-11), as the export would send it: false when the switch
    // is off or not available on this server.
    excludeFileComponents: boolean
}

/**
 * releaseSbomScore's variables for the export form. The content options are mapped exactly as
 * exportReleaseSbom maps them for releaseSbomExport (backend rule: same names, same defaults).
 * The two metadata flags are always omitted (null): the score has no metadata switches and
 * follows the organization setting, support facts included when the support disclosure is
 * ENABLED and ReARM's markers kept (SCORE-12 ADR-3). No mediaType: the score is always of the
 * JSON export.
 */
export function buildReleaseScoreVariables (form: ReleaseScoreForm): Record<string, any> {
    const variables: Record<string, any> = {
        release: form.release,
        tldOnly: form.tldOnly,
        ignoreDev: form.ignoreDev,
        structure: form.selectedBomStructureType,
        belongsTo: form.selectedRebomType ? form.selectedRebomType : null,
        excludeCoverageTypes: form.computedExcludeCoverageTypes.length > 0 ? form.computedExcludeCoverageTypes : null,
        // true or null, never false: an unticked switch is no request at all (SCORE-11 ADR-6).
        excludeFileComponents: form.excludeFileComponents ? true : null,
        profiles: [...SBOM_SCORE_PROFILES]
    }
    if (!form.exportMetadataArgsUnsupported) {
        variables.includeSupportMetadata = null
        variables.includeInternalMetadata = null
    }
    return variables
}

export type SbomScoreErrorKind = SbomScoreReason | 'NOT_AUTHORIZED' | 'NOT_AVAILABLE' | 'PARSE' | 'OTHER'

export interface SbomScoreErrorView {
    kind: SbomScoreErrorKind
    // extensions.reason, when the server gave one.
    reason?: string
    // The one line the panel shows.
    message: string
    retryable: boolean
}

export const SBOM_SCORE_TIMEOUT_MESSAGE = 'Scoring took longer than 3 minutes and was stopped. Try again, or export a smaller document (top level only, exclude dev).'

// Per reason: the text, whether the server's own message is appended (only where it is fit to
// show), and whether Retry is offered.
type SbomScoreReason = 'UNKNOWN_PROFILE' | 'INPUT_TOO_LARGE' | 'INPUT_REFUSED' | 'NOT_SCORABLE' | 'TIMEOUT' | 'SCORING_UNAVAILABLE' | 'SCORING_FAILED'

// The backend's SbomScoreErrorReason values.
const REASON_VIEWS: Record<SbomScoreReason, { text: string, withServerMessage: boolean, retryable: boolean }> = {
    UNKNOWN_PROFILE: { text: `The server does not know the profiles this UI asked for (${SBOM_SCORE_PROFILES.join(', ')}). Update the server.`, withServerMessage: true, retryable: false },
    INPUT_TOO_LARGE: { text: 'This SBOM is too large to score on this server.', withServerMessage: true, retryable: false },
    INPUT_REFUSED: { text: 'The scoring engine cannot score this document:', withServerMessage: true, retryable: false },
    NOT_SCORABLE: { text: 'This artifact is not a scorable SBOM:', withServerMessage: true, retryable: false },
    TIMEOUT: { text: SBOM_SCORE_TIMEOUT_MESSAGE, withServerMessage: false, retryable: true },
    SCORING_UNAVAILABLE: { text: 'SBOM scoring is not available on this server right now (the scoring engine is missing on this server\'s platform, for example arm64, or the scoring service is down).', withServerMessage: false, retryable: true },
    SCORING_FAILED: { text: 'The scoring engine failed:', withServerMessage: true, retryable: true },
}

function isAbort (err: any): boolean {
    return [err, err?.cause, err?.networkError].some((e: any) => e?.name === 'AbortError')
}

/** What the panel says about a failed score, decided on extensions.reason, never on the message. */
export function classifySbomScoreError (err: any): SbomScoreErrorView {
    if (err instanceof SbomScoreParseError) {
        return { kind: 'PARSE', message: err.message, retryable: false }
    }
    const gqlErrors = graphqlErrorsOf(err)
    const scoreError = gqlErrors.find(e => e?.extensions?.code === 'SBOM_SCORE_ERROR')
    if (scoreError) {
        const reason = String(scoreError.extensions.reason ?? '')
        const view = Object.prototype.hasOwnProperty.call(REASON_VIEWS, reason) ? REASON_VIEWS[reason as SbomScoreReason] : undefined
        if (view) {
            const serverMessage = typeof scoreError.message === 'string' ? scoreError.message.trim() : ''
            const message = view.withServerMessage && serverMessage ? `${view.text} ${serverMessage}` : view.text
            return { kind: reason as SbomScoreErrorKind, reason, message, retryable: view.retryable }
        }
    }
    if (gqlErrors.some(e => e?.message === 'Not authorized' && !e?.extensions?.code)) {
        return { kind: 'NOT_AUTHORIZED', message: 'You are not authorized to score this SBOM (it needs the permission to download it).', retryable: false }
    }
    const status = httpStatusOf(err)
    if (isAbort(err) || status === 504) {
        return { kind: 'TIMEOUT', reason: 'TIMEOUT', message: SBOM_SCORE_TIMEOUT_MESSAGE, retryable: true }
    }
    // The whole request is over the server's body limit: the document never reached the engine.
    if (status === 413) {
        return { kind: 'INPUT_TOO_LARGE', reason: 'INPUT_TOO_LARGE', message: REASON_VIEWS.INPUT_TOO_LARGE.text, retryable: false }
    }
    // An older server, or CE before its sync: the fields are not in its schema.
    if (isSchemaDriftError(err)) {
        return { kind: 'NOT_AVAILABLE', message: 'SBOM scoring is not available on this server.', retryable: false }
    }
    return { kind: 'OTHER', message: `Scoring failed: ${commonFunctions.extractGraphQLErrorMessage(err)}`, retryable: true }
}

/** What was scored: a release export or one artifact. */
export type SbomScoreFileKind = 'release' | 'artifact'

/** sbom-score-<release|artifact>-<id first 8>-<YYYY-MM-DD>.json, the date the score was taken. */
export function rawReportFileName (kind: SbomScoreFileKind, id: string, now: Date = new Date()): string {
    return `sbom-score-${kind}-${id.slice(0, 8)}-${now.toISOString().slice(0, 10)}.json`
}

/** Download `raw` byte for byte as a .json file: the report as the engine wrote it. */
export function downloadRawReport (raw: string, fileName: string): void {
    const blob = new Blob([raw], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
}
