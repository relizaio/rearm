// @vitest-environment happy-dom
//
// Every surface that prints a token total shows the classes behind it (task RD2-3): the session
// and task usage summary, each hop row, and every row of the board's usage tables.
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))

const { default: AgentUsageSummary } = await import('./AgentUsageSummary.vue')
const { default: TaskUsage } = await import('./task/TaskUsage.vue')
const { default: TaskHops } = await import('./task/TaskHops.vue')
const { default: AgentBoardAgentsPanel } = await import('./AgentBoardAgentsPanel.vue')

const tooltip = { template: '<span class="tt"><slot name="trigger"/><span class="tt__body"><slot/></span></span>' }
const stubs = { NTooltip: tooltip, Tooltip: tooltip }
const usage = { reports: 2, requests: 3, turns: 4, inputTokens: 300_000, outputTokens: 120_000,
    cacheReadTokens: 700_000, cacheWriteTokens: 80_000, derivedCostMicros: 1_500_000 }
const split = 'in 300.0k · out 120.0k · cache 700.0k read / 80.0k write'

describe('the token split on every usage surface', () => {
    it('the usage summary (session, agent and board totals)', () => {
        const w = mount(AgentUsageSummary, { props: { usage }, global: { stubs } })
        expect(w.find('.usage-split').text()).toBe(split)
        expect(w.find('.usage-value .tt__body').text()).toBe(split)
    })

    it('the task usage section', () => {
        const w = mount(TaskUsage, { props: { task: { usage, spentMicros: 1_500_000 }, board: {} }, global: { stubs } })
        expect(w.find('.usage-split').text()).toBe(split)
    })

    it('each hop row, on hover', () => {
        const task = { uuid: 't1', documents: [], returns: [],
            signOffs: [{ role: 'coder', outcome: 'PASSED', signedOffAt: '2026-09-27T01:00:00Z', outputs: [], usage }] }
        const w = mount(TaskHops, { props: { task, agentNames: {} } })
        expect(w.find('.hist__usage').attributes('title')).toBe('3 requests, 4 turns\n' + split)
    })

    it('every row of the board usage tables', async () => {
        // The total, and its breakdown by role (RD2-8), each row carrying its classes. The per-session rows
        // moved to the Agents table (RD3-5), which shows a session's spend and cache share, not a token split.
        dispatch.mockImplementation(async (action: string) => action === 'fetchAgentBoardSpendBreakdown'
            ? { totalMicros: 1_500_000, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0,
                byRole: [{ role: 'coder', costMicros: 1_500_000, closedHops: 1, openHops: 0, tokens: usage }],
                bySession: [{ session: 's1', agent: 'a1', role: 'coder', costMicros: 1_500_000, tokens: usage }] }
            : action === 'fetchAgentBoardAgents' ? []
                : { ...usage, byModel: [{ model: 'm1', modelName: 'Opus', ...usage }] })
        const w = mount(AgentBoardAgentsPanel, { props: { boardUuid: 'b1' }, global: { stubs } })
        await flushPromises()
        const cells = w.findAll('.tokensplit')
        // the total's figure, the model row, the role row
        expect(cells.length).toBe(3)
        for (const c of cells) expect(c.find('.tt__body').text()).toBe(split)
    })
})
