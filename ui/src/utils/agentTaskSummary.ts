// What the task drawer shows as its preview (gaps §1.26): enough for a person to decide whether to
// open the task page, and nothing that needs a page to read -- counts rather than tables, one line
// per concern.
import { DocumentRelease, Finding, INDEXED_TYPES, latestRound } from './agentDocuments'
import { costLabel, formatTokens, totalTokens } from './agentUsage'
import { agentName, dur, roleName, ts } from './agentTaskFormat'
import { aboutLabel, latestQuestionRound } from './agentQuestionRounds'

export type TaskSummary = {
    /** Open findings of the newest round of each indexed type, counted by priority, P1 first. */
    openFindings: { priority: number | null, count: number }[]
    openQuestions: number
    /**
     * The open questions in one line: how many, who asked, in which round, about what (gaps
     * §1.27). Null when none is open.
     */
    questions: string | null
    /** The newest release of each document type, in the order they were first produced. */
    latestDocuments: DocumentRelease[]
    dependencies: { done: number, pending: number, blocks: number }
    assignment: string | null
    usage: string | null
}

function openQuestionsLine (task: any, roles: any[] | null | undefined, stack: any[]): string | null {
    const n = (task?.openQuestions ?? []).length
    if (!n) return null
    const latest = latestQuestionRound(task, roles)
    // No QUESTIONS round on the read (an older task): the newest frame still says who asked.
    const asker = latest?.askedBy?.roleName
        ?? (stack.length ? roleName(roles, stack[stack.length - 1].askingRole) || null : null)
    const detail = [latest?.round != null ? `round ${latest.round}` : '', aboutLabel(latest)].filter(Boolean)
    return `${n} open question${n === 1 ? '' : 's'}${asker ? ` from ${asker}` : ''}`
        + (detail.length ? ` (${detail.join(', ')})` : '')
}

export function taskSummary (task: any, tasks: any[], roles: any[] | null | undefined,
    agentNames: Record<string, string>): TaskSummary {
    const documents: DocumentRelease[] = task?.documents ?? []

    const byPriority = new Map<number | null, number>()
    for (const spec of INDEXED_TYPES) {
        const round = latestRound(documents, spec)
        for (const f of (round?.document?.findings?.findings ?? []) as Finding[]) {
            if (f.status !== 'OPEN') continue
            const p = typeof f.priority === 'number' ? f.priority : null
            byPriority.set(p, (byPriority.get(p) ?? 0) + 1)
        }
    }
    const openFindings = [...byPriority.entries()]
        .sort((a, b) => (a[0] ?? Number.MAX_SAFE_INTEGER) - (b[0] ?? Number.MAX_SAFE_INTEGER))
        .map(([priority, count]) => ({ priority, count }))

    // documents arrive newest first, so the first of each type is its latest
    const seen = new Set<string>()
    const latestDocuments: DocumentRelease[] = []
    for (const d of documents) {
        const spec = d?.document?.specification
        if (!spec || seen.has(spec)) continue
        seen.add(spec)
        latestDocuments.push(d)
    }
    latestDocuments.reverse()

    const deps = (task?.dependsOn ?? []).map((u: string) => tasks.find(t => t.uuid === u))
    const done = deps.filter((t: any) => t?.status === 'COMPLETED').length
    const stack = task?.questionStack ?? []

    const a = task?.assignment
    const usage = task?.usage
    return {
        openFindings,
        openQuestions: (task?.openQuestions ?? []).length,
        questions: openQuestionsLine(task, roles, stack),
        latestDocuments,
        dependencies: {
            done,
            pending: deps.length - done,
            blocks: task ? tasks.filter(t => (t.dependsOn ?? []).includes(task.uuid)).length : 0,
        },
        assignment: a ? `${a.role} · ${agentName(agentNames, a.agent)} · since ${ts(a.assignedAt)} (${dur(a.assignedAt, null)})` : null,
        usage: (usage?.reports ?? 0) > 0
            ? `${costLabel(usage)} · ${formatTokens(totalTokens(usage))} tok · ${usage.requests ?? 0} requests`
            : null,
    }
}
