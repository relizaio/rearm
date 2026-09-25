// A person's actions on a task, for every place a task is shown: the board panel's drawer and the
// task page. One implementation of each verb -- the store action it runs, the message it shows,
// and what happens to the view afterwards -- so the two entry points cannot drift.
import { useStore } from 'vuex'
import { useNotification } from 'naive-ui'

export type AfterAction = (task: any, keepOpen: boolean) => Promise<void>

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

    function operatorRelease (p: { task: any, note?: string } | any) {
        // Tolerates the bare task the drawer used to emit, so a stale caller does not lose the release.
        const t = p?.task ?? p
        return handedOn(t,
            () => store.dispatch('agentTaskOperatorHold', { taskUuid: t.uuid, hold: false, reason: p?.note || undefined }),
            () => 'Hold released', 'Release failed')
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

    function orderTask (p: { task: any, orderIndex: number }) {
        return kept(p.task,
            () => store.dispatch('agentTaskOrder', { taskUuid: p.task.uuid, orderIndex: p.orderIndex }),
            () => `Order set to ${p.orderIndex}`, 'Reorder failed')
    }

    function completeTask (p: { task: any, note: string, skipRequiredRoles: boolean }) {
        return kept(p.task,
            () => store.dispatch('agentTaskComplete', { taskUuid: p.task.uuid, note: p.note,
                skipRequiredRoles: p.skipRequiredRoles }),
            () => 'Task completed', 'Complete failed')
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
                ? `Decided — back to ${res.role}` : 'Decided', 'Decision failed')
    }

    function requireReview (p: { task: any, value: boolean }) {
        return kept(p.task,
            () => store.dispatch('agentTaskRequireHumanReview', { taskUuid: p.task.uuid, value: p.value }),
            () => p.value ? 'Next sign-off will require human review' : 'Human-review flag cleared', 'Update failed')
    }

    return {
        humanReview, humanSignOff, operatorRelease, answerQuestions, authorizeTask, orderTask,
        completeTask, cancelTask, reopenTask, decideFindings, requireReview,
    }
}
