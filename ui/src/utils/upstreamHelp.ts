// The explanations behind the info icons of the upstream pages (ReARM Pro): what a version line, a
// depth, the drift behaviour, the update state, a one-off and each run counter mean. Text only; the
// enum words themselves stay in upstreamLabels.ts.

export const VERSION_LINE_HELP = 'A version line is a series of upstream releases that share the leading numbers of their '
    + 'version, for example 2.25.0, 2.25.1 and 2.25.2 on line 2.25. Each line gets its own branch here (named like 2.25.x, '
    + 'a feature set on a product), and its releases are ordered by version on it. Releases that fit no line go to the '
    + 'base branch.'

export const DEPTH_HELP = 'How many leading version numbers make a line: at depth 1 the versions 26.11.10 and 26.10.2 are '
    + 'both on line 26, at depth 2 they are on lines 26.11 and 26.10. Depth 0 means no lines: every release goes to the '
    + 'base branch. ReARM proposes a depth from the release history when you follow an item; an administrator can '
    + 'choose another, and under Adapt ReARM may change it after drift.'

export const VERSION_LINES_AT_DEPTH_HELP = `${VERSION_LINE_HELP} ${DEPTH_HELP}`

export const LINES_COUNT_HELP = 'The number of version lines ingested for this component so far: each line is a branch '
    + 'of the component here.'

export const RELEASE_LINE_HELP = 'The version line this release was placed on, and so the branch it is on here. Empty '
    + 'when it went to the base branch or was not placed (held, skipped or failed).'

export const ON_DRIFT_HELP = 'What happens when a sync meets a later release that does not fit the version lines (drift): '
    + 'Stop holds it as pending until an administrator reconfigures the follow; Adapt works out a new depth from the '
    + 'release history and goes on with it, and holds the release only when the history gives no clear answer. Either '
    + 'way an alert tells the administrators.'

export const UPDATE_STATE_HELP: Record<string, string> = {
    UPDATED: 'An active follow of an active source covers this, so every sync of the source brings in its new '
        + 'releases and new documents.',
    NO_LONGER_UPDATED: 'No active follow of an active source covers this any more (the follow was ended or the source '
        + 'archived). What was ingested is kept, but nothing new comes in.',
}

export const ONE_OFF_HELP = 'A one-off is a single release ingested by its TEI on the By TEI tab, without following its '
    + 'product. Syncs do not touch it: only a refresh of the one-off, or a follow of its product (which takes the '
    + 'release over), updates it.'

export const TEI_SOURCE_HELP = 'Which source\'s credential the TEI is resolved with. Any source that matches resolves it '
    + 'anonymously, then matches the servers it leads to against the roots of your active sources: one match is '
    + 'ingested, several let you choose, none is refused. Choose a source when its TEA server asks for authentication '
    + 'even to resolve a TEI; the match is still made against all active sources.'

/** The counters of a run in the order they are listed, with their label and explanation. */
export const RUN_COUNTERS: ReadonlyArray<{ key: string, label: string, help: string }> = [
    { key: 'releasesInScope', label: 'Releases in scope', help: 'Upstream releases inside the follow\'s scope for this run: from the starting point on, as the depth places them. A one-off run has 1.' },
    { key: 'created', label: 'Created', help: 'Releases this run created here.' },
    { key: 'adopted', label: 'Adopted', help: 'Releases that already existed here with the same version and that this run linked to the upstream release instead of creating them; they keep their branch, lifecycle and artifacts.' },
    { key: 'alreadyIngested', label: 'Already ingested', help: 'Releases in scope that an earlier run had already ingested; this run left them as they were.' },
    { key: 'skippedPreReleases', label: 'Pre-releases skipped', help: 'Pre-releases inside the scope that were not ingested: only final releases are.' },
    { key: 'failed', label: 'Failed', help: 'Releases this run could not ingest, and on a sync the ingested releases whose re-read failed. The Releases section shows why; the next run tries again.' },
    { key: 'failedComponentRefs', label: 'Component references failed', help: 'References of product releases to component releases that could not be resolved or linked. The next run tries again.' },
    { key: 'documentsPending', label: 'Documents pending', help: 'Releases ingested with some of their documents still to be stored. The next run tries again.' },
    { key: 'filesStored', label: 'Files stored', help: 'Documents of the collection versions this run committed that were stored here, as an SBOM or as a file.' },
    { key: 'filesNotKept', label: 'Files not kept', help: 'Documents of the collection versions this run committed that were not stored; the release\'s ingested documents say why.' },
    { key: 'bytes', label: 'Bytes downloaded', help: 'How much this run downloaded from the source.' },
    { key: 'checked', label: 'Checked again', help: 'Releases ingested earlier that a sync read again from the source, to find new documents, changes and releases gone upstream.' },
    { key: 'newCollectionVersions', label: 'New collection versions', help: 'Later collection versions (new or changed documents) committed for releases ingested earlier.' },
    { key: 'gone', label: 'Gone upstream', help: 'Releases this run marked as no longer listed by the source. What was ingested is kept.' },
    { key: 'repaired', label: 'Repaired', help: 'Artifacts and parent links a sync put back on ingested releases after they went missing here.' },
    { key: 'held', label: 'Held', help: 'Releases held because they do not fit the version lines; they are listed as pending on the follow.' },
]

const BYTES_PER_MB = 1024 * 1024

/**
 * A byte count in megabytes with thousands separators and one decimal, for example 1,234.5 MB (1 MB = 1024 * 1024
 * bytes, as the document sizes of the snapshot). Zero is 0 MB; a count below a tenth of a megabyte is < 0.1 MB.
 */
export function megabytesOf (bytes: number | null | undefined): string {
    const n = typeof bytes === 'number' && Number.isFinite(bytes) && bytes > 0 ? bytes : 0
    if (n === 0) return '0 MB'
    const mb = n / BYTES_PER_MB
    if (mb < 0.1) return '< 0.1 MB'
    return mb.toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' MB'
}

/** The exact byte count with thousands separators, for the tooltip of a megabyte value. */
export function bytesExactly (bytes: number | null | undefined): string {
    const n = typeof bytes === 'number' && Number.isFinite(bytes) && bytes > 0 ? Math.round(bytes) : 0
    return n.toLocaleString('en-US') + ' bytes'
}

/** A run counter's value as shown: the byte count in megabytes, every other count as a number. */
export function counterValueOf (key: string, counters: Record<string, unknown> | null | undefined): string {
    const v = counters ? counters[key] : undefined
    if (key === 'bytes') return megabytesOf(typeof v === 'number' ? v : 0)
    return String(typeof v === 'number' ? v : 0)
}
