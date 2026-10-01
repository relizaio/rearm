// Investigation tasks (task RD4-12): a role commissions another for a report through the board, and the report
// comes back to the asker pinned. What the task page shows of one, what the "Commission investigation" form
// sends, and a role's commissions as the role form edits them. Option lists live here, with a spec, because a
// broken list renders a blank select and sends bad values on save.
import { dollarsToMicros, microsToDollars } from './agentBudget'
import type { Option } from './agentTaskOptions'

export const BOARD_INVESTIGATION_REPORT = 'BOARD_INVESTIGATION_REPORT'

/** Whether the task is an investigation. */
export function isInvestigation (task: any): boolean {
    return task?.kind === 'INVESTIGATION' && !!task?.investigation
}

/** Whether a role may be asked for a report: it produces BOARD_INVESTIGATION_REPORT at TASK scope. */
export function producesReport (role: any): boolean {
    return (role?.producesOutputs ?? []).some((o: any) => o?.specification === BOARD_INVESTIGATION_REPORT
        && (o?.scope ?? 'TASK') === 'TASK')
}

/** The roles a person may commission: every active role that produces the report. */
export function investigatingRoleOptions (roles: any[] | null | undefined): Option<string>[] {
    return (roles ?? []).filter((r: any) => r?.active && r?.kind !== 'HUMAN' && producesReport(r))
        .map((r: any) => ({ label: r.name, value: r.name }))
}

/** The roles that may review a report: every active role but the one investigating. */
export function reviewRoleOptions (roles: any[] | null | undefined, investigator: string | null): Option<string>[] {
    return (roles ?? []).filter((r: any) => r?.active && r?.name !== investigator)
        .map((r: any) => ({ label: r.name, value: r.name }))
}

export type CommissionDraft = {
    role: string | null
    title: string
    brief: string
    budgetDollars: number | null
    /** Epoch milliseconds, as naive-ui's date picker holds it; null for no deadline. */
    deadline: number | null
    review: string | null
    /** Bring the report back to this task; off, or on a board-level form, it stays on the investigation. */
    returnToTask: boolean
}

export function commissionDraftOf (): CommissionDraft {
    return { role: null, title: '', brief: '', budgetDollars: null, deadline: null, review: null, returnToTask: true }
}

/** Why the form cannot be sent, or '' when it can: a role, a one-line title of at most 120, a future deadline. */
export function commissionProblem (draft: CommissionDraft, now: number = Date.now()): string {
    if (!draft.role) return 'Pick the role to investigate'
    const title = draft.title.trim()
    if (!title) return 'A title is required'
    if (title.includes('\n')) return 'A title is one line; put the rest in the brief'
    if (title.length > 120) return `Titles are at most 120 characters (this one is ${title.length})`
    if (draft.deadline != null && draft.deadline <= now) return 'The deadline has passed; pick one in the future'
    if (draft.budgetDollars != null && draft.budgetDollars < 0) return 'A budget cannot be negative'
    return ''
}

/** What agentTaskCommission takes, or null while the draft has a problem. */
export function commissionInput (boardUuid: string, task: any | null, draft: CommissionDraft,
        now: number = Date.now()): Record<string, any> | null {
    if (!boardUuid || commissionProblem(draft, now)) return null
    const fromTask = task?.uuid && draft.returnToTask ? task.uuid : null
    const input: Record<string, any> = {
        boardUuid,
        role: draft.role,
        title: draft.title.trim(),
        brief: draft.brief.trim() || null,
        fromTask,
        returnTo: fromTask ? 'TASK' : 'NONE',
    }
    if (draft.budgetDollars != null) input.budgetMicros = dollarsToMicros(draft.budgetDollars)
    if (draft.deadline != null) input.deadline = new Date(draft.deadline).toISOString()
    if (draft.review) input.review = draft.review
    return input
}

/** The lines of a task's investigation block, label and value, in reading order. */
export function investigationLines (task: any, taskKeyOf: (uuid: string) => string | null = () => null,
        at: (iso: string) => string = iso => iso): { label: string, value: string }[] {
    if (!isInvestigation(task)) return []
    const inv = task.investigation
    const by = inv.commissionedBy ?? {}
    const who = by.role ? `the ${by.role} role` : (by.by?.name ?? 'a person')
    const from = by.task ? (taskKeyOf(by.task) ?? by.task) : null
    const out = [
        { label: 'investigating role', value: inv.role ?? '—' },
        { label: 'commissioned by', value: who + (from ? ` from ${from}` : '') },
        { label: 'deliverable', value: 'investigation report' },
        { label: 'review', value: inv.review ?? 'none' },
        { label: 'deadline', value: inv.deadline ? at(inv.deadline) : 'none' },
        { label: 'report goes', value: inv.returnTo === 'TASK' && from ? `back to ${from}, pinned` : 'nowhere: it stays here' },
    ]
    return out
}

/** The report release among the task's documents: the one the investigation completed with, else its newest. */
export function reportOf (task: any): any | null {
    const docs = (task?.documents ?? []).filter((d: any) => d?.document?.specification === BOARD_INVESTIGATION_REPORT
        && !d?.document?.supersededBy)
    if (!docs.length) return null
    const done = task?.investigation?.report
    return docs.find((d: any) => d.uuid === done) ?? [...docs].sort((a: any, b: any) =>
        (b.document?.round ?? 0) - (a.document?.round ?? 0))[0]
}

/** The roles an investigation needs a pass from: its investigating role, and its reviewer when it has one. */
export function investigationRequiredRoles (task: any): string[] {
    if (!isInvestigation(task)) return []
    return [task.investigation.role, task.investigation.review].filter((r: any) => !!r)
}

export type CommissionsDraft = {
    roles: string[]
    intake: 'AUTO' | 'COORDINATOR'
    defaultBudgetDollars: number | null
    review: string | null
}

export const INTAKE_OPTIONS: Option<'AUTO' | 'COORDINATOR'>[] = [
    { label: 'AUTO: queued for the role at once', value: 'AUTO' },
    { label: 'COORDINATOR: through the coordinator\'s intake', value: 'COORDINATOR' },
]

/** The role form's draft of a role's commissions. */
export function commissionsDraftOf (role: any | null | undefined): CommissionsDraft {
    const c = role?.commissions
    return {
        roles: [...(c?.roles ?? [])],
        intake: c?.intake === 'COORDINATOR' ? 'COORDINATOR' : 'AUTO',
        defaultBudgetDollars: microsToDollars(c?.defaultBudgetMicros),
        review: c?.review ?? null,
    }
}

/** What the role form sends as commissions: null when it names no role (commissions nobody), else the block. */
export function commissionsPatch (draft: CommissionsDraft | null | undefined): Record<string, any> | null {
    const roles = (draft?.roles ?? []).map(r => r?.trim()).filter(r => !!r)
    if (!draft || !roles.length) return null
    return {
        roles,
        intake: draft.intake,
        defaultBudgetMicros: dollarsToMicros(draft.defaultBudgetDollars),
        review: draft.review || null,
    }
}

/** The roles a role may commission: the others that produce the report. */
export function commissionableRoleOptions (roles: any[] | null | undefined, self: string | null | undefined): Option<string>[] {
    return (roles ?? []).filter((r: any) => r?.name !== self && producesReport(r))
        .map((r: any) => ({ label: r.name, value: r.name }))
}
