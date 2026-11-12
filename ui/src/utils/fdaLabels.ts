// The words of the FDA screens and documents (FDAW-78 architecture 3.1, the UI half). One rule: no
// text a person reads on an FDA screen or in an FDA document is a stored enum name, a uuid or a
// machine instant. A value is printed through a label table; a value the table does not know (a
// future enum value) prints its humanized form (lower case, `_` to a space, first letter capital);
// a null or empty value prints the empty string. The raw name is never the fallback.
//
// This module imports nothing from the other label modules: packageSettings.ts and submissions.ts
// import from it, so the two tables the release History parses with (the kinds and the submission
// states) are defined here and re-exported there under their old names.

import constants from './constants'

/** The fallback of the rule: `SOME_NEW_VALUE` reads `Some new value`; null, undefined or empty read ''. */
export function humanizeEnum (value: string | null | undefined): string {
    if (value == null) return ''
    const s = String(value).toLowerCase().replace(/_/g, ' ')
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : ''
}

/** `humanizeEnum` for a lower-case document cell: `SOME_NEW_VALUE` reads `some new value`. */
export function lowerEnum (value: string | null | undefined): string {
    const s = humanizeEnum(value)
    return s ? s.charAt(0).toLowerCase() + s.slice(1) : ''
}

/** The table's label of a value, else the value humanized; '' for null or empty. */
export function labelOrHumanized (table: Record<string, string>, value: string | null | undefined): string {
    if (!value) return ''
    return Object.prototype.hasOwnProperty.call(table, value) ? table[value] : humanizeEnum(value)
}

/** `labelOrHumanized` with `lowerEnum` as the fallback, for lower-case document cells. */
export function labelOrLower (table: Record<string, string>, value: string | null | undefined): string {
    if (!value) return ''
    return Object.prototype.hasOwnProperty.call(table, value) ? table[value] : lowerEnum(value)
}

export const REQUIREMENT_LABELS: Record<string, string> = {
    REQUIRED: 'Required',
    OPTIONAL: 'Optional',
}

/** The release History's Action column (ReleaseUpdateAction, the artifact-collection rows' UPDATED included). */
export const HISTORY_ACTION_LABELS: Record<string, string> = {
    ADDED: 'Added',
    REMOVED: 'Removed',
    CHANGED: 'Changed',
    UPDATED: 'Updated',
}

/** The release History's Scope column: every value of the server's ReleaseUpdateScope. */
export const HISTORY_SCOPE_LABELS: Record<string, string> = {
    RELEASE_CREATED: 'Release created',
    LIFECYCLE: 'Lifecycle',
    SOURCE_CODE_ENTRY: 'Source code entry',
    PARENT_RELEASE: 'Parent release',
    ARTIFACT: 'Artifact',
    INBOUND_DELIVERY: 'Inbound delivery',
    OUTBOUND_DELIVERY: 'Outbound delivery',
    VARIANT: 'Variant',
    VERSION: 'Version',
    NOTES: 'Notes',
    TAGS: 'Tags',
    MARKETING_VERSION: 'Marketing version',
    TRIGGER: 'Trigger',
    INPUT_TRIGGER: 'Input trigger',
    GUARD: 'Guard',
    APPROVED_ENVIRONMENT: 'Approved environment',
    SUPPORT_WINDOW: 'Release support dates',
    FDA_NARRATIVE: 'FDA assessment narrative',
    SYNTHETIC_ARTIFACT: 'Generated artifact',
    TEA_PUBLICATION: 'TEA publication',
    SUBMISSION: 'Regulatory submission',
    DECLARED_LICENSE: 'Declared license',
}

