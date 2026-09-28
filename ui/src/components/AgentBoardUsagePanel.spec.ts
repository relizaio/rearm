// @vitest-environment happy-dom
//
// The Usage tab's breakdowns add up to the total (RD2-8, sweep UI-08): By role and Top sessions come
// from the server's breakdown of the same rows as the total, with the coordinator seat and the
// unattributed rest as rows of their own; "no usage" shows only when the window has none.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))
const { default: Panel } = await import('./AgentBoardUsagePanel.vue')

const tooltip = { template: '<span class="tt"><slot name="trigger"/><span class="tt__body"><slot/></span></span>' }
const stubs = { NTooltip: tooltip, Tooltip: tooltip }
const tokens = { inputTokens: 1000, outputTokens: 100, cacheReadTokens: 0, cacheWriteTokens: 0, requests: 1, turns: 1, reports: 1 }

function serve (breakdown: any, total = 800_000) {
    dispatch.mockImplementation(async (action: string) => action === 'fetchAgentBoardSpendBreakdown' ? breakdown
        : { ...tokens, derivedCostMicros: total, costComplete: breakdown?.costComplete ?? true, byModel: [] })
}

async function mounted () {
    const w = mount(Panel, { props: { boardUuid: 'b1', agentNames: { a1: 'claude' } }, global: { stubs } })
    await flushPromises()
    return w
}

describe('AgentBoardUsagePanel', () => {
    beforeEach(() => dispatch.mockReset())

    it('reads the breakdown for the window it reads the total for', async () => {
        serve({ totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }, 0)
        await mounted()
        const usageCall = dispatch.mock.calls.find(c => c[0] === 'fetchAgentBoardUsage')!
        const breakdownCall = dispatch.mock.calls.find(c => c[0] === 'fetchAgentBoardSpendBreakdown')!
        expect(breakdownCall[1]).toEqual(usageCall[1])
    })

    it('shows a coordinator-only board\'s spend as a row, not "no usage" beside a non-zero total', async () => {
        // The sweep's case: $0.80 total, open hops, nothing closed.
        serve({ totalMicros: 800_000, costComplete: true, coordinatorEstimateMicros: 500_000, unattributedMicros: 0,
            byRole: [{ role: 'coder', costMicros: 300_000, closedHops: 0, openHops: 1, tokens }],
            bySession: [{ session: 's1', agent: 'a1', role: 'coder', costMicros: 300_000, tokens },
                { session: 's2', agent: 'a2', role: 'coordinator', costMicros: 500_000, tokens }] })
        const w = await mounted()
        expect(w.find('[data-testid="by-role-empty"]').exists()).toBe(false)
        expect(w.find('[data-testid="top-sessions-empty"]').exists()).toBe(false)
        const roles = w.find('[data-testid="by-role"]')
        expect(roles.find('[data-row="role:coder"]').exists()).toBe(true)
        expect(roles.text()).toContain('1 open')
        expect(roles.find('[data-row="coordinator"]').text()).toBe('coordinator estimate')
        const sessions = w.find('[data-testid="top-sessions"]')
        expect(sessions.findAll('[data-session]').map(c => c.attributes('data-session'))).toEqual(['s1', 's2'])
        expect(sessions.text()).toContain('claude')
        expect(sessions.text()).toContain('coordinator')
    })

    it('names the unattributed rest, and says when the figures are a lower bound', async () => {
        serve({ totalMicros: 1_000, costComplete: false, coordinatorEstimateMicros: 0, unattributedMicros: 400,
            byRole: [{ role: 'coder', costMicros: 600, closedHops: 2, openHops: 0, tokens }], bySession: [] }, 1_000)
        const w = await mounted()
        expect(w.find('[data-row="unattributed"]').exists()).toBe(true)
        expect(w.find('[data-testid="lower-bound"]').text()).toBe('Some usage has no price; totals are a lower bound.')
        expect(w.find('[data-testid="top-sessions-empty"]').exists()).toBe(true)
    })

    it('says "no usage" only when the window has none', async () => {
        serve({ totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }, 0)
        const w = await mounted()
        expect(w.find('[data-testid="by-role-empty"]').text()).toBe('No usage in this window.')
        expect(w.find('[data-testid="top-sessions-empty"]').exists()).toBe(true)
        expect(w.find('[data-testid="lower-bound"]').exists()).toBe(false)
    })

    // Tester run 1 T-2: the breakdown failed (T-1's ClassCastException) and the panel printed "no usage"
    // beside a non-zero Total. The failure now shows in the breakdown's place, and the total still reads.
    it('shows why the breakdown could not be read, never "no usage", beside the total', async () => {
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentBoardSpendBreakdown') throw new Error('GraphQL error: Internal server error')
            return { ...tokens, derivedCostMicros: 800_000, costComplete: true, byModel: [] }
        })
        const w = await mounted()
        expect(w.find('[data-testid="by-role-error"]').text()).toBe('Could not read the breakdown: Internal server error')
        expect(w.find('[data-testid="top-sessions-error"]').text()).toBe('Could not read the breakdown: Internal server error')
        expect(w.find('[data-testid="by-role-empty"]').exists()).toBe(false)
        expect(w.find('[data-testid="top-sessions-empty"]').exists()).toBe(false)
        expect(w.text()).toContain('$0.80')
    })

    it('clears the error once a later read succeeds', async () => {
        let fail = true
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentBoardSpendBreakdown') {
                if (fail) throw new Error('boom')
                return { totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }
            }
            return { ...tokens, derivedCostMicros: 0, costComplete: true, byModel: [] }
        })
        const w = await mounted()
        expect(w.find('[data-testid="by-role-error"]').exists()).toBe(true)
        fail = false
        await (w.vm as any).load()
        await flushPromises()
        expect(w.find('[data-testid="by-role-error"]').exists()).toBe(false)
        expect(w.find('[data-testid="by-role-empty"]').exists()).toBe(true)
    })
})
