// Regulatory submissions (task TEA-30, the UI of TEA-35): the labels, the table rows of the product
// page's Submissions tab and the release page's section, the label tables of the draft flow (FDAW-32
// 3.3), the Cancel confirmation, the reason check and the History text. Pure logic, so the specs pin
// it. Names and reasons go into the confirmations' `text` only; titles and buttons are constants
// (design 3.8).

import { regulatoryPackagesAvailable, packageKindLabel, packagePartLabel, packageSourceLabel } from './packageSettings'
import { teaActErrorText, teaOneLine, teaShortSha } from './teaPublication'
import { humanizeEnum, SUBMISSION_STATE_LABELS, submissionHistoryShape } from './fdaLabels'
import { SBOM_DEFAULT_NAME } from './sbomProfile'
import { artifactDownloadRef, ArtifactDownloadRef } from './artifactDownload'

/** One gate, two names: the product and release pages ask this one. */
export const submissionsAvailable = regulatoryPackagesAvailable

/** Defined in fdaLabels.ts (the release History parses with it) and read here under its old name. */
export { SUBMISSION_STATE_LABELS }

/** The kind of a submission act (SubmissionActKind), in the Pro enum's order (FDAW-32 3.3). */
export const SUBMISSION_ACT_KIND_LABELS: Record<string, string> = {
    START_DRAFT: 'Draft started',
    DISCARD_DRAFT: 'Draft discarded',
    RETURN_TO_DRAFT: 'Returned to draft',
    CONFIRM: 'Confirmed',
    FINALIZE: 'Finalized',
    RECORD_SENT: 'Recorded as sent',
    FDA_NUMBER_ADDED: 'FDA number added',
    TIMELINE_ENTRY: 'Timeline entry',
    AMENDED: 'Amended',
    SUPERSEDED: 'Superseded',
    WITHDRAW: 'Withdrawn',
    CANCEL: 'Cancelled',
    MIGRATED: 'Migrated',
}

/** The reason of a package's first version: offered alone without a head, never over one. */
export const FIRST_VERSION_REASON = 'FIRST_VERSION'

/** Why a next version is drafted (NewVersionReason). */
export const NEW_VERSION_REASON_LABELS: Record<string, string> = {
    FIRST_VERSION: 'First version',
    FDA_REQUEST_RESPONSE: 'Response to an FDA request',
    CORRECTION: 'Correction',
    OTHER: 'Other',
}

/** Where a draft stands (SubmissionDraftState). */
export const DRAFT_STATE_LABELS: Record<string, string> = {
    EDITING: 'Editing',
    AWAITING_CONFIRMATION: 'Awaiting second confirmation',
}

/** Where a draft's build job stands (DraftBuildState). */
export const DRAFT_JOB_STATE_LABELS: Record<string, string> = {
    NONE: 'Not built yet',
    REQUESTED: 'Rebuild requested',
    RUNNING: 'Rebuilding',
    BUILT: 'Built',
    FAILED: 'Rebuild failed',
}

export const SUBMISSION_PART_OUTCOME_LABELS: Record<string, string> = {
    PRODUCED: 'Generated',
    REFERENCED: 'Referenced',
    SKIPPED: 'Skipped',
}

export const PART_SKIP_REASON_LABELS: Record<string, string> = {
    NOT_APPLICABLE: 'not applicable',
    NO_CONTENT: 'nothing to generate',
    EXCLUDED_BY_SETTINGS: 'excluded by settings',
}

/** A label from one of the tables; an unknown value renders humanized (FDAW-78 3.1), never as itself. */
export function labelOf (table: Record<string, string>, value: string | null | undefined): string {
    if (!value) return ''
    return table[value] || humanizeEnum(value)
}

/** A date as the submission panels show it; empty for none. */
export function submissionDateText (d: string | null | undefined): string {
    return d ? new Date(d).toLocaleString('en-CA') : ''
}

/** The head line's word for the last act (FDAW-32 3.3): from lastAct.kind; `Last act` for a head stored before Finalize. */
export function submissionLastActWord (head: any): string {
    const kind = head?.lastAct?.kind
    return kind ? labelOf(SUBMISSION_ACT_KIND_LABELS, kind) : 'Last act'
}

/** The head's newest version: `latest` when read, else the entry of `versions` at latestVersion. */
function newestVersionOf (head: any): any {
    if (head?.latest) return head.latest
    return (head?.versions ?? []).find((v: any) => v?.version === head?.latestVersion) ?? null
}