/** Every ArtifactType value, in the order of packageSettings.ts ARTIFACT_TYPES (the spec ties the two). */
export const ARTIFACT_TYPE_LABELS: Record<string, string> = {
    BOM: 'BOM',
    ATTESTATION: 'Attestation',
    VDR: 'VDR',
    BOV: 'BOV',
    VEX: 'VEX',
    USER_DOCUMENT: 'User document',
    DEVELOPMENT_DOCUMENT: 'Development document',
    PROJECT_DOCUMENT: 'Project document',
    MARKETING_DOCUMENT: 'Marketing document',
    TEST_REPORT: 'Test report',
    CODE_SCANNING_RESULT: 'Code scanning result',
    SARIF: 'SARIF',
    BUILD_META: 'Build metadata',
    CERTIFICATION: 'Certification',
    FORMULATION: 'Formulation',
    LICENSE: 'License',
    RELEASE_NOTES: 'Release notes',
    SECURITY_TXT: 'security.txt',
    THREAT_MODEL: 'Threat model',
    SIGNATURE: 'Signature',
    SIGNED_PAYLOAD: 'Signed payload',
    PUBLIC_KEY: 'Public key',
    CERTIFICATE_X_509: 'X.509 certificate',
    CERTIFICATE_PGP: 'PGP certificate',
    RISK_ASSESSMENT: 'Risk assessment',
    AGENTIC_REPORT: 'Agentic report',
    REGULATORY_DOCUMENT: 'Regulatory document',
    OTHER: 'Uncategorized',
}

/** The addendum's Attestation state cell; the server's FdaDocumentText.attestationStateText, character for character. */
export const ATTESTATION_STATE_TEXT: Record<string, string> = {
    ATTESTED: 'attested',
    WITHDRAWN: 'withdrawn',
}

/** The addendum's End of support reason; the server's FdaDocumentText.unknownReasonText, character for character. */
export const UNKNOWN_REASON_TEXT: Record<string, string> = {
    NO_COMMITTED_END_OF_SUPPORT: 'no committed end of support',
    COMMUNITY_PROJECT: 'community project, no support commitment',
    SUPPLIER_PUBLISHES_NO_DATE: 'supplier publishes no end-of-support date',
    DISTRIBUTION_PUBLISHES_NO_HASH: 'distribution publishes no per-package hash',
    VENDORED_SOURCE_NO_HASH: 'vendored source, no distributed artifact to hash',
    OTHER: 'see the manufacturer\'s recorded justification',
}

/** The regulatory package kinds (re-exported by packageSettings.ts, where the editor reads it). */
export const PACKAGE_KIND_LABELS: Record<string, string> = {
    FDA_PREMARKET: 'FDA premarket',
}

/** The submission states (re-exported by submissions.ts, where the panels read it). */
export const SUBMISSION_STATE_LABELS: Record<string, string> = {
    FINAL: 'Final',
    SENT: 'Sent',
    AMENDED: 'Amended',
    SUPERSEDED: 'Superseded',
    WITHDRAWN: 'Withdrawn',
    CANCELLED: 'Cancelled',
}

/** A lifecycle on an FDA screen: the lifecycle select's label, else the value humanized; '' for none. */
export function fdaLifecycleLabel (value: string | null | undefined): string {
    if (!value) return ''
    const known = (constants.LifecycleOptions as Array<{ label: string, key: string }>).find(o => o.key === value)
    return known ? known.label : humanizeEnum(value)
}

/** The plural of a terminology word, lower case: `Branch` reads `branches`, `Feature Set` `feature sets`. */
export function pluralWord (word: string | null | undefined): string {
    const w = (word ?? '').toLowerCase()
    if (!w) return ''
    if (/(s|x|z|ch|sh)$/.test(w)) return w + 'es'
    if (/[^aeiou]y$/.test(w)) return w.slice(0, -1) + 'ies'
    return w + 's'
}

/**
 * An instant in a document: `yyyy-MM-dd HH:mm UTC`, truncated to the minute (the server's
 * FdaDocumentText.instant). An empty or unparsable input reads '' or, with `keepUnparsed`, the input text
 * (the Last assessed cell: a text that is not an instant is printed as it is).
 */
export function documentInstantText (iso: string | null | undefined, keepUnparsed = false): string {
    if (iso == null || iso === '') return ''
    const d = new Date(iso)
    if (isNaN(d.getTime())) return keepUnparsed ? String(iso) : ''
    return d.toISOString().slice(0, 16).replace('T', ' ') + ' UTC'
}

/* ---------------------------------------------------------------- the release History (3.4) */

const LEGACY_SUBMISSION_STATES = ['ACTIVE']

function isSubmissionState (token: string): boolean {
    return Object.prototype.hasOwnProperty.call(SUBMISSION_STATE_LABELS, token) || LEGACY_SUBMISSION_STATES.includes(token)
}

