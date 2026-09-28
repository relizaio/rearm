// A person's actions on a task, for every place a task is shown: the board panel's drawer and the
// task page. One implementation of each verb -- the store action it runs, the message it shows,
// and what happens to the view afterwards -- so the two entry points cannot drift.
import { useStore } from 'vuex'
import { useNotification } from 'naive-ui'

export type AfterAction = (task: any, keepOpen: boolean) => Promise<void>

/**
 * The failure toast's lead for a findings decision (task RD2-23, sweep UI-49): a refused File names filing,
 * not a decision the person did not make; any other decision keeps "Decision failed".
 */
export function decisionFailedText (decisions: { action?: string | null }[] | null | undefined): string {
    const all = decisions ?? []
    return all.length > 0 && all.every(d => d?.action === 'FILE') ? 'Could not file the finding' : 'Decision failed'
}

/**
 * @param after reloads once an action succeeded. keepOpen is false after a verdict that hands the
 * task on (a gate review, a human sign-off, a release, an answer), where the board panel closes its
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

    function humanReview (p: { task: any, approve: boolean, note: string, findings?: any[],
            about?: { specification: string } | null }) {
        return handedOn(p.task,
            () => store.dispatch('agentTaskHumanReview', { taskUuid: p.task.uuid, approve: p.approve,
                note: p.note || undefined, findings: p.findings, about: p.about }),
            (res: any) => `${p.approve ? 'Approved' : 'Rejected'} ${p.task.hold?.gateRole ?? ''} pass`
                + (p.findings?.length && p.approve ? ' with a correction' : '')
                + (res?.status === 'QUEUED' && res?.role ? ` — ${p.approve ? 'on' : 'back'} to ${res.role}` : ''),
            'Review failed')
    }

    function humanSignOff (p: { task: any, outcome: string, note: string }) {
        return handedOn(p.task,
            () => store.dispatch('agentTaskHumanSignOff', { taskUuid: p.task.uuid, outcome: p.outcome,
                note: p.note || undefined }),
            () => `Signed off ${p.outcome} — returned to the coordinator`, 'Sign-off failed')
    }

    function operatorRelease (p: { task: any, note?: string, role?: string } | any) {
        // Tolerates the bare task the drawer used to emit, so a stale caller does not lose the release.
        const t = p?.task ?? p
        // A role only from a release payload: a bare task carries its own role field, which is not
        // where the person asked to send it.
        const role: string | undefined = p?.task ? (p.role || undefined) : undefined
        return handedOn(t,
            () => store.dispatch('agentTaskOperatorHold', { taskUuid: t.uuid, hold: false, reason: p?.note || undefined,
                ...(role ? { role } : {}) }),
            () => role ? `Hold released to ${role}` : 'Hold released', 'Release failed')
    }

    function answerQuestions (p: { task: any,
            answers: { id: string, status: string, resolution: string }[], answerAll?: string }) {
        return handedOn(p.task,
            () => store.dispatch('agentTaskAnswer', {
                taskUuid: p.task.uuid, answers: p.answers, answerAll: p.answerAll, releaseHold: true }),
            (res: any) => res?.role ? `Answered — back to ${res.role}` : 'Answered', 'Answer failed')
    }

    function authorizeTask (p: { task: any, role: string, orderIndex?: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskAuthorize', { taskUuid: p.task.uuid, role: p.role, orderIndex: p.orderIndex }),
            () => `Authorized for ${p.role}`, 'Authorize failed')
    }

    /**
     * A task's budget; null clears it (task 6f1b348d). A raise does not release a budget hold, so a
     * held task's confirmation says to release it.
     */
    function setBudget (p: { task: any, budgetMicros: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetBudget', { taskUuid: p.task.uuid, budgetMicros: p.budgetMicros }),
            (res: any) => (p.budgetMicros === null ? 'Task budget cleared' : 'Task budget set')
                + (res?.status === 'ON_HOLD' ? '; release the hold to resume' : ''), 'Setting the budget failed')
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

    function decideFindings (p: { task: any, specification: string, decisions: any[],
            about?: { specification: string } | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskDecideFindings', { taskUuid: p.task.uuid, specification: p.specification,
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

    /** A task's level, 0 to 9; null clears it to the board default (RD2-1). */
    function setLevel (p: { task: any, level: number | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskSetLevel', { taskUuid: p.task.uuid, level: p.level }),
            () => p.level == null ? 'Level cleared' : `Level set to ${p.level}`,
            'Could not set the level')
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

    /** A person's attestation of a delivery unit, or of its abandonment (RD2-10). */
    function delivered (p: { task: any, unit: string, commit: string | null, outcome: string, note: string | null }) {
        return kept(p.task,
            () => store.dispatch('agentTaskDelivered', { taskUuid: p.task.uuid, unit: p.unit, commit: p.commit,
                outcome: p.outcome, note: p.note }),
            (res: any) => p.outcome === 'ABANDONED' ? `${p.unit} marked abandoned`
                : res?.status === 'COMPLETED' ? 'Delivery attested: task completed' : 'Delivery attested',
            'Could not attest')
    }

    /** An operator hold, which the coordinator cannot lift (task 6fdc5a37). */
    function operatorHold (p: { task: any, reason: string }) {
        return kept(p.task,
            () => store.dispatch('agentTaskOperatorHold', { taskUuid: p.task.uuid, hold: true, reason: p.reason }),
            () => 'On hold (operator)', 'Hold failed')
    }

    /** A person takes a stalled assignment back to the queue for the same role (task RD3-4). */
    function releaseAssignment (p: { task: any, reason: string }) {
        return kept(p.task,
            () => store.dispatch('agentTaskReleaseAssignment', { taskUuid: p.task.uuid, reason: p.reason }),
            () => 'Assignment released: the task is queued again', 'Release failed')
    }

    function requireReview (p: { task: any, value: boolean }) {
        return kept(p.task,
            () => store.dispatch('agentTaskRequireHumanReview', { taskUuid: p.task.uuid, value: p.value }),
            () => p.value ? 'Next sign-off will require human review' : 'Human-review flag cleared', 'Update failed')
    }

    return {
        humanReview, humanSignOff, operatorRelease, answerQuestions, authorizeTask, orderTask,
        completeTask, cancelTask, reopenTask, decideFindings, requireReview, setStrength, operatorHold, releaseAssignment, setBudget,
        setLevel, setGroup, setTags, delivered,
    }
}
