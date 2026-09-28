// Where an agent-board notification's reader acts (task RD2-15, sweep UI-30): the task the event is about and
// its board, from the payload the server wrote (AgentBoardEventPayload: board, task, link). Pure; a payload
// that is missing or malformed gives no links, never an error.

export interface InboxLinks {
    /** The task page, when the event is about a task. */
    task: string | null
    /** The board, within its organization's agents page. */
    board: string | null
}

function payloadOf (row: any): Record<string, any> | null {
    const raw = row?.payloadJson
    if (!raw) return null
    if (typeof raw === 'object') return raw
    try {
        const p = JSON.parse(raw)
        return p && typeof p === 'object' ? p : null
    } catch {
        return null
    }
}

/** An agent-board row's links: its task and its board. None for another kind of row. */
export function inboxLinksOf (row: any): InboxLinks {
    const none = { task: null, board: null }
    if (!String(row?.eventType ?? '').startsWith('AGENT_')) return none
    const p = payloadOf(row)
    if (!p) return none
    const org = row?.org ?? null
    return {
        task: p.task ? `/aiAgentTask/${p.task}` : null,
        board: p.board && org ? `/aiAgentsOfOrg/${org}?tab=boards&board=${p.board}` : null,
    }
}
