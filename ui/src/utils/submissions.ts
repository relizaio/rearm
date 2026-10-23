// Regulatory submissions (task TEA-30, the UI of TEA-35): the labels, the table rows of the product
// page's Submissions tab and the release page's section, the confirmations of the three acts, the
// reason check and the History text. Pure logic, so the specs pin it. Names and reasons go into the
// confirmations' `text` only; titles and buttons are constants (design 3.8).

import { regulatoryPackagesAvailable, packageKindLabel, packagePartLabel } from './packageSettings'
import { teaActErrorText, teaShortSha } from './teaPublication'
import { teaSourceLabel } from './teaProfile'
import { artifactDownloadRef, ArtifactDownloadRef } from './artifactDownload'

/** One gate, two names: the product and release pages ask this one. */
export const submissionsAvailable = regulatoryPackagesAvailable

export const SUBMISSION_STATE_LABELS: Record<string, string> = {
    ACTIVE: 'Active',
    WITHDRAWN: 'Withdrawn',
}

export const SUBMISSION_ACT_LABELS: Record<string, string> = {
    CREATED: 'Created',
    RESUBMITTED: 'Resubmitted',
    WITHDRAWN: 'Withdrawn',
    UNCHANGED: 'Unchanged',
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

/** A label from one of the tables; an unknown value renders itself. */
export function labelOf (table: Record<string, string>, value: string | null | undefined): string {
    return (value && table[value]) || value || ''
}

/** The lifecycles a submission may be created at: ASSEMBLED and above (the server decides again). */
export const SUBMITTABLE_LIFECYCLES = ['ASSEMBLED', 'READY_TO_SHIP', 'GENERAL_AVAILABILITY', 'END_OF_MARKETING',
    'END_OF_DISTRIBUTION', 'END_OF_SUPPORT', 'END_OF_LIFE']

export const CREATE_LIFECYCLE_REASON = 'The release must be ASSEMBLED or above'

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

/** "<source label>, r<n>", or the built-in defaults when the ref names no row. */
export function submissionRefLabel (ref: any, names: Record<string, string> = {}): string {
    if (!ref || !ref.row || !ref.source || ref.source === 'DEFAULT') return 'built-in defaults'
    return teaSourceLabel(ref.source, ref.sourceObject, names) + ', r' + (ref.revision ?? 0)
}

/** "<name> (<source label>, r<n>)" for the SBOM profile a version applied. */
export function submissionProfileLabel (ref: any, names: Record<string, string> = {}): string {
    if (!ref) return ''
    return (ref.name ?? '') + ' (' + submissionRefLabel(ref, names) + ')'
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
export function submissionVersionRows (head: any, names: Record<string, string> = {}): any[] {
    const versions: any[] = [...(head?.versions ?? [])].sort((a, b) => (b?.version ?? 0) - (a?.version ?? 0))
    return versions.map(v => {
        const parts: any[] = v.parts ?? []
        return {
            key: v.version,
            version: v.version,
            createdDate: v.createdDate ?? null,
            createdBy: v.createdBy ?? null,
            reason: v.reason ?? '',
            settingsLabel: submissionRefLabel(v.settings, names),
            profileLabel: submissionProfileLabel(v.sbomProfile, names),
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

/** A Swal confirmation: constant title and button, the names in `text` only. */
export interface SubmissionConfirm { title: string, text: string, confirmButtonText: string }

export interface SubmissionNames { componentName?: string | null, releaseVersion?: string | null }

function planText (preview: any): string {
    const plan: any[] = preview?.plan ?? []
    return plan.map(p => packagePartLabel(p.part) + ' ' + (p.selection === 'INCLUDE' ? 'included' : 'excluded')).join(', ')
}

function previewFacts (preview: any, names: Record<string, string>): string {
    return 'Parts: ' + planText(preview) + '; SBOM profile ' + submissionProfileLabel(preview?.sbomProfile, names)
        + '; settings ' + submissionRefLabel(preview?.settings, names) + '.'
}

function releaseLabel (n: SubmissionNames): string {
    return [n.componentName, n.releaseVersion].filter(Boolean).join(' ')
}

export function submissionCreateConfirm (preview: any, kind: string, n: SubmissionNames,
    names: Record<string, string> = {}): SubmissionConfirm {
    const next = preview?.nextVersion ?? 1
    return {
        title: 'Create submission?',
        text: 'Writes v' + next + ' of the ' + packageKindLabel(kind) + ' submission of ' + releaseLabel(n) + '. '
            + previewFacts(preview, names),
        confirmButtonText: 'Create v' + next,
    }
}

export function submissionResubmitConfirm (preview: any, kind: string, n: SubmissionNames,
    names: Record<string, string> = {}): SubmissionConfirm {
    const next = preview?.nextVersion ?? 1
    return {
        title: 'Resubmit?',
        text: 'Writes v' + next + ' of the ' + packageKindLabel(kind) + ' submission of ' + releaseLabel(n) + '. '
            + previewFacts(preview, names) + ' A resubmission always writes a new version, equal content or not.',
        confirmButtonText: 'Resubmit v' + next,
    }
}

export function submissionWithdrawConfirm (head: any, n: SubmissionNames): SubmissionConfirm {
    return {
        title: 'Withdraw submission?',
        text: 'Withdraws the ' + packageKindLabel(head?.kind) + ' submission of ' + releaseLabel(n) + ' at v'
            + (head?.latestVersion ?? '') + '. Every version and document is kept; the submission is marked withdrawn '
            + 'and can be resubmitted later.',
        confirmButtonText: 'Withdraw',
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

/** The History text of a SUBMISSION release event (the four shapes the server writes, design 3.5.6). */
export function submissionHistoryText (row: any): string {
    const oldValue = typeof row?.oldValue === 'string' ? row.oldValue.trim() : ''
    const newValue = typeof row?.newValue === 'string' ? row.newValue.trim() : ''
    if (!newValue) return row?.objectId ?? ''
    return oldValue ? 'Submission ' + oldValue + ' -> ' + newValue : 'Submission ' + newValue
}
