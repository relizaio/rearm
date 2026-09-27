// The human-review box at a gate (task RD2-25): what the gated hop left blocking and what the person
// must decide before approving past it. Pure, so the rule the buttons follow is tested on data.
//
// The server's rule: an approval of a REJECTED gated sign-off is refused while an item of that hop's
// findings index is OPEN and blocks on the board, unless the verdict decides it. A person moves a
// task past a blocking finding only by deciding it.

export type GateAction = 'ACCEPT' | 'DISMISS'
export interface GateDecision { action: GateAction | null, reason: string }

/** The gated hop: the last sign-off of the role the gate holds. */
export function gatedSignOff (task: any): any | null {
    const role = task?.hold?.gateRole
    const offs = (task?.signOffs ?? []).filter((s: any) => s?.role === role)
    return offs.length ? offs[offs.length - 1] : null
}

/** Whether the gated hop rejected: the box leads with sending it back. */
export function gateRejected (task: any): boolean {
    return task?.hold?.kind === 'HUMAN_GATE' && gatedSignOff(task)?.outcome === 'REJECTED'
}

/**
 * The gated hop's findings still OPEN that block on this board, as the server reads them: its
 * outputs' findings indexes; corrections never block; no blocking priority (strict) means every
 * open item blocks, else those at or above it (a lower number), and an unprioritised one blocks.
 */
export function gateBlockingFindings (task: any, board: any): any[] {
    const outputs = new Set<string>(gatedSignOff(task)?.outputs ?? [])
    const bp = board?.blockingPriority ?? null
    const out: any[] = []
    for (const d of task?.documents ?? []) {
        if (!outputs.has(d?.uuid)) continue
        for (const f of d?.document?.findings?.findings ?? []) {
            if (f?.status !== 'OPEN' || f?.correction) continue
            if (bp == null || f.priority == null || f.priority <= bp) out.push(f)
        }
    }
    return out
}

/** The findings the person has not yet decided, each needing an action and a reason. */
export function undecided (blocking: any[], decisions: Record<string, GateDecision>): any[] {
    return blocking.filter(f => !decisions[f.id]?.action || !decisions[f.id]?.reason?.trim())
}

/** The decisions to send with the verdict, one per blocking finding, in the findings' order. */
export function gateDecisions (blocking: any[], decisions: Record<string, GateDecision>): any[] {
    return blocking.filter(f => decisions[f.id]?.action).map(f => ({
        action: decisions[f.id].action, findingId: f.id, resolution: decisions[f.id].reason.trim(),
    }))
}

const priorityOf = (f: any) => f?.priority == null ? '' : ` (P${f.priority})`
const done = (a: GateAction | null) => a === 'ACCEPT' ? 'accepted' : a === 'DISMISS' ? 'dismissed' : 'undecided'

/** The approve confirmation: "Approving past F-1 (P1): accepted · F-2 (P1): dismissed". */
export function approveConfirm (blocking: any[], decisions: Record<string, GateDecision>): string {
    return 'Approving past ' + blocking.map(f => `${f.id}${priorityOf(f)}: ${done(decisions[f.id]?.action ?? null)}`).join(' · ')
}

/** The approve button's label; never "pass" on a rejection. */
export function approveLabel (task: any, rejected: boolean, blocking: any[], correction: boolean): string {
    if (rejected) return blocking.length ? 'Approve past the findings' : 'Approve anyway'
    return correction ? 'Approve with correction' : `Approve ${task?.hold?.gateRole ?? ''} pass`.replace('  ', ' ')
}

/** The reject button's label: on a rejection it leads, and says what it does. */
export function rejectLabel (rejected: boolean, withFinding: boolean): string {
    if (rejected) return withFinding ? 'Reject (send back) with finding' : 'Reject (send back)'
    return withFinding ? 'Reject with finding' : 'Reject'
}
