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
    ['humanReview', { task, accept: true, note: '', reviewItems: undefined, about: null }, 'agentTaskHumanReview',
        { taskUuid: 't1', accept: true, note: undefined, reviewItems: undefined, about: null }, false],
    ['humanSignOff', { task, outcome: 'PASSED', note: 'ok' }, 'agentTaskHumanSignOff',
        { taskUuid: 't1', outcome: 'PASSED', note: 'ok' }, false],
    ['liftHold', { task, note: 'go' }, 'agentTaskOperatorHold', { taskUuid: 't1', hold: false, reason: 'go' }, false],
    ['answerQuestions', { task, answers: [], answerAll: 'x' }, 'agentTaskAnswer',
        { taskUuid: 't1', answers: [], answerAll: 'x', liftHold: true }, false],
    ['authorizeTask', { task, role: 'coder', orderIndex: 2 }, 'agentTaskAuthorize', { taskUuid: 't1', role: 'coder', orderIndex: 2 }, true],
    ['orderTask', { task, orderIndex: 4 }, 'agentTaskOrder', { taskUuid: 't1', orderIndex: 4 }, true],
    ['completeTask', { task, note: 'n', skipRequiredRoles: true }, 'agentTaskComplete',
        { taskUuid: 't1', note: 'n', skipRequiredRoles: true }, true],
    ['cancelTask', { task, note: 'n' }, 'agentTaskCancel', { taskUuid: 't1', note: 'n' }, true],
    ['reopenTask', { task, role: 'coder', reason: 'r' }, 'agentTaskReopen', { taskUuid: 't1', role: 'coder', reason: 'r' }, true],
    ['decideReviewItems', { task, specification: 'BOARD_REVIEW_ITEMS', decisions: [{ action: 'ACCEPT' }], about: null },
        'agentTaskDecideReviewItems', { taskUuid: 't1', specification: 'BOARD_REVIEW_ITEMS', decisions: [{ action: 'ACCEPT' }], about: null }, true],
    ['requireReview', { task, value: true }, 'agentTaskRequireHumanReview', { taskUuid: 't1', value: true }, true],
    ['setStrength', { task, requiredStrength: 4.5 }, 'agentTaskSetStrength', { taskUuid: 't1', requiredStrength: 4.5 }, true],
    ['operatorHold', { task, reason: 'waiting on legal' }, 'agentTaskOperatorHold',
        { taskUuid: 't1', hold: true, reason: 'waiting on legal' }, true],
    ['unassign', { task, reason: 'the agent is gone' }, 'agentTaskUnassign',
        { taskUuid: 't1', reason: 'the agent is gone' }, true],
    ['setBudget', { task, budgetMicros: 2_500_000 }, 'agentTaskSetBudget', { taskUuid: 't1', budgetMicros: 2_500_000 }, true],
    ['setWorkLevel', { task, workLevel: 2 }, 'agentTaskSetWorkLevel', { taskUuid: 't1', workLevel: 2 }, true],
    ['setGroup', { task, group: 'core-work' }, 'agentTaskSetGroup', { taskUuid: 't1', group: 'core-work' }, true],
    ['setTags', { task, tags: [{ key: 'urgent' }] }, 'agentTaskSetTags', { taskUuid: 't1', tags: [{ key: 'urgent' }] }, true],
    ['supersedePr', { task, oldUrl: 'https://github.com/o/r/pull/1', byUrl: 'https://github.com/o/r/pull/2', note: null },
        'agentTaskSupersedePullRequest', { taskUuid: 't1', oldUrl: 'https://github.com/o/r/pull/1',
            byUrl: 'https://github.com/o/r/pull/2', note: null }, true],
    // task t20261010-033523-18839: a person unlinks a PR; the task stays shown.
    ['unlinkPr', { task, prUrl: 'https://github.com/o/r/pull/1', note: 'linked by mistake' },
        'agentTaskUnlinkPr', { taskUuid: 't1', prUrl: 'https://github.com/o/r/pull/1', note: 'linked by mistake' }, true],
    ['declareDelivery', { task, unit: 'https://github.com/o/r/pull/1', commit: 'abc1234', outcome: 'DELIVERED', note: null },
        'agentTaskDeclareDelivery', { taskUuid: 't1', unit: 'https://github.com/o/r/pull/1', commit: 'abc1234', outcome: 'DELIVERED', note: null }, true],
    // task RD4-12: a person commissions an investigation from the task, which stays shown.
    ['commission', { task, input: { boardUuid: 'b1', role: 'tester', title: 'measure it', fromTask: 't1', returnTo: 'TASK' } },
        'agentTaskCommission', { input: { boardUuid: 'b1', role: 'tester', title: 'measure it', fromTask: 't1', returnTo: 'TASK' } }, true],
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

    it('a release carries the role the person picked (4c566d0d)', async () => {
        dispatch.mockResolvedValue({ status: 'QUEUED', role: 'coder' })
        await useAgentTaskActions(async () => {}).liftHold({ task, note: 'go', role: 'coder' })
        expect(dispatch).toHaveBeenCalledWith('agentTaskOperatorHold', { taskUuid: 't1', hold: false, reason: 'go', role: 'coder' })
        expect(success).toHaveBeenCalledWith(expect.objectContaining({ content: 'Hold lifted, routed to coder' }))
    })

    it('a failure says so and reloads nothing', async () => {
        dispatch.mockRejectedValue(new Error('Not authorized'))
        const after = vi.fn(async () => {})
        await useAgentTaskActions(after).cancelTask({ task, note: '' })
        expect(error).toHaveBeenCalledWith({ content: 'Cancel failed: Not authorized', duration: 8000 })
        expect(after).not.toHaveBeenCalled()
    })

    it('says a refused declaration in its own words (RD2-10)', async () => {
        dispatch.mockRejectedValue(new Error('The unit x is not a PR linked to this task'))
        const after = vi.fn(async () => {})
        await useAgentTaskActions(after).declareDelivery({ task, unit: 'x', commit: 'abc1234', outcome: 'DELIVERED', note: null })
        expect(error).toHaveBeenCalledWith({ content: 'Could not declare: The unit x is not a PR linked to this task', duration: 8000 })
        expect(after).not.toHaveBeenCalled()
        dispatch.mockResolvedValue({ status: 'COMPLETED' })
        await useAgentTaskActions(after).declareDelivery({ task, unit: 'x', commit: 'abc1234', outcome: 'DELIVERED', note: null })
        expect(success).toHaveBeenCalledWith(expect.objectContaining({ content: 'Delivery declared: task completed' }))
    })

    it('keeps the messages the panel showed', async () => {
        dispatch.mockResolvedValue({ status: 'QUEUED', role: 'coder' })
        await useAgentTaskActions(async () => {}).humanReview({ task, accept: false, note: '' })
        expect(success).toHaveBeenCalledWith({ content: 'Rejected reviewer pass — back to coder', duration: 3000 })
        dispatch.mockResolvedValue({ status: 'ON_HOLD' })
        await useAgentTaskActions(async () => {}).reopenTask({ task, role: 'coder', reason: 'r' })
        expect(success).toHaveBeenLastCalledWith({ content: 'Reopened to coder, held: the budget does not cover the round', duration: 3000 })
        // the release tolerates a bare task
        await useAgentTaskActions(async () => {}).liftHold(task)
        expect(dispatch).toHaveBeenLastCalledWith('agentTaskOperatorHold', { taskUuid: 't1', hold: false, reason: undefined })
    })
})
