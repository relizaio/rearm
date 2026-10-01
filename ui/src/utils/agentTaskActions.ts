// A person's actions on a task, for every place a task is shown: the board panel's drawer and the
// task page. One implementation of each verb -- the store action it runs, the message it shows,
// and what happens to the view afterwards -- so the two entry points cannot drift.
import { useStore } from 'vuex'
import { useNotification } from 'naive-ui'

export type AfterAction = (task: any, keepOpen: boolean) => Promise<void>

/**
 * The failure toast's lead for a review item decision (task RD2-23, sweep UI-49): a refused File names filing,
 * not a decision the person did not make; any other decision keeps "Decision failed".
 */
export function decisionFailedText (decisions: { action?: string | null }[] | null | undefined): string {
    const all = decisions ?? []
    return all.length > 0 && all.every(d => d?.action === 'FILE') ? 'Could not file the review item' : 'Decision failed'
}

/**
 * @param after reloads once an action succeeded. keepOpen is false after a verdict that hands the
 * task on (a gate review, a human sign-off, a lift, an answer), where the board panel closes its
 * drawer; the task page stays on the task either way.
 */
export function useAgentTaskActions (after: AfterAction) {
    const store = useStore()
    const notification = useNotification()

    async function handedOn (t: any, run: () => Promise<any>, done: (res: any) => string, failed: string) {
        try {
            const res = await run()
            notification.success({ content: done(res), duration: 3000 })
            await after(t, false)
        } catch (e: any) {
            notification.error({ content: `${failed}: ${e?.message ?? e}`, duration: 8000 })
        }
    }

    /** Run an action on a task, reload and keep the same task shown. */
    async function kept (t: any, run: () => Promise<any>, done: (res: any) => string, failed: string) {
        try {
            const res = await run()
            notification.success({ content: done(res), duration: 3000 })
            await after(t, true)
        } catch (e: any) {
            notification.error({ content: `${failed}: ${e?.message ?? e}`, duration: 8000 })
        }
    }

    function humanReview (p: { task: any, accept: boolean, note: string, reviewItems?: any[],
            about?: { specification: string } | null }) {
        return handedOn(p.task,
            () => store.dispatch('agentTaskHumanReview', { taskUuid: p.task.uuid, accept: p.accept,
                note: p.note || undefined, reviewItems: p.reviewItems, about: p.about }),
            (res: any) => `${p.accept ? 'Accepted' : 'Rejected'} ${p.task.hold?.gateRole ?? ''} pass`
                + (p.reviewItems?.length && p.accept ? ' with a correction' : '')
                + (res?.status === 'QUEUED' && res?.role ? ` — ${p.accept ? 'on' : 'back'} to ${res.role}` : ''),
            'Review failed')
    }

    function humanSignOff (p: { task: any, outcome: string, note: string }) {
        return handedOn(p.task,
            () => store.dispatch('agentTaskHumanSignOff', { taskUuid: p.task.uuid, outcome: p.outcome,
                note: p.note || undefined }),
            () => `Signed off ${p.outcome} — returned to the coordinator`, 'Sign-off failed')
    }

    function liftHold (p: { task: any, note?: string, role?: string } | any) {
        // Tolerates the bare task the drawer used to emit, so a stale caller does not lose the release.
        const t = p?.task ?? p
        // A role only from a release payload: a bare task carries its own role field, which is not
        // where the person asked to send it.
        const role: string | undefined = p?.task ? (p.role || undefined) : undefined
        return handedOn(t,
            () => store.dispatch('agentTaskOperatorHold', { taskUuid: t.uuid, hold: false, reason: p?.note || undefined,
                ...(role ? { role } : {}) }),
            () => role ? `Hold lifted, routed to ${role}` : 'Hold lifted', 'Lift failed')
    }

    function answerQuestions (p: { task: any,
            answers: { id: string, status: string, resolution: string }[], answerAll?: string }) {
        return handedOn(p.task,
            () => store.dispatch('agentTaskAnswer', {
                taskUuid: p.task.uuid, answers: p.answers, answerAll: p.answerAll, liftHold: true }),
            (res: any) => res?.role ? `Answered — back to ${res.role}` : 'Answered', 'Answer failed')
    }

    function authorizeTask (p: { task: any, role: string, orderIndex?: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskAuthorize', { taskUuid: p.task.uuid, role: p.role, orderIndex: p.orderIndex }),
            () => `Authorized for ${p.role}`, 'Authorize failed')
    }

    /**
     * A task's budget; null clears it (task 6f1b348d). A raise does not lift a budget hold, so a
     * held task's confirmation says to lift it.
     */
    function setBudget (p: { task: any, budgetMicros: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetBudget', { taskUuid: p.task.uuid, budgetMicros: p.budgetMicros }),
            (res: any) => (p.budgetMicros === null ? 'Task budget cleared' : 'Task budget set')
                + (res?.status === 'ON_HOLD' ? '; lift the hold to resume' : ''), 'Setting the budget failed')
    }

    function orderTask (p: { task: any, orderIndex: number }) {
        return kept(p.task,
            () => store.dispatch('agentTaskOrder', { taskUuid: p.task.uuid, orderIndex: p.orderIndex }),
            () => `Order set to ${p.orderIndex}`, 'Reorder failed')
    }

    function completeTask (p: { task: any, note: string, skipRequiredRoles: boolean }) {
        return kept(p.task,
            () => store.dispatch('agentTaskComplete', { taskUuid: p.task.uuid, note: p.note,
                skipRequiredRoles: p.skipRequiredRoles }),
            (res: any) => res?.status === 'DELIVERING' ? 'Passed: waiting for its delivery' : 'Task completed',
            'Could not complete')
    }

    function cancelTask (p: { task: any, note: string }) {
        return kept(p.task,
            () => store.dispatch('agentTaskCancel', { taskUuid: p.task.uuid, note: p.note }),
            () => 'Task cancelled', 'Cancel failed')
    }

    function reopenTask (p: { task: any, role: string, reason: string }) {
        return kept(p.task,
            () => store.dispatch('agentTaskReopen', { taskUuid: p.task.uuid, role: p.role, reason: p.reason }),
            (res: any) => res?.status === 'ON_HOLD' ? `Reopened to ${p.role}, held: the budget does not cover the round`
                : `Reopened to ${p.role}`, 'Reopen failed')
    }

    function decideReviewItems (p: { task: any, specification: string, decisions: any[],
            about?: { specification: string } | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskDecideReviewItems', { taskUuid: p.task.uuid, specification: p.specification,
                decisions: p.decisions, about: p.about }),
            (res: any) => res?.status === 'QUEUED' && res?.role !== p.task.role
                ? `Decided — back to ${res.role}` : 'Decided', decisionFailedText(p.decisions))
    }

    /** A task's required strength; null clears it (task 6fdc5a37). */
    function setStrength (p: { task: any, requiredStrength: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetStrength', { taskUuid: p.task.uuid, requiredStrength: p.requiredStrength }),
            () => p.requiredStrength == null ? 'Strength cleared' : `Strength set to ${p.requiredStrength}`,
            'Setting strength failed')
    }

    /** A task's work level, 0 to 9; null clears it to the board default (RD2-1). */
    function setWorkLevel (p: { task: any, workLevel: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetWorkLevel', { taskUuid: p.task.uuid, workLevel: p.workLevel }),
            () => p.workLevel == null ? 'Work level cleared' : `Work level set to ${p.workLevel}`,
            'Could not set the work level')
    }

    /** Move a task into a group by key, or out of every group with null (RD2-31). */
    function setGroup (p: { task: any, group: string | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetGroup', { taskUuid: p.task.uuid, group: p.group }),
            () => p.group == null ? 'Moved out of its group' : `Moved into group ${p.group}`,
            'Could not move the task')
    }

    /** Replace a task's tags (RD2-31). */
    function setTags (p: { task: any, tags: { key: string, value?: string | null }[] }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetTags', { taskUuid: p.task.uuid, tags: p.tags }),
            () => p.tags.length ? `Tags: ${p.tags.map(t => t.key).join(', ')}` : 'Tags cleared',
            'Could not set the tags')
    }

    /** A person's declaration of a delivery unit, or of its abandonment (RD2-10). */
    function declareDelivery (p: { task: any, unit: string, commit: string | null, outcome: string, note: string | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskDeclareDelivery', { taskUuid: p.task.uuid, unit: p.unit, commit: p.commit,
                outcome: p.outcome, note: p.note }),
            (res: any) => p.outcome === 'ABANDONED' ? `${p.unit} marked abandoned`
                : res?.status === 'COMPLETED' ? 'Delivery declared: task completed' : 'Delivery declared',
            'Could not declare')
    }

    /** A person declares a linked PR superseded by its replacement (task RD3-18). */
    function supersedePr (p: { task: any, oldUrl: string, byUrl: string, note: string | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSupersedePullRequest', { taskUuid: p.task.uuid, oldUrl: p.oldUrl, byUrl: p.byUrl,
                note: p.note }),
            (res: any) => `${p.oldUrl} superseded by ${p.byUrl}` + (res?.status === 'COMPLETED' ? ': task completed' : ''),
            'Could not declare it superseded')
    }

    /** An operator hold, which the coordinator cannot lift (task 6fdc5a37). */
    function operatorHold (p: { task: any, reason: string }) {
        return kept(p.task,
            () => store.dispatch('agentTaskOperatorHold', { taskUuid: p.task.uuid, hold: true, reason: p.reason }),
            () => 'On hold (operator)', 'Hold failed')
    }

    /** A person takes a stalled assignment back to the queue for the same role (task RD3-4). */
    function unassign (p: { task: any, reason: string }) {
        return kept(p.task,
            () => store.dispatch('agentTaskUnassign', { taskUuid: p.task.uuid, reason: p.reason }),
            () => 'Unassigned: the task is queued again', 'Unassign failed')
    }

    /**
     * A person commissions an investigation from this task (task RD4-12). The page stays on this task: the
     * investigation is a task of its own, and its report comes back here.
     */
    function commission (p: { task: any, input: Record<string, any> }) {
        return kept(p.task,
            () => store.dispatch('agentTaskCommission', { input: p.input }),
            (res: any) => `Investigation ${res?.key ?? ''} commissioned for ${p.input.role}`
                + (res?.status === 'PENDING_INTAKE' ? ': waiting for intake' : ''),
            'Could not commission')
    }

    function requireReview (p: { task: any, value: boolean }) {
        return kept(p.task,
            () => store.dispatch('agentTaskRequireHumanReview', { taskUuid: p.task.uuid, value: p.value }),
            () => p.value ? 'Next sign-off will require human review' : 'Human-review flag cleared', 'Update failed')
    }

    return {
        humanReview, humanSignOff, liftHold, answerQuestions, authorizeTask, orderTask,
        completeTask, cancelTask, reopenTask, decideReviewItems, requireReview, setStrength, operatorHold, unassign, setBudget,
        setWorkLevel, setGroup, setTags, declareDelivery, supersedePr, commission,
    }
}
