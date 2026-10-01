// A board's documents as the UI shows them (board-documents.md §5, task 36d0549e): inside their board,
// out of the org's component lists and pickers, and on a component page that speaks the document's
// language. Access is unchanged (board-permissions.md D18); this is presentation only.
import { lifecycleWord, specWord } from './agentWords'

/** The kinds the component lists and pickers show: software. BOARD_DOCUMENT components are shown inside their board. */
export const SOFTWARE_KINDS = ['GENERIC', 'HELM']

/**
 * The componentKinds the most-recent-releases widget sends (RD4-10): the software kinds, so a board's
 * document rounds stay off the home page; null, every kind, for a caller that wants the rounds too.
 */
export function recentReleasesKinds (includeDocuments: boolean): string[] | null {
    return includeDocuments ? null : SOFTWARE_KINDS
}

export function isDocumentComponent (c: { kind?: string | null } | null | undefined): boolean {
    return c?.kind === 'BOARD_DOCUMENT'
}

/**
 * A branch's dependencies split into software and the board document components a target's base
 * branch carries from before boards owned them: the documents go in a collapsed group at the end.
 */
export function splitDocumentDependencies<T extends { component?: { kind?: string | null } | null }> (deps: T[] | null | undefined):
        { software: T[], documents: T[] } {
    const software: T[] = []
    const documents: T[] = []
    for (const d of deps ?? []) (isDocumentComponent(d.component) ? documents : software).push(d)
    return { software, documents }
}

export interface BoardRef { uuid?: string | null, name?: string | null, taskPrefix?: string | null, readable?: boolean | null }

/** The banner on a document component's page: its board, or that no board owns it. */
export function documentBoardBanner (ref: BoardRef | null | undefined): { text: string, board: string | null } {
    if (!ref?.uuid) return { text: 'Document component, no board', board: null }
    const name = ref.name ?? ref.uuid
    const text = `Belongs to board ${ref.taskPrefix ? ref.taskPrefix + ' · ' : ''}${name}`
    // The component reads under its own permission, the board under the board's (RD2-9): link only to a
    // board the person may open, and say so otherwise rather than link to a refusal.
    if (ref.readable !== true) return { text: `${text} (board not visible to you)`, board: null }
    return { text, board: ref.uuid }
}

export interface RoundRow {
    uuid: string
    round: number | null
    version: string
    lifecycle: string
    path: string
    task: string
    /** The task's uuid, for its link; empty when the round names none. */
    taskUuid: string
    publishedAt: string | null
}

/** The Documents tab's Latest: "round 1 · v0", the version labelled (RD2-24). */
export function latestLabel (latest: { round?: number | null, version?: string | null } | null | undefined): string {
    if (!latest) return '—'
    return `round ${latest.round ?? '?'}` + (latest.version ? ` · v${latest.version}` : '')
}

export interface ElementCheckCounts { pass: number, fail: number, skip: number, blockingFailed: number }

/**
 * The verdict of a check report from its counts, the one rule the task page and the Documents tab share
 * (RD2-24): FAIL when a blocking check failed, WARN when only a non-blocking one did, PASS otherwise. The
 * server's elementCheckVerdict is read from its counts by the same rule.
 */
export function elementCheckVerdictOf (c: ElementCheckCounts): 'FAIL' | 'WARN' | 'PASS' {
    if (c.blockingFailed > 0) return 'FAIL'
    return c.fail > 0 ? 'WARN' : 'PASS'
}

/** "7 pass · 1 fail · 2 skip", as the task page words it; the verdict beside it; '—' when unchecked. */
export function elementChecksLine (counts: ElementCheckCounts | null | undefined, fallbackVerdict?: string | null): { line: string, verdict: string | null } {
    if (!counts) return { line: fallbackVerdict ?? '—', verdict: fallbackVerdict ?? null }
    return { line: `${counts.pass} pass · ${counts.fail} fail · ${counts.skip} skip`, verdict: elementCheckVerdictOf(counts) }
}

/**
 * A document series' rounds, newest first: round, version, state, path, the task by its key (its
 * uuid's first eight when the key is not known), published date.
 */
export function documentRoundRows (releases: any[] | null | undefined, taskKeys: Record<string, string> = {}): RoundRow[] {
    return (releases ?? [])
        .filter(r => r?.document)
        .map(r => ({
            uuid: r.uuid,
            round: r.document.round ?? null,
            version: r.version ?? '',
            lifecycle: r.lifecycle ?? '',
            path: r.document.path ?? '',
            task: r.document.task ? (taskKeys[r.document.task] ?? String(r.document.task).slice(0, 8)) : '',
            taskUuid: r.document.task ?? '',
            publishedAt: r.createdDate ?? null,
        }))
        .sort((a, b) => (b.publishedAt ?? '').localeCompare(a.publishedAt ?? ''))
}

export interface SeriesRow {
    specification: string
    label: string
    component: string
    componentName: string
    latest: string
    lifecycle: string
    roundsCount: number
    openReviewItems: number | null
    elementCheckVerdict: string | null
    /** "7 pass · 1 fail · 2 skip", or the verdict alone from a server without counts, or '—'. */
    elementChecks: string
}

/** The board page's Documents section: one row per document series. */
export function documentSeriesRows (series: any[] | null | undefined): SeriesRow[] {
    return (series ?? []).map(s => ({
        specification: s.specification,
        label: specWord(s.specification),
        component: s.component?.uuid ?? '',
        componentName: s.component?.name ?? s.component?.uuid ?? '',
        latest: latestLabel(s.latestRound),
        lifecycle: lifecycleWord(s.latestRound?.lifecycle),
        roundsCount: s.roundsCount ?? 0,
        openReviewItems: s.openReviewItems ?? null,
        elementCheckVerdict: s.elementCheckCounts ? elementCheckVerdictOf(s.elementCheckCounts) : s.elementCheckVerdict ?? null,
        elementChecks: elementChecksLine(s.elementCheckCounts, s.elementCheckVerdict).line,
    }))
}

export interface DocumentRoundView {
    specification: string
    round: number | null
    path: string
    taskLabel: string | null
    taskPath: string | null
    boardPath: string | null
    /** "2 open · 5 items" for an index round; null for prose. */
    reviewItems: string | null
    elementChecks: { line: string, verdict: string | null } | null
    elementsCount: number | null
}

/**
 * A round's release page (RD2-24): what the round is, where it sits, and what it said -- instead of the
 * software layout. The task comes from the round's own read of it (key, board, and its BOARD_ELEMENT_CHECK_REPORT rounds);
 * without that read the task is named by its uuid's first eight.
 */
export function documentRoundView (release: any, task: any, orgUuid: string | null | undefined,
    elementChecks: ElementCheckCounts | null): DocumentRoundView | null {
    const d = release?.document
    if (!d) return null
    const items: any[] = d.reviewItems?.reviewItems ?? []
    const open = items.filter(f => f?.status === 'OPEN').length
    return {
        specification: specWord(d.specification),
        round: d.round ?? null,
        path: d.path ?? '',
        taskLabel: d.task ? (task?.key ?? String(d.task).slice(0, 8)) : null,
        taskPath: d.task ? `/aiAgentTask/${d.task}` : null,
        boardPath: task?.board && orgUuid ? `/aiAgentsOfOrg/${orgUuid}?tab=boards&board=${task.board}` : null,
        reviewItems: d.reviewItems ? `${open} open · ${items.length} item${items.length === 1 ? '' : 's'}` : null,
        elementChecks: elementChecks ? elementChecksLine(elementChecks) : null,
        elementsCount: d.elements?.elements ? d.elements.elements.length : null,
    }
}
