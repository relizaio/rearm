import { beforeEach, describe, expect, it, vi } from 'vitest'

const dispatch = vi.fn()
const success = vi.fn()
const error = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch }) }))
vi.mock('naive-ui', () => ({ useNotification: () => ({ success, error }) }))

const { useAgentTaskActions } = await import('./agentTaskActions')

const task = { uuid: 't1', role: 'coder', hold: { gateRole: 'reviewer' } }

// verb, payload, store action, what the store receives, whether the task stays shown
const TABLE: [string, any, string, any, boolean][] = [
    ['humanReview', { task, approve: true, note: '', findings: undefined, about: null }, 'agentTaskHumanReview',
        { taskUuid: 't1', approve: true, note: undefined, findings: undefined, about: null }, false],
    ['humanSignOff', { task, outcome: 'PASSED', note: 'ok' }, 'agentTaskHumanSignOff',
        { taskUuid: 't1', outcome: 'PASSED', note: 'ok' }, false],
    ['operatorRelease', { task, note: 'go' }, 'agentTaskOperatorHold', { taskUuid: 't1', hold: false, reason: 'go' }, false],
    ['answerQuestions', { task, answers: [], answerAll: 'x' }, 'agentTaskAnswer',
        { taskUuid: 't1', answers: [], answerAll: 'x', releaseHold: true }, false],
    ['authorizeTask', { task, role: 'coder', orderIndex: 2 }, 'agentTaskAuthorize', { taskUuid: 't1', role: 'coder', orderIndex: 2 }, true],
    ['orderTask', { task, orderIndex: 4 }, 'agentTaskOrder', { taskUuid: 't1', orderIndex: 4 }, true],
    ['completeTask', { task, note: 'n', skipRequiredRoles: true }, 'agentTaskComplete',
        { taskUuid: 't1', note: 'n', skipRequiredRoles: true }, true],
    ['cancelTask', { task, note: 'n' }, 'agentTaskCancel', { taskUuid: 't1', note: 'n' }, true],
    ['reopenTask', { task, role: 'coder', reason: 'r' }, 'agentTaskReopen', { taskUuid: 't1', role: 'coder', reason: 'r' }, true],
    ['decideFindings', { task, specification: 'REVIEW_FINDINGS', decisions: [{ action: 'ACCEPT' }], about: null },
        'agentTaskDecideFindings', { taskUuid: 't1', specification: 'REVIEW_FINDINGS', decisions: [{ action: 'ACCEPT' }], about: null }, true],
    ['requireReview', { task, value: true }, 'agentTaskRequireHumanReview', { taskUuid: 't1', value: true }, true],
    ['setBudget', { task, budgetMicros: 2_500_000 }, 'agentTaskSetBudget', { taskUuid: 't1', budgetMicros: 2_500_000 }, true],
]

describe('useAgentTaskActions', () => {
    beforeEach(() => { dispatch.mockReset(); success.mockReset(); error.mockReset() })

    it('covers every verb the drawer emits', () => {
        const verbs = Object.keys(useAgentTaskActions(async () => {})).sort()
        expect(verbs).toEqual(TABLE.map(r => r[0]).sort())
    })

    for (const [verb, payload, action, sent, keepOpen] of TABLE) {
        it(`${verb} runs ${action} and ${keepOpen ? 'keeps' : 'hands on'} the task`, async () => {
            dispatch.mockResolvedValue({ status: 'QUEUED', role: 'reviewer' })
            const after = vi.fn(async () => {})
            await (useAgentTaskActions(after) as any)[verb](payload)
            expect(dispatch).toHaveBeenCalledWith(action, sent)
            expect(success).toHaveBeenCalledOnce()
            expect(after).toHaveBeenCalledWith(task, keepOpen)
        })
    }

    it('a failure says so and reloads nothing', async () => {
        dispatch.mockRejectedValue(new Error('Not authorized'))
        const after = vi.fn(async () => {})
        await useAgentTaskActions(after).cancelTask({ task, note: '' })
        expect(error).toHaveBeenCalledWith({ content: 'Cancel failed: Not authorized', duration: 8000 })
        expect(after).not.toHaveBeenCalled()
    })

    it('keeps the messages the panel showed', async () => {
        dispatch.mockResolvedValue({ status: 'QUEUED', role: 'coder' })
        await useAgentTaskActions(async () => {}).humanReview({ task, approve: false, note: '' })
        expect(success).toHaveBeenCalledWith({ content: 'Rejected reviewer pass — back to coder', duration: 3000 })
        dispatch.mockResolvedValue({ status: 'ON_HOLD' })
        await useAgentTaskActions(async () => {}).reopenTask({ task, role: 'coder', reason: 'r' })
        expect(success).toHaveBeenLastCalledWith({ content: 'Reopened to coder, held: the budget does not cover the round', duration: 3000 })
        // the release tolerates a bare task
        await useAgentTaskActions(async () => {}).operatorRelease(task)
        expect(dispatch).toHaveBeenLastCalledWith('agentTaskOperatorHold', { taskUuid: 't1', hold: false, reason: undefined })
    })
})
