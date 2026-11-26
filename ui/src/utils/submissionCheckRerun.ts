import { reactive } from 'vue'
import { artifactDownloadRef } from '@/utils/artifactDownload'
import { checkRerunHeader } from '@/utils/sbomScore'

// The re-run of a Final version's check (FDAW-16 3.9a): the engine report the version's Check report part
// stored and the report the engine gives now on the same pinned release SBOM, side by side. A read that
// stores nothing; the server needs the download right on the version's release and refuses anyone else.

export const RERUN_CHECK_BUTTON = 'Re-run check'
export const RERUN_CHECK_TITLE = 'Score the stored release SBOM of this version again and show both reports'

/** The query's answer: the two report strings and the checker that made each. */
export interface CheckRerun {
    storedChecker: string
    stored: string
    freshChecker: string
    fresh: string
}

/**
 * Whether a version offers the re-run: it holds a stored check, and its Check report part is one the page
 * offers to download (a produced part with a recorded artifact version), as the parts table's Download is.
 */
export function canRerunCheck (version: any): boolean {
    if (!version?.checkSummary) return false
    const part = (version?.parts ?? []).find((p: any) => p?.part === 'CHECK_REPORT')
    return Boolean(part && part.outcome === 'PRODUCED' && artifactDownloadRef(part.artifact, part.artifactVersion, part.fileName))
}

/**
 * The heading of the stored report's panel: its checker and the FDA checker rule set the report names
 * (FDAW-126 3.4); 'Stored' until the answer is in.
 */
export function storedHeading (checker: string | null | undefined, report: string | null | undefined): string {
    return checker ? checkRerunHeader('Stored', checker, report ?? '') : 'Stored'
}

/** The heading of the fresh report's panel, as the stored one's. */
export function freshHeading (checker: string | null | undefined, report: string | null | undefined): string {
    return checker ? checkRerunHeader('Now', checker, report ?? '') : 'Now'
}

/**
 * One request for both panels: each panel's load asks for its report, the first ask sends the query and the
 * other waits on the same answer; a failed answer is dropped, so a Retry asks again. The checkers and the
 * two reports are set when the answer arrives, for the headings.
 */
export function checkRerunLoader (fetch: (signal: AbortSignal) => Promise<CheckRerun | null | undefined>) {
    const checkers = reactive<{ stored: string | null, fresh: string | null }>({ stored: null, fresh: null })
    const reports = reactive<{ stored: string | null, fresh: string | null }>({ stored: null, fresh: null })
    let pending: Promise<CheckRerun> | null = null
    function both (signal: AbortSignal): Promise<CheckRerun> {
        if (pending === null) {
            const asked: Promise<CheckRerun> = fetch(signal).then((r) => {
                if (!r) throw new Error('the server returned no report')
                checkers.stored = r.storedChecker ?? null
                checkers.fresh = r.freshChecker ?? null
                reports.stored = r.stored ?? null
                reports.fresh = r.fresh ?? null
                return r
            })
            pending = asked
            asked.catch(() => { if (pending === asked) pending = null })
        }
        return pending
    }
    return {
        checkers,
        reports,
        loadStored: (signal: AbortSignal) => both(signal).then(r => r.stored),
        loadFresh: (signal: AbortSignal) => both(signal).then(r => r.fresh),
    }
}
