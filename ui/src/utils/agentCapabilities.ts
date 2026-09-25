// The capability verbs the board form offers, as select options. Kept out of the component so a
// broken list is a failing spec rather than a select that renders blank (22d403c5 T-1).

export const CAPABILITIES = ['TRACKER_READ', 'TRACKER_WRITE', 'CODE_PUSH', 'PR_MERGE']

/** What a board may declare its coordinator covers: never the tracker verbs, which it always has. */
export const COORDINATOR_CAPABILITIES = ['CODE_PUSH', 'PR_MERGE']

export function toOptions (verbs: string[]): { label: string, value: string }[] {
    return verbs.map(v => ({ label: v, value: v }))
}