/**
 * The head's state as its tag reads (FDAW-8). A FINAL head whose newest version holds a confirmation
 * (written by Finalize) is `Final - not sent` (FDAW-32 3.3); one without is said to have no confirmation
 * record; a head the FINAL/CANCELLED migration moved says so.
 */
export function submissionStateText (head: any): string {
    const state = head?.state
    if (state === 'FINAL') {
        const confirmations = newestVersionOf(head)?.confirmations
        if (Array.isArray(confirmations) && confirmations.length > 0) return 'Final - not sent'
        return head?.legacyState === 'ACTIVE' ? 'Final (legacy, no confirmation record) - not sent'
            : 'Final (no confirmation record) - not sent'
    }
    if (state === 'CANCELLED' && head?.legacyState === 'WITHDRAWN') {
        return 'Cancelled (legacy: withdrawn ' + submissionDateText(head?.withdrawnDate) + ')'
    }
    return labelOf(SUBMISSION_STATE_LABELS, state)
}

/** The tag type of a head's state: settled states green, cancelled or withdrawn ones amber. */
export function submissionStateTagType (state: string | null | undefined): 'success' | 'warning' | 'default' {
    if (state === 'FINAL' || state === 'SENT') return 'success'
    if (state === 'CANCELLED' || state === 'WITHDRAWN') return 'warning'
    return 'default'
}

/**
 * The note under a cancelled head: the newest CANCEL act's date, actor and reason, or, for a head
 * withdrawn before the migration (no CANCEL act), the read-only withdrawal triple. Null otherwise.
 */
export function submissionCancelNote (head: any): { lead: string, reason: string } | null {
    if (head?.state !== 'CANCELLED' && head?.state !== 'WITHDRAWN') return null
    const cancels = (head?.acts ?? []).filter((a: any) => a?.kind === 'CANCEL')
    const last = cancels.length ? cancels[cancels.length - 1] : null
    if (last) {
        // The canceller by the one rule of the package screens (FDAW-4, B14, D8): the act's snapshot
        // name; for a member with no stored name who is also the head's last actor, the head's
        // server-resolved name (functional F-3); else 'removed user'. Never the uuid.
        const name = last.actorName || (last.actor && last.actor === head?.lastActBy ? head?.lastActByName : null)
        return { lead: 'Cancelled on ' + submissionDateText(last.actedAt) + ' by ' + submissionActorText(name, last.actor) + ':',
            reason: last.reasonText ?? '' }
    }
    if (head?.withdrawnDate || head?.withdrawReason) {
        return { lead: 'Withdrawn on ' + submissionDateText(head.withdrawnDate) + ':', reason: head.withdrawReason ?? '' }
    }
    return null
}

/** The lifecycles a submission may be created at: ASSEMBLED and above (the server decides again). */
export const SUBMITTABLE_LIFECYCLES = ['ASSEMBLED', 'READY_TO_SHIP', 'GENERAL_AVAILABILITY', 'END_OF_MARKETING',
    'END_OF_DISTRIBUTION', 'END_OF_SUPPORT', 'END_OF_LIFE']

export const CREATE_LIFECYCLE_REASON = 'The release must be Assembled or later'

export function canCreateSubmission (lifecycle: string | null | undefined): boolean {
    return !!lifecycle && SUBMITTABLE_LIFECYCLES.includes(lifecycle)
}

export interface SubmissionReleaseRow {
    release: string
    version: string
    lifecycle: string
    createdDate: string | null
    byKind: Record<string, any | null>
}

function newestFirst (a: any, b: any): number {
    const ta = a?.createdDate ? Date.parse(a.createdDate) : 0
    const tb = b?.createdDate ? Date.parse(b.createdDate) : 0
    return tb - ta
}

/** One row per release of the selected feature set, newest first, with its head per kind (or null). */
export function submissionReleaseRows (releases: any[] | null | undefined, heads: any[] | null | undefined,
    kinds: string[]): SubmissionReleaseRow[] {
    const byRelease = new Map<string, Record<string, any>>()
    for (const h of heads ?? []) {
        if (!h?.release) continue
        const m = byRelease.get(h.release) ?? {}
        m[h.kind] = h
        byRelease.set(h.release, m)
    }
    return [...(releases ?? [])].filter(r => r?.uuid).sort(newestFirst).map(r => {
        const m = byRelease.get(r.uuid) ?? {}
        const byKind: Record<string, any | null> = {}
        for (const k of kinds) byKind[k] = m[k] ?? null
        return { release: r.uuid, version: r.version ?? '', lifecycle: r.lifecycle ?? '', createdDate: r.createdDate ?? null, byKind }
    })
}