function stateLower (state: string): string {
    return labelOrHumanized(SUBMISSION_STATE_LABELS, state).toLowerCase()
}

function capitalized (s: string): string {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : ''
}

interface StoredValue { kind?: string, state?: string, version?: number, draft?: boolean }

/** One stored SUBMISSION event value: `<KIND> v<n>`, `<STATE> v<n>`, `<KIND> draft` or `<STATE>`; null otherwise. */
function parseStoredValue (raw: any): StoredValue | null {
    const v = typeof raw === 'string' ? raw.trim() : ''
    if (!v) return null
    let m = /^(\S+) v(\d+)$/.exec(v)
    if (m) {
        return isSubmissionState(m[1]) ? { state: m[1], version: Number(m[2]) } : { kind: m[1], version: Number(m[2]) }
    }
    m = /^(\S+) draft$/.exec(v)
    if (m) return isSubmissionState(m[1]) ? null : { kind: m[1], draft: true }
    m = /^(\S+)$/.exec(v)
    if (m && isSubmissionState(m[1])) return { state: m[1] }
    return null
}

function kindWord (kind: string): string {
    return labelOrHumanized(PACKAGE_KIND_LABELS, kind)
}

const SUBMISSION_UPDATED = 'Regulatory submission updated'

/**
 * The Action word and the details text of a SUBMISSION History row, from the unchanged stored values
 * (3.4's table). A shape this build does not know reads the generic action and `Regulatory submission
 * updated`; the submission's uuid (`objectId`) is never the text.
 */
export function submissionHistoryShape (row: any): { action: string, text: string } {
    const rua = typeof row?.rua === 'string' ? row.rua : ''
    const oldRaw = typeof row?.oldValue === 'string' ? row.oldValue.trim() : ''
    const newRaw = typeof row?.newValue === 'string' ? row.newValue.trim() : ''
    const o = parseStoredValue(oldRaw)
    const n = parseStoredValue(newRaw)
    if (rua === 'ADDED' && !oldRaw && n?.kind && n.version != null) {
        return { action: 'Created', text: kindWord(n.kind) + ' submission: version ' + n.version + ' created' }
    }
    if (rua === 'ADDED' && !oldRaw && n?.kind && n.draft) {
        return { action: 'Draft started', text: kindWord(n.kind) + ' submission: draft started' }
    }
    if (rua === 'REMOVED' && o?.kind && o.draft && !newRaw) {
        return { action: 'Draft discarded', text: kindWord(o.kind) + ' submission: draft discarded' }
    }
    if (rua === 'CHANGED' && o?.kind && o.version != null && n?.kind && n.version != null) {
        return { action: 'Resubmitted', text: kindWord(n.kind) + ' submission: version ' + n.version
            + ' created, replacing version ' + o.version }
    }
    if (rua === 'CHANGED' && o?.state === 'CANCELLED' && o.version == null && n?.state && n.version != null) {
        return { action: 'Created', text: 'Regulatory submission: version ' + n.version + ' created after a cancellation' }
    }
    if (rua === 'REMOVED' && o?.state && o.version != null && n?.state && n.version == null) {
        const word = stateLower(n.state)
        return { action: capitalized(word), text: 'Regulatory submission: version ' + o.version + ' ' + word }
    }
    return { action: labelOrHumanized(HISTORY_ACTION_LABELS, rua), text: SUBMISSION_UPDATED }
}

/** The History's Action cell: the act word of a SUBMISSION row, else the action's label; '' for no row. */
export function historyActionLabel (row: any): string {
    if (!row) return ''
    if (row.rus === 'SUBMISSION') return submissionHistoryShape(row).action
    return labelOrHumanized(HISTORY_ACTION_LABELS, row.rua)
}

/* ---------------------------------------------------------------- the PDF footers (3.8) */

/** A PDF footer's left column: `<device> <version>`, no uuid; a null device `(unnamed device)`, no trailing space. */
export function documentFooterIdentity (componentName: string | null | undefined, releaseVersion: string | null | undefined): string {
    return ((componentName || '(unnamed device)') + ' ' + (releaseVersion || '')).trim()
}
