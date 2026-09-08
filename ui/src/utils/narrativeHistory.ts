// Rendering for FDA_NARRATIVE release update events.

/**
 * The marker the backend appends when it excerpts a narrative into an update event.
 *
 * ReleaseData.narrativeExcerpt stores at most 200 characters and, when it truncates, appends
 * "... (N characters)" with the TRUE length. Anything longer than that is not in the event
 * at all -- the full text lives on the release.
 */
const EXCERPT_MARKER = /\.\.\. \((\d+) characters\)$/

/**
 * The excerpt length the backend cuts at, mirroring ReleaseData.NARRATIVE_EVENT_EXCERPT_MAX.
 *
 * Load-bearing for the guard below, not decoration: the marker is appended ONLY when the
 * narrative exceeded this, so a short narrative that legitimately ENDS in something
 * marker-shaped -- prose quoting an earlier history row, say -- must be measured by its own
 * length rather than believed. Without the guard that narrative reports 42 characters.
 */
const EXCERPT_MAX = 200

/**
 * How long the narrative actually was, given what the event carries.
 *
 * THE POINT OF THIS FUNCTION: the event value is an EXCERPT, so its own `.length` is 200-ish
 * for every narrative longer than that. Reporting it would tell an auditor that a 9,000
 * character justification was 218 characters -- a specific, plausible, wrong number in the
 * one place someone goes to reconstruct what happened. The marker carries the real length;
 * use it when present and fall back to the string only when the value was stored whole.
 */
export function narrativeLength (eventValue: string | null | undefined): number {
    if (!eventValue) return 0
    const m = EXCERPT_MARKER.exec(eventValue)
    // Both conditions. A value at or under the cut was stored WHOLE, so any marker in it is
    // the author's own text and not ours.
    return (m && eventValue.length > EXCERPT_MAX) ? Number(m[1]) : eventValue.length
}

/** Thousands separators, so a five-digit count is readable at a glance in a table. */
function count (n: number): string {
    return `${n.toLocaleString('en-US')} character${n === 1 ? '' : 's'}`
}

/**
 * One history row for a narrative change.
 *
 * NEVER THE TEXT, only what happened and how big it was. Two reasons, and the second is the
 * load-bearing one:
 *
 *   - the event holds an excerpt, so rendering it would show a sentence that stops
 *     mid-word and read as data corruption rather than as a deliberate summary;
 *   - the history table is a dense list of every change to a release. Pasting paragraphs of
 *     regulatory prose into a cell there makes the surrounding rows unreadable, and the full
 *     current text is one panel away on the same page.
 *
 * "cleared" is deliberately not "removed": clearing the override returns the release to
 * INHERITING the org default, so the document does not lose its justification section. An
 * auditor reading "removed" would reasonably conclude the opposite.
 */
export function formatNarrativeChange (
    oldValue: string | null | undefined,
    newValue: string | null | undefined
): string {
    const before = narrativeLength(oldValue)
    const after = narrativeLength(newValue)
    if (!before && after) return `narrative set (${count(after)})`
    if (before && !after) return `narrative cleared, release returns to the org default (was ${count(before)})`
    if (before && after) return `narrative changed (${count(before)} -> ${count(after)})`
    return 'narrative unchanged'
}
