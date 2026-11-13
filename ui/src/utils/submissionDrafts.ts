// The draft of a submission's next version on the release's Regulatory tab (task FDAW-32, the package page):
// the pure helpers of the draft card, the Start form and the draft dialogs. No Vue, no client, no dialog, so
// the specs pin them. Every text is the page's own constant (FDAW-32 3.0, 3.10); a value the page did not
// write (a moved fingerprint line, a name, a reason) is never printed as itself where a label stands, and a
// name enters a dialog's `text` only through teaOneLine (submissionReleaseLabel).
import { documentInstantText } from './fdaLabels'
import { packageKindLabel, packagePartLabel } from './packageSettings'
import { teaOneLine, teaShortSha } from './teaPublication'
import { ARTIFACT_UUID, downloadFileName } from './artifactDownload'
import {
    CREATE_LIFECYCLE_REASON, FIRST_VERSION_REASON, NEW_VERSION_REASON_LABELS, SUBMISSION_STATE_LABELS, SubmissionConfirm, canCreateSubmission, labelOf,
    submissionActorText, submissionReasonError, submissionReleaseLabel,
} from './submissions'

/** The labels of a moved fingerprint line, by its first word (the text before the first `:`), in this order. */
export const MOVED_LINE_LABELS: [string, string][] = [
    ['profile', 'SBOM profile changed'],
    ['settings', 'package settings changed'],
    ['release', 'a release of the package changed'],
    ['artifact', 'files of a release changed'],
    ['parent', 'component releases of the product changed'],
    ['inbound', 'inbound deliverables changed'],
    ['outbound', 'outbound deliverables changed'],
    ['prose', 'organization FDA statements changed'],
    ['narrative', 'assessment narrative changed'],
    ['displayed', 'displayed version changed'],
    ['device', 'device support dates changed'],
    ['support', 'support data of a component changed'],
    ['overlay', 'manual or rule overlay changed'],
    ['justifications', 'justifications changed'],
    ['merge', 'SBOM merge version changed'],
    ['disclosure', 'support disclosure setting changed'],
]
export const MOVED_LINE_OTHER = 'another input changed'
export const STALE_NOT_BUILT = 'Not built yet'
export const STALE_UNCHANGED = 'Inputs unchanged'
export const CONFIRMATION_STATEMENT = 'I reviewed the contents and the accepted items and confirm this package is ready for submission.'
export const STATEMENT_REQUIRED = 'Tick the statement to continue'
export const DIFF_NO_BASELINE = 'No earlier version to compare with'
export const START_NO_CATEGORY = 'Choose a reason'
export const SETTING_UNREAD = 'The second-confirmer setting could not be read; this page reads it again every 30 seconds'
export const STILL_RUNNING = 'Still running; this page checks again every 30 seconds'
export const REPLACES_UNREAD = 'The versions that can be replaced could not be read; the draft can be started without one'
export const DRAFT_POLL_FAST_MS = 5000
export const DRAFT_POLL_SLOW_MS = 30000
export const DRAFT_POLL_FAST_MAX = 180
/** The server's sentence for a draft that no longer exists: a poll that gets it reads the whole panel again. */
export const DRAFT_NOT_FOUND = 'Draft not found'

/** The job states of a rebuild in flight (DraftBuildState). */
export const DRAFT_JOB_IN_FLIGHT = ['REQUESTED', 'RUNNING']

/** Whether the draft's rebuild is requested or running. */
export function draftJobInFlight (draft: any): boolean {
    return DRAFT_JOB_IN_FLIGHT.includes(draft?.job?.state)
}

/** The second-confirmer setting of one organization in an OrgSecondConfirmer answer: on only for exactly true; null when absent. */
export function secondConfirmerOf (resp: any, orgUuid: string | null | undefined): boolean | null {
    const org = (resp?.data?.organizations ?? []).find((o: any) => o?.uuid === orgUuid)
    return org ? org.settings?.submissionSecondConfirmer === true : null
}

/** What the Start form collected. */
export interface DraftStartForm { reasonCategory: string, reasonText?: string | null, replaces?: string | null }

/** A draft part's download: the artifact uuid and the name used when the server sends none. */
export interface DraftPartDownload { uuid: string, fallbackName: string }