/** The product panel's marker for a head its state filter hides (FDAW-63); fixed text, never the hidden state. */
export const SUBMISSION_FILTERED_OUT_TEXT = 'Not in filter'

/** Whether a head passes the product panel's state filter (FDAW-63): no filter passes every head, as the server's rule. */
export function submissionInStateFilter (head: any, state: string | null | undefined): boolean {
    return !state || head?.state === state
}

/** The heads whose release is not in the list: submissions on releases of other feature sets. */
export function submissionOtherRows (releases: any[] | null | undefined, heads: any[] | null | undefined): any[] {
    const listed = new Set((releases ?? []).map(r => r?.uuid))
    return (heads ?? []).filter(h => h?.release && !listed.has(h.release)).map(h => ({
        uuid: h.uuid,
        release: h.release,
        releaseVersion: h.releaseVersion ?? '',
        kind: h.kind,
        state: h.state,
        latestVersion: h.latestVersion,
        lastActDate: h.lastActDate ?? null,
    }))
}

/** Whether a submission ref names no stored row: the server's built-in (source DEFAULT, row null). */
export function submissionRefBuiltIn (ref: any): boolean {
    return !ref || !ref.row || !ref.source || ref.source === 'DEFAULT'
}

/**
 * "<source label>, revision <n>" (FDAW-78 3.5, L5: `this product's own settings, revision 0`), or the built-in
 * defaults when the ref names no row. `typeWord` words a COMPONENT source: `product` or `component`.
 */
export function submissionRefLabel (ref: any, names: Record<string, string> = {}, typeWord?: string | null): string {
    if (submissionRefBuiltIn(ref)) return 'built-in defaults'
    return packageSourceLabel(ref, names, typeWord) + ', revision ' + (ref.revision ?? 0)
}

/** The product-or-component word of a component type: PRODUCT reads `product`, anything else `component`. */
export function componentTypeWord (componentType: string | null | undefined): string {
    return componentType === 'PRODUCT' ? 'product' : 'component'
}

/** A text with its first letter capital, for a table cell. */
export function capitalFirst (s: string | null | undefined): string {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : ''
}

/**
 * "<name> (<source label>, revision <n>)" for the SBOM profile a version applied; a built-in other than
 * `default` (the server's `fda`) reads "<name> (built-in)".
 */
export function submissionProfileLabel (ref: any, names: Record<string, string> = {}, typeWord?: string | null): string {
    if (!ref) return ''
    if (submissionRefBuiltIn(ref) && ref.name && ref.name !== SBOM_DEFAULT_NAME) return ref.name + ' (built-in)'
    return (ref.name ?? '') + ' (' + submissionRefLabel(ref, names, typeWord) + ')'
}

/** The manifest's download name (no field carries one; decision D7). */
export function manifestFileName (version: any): string {
    return 'submission-' + (version?.submission ?? '') + '-v' + (version?.version ?? '') + '-manifest.json'
}

/** The manifest's download, or null when no integer version of it is recorded. */
export function manifestDownloadRef (version: any): ArtifactDownloadRef | null {
    return artifactDownloadRef(version?.manifestArtifact, version?.manifestArtifactVersion, manifestFileName(version))
}

/** A head's versions, newest first, with the table's labels. */
export function submissionVersionRows (head: any, names: Record<string, string> = {}, typeWord?: string | null): any[] {
    const versions: any[] = [...(head?.versions ?? [])].sort((a, b) => (b?.version ?? 0) - (a?.version ?? 0))
    return versions.map(v => {
        const parts: any[] = v.parts ?? []
        return {
            key: v.version,
            version: v.version,
            createdDate: v.createdDate ?? null,
            createdBy: v.createdBy ?? null,
            createdByName: v.createdByName ?? null,
            reason: v.reason ?? '',
            settingsLabel: capitalFirst(submissionRefLabel(v.settings, names, typeWord)),
            profileLabel: submissionProfileLabel(v.sbomProfile, names, typeWord),
            partCount: parts.length,
            producedCount: parts.filter(p => p?.outcome === 'PRODUCED' || p?.outcome === 'REFERENCED').length,
            skippedCount: parts.filter(p => p?.outcome === 'SKIPPED').length,
            manifestSha256: v.manifestSha256 ?? '',
            manifestSha256Short: teaShortSha(v.manifestSha256),
            versionObject: v,
        }
    })
}

