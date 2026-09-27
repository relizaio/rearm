// Sessions and agents told apart (task RD2-11). Every session on one API key used to read as the key's
// notes ("agent board budget key"), because the page printed the agent's effectiveDisplayName, which
// falls back to the key's note. Here a session reads "<role> · <agent or session name> · <uuid8>", and
// the key's note comes last, only when nothing names the agent. Pure, so the rule is testable.

export interface AgentName {
    /** The agent's own name: its displayName, else its name. */
    name: string | null
    /** The bound key's note, when that is what effectiveDisplayName fell back to. */
    notes: string | null
}

/** An agent as the page names it: its own name, with the key's note apart. */
export function agentNameOf (agent: any): AgentName {
    const own = (agent?.displayName || agent?.name || '').trim() || null
    const effective = (agent?.effectiveDisplayName || '').trim() || null
    return { name: own, notes: effective && effective !== own ? effective : null }
}

/** Every agent of the organization by uuid, for the pages that name sessions. */
export function agentDirectory (agents: any[] | null | undefined): Record<string, AgentName> {
    const out: Record<string, AgentName> = {}
    for (const a of agents ?? []) if (a?.uuid) out[a.uuid] = agentNameOf(a)
    return out
}

export interface SessionLabelInput {
    uuid: string | null | undefined
    role?: string | null
    /** The session's title, else the agent's own name. */
    name?: string | null
    /** The key's note: shown last, and only when nothing else names the session. */
    notes?: string | null
}

/** "coder · claude-code · 3b6fa5e8"; "3b6fa5e8 · agent board budget key" when only the key's note names it. */
export function sessionLabel (s: SessionLabelInput): string {
    const id = String(s.uuid ?? '').slice(0, 8)
    const name = (s.name ?? '').trim()
    const parts = [s.role ?? '', name, id, name ? '' : (s.notes ?? '').trim()]
    return parts.filter(p => !!p).join(' · ') || 'session'
}

/** A session named by an agent directory entry, with the role it worked as. */
export function sessionOf (uuid: string | null | undefined, agent: string | null | undefined,
    agents: Record<string, AgentName> | null | undefined, role?: string | null): SessionLabelInput {
    const a = agent ? agents?.[agent] : undefined
    return { uuid, role: role ?? null, name: a?.name ?? null, notes: a?.notes ?? null }
}

/** An actor of kind SESSION as a session reference; the actor's name is what the server recorded. */
export function sessionOfActor (actor: any, role?: string | null): SessionLabelInput | null {
    if (actor?.kind !== 'SESSION' || !actor?.uuid) return null
    return { uuid: actor.uuid, role: role ?? null, name: null, notes: actor.name ?? null }
}

/** Where a session's page is. */
export function sessionPath (uuid: string): string {
    return `/aiAgentSession/${uuid}`
}

/** The role a session last worked a task as: its open assignment, else its newest sign-off or return. */
export function roleOfSession (task: any, session: string): string | null {
    if (task?.assignment?.session === session) return task.assignment.role ?? null
    let role: string | null = null
    let at = ''
    for (const so of task?.signOffs ?? []) {
        if (so?.session === session && String(so.signedOffAt ?? '') >= at) { role = so.role ?? null; at = String(so.signedOffAt ?? '') }
    }
    for (const r of task?.returns ?? []) {
        if (r?.session === session && String(r.returnedAt ?? '') >= at) { role = r.role ?? null; at = String(r.returnedAt ?? '') }
    }
    return role
}

/** An agent in one line: its own name and short id, the key's note only when it has no name. */
export function agentLabel (agent: string, agents: Record<string, AgentName> | null | undefined): string {
    const a = agents?.[agent]
    const id = String(agent ?? '').slice(0, 8)
    if (a?.name) return `${a.name} · ${id}`
    return a?.notes ? `${id} · ${a.notes}` : id
}

/** The agent-name map the task surfaces take: the agent's own name first, else the key's note, else its id. */
export function agentNamesOf (agents: any[] | null | undefined): Record<string, string> {
    const out: Record<string, string> = {}
    for (const a of agents ?? []) {
        if (!a?.uuid) continue
        const n = agentNameOf(a)
        out[a.uuid] = n.name || n.notes || a.uuid.slice(0, 8)
    }
    return out
}