export function movedLineLabels (movedLines: unknown): string[] {
    const lines: unknown[] = Array.isArray(movedLines) ? movedLines : []
    const seen = new Set<string>()
    let other = false
    for (const l of lines) {
        if (typeof l !== 'string') { other = true; continue }
        const i = l.indexOf(':')
        const prefix = i < 0 ? l : l.slice(0, i)
        if (MOVED_LINE_LABELS.some(([p]) => p === prefix)) seen.add(prefix)
        else other = true
    }
    const out = MOVED_LINE_LABELS.filter(([p]) => seen.has(p)).map(([, label]) => label)
    if (other) out.push(MOVED_LINE_OTHER)
    return out
}

export interface StaleBanner { kind: 'not-built' | 'unchanged' | 'changed', text: string, labels: string[] }

export function draftStaleBanner (draft: any): StaleBanner {
    if (!draft?.build) return { kind: 'not-built', text: STALE_NOT_BUILT, labels: [] }
    const c = draft.changesSinceBuild
    if (c?.fingerprintEqual === true) return { kind: 'unchanged', text: STALE_UNCHANGED, labels: [] }
    const count = Number.isInteger(c?.count) && c.count > 0 ? c.count : 1
    return { kind: 'changed', text: count + ' changes since this build', labels: movedLineLabels(c?.movedLines) }
}

export interface DraftButton { shown: boolean, disabled: boolean, title: string }
export interface DraftButtons { rebuild: DraftButton, discard: DraftButton, confirm: DraftButton, returnToEditing: DraftButton, finalize: DraftButton }
export interface DraftButtonFacts {
    secondConfirmer?: boolean | null, writable?: boolean, busy?: boolean, currentUser?: string | null,
    lifecycle?: string | null, head?: any
}
export const REBUILD_FIRST_TITLE = 'Rebuild first'
export const REBUILD_IN_PROGRESS_TITLE = 'A rebuild of this draft is in progress; wait for it to finish'
export const RETURN_AND_REBUILD_TITLE = 'Return the draft to editing and rebuild; the inputs changed since this build'
export const SAME_CONFIRMER_TITLE = 'The second confirmation must come from a different person than the first; ask another member to finalize'

/** The plan a Finalize would be refused for: the package already has the planned version. Null without a head. */
export function draftPlanProblem (draft: any, head: any): string | null {
    if (!draft || !head) return null
    const planned = draft.plannedVersion
    const stale = head.uuid !== (draft.submission ?? null)
        || (Number.isInteger(head.latestVersion) && Number.isInteger(planned) && head.latestVersion >= planned)
    return stale ? 'This draft was planned as version ' + planned + ', but the package already has version '
        + head.latestVersion + '; discard this draft and start a new one' : null
}

export function draftButtons (draft: any, o: DraftButtonFacts): DraftButtons {
    const hidden: DraftButton = { shown: false, disabled: true, title: '' }
    const all = { rebuild: hidden, discard: hidden, confirm: hidden, returnToEditing: hidden, finalize: hidden }
    if (!o?.writable || !draft) return all
    const busy = Boolean(o.busy)
    const on = o.secondConfirmer === true
    const job = draft.job?.state
    const inFlight = draftJobInFlight(draft)
    const built = job === 'BUILT' && Boolean(draft.build)
    const changed = draftStaleBanner(draft).kind === 'changed'
    const awaiting = draft.state === 'AWAITING_CONFIRMATION'
    const button = (reason: string | null): DraftButton => ({ shown: true, disabled: busy || reason !== null, title: reason ?? '' })
    const buildReason = inFlight ? REBUILD_IN_PROGRESS_TITLE : (built ? null : REBUILD_FIRST_TITLE)
    const sameConfirmer = awaiting && on && Boolean(o.currentUser) && draft.confirmation?.actor === o.currentUser
    const finalizeReason = buildReason
        ?? (sameConfirmer ? SAME_CONFIRMER_TITLE : null)
        ?? (canCreateSubmission(o.lifecycle) ? null : CREATE_LIFECYCLE_REASON)
        ?? draftPlanProblem(draft, o.head)
        ?? (changed ? (awaiting ? RETURN_AND_REBUILD_TITLE : REBUILD_FIRST_TITLE) : null)
    if (draft.state === 'EDITING') {
        return { ...all, rebuild: button(null), discard: button(job === 'REQUESTED' ? REBUILD_IN_PROGRESS_TITLE : null),
            confirm: on ? button(buildReason ?? (changed ? REBUILD_FIRST_TITLE : null)) : hidden,
            finalize: on ? hidden : button(finalizeReason) }
    }
    if (awaiting) return { ...all, returnToEditing: button(null), finalize: button(finalizeReason) }
    return all
}