/** The parts of one version, in manifest order; a SKIPPED part and one with no integer version have no download. */
export function submissionPartRows (version: any): any[] {
    return (version?.parts ?? []).map((p: any) => {
        const skipped = p?.outcome === 'SKIPPED'
        const reason = labelOf(PART_SKIP_REASON_LABELS, p?.skipReason)
        return {
            key: p.part,
            part: p.part,
            label: packagePartLabel(p.part),
            requirement: p.requirement ?? '',
            outcome: p.outcome ?? '',
            outcomeLabel: labelOf(SUBMISSION_PART_OUTCOME_LABELS, p.outcome),
            fileName: p.fileName ?? '',
            mediaType: p.mediaType ?? '',
            size: p.size ?? null,
            sha256: p.sha256 ?? '',
            sha256Short: teaShortSha(p.sha256),
            skipText: skipped ? [reason, p.detail].filter(Boolean).join(': ') : '',
            download: skipped ? null : artifactDownloadRef(p.artifact, p.artifactVersion, p.fileName),
        }
    })
}

/** A Swal confirmation: constant title (titleText) and button, the names in `text` only. */
export interface SubmissionConfirm { titleText: string, text: string, confirmButtonText: string }

/** The release an act names. */
export interface SubmissionNames {
    componentName?: string | null
    releaseVersion?: string | null
    componentType?: string | null
}

/**
 * Who acted, as the page shows it (task FDAW-4, B14): the server-resolved name (a name, or a current
 * member's email), else 'removed user' when the record names someone the server could not resolve.
 * The uuid itself is never the text; the panel puts it in a title attribute.
 */
export function submissionActorText (name: string | null | undefined, uuid: string | null | undefined): string {
    if (name) return name
    return uuid ? 'removed user' : ''
}

/**
 * The `<release>` of every dialog (FDAW-32 3.3): the component name and the release version, each one line
 * with no bidirectional control (teaOneLine), joined by one space.
 */
export function submissionReleaseLabel (n: SubmissionNames | null | undefined): string {
    return [teaOneLine(n?.componentName), teaOneLine(n?.releaseVersion)].filter(Boolean).join(' ')
}

export function submissionCancelConfirm (head: any, n: SubmissionNames): SubmissionConfirm {
    return {
        titleText: 'Cancel submission?',
        text: 'Cancels the ' + packageKindLabel(head?.kind) + ' submission of ' + submissionReleaseLabel(n) + ' at v'
            + (head?.latestVersion ?? '') + '. Every version and document is kept; the version is marked cancelled, '
            + 'and a new draft writes the next version.',
        confirmButtonText: 'Cancel submission',
    }
}

export const SUBMISSION_REASON_MAX = 2048

/** The client copy of the server's REASON_REQUIRED and REASON_INVALID rules; null when the reason may be sent. */
export function submissionReasonError (reason: string | null | undefined, required: boolean): string | null {
    // The server strips the reason first (SubmissionService.normalizedReason): so does the check.
    const r = (reason ?? '').trim()
    if (required && !r) return 'A reason is required'
    if (r.length > SUBMISSION_REASON_MAX) return 'At most 2048 characters'
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(r)) return 'Control characters are not allowed'
    return null
}

/** The error dialog's text for a failed act: the TEA gateway sentence, else the server's message. */
export function submissionActErrorText (err: any): string {
    return teaActErrorText(err)
}

/**
 * The History text of a SUBMISSION release event, in words, from the unchanged stored values (FDAW-78 3.4):
 * `FDA premarket submission: version 1 created`; a shape this build does not know reads `Regulatory
 * submission updated`. Never the submission's uuid. The Action word is fdaLabels.ts historyActionLabel.
 */
export function submissionHistoryText (row: any): string {
    return submissionHistoryShape(row).text
}

/**
 * The text of a Finalize result (FDAW-78 3.5, L11): `Version 1 created.`; with no version number
 * `Submission created.`. The act's outcome is not echoed.
 */
export function submissionWrittenText (result: any): string {
    const n = result?.version?.version
    return n == null ? 'Submission created.' : 'Version ' + n + ' created.'
}
