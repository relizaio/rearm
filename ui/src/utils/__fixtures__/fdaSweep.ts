// The sweep of FDAW-78 architecture section 4, for the specs of its rows 11 to 18. A sweep is: none of
// the four expressions matches the text. Every sweep is paired, in the same test, with its control
// (expectSweepControl): the four expressions each match the control literal, so a broken expression
// fails the test. The expressions are written here as the design writes them, never imported from
// production code.
import { expect } from 'vitest'

export const UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i
export const ENUM_RE = /\b[A-Z][A-Z0-9]+(_[A-Z0-9]+)+\b/
export const WORD_RE = /\b(REQUIRED|OPTIONAL|PENDING|DRAFT|ASSEMBLED|REJECTED|FINAL|SENT|AMENDED|SUPERSEDED|WITHDRAWN|CANCELLED|ATTESTED|ADDED|REMOVED|CHANGED|UPDATED|SUBMISSION|LIFECYCLE|ARTIFACT|NARRATIVE|ORGANIZATION|PERSPECTIVE|COMPONENT|ENABLED|DISABLED|INCLUDE|EXCLUDE|MANUAL|SUPPLIER|ENRICHED|RULE|DEFAULT|OTHER)\b/
export const INSTANT_RE = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

export const SWEEP_CONTROL = 'REQUIRED 3bef7cde-2edb-47cb-aa4e-94487c2cdced NO_COMMITTED_END_OF_SUPPORT 2026-11-12T08:43:00.873899189Z'

/** The four expressions match the control literal, and WORD_RE each of DRAFT, ORGANIZATION and NARRATIVE alone. */
export function expectSweepControl (): void {
    for (const re of [UUID_RE, ENUM_RE, WORD_RE, INSTANT_RE]) expect(SWEEP_CONTROL, String(re)).toMatch(re)
    for (const w of ['DRAFT', 'ORGANIZATION', 'NARRATIVE']) expect(w).toMatch(WORD_RE)
}

/** None of the four expressions matches the text; the control runs in the same call. */
export function expectSwept (text: string, label = ''): void {
    expectSweepControl()
    for (const re of [UUID_RE, ENUM_RE, WORD_RE, INSTANT_RE]) expect(text, label + ' ' + String(re)).not.toMatch(re)
}
