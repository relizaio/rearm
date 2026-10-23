// TPF-78 (TPF-73 part 2): pure helpers behind the component page's selection mode and its
// "Archive selected" action over the archiveBranches mutation. No Vue, no store: ComponentView
// wires them, branchBulkArchive.spec.ts runs them.

export const BULK_ARCHIVE_CHUNK = 200           // = BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS
export type BulkWords = { branch: string, branchFirstUpper: string }
export type BranchArchiveOutcome = 'ARCHIVED' | 'SKIPPED_BASE' | 'SKIPPED_ARCHIVED' | 'NOT_FOUND' | 'FORBIDDEN' | 'FAILED'
export type BranchArchiveResult = { branchUuid: string, outcome: BranchArchiveOutcome, message?: string | null }
export type BulkArchiveSummary = { type: 'success' | 'warning' | 'error', title: string, text: string }

/** The lower-case plural of the page's branch word ("branches", "feature sets"). */
export function pluralOf (words: BulkWords): string {
    return words.branch === 'branch' ? 'branches' : words.branch + 's'
}

/** The base branch is never selectable; the server would skip it anyway. */
export function selectableBranch (row: { type?: string }): boolean {
    return row.type !== 'BASE'
}

/** The uuids of the selectable rows, in list order ("Select all"). */
export function selectableKeys (rows: { uuid: string, type?: string }[]): string[] {
    return rows.filter(selectableBranch).map((r) => r.uuid)
}

/**
 * The no-undo sentence ending every bulk archive confirmation. There is no user-lane unarchive;
 * only the programmatic lane (a release or version from CI) or, for a component, a CycloneDX
 * import naming the branch brings an archived branch back. A release added by hand does not.
 */
export function NO_UNDO_SENTENCE (words: BulkWords): string {
    if (words.branch === 'branch') {
        return 'Archived branches leave this list and cannot be restored from ReARM today; only a new release or version for a branch from CI (an API key), or a CycloneDX import naming it, brings it back (a release added by hand does not).'
    }
    return `Archived ${pluralOf(words)} leave this list and cannot be restored from ReARM today; only a new release or version for a ${words.branch} from CI (an API key) brings it back (a release added by hand does not).`
}

/** The confirmation text: the count, the first max names, then the no-undo sentence. */
export function confirmText (names: string[], words: BulkWords, max = 10): string {
    const n = names.length
    const listed = names.slice(0, max).map((name) => `"${name}"`).join(', ')
    const more = n > max ? ` and ${n - max} more` : ''
    return `Archive ${n} ${n === 1 ? words.branch : pluralOf(words)}? ${listed}${more}. ${NO_UNDO_SENTENCE(words)}`
}

const DROPPED_OUTCOMES = new Set<string>(['ARCHIVED', 'SKIPPED_BASE', 'SKIPPED_ARCHIVED'])
const FAILED_GROUPS: [string, string][] = [['NOT_FOUND', 'Not found'], ['FORBIDDEN', 'Not permitted']]

/** One notification for a whole run, the rows named; counts come from the list, not the server. */
export function summarizeOutcomes (results: BranchArchiveResult[], rowsByUuid: Map<string, { name?: string }>, words: BulkWords): BulkArchiveSummary {
    const nameOf = (uuid: string): string => rowsByUuid.get(uuid)?.name || uuid
    const namesWith = (outcome: string): string[] => results.filter((r) => r.outcome === outcome).map((r) => nameOf(r.branchUuid))
    const archived = namesWith('ARCHIVED')
    const sentences: string[] = []
    if (archived.length) sentences.push(`Archived: ${archived.join(', ')}.`)
    const skippedBase = namesWith('SKIPPED_BASE')
    if (skippedBase.length) sentences.push(`Skipped (base ${words.branch}): ${skippedBase.join(', ')}.`)
    const skippedArchived = namesWith('SKIPPED_ARCHIVED')
    if (skippedArchived.length) sentences.push(`Skipped (already archived): ${skippedArchived.join(', ')}.`)
    let failedCount = 0
    for (const [outcome, label] of FAILED_GROUPS) {
        const names = namesWith(outcome)
        failedCount += names.length
        if (names.length) sentences.push(`${label}: ${names.join(', ')}.`)
    }
    // FAILED carries a message written for a person; an outcome this UI does not know is
    // reported here too, with its raw value, never dropped.
    for (const r of results) {
        if (DROPPED_OUTCOMES.has(r.outcome) || FAILED_GROUPS.some(([o]) => o === r.outcome)) continue
        failedCount += 1
        const why = r.outcome === 'FAILED' ? r.message : r.outcome
        sentences.push(why ? `Failed: ${nameOf(r.branchUuid)} (${why}).` : `Failed: ${nameOf(r.branchUuid)}.`)
    }
    const title = `${archived.length} ${archived.length === 1 ? words.branch : pluralOf(words)} archived`
    const type = failedCount === 0 ? 'success' : (archived.length > 0 ? 'warning' : 'error')
    return { type, title, text: sentences.join(' ') }
}

/** The next selection: the rows that were not archived and are still listed, in results order. */
export function keepFailedSelected (results: BranchArchiveResult[], rows: { uuid: string }[]): string[] {
    const listed = new Set(rows.map((r) => r.uuid))
    return results.filter((r) => !DROPPED_OUTCOMES.has(r.outcome) && listed.has(r.branchUuid)).map((r) => r.branchUuid)
}

/** The selection in calls of at most size uuids, in order. */
export function chunk (uuids: string[], size = BULK_ARCHIVE_CHUNK): string[][] {
    const batches: string[][] = []
    for (let i = 0; i < uuids.length; i += size) batches.push(uuids.slice(i, i + size))
    return batches
}

/** After a thrown call: the failing batch (index sent) and every later batch, in order. */
export function unsentAfter (batches: string[][], sent: number): string[] {
    return batches.slice(sent).flat()
}
