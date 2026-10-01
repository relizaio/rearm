// The board's Agents tab (task RD3-5): who works the board, what each is doing, what it cost and whether it is
// stale. The server builds the rows in their order (open sessions first, then closed, each by last activity);
// these are the words and figures the table shows. Pure, so the specs need no store.
import { sessionPath } from './agentSessionLabel'
import { taskPagePath, ts } from './agentTaskFormat'
import { actorLabel } from './agentActors'

/** The state as the table says it: WORKING RD3-5 since …, waiting since …, idle since …, closed by … . */
export function stateWords (state: any): string {
    const since = state?.since ? ts(state.since) : null
    switch (state?.kind) {
    case 'WORKING':
        return `working ${state.taskKey ?? 'a task'}` + (since ? ` since ${since}` : '')
    case 'WAITING':
        return 'waiting for work' + (since ? ` since ${since}` : '')
    case 'IDLE':
        return 'idle' + (since ? ` since ${since}` : '')
    case 'CLOSED': {
        const by = actorLabel(state.closedBy)
        return 'closed' + (by ? ` by ${by}` : '') + (since ? ` at ${since}` : '')
    }
    default:
        return '—'
    }
}

/** A naive-ui tag type per state. */
export function stateTagType (kind: string | null | undefined): 'success' | 'info' | 'default' | 'warning' {
    return kind === 'WORKING' ? 'success' : kind === 'WAITING' ? 'info' : kind === 'IDLE' ? 'warning' : 'default'
}

/** The cache share as a percentage, "—" when none was counted. */
export function cacheSharePercent (share: number | null | undefined): string {
    if (share === null || share === undefined || Number.isNaN(share)) return '—'
    return `${Math.round(share * 100)}%`
}

/** The raw counts behind the share, for its hover. */
export function cacheShareTitle (tokens: any): string | null {
    if (!tokens) return null
    const input = Number(tokens.inputTokens ?? 0)
    const read = Number(tokens.cacheReadTokens ?? 0)
    const write = Number(tokens.cacheWriteTokens ?? 0)
    return `${read.toLocaleString('en-US')} cache read of ${(input + read + write).toLocaleString('en-US')}`
        + ` (input ${input.toLocaleString('en-US')}, cache write ${write.toLocaleString('en-US')})`
}

/** The stale mark's hover: each rule the row breaches, with the ALERT it posts. */
export function staleTitle (stale: any[] | null | undefined): string | null {
    if (!stale?.length) return null
    return stale.map(s => `${s.rule}: ${s.message}`).join('\n')
}

/** Where the row's links go: the session page, and the task it works when WORKING. */
export function agentLinks (row: any): { session: string | null, task: string | null } {
    return {
        session: row?.session ? sessionPath(row.session) : null,
        task: row?.state?.kind === 'WORKING' && row.state.taskUuid ? taskPagePath(row.state.taskUuid) : null,
    }
}

/** The agent's name, else the short session id. */
export function agentLabel (row: any): string {
    return row?.agentName || (row?.session ? String(row.session).slice(0, 8) : '—')
}

/** Why the Agents table could not be read, in its place. */
export function agentsErrorText (e: any): string {
    const why = String(e?.message ?? e ?? '').replace(/^GraphQL error:\s*/, '').trim()
    return why ? `Could not read the agents: ${why}` : 'Could not read the agents.'
}