export function draftPollDelay (draft: any, fastPollsDone: number): number {
    return draftJobInFlight(draft) && fastPollsDone < DRAFT_POLL_FAST_MAX ? DRAFT_POLL_FAST_MS : DRAFT_POLL_SLOW_MS
}

export function draftStillRunning (draft: any, fastPollsDone: number): boolean {
    return draftJobInFlight(draft) && fastPollsDone >= DRAFT_POLL_FAST_MAX
}

export function draftDiffSummary (diff: any): string {
    if (!diff) return DIFF_NO_BASELINE
    const n = (v: any) => (Number.isInteger(v) ? v : 0)
    const findings = diff.findingsCompared === true
        ? (n(diff.findingsAddedCount) + n(diff.findingsRemovedCount)) + ' findings changed' : 'findings not compared'
    return 'Compared with version ' + n(diff.baseline?.version) + ': ' + n(diff.addedCount) + ' added, ' + n(diff.removedCount)
        + ' removed, ' + n(diff.changedCount) + ' with a changed version, ' + n(diff.supportChangedCount)
        + ' support changes, ' + findings
}

export interface DiffSection { key: string, title: string, count: number, rows: string[], more: number }

export function draftDiffSections (diff: any): DiffSection[] {
    if (!diff) return []
    const list = (v: any): any[] => (Array.isArray(v) ? v : [])
    const named = (c: any) => String(c?.name || c?.identity || '')
    const section = (key: string, title: string, count: any, rows: string[]): DiffSection => {
        const total = Number.isInteger(count) ? count : rows.length
        return { key, title, count: total, rows, more: Math.max(0, total - rows.length) }
    }
    const out = [
        section('added', 'Added', diff.addedCount, list(diff.added).map(c => named(c) + ' ' + String(c?.version ?? ''))),
        section('removed', 'Removed', diff.removedCount, list(diff.removed).map(c => named(c) + ' ' + String(c?.version ?? ''))),
        section('changed', 'Changed version', diff.changedCount, list(diff.changed).map(c => named(c) + ': '
            + String(c?.fromVersion ?? '') + ' to ' + String(c?.toVersion ?? ''))),
        section('support', 'Support changes', diff.supportChangedCount,
            list(diff.supportChanged).map(c => String(c?.key ?? '') + ': ' + String(c?.property ?? ''))),
    ]
    if (diff.findingsCompared === true) {
        const finding = (f: any) => String(f?.vulnerabilityId ?? '') + (f?.affectedRef ? ' in ' + String(f.affectedRef) : '')
        out.push(section('findings-added', 'Findings added', diff.findingsAddedCount, list(diff.findingsAdded).map(finding)))
        out.push(section('findings-removed', 'Findings removed', diff.findingsRemovedCount, list(diff.findingsRemoved).map(finding)))
    }
    return out
}

export function draftPartRows (draft: any): any[] {
    const parts: any[] = Array.isArray(draft?.build?.parts) ? draft.build.parts : []
    return parts.map(p => ({
        key: p.part,
        label: packagePartLabel(p.part),
        size: p.sizeBytes ?? null,
        sha256: p.sha256 ?? '',
        sha256Short: teaShortSha(p.sha256),
        download: typeof p.artifact === 'string' && ARTIFACT_UUID.test(p.artifact)
            ? { uuid: p.artifact, fallbackName: 'draft-v' + (draft?.plannedVersion ?? '') + '-' + String(p.part ?? '').toLowerCase().replace(/_/g, '-') }
            : null,
    }))
}

/** The file name of a Content-Disposition header, cleaned; the fallback when it names none. */
export function contentDispositionFileName (header: string | null | undefined, fallback: string): string {
    const m = /filename="([^"]*)"/i.exec(String(header ?? ''))
    return downloadFileName(m ? m[1] : '', fallback)
}

export function draftReasonOptions (hasHead: boolean): { label: string, value: string }[] {
    return Object.entries(NEW_VERSION_REASON_LABELS)
        .filter(([value]) => (value === FIRST_VERSION_REASON) !== hasHead)
        .map(([value, label]) => ({ label, value }))
}

export function draftStartProblem (category: string | null | undefined, text: string | null | undefined): string | null {
    if (!category) return START_NO_CATEGORY
    return submissionReasonError(text, category !== FIRST_VERSION_REASON)
}

