// Per-person approval state for the release approval matrix.
//
// The backend counts each voter once per requirement, by their latest vote on any of its roles,
// and refuses a second vote by the same person on the same requirement. The matrix used to key a
// cell by entry and role across everyone, so the first QA vote locked the QA cell for every other
// QA approver -- a requirement of two could not be met from the UI.

export type VoteState = 'APPROVED' | 'DISAPPROVED'

export interface MatrixApprovalEvent {
    approvalEntry: string
    approvalRoleId: string
    state: string
    wu?: { lastUpdatedBy?: string | null, actor?: string | null } | null
}

/**
 * Who cast a vote, as the backend reads it: the human behind a key when recorded, else the writer.
 * The GraphQL WhoUpdated does not expose the actor yet, so a vote cast through a CLI-session or
 * held FREEFORM key reads as the key's here; the backend still refuses a second vote by that person.
 */
export function voterOf(e: MatrixApprovalEvent): string | null {
    return e.wu?.actor || e.wu?.lastUpdatedBy || null
}

function isVote(state: string): state is VoteState {
    return state === 'APPROVED' || state === 'DISAPPROVED'
}

/** This user's own votes: entry -> role -> latest state. */
export function myVotes(events: MatrixApprovalEvent[] | null | undefined, me: string | null | undefined):
        Record<string, Record<string, VoteState>> {
    const out: Record<string, Record<string, VoteState>> = {}
    if (!events || !me) return out
    for (const e of events) {
        if (voterOf(e) !== me || !isVote(e.state)) continue
        if (!out[e.approvalEntry]) out[e.approvalEntry] = {}
        out[e.approvalEntry][e.approvalRoleId] = e.state
    }
    return out
}

/** How many other people's latest vote on an entry and role is each state: entry -> role -> counts. */
export function othersVoteCounts(events: MatrixApprovalEvent[] | null | undefined, me: string | null | undefined):
        Record<string, Record<string, Record<VoteState, number>>> {
    const latest = new Map<string, MatrixApprovalEvent>()
    let anonymous = 0
    for (const e of events || []) {
        const voter = voterOf(e)
        if (!isVote(e.state) || (voter && voter === me)) continue
        // A vote with no recorded voter predates voter tracking: it counts on its own.
        const key = voter ? `${e.approvalEntry}|${e.approvalRoleId}|${voter}` : `${e.approvalEntry}|${e.approvalRoleId}|#${anonymous++}`
        latest.set(key, e)
    }
    const out: Record<string, Record<string, Record<VoteState, number>>> = {}
    for (const e of latest.values()) {
        const byRole = out[e.approvalEntry] ?? (out[e.approvalEntry] = {})
        const counts = byRole[e.approvalRoleId] ?? (byRole[e.approvalRoleId] = { APPROVED: 0, DISAPPROVED: 0 })
        counts[e.state as VoteState]++
    }
    return out
}

/**
 * Whether this user's cell for {@code role} on an entry is closed to them because they already
 * voted -- saved, or picked and not yet saved -- on a requirement that allows the role: with that
 * role (the saved vote stands), or with another of the requirement's roles (one vote per
 * requirement).
 *
 * @param requirementRoles the role ids of each of the entry's requirements
 * @param saved this user's saved votes on the entry, role -> state
 * @param pending the matrix's current picks on the entry, role -> state or 'UNSET'
 */
export function isLockedByOwnVote(requirementRoles: string[][], saved: Record<string, string> | undefined,
        pending: Record<string, string> | undefined, role: string): boolean {
    if (saved?.[role]) return true
    return requirementRoles.some(roles => roles.includes(role)
        && roles.some(r => r !== role && (!!saved?.[r] || (!!pending?.[r] && pending[r] !== 'UNSET'))))
}
