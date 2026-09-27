// A board's documents as the UI shows them (board-documents.md §5, task 36d0549e): inside their board,
// out of the org's component lists and pickers, and on a component page that speaks the document's
// language. Access is unchanged (board-permissions.md D18); this is presentation only.

/** The kinds the component lists and pickers show: software. DOCUMENT components are shown inside their board. */
export const SOFTWARE_KINDS = ['GENERIC', 'HELM']

export function isDocumentComponent (c: { kind?: string | null } | null | undefined): boolean {
    return c?.kind === 'DOCUMENT'
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

export interface BoardRef { uuid?: string | null, name?: string | null, taskPrefix?: string | null }

/** The banner on a document component's page: its board, or that no board owns it. */
export function documentBoardBanner (ref: BoardRef | null | undefined): { text: string, board: string | null } {
    if (!ref?.uuid) return { text: 'Document component, no board', board: null }
    const name = ref.name ?? ref.uuid
    return { text: `Belongs to board ${ref.taskPrefix ? ref.taskPrefix + ' · ' : ''}${name}`, board: ref.uuid }
}

export interface RoundRow {
    uuid: string
    round: number | null
    version: string
    lifecycle: string
    path: string
    task: string
    publishedAt: string | null
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
    openFindings: number | null
    checkVerdict: string | null
}

/** The board page's Documents section: one row per document series. */
export function documentSeriesRows (series: any[] | null | undefined): SeriesRow[] {
    return (series ?? []).map(s => ({
        specification: s.specification,
        label: String(s.specification ?? '').toLowerCase().replace(/_/g, ' '),
        component: s.component?.uuid ?? '',
        componentName: s.component?.name ?? s.component?.uuid ?? '',
        latest: s.latestRound ? `round ${s.latestRound.round ?? '?'} · ${s.latestRound.version ?? ''}`.trim() : '—',
        lifecycle: s.latestRound?.lifecycle ?? '',
        roundsCount: s.roundsCount ?? 0,
        openFindings: s.openFindings ?? null,
        checkVerdict: s.checkVerdict ?? null,
    }))
}