export function draftReplacesOptions (heads: any[] | null | undefined, thisRelease: string): { label: string, value: string }[] {
    const out: { label: string, value: string }[] = []
    for (const h of heads ?? []) {
        if (!h?.uuid || h.release === thisRelease) continue
        const states: any[] = [...(h.versionStates ?? [])].filter(v => v?.state === 'FINAL' || v?.state === 'SENT')
            .sort((a, b) => b.version - a.version)
        for (const v of states) {
            out.push({ value: h.uuid + ':' + v.version, label: 'Release ' + (h.releaseVersion || '(no version)') + ', version ' + v.version
                + ' (' + labelOf(SUBMISSION_STATE_LABELS, v.state) + ')' })
        }
    }
    return out
}

export function draftStartVariables (release: string, kind: string, form: DraftStartForm) {
    const text = (form.reasonText ?? '').trim()
    const r = form.replaces ? /^(.+):(\d+)$/.exec(form.replaces) : null
    return { release, kind, reasonCategory: form.reasonCategory, reasonText: text || null,
        replacesSubmission: r ? r[1] : null, replacesVersion: r ? Number(r[2]) : null }
}

export function confirmationRecordLine (c: any): string {
    return 'Reviewed and confirmed ready for submission by ' + submissionActorText(c?.actorName, c?.actor)
        + ' on ' + documentInstantText(c?.confirmedAt)
}

/** The card's line for a held confirmation, by the organization's setting as the page read it; null without one. */
export function draftConfirmationLine (draft: any, secondConfirmer: boolean | null | undefined): string | null {
    const c = draft?.confirmation
    if (!c) return null
    const lead = 'Confirmed by ' + submissionActorText(c.actorName, c.actor) + ' on ' + documentInstantText(c.confirmedAt)
    if (secondConfirmer === true) return lead + '; a different person finalizes'
    if (secondConfirmer === false) {
        return lead + '. The organization no longer requires a second confirmer: this confirmation will not be '
            + 'recorded, and anyone who may write can finalize'
    }
    return lead
}

/** The confirmer's name in a dialog: one line (teaOneLine); `removed user` when the record has none. */
function confirmerName (draft: any): string {
    return teaOneLine(draft?.confirmation?.actorName) || 'removed user'
}

function draftOf (draft: any, n: any): string {
    return 'the draft of v' + (draft?.plannedVersion ?? '') + ' of the ' + packageKindLabel(draft?.kind) + ' submission of '
        + submissionReleaseLabel(n)
}

/** Text 17, with 17a, 17b or 17c for a held confirmation by the setting as the page read it. */
export function draftFinalizeConfirm (draft: any, n: any, secondConfirmer?: boolean | null): SubmissionConfirm {
    let text = 'Writes v' + (draft?.plannedVersion ?? '') + ' of the ' + packageKindLabel(draft?.kind)
        + ' submission of ' + submissionReleaseLabel(n) + ' from the build of ' + documentInstantText(draft?.build?.builtAt)
        + '. Nothing is generated again, and a Final version cannot be changed.'
    if (draft?.confirmation) {
        if (secondConfirmer === true) text += ' The confirmation by ' + confirmerName(draft) + ' is recorded first, then yours.'
        else if (secondConfirmer === false) {
            text += ' The organization no longer requires a second confirmer: the confirmation by ' + confirmerName(draft)
                + ' will not be recorded, and you are recorded alone.'
        } else {
            text += ' The second-confirmer setting could not be read: whether the confirmation by ' + confirmerName(draft)
                + ' is recorded follows the setting as it is now.'
        }
    }
    return { titleText: 'Finalize draft?', text, confirmButtonText: 'Finalize' }
}
export function draftConfirmConfirm (draft: any, n: any): SubmissionConfirm {
    return { titleText: 'Confirm draft?', text: 'Confirms ' + draftOf(draft, n) + ' as built on ' + documentInstantText(draft?.build?.builtAt)
        + '. A different person then finalizes it.', confirmButtonText: 'Confirm' }
}
export function draftDiscardConfirm (draft: any, n: any): SubmissionConfirm {
    return { titleText: 'Discard draft?', text: 'Discards ' + draftOf(draft, n)
        + '. Its generated documents are archived; the reason stays in the release history.', confirmButtonText: 'Discard draft' }
}
export function draftReturnConfirm (draft: any, n: any): SubmissionConfirm {
    return { titleText: 'Return to draft?', text: 'Returns ' + draftOf(draft, n) + ' to editing. The confirmation by '
        + confirmerName(draft) + ' is dropped.', confirmButtonText: 'Return to draft' }
}
