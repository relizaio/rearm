// @vitest-environment happy-dom
//
// The Agents tab (task RD3-5, replacing Usage): the table of who works the board on top, then the Spend part,
// whose breakdowns add up to the total (RD2-8, sweep UI-08): By role comes from the server's breakdown of the
// same rows as the total, with the coordinator seat and the unattributed rest as rows of their own; "no usage"
// shows only when the window has none. "Top sessions" is gone: the Agents table carries spend per session.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))
const { default: Panel } = await import('./AgentBoardAgentsPanel.vue')

const tooltip = { template: '<span class="tt"><slot name="trigger"/><span class="tt__body"><slot/></span></span>' }
const stubs = { NTooltip: tooltip, Tooltip: tooltip }
const routerLink = { props: ['to'], template: '<a :href="to"><slot/></a>' }
const tokens = { inputTokens: 1000, outputTokens: 100, cacheReadTokens: 0, cacheWriteTokens: 0, requests: 1, turns: 1, reports: 1 }

function serve (breakdown: any, total = 800_000, agents: any[] = []) {
    dispatch.mockImplementation(async (action: string) => action === 'fetchAgentBoardSpendBreakdown' ? breakdown
        : action === 'fetchAgentBoardAgents' ? agents
            : { ...tokens, derivedCostMicros: total, costComplete: breakdown?.costComplete ?? true, byModel: [] })
}

async function mounted () {
    const w = mount(Panel, { props: { boardUuid: 'b1' }, global: { stubs: { ...stubs, RouterLink: routerLink } } })
    await flushPromises()
    return w
}

describe('AgentBoardAgentsPanel: the Spend part', () => {
    beforeEach(() => dispatch.mockReset())

    it('reads the breakdown for the window it reads the total for', async () => {
        serve({ totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }, 0)
        await mounted()
        const usageCall = dispatch.mock.calls.find(c => c[0] === 'fetchAgentBoardUsage')!
        const breakdownCall = dispatch.mock.calls.find(c => c[0] === 'fetchAgentBoardSpendBreakdown')!
        const agentsCall = dispatch.mock.calls.find(c => c[0] === 'fetchAgentBoardAgents')!
        expect(breakdownCall[1]).toEqual(usageCall[1])
        expect(agentsCall[1]).toEqual(usageCall[1])
    })

    it('shows a coordinator-only board\'s spend as a row, not "no usage" beside a non-zero total', async () => {
        // The sweep's case: $0.80 total, open hops, nothing closed.
        serve({ totalMicros: 800_000, costComplete: true, coordinatorEstimateMicros: 500_000, unattributedMicros: 0,
            byRole: [{ role: 'coder', costMicros: 300_000, closedHops: 0, openHops: 1, tokens }],
            bySession: [{ session: 's1', agent: 'a1', role: 'coder', costMicros: 300_000, tokens },
                { session: 's2', agent: 'a2', role: 'coordinator', costMicros: 500_000, tokens }] })
        const w = await mounted()
        expect(w.find('[data-testid="by-role-empty"]').exists()).toBe(false)
        const roles = w.find('[data-testid="by-role"]')
        expect(roles.find('[data-row="role:coder"]').exists()).toBe(true)
        expect(roles.text()).toContain('1 open')
        expect(roles.find('[data-row="coordinator"]').text()).toBe('coordinator estimate')
        expect(w.find('[data-testid="top-sessions"]').exists()).toBe(false)
    })

    it('names the unattributed rest, and says when the figures are a lower bound', async () => {
        serve({ totalMicros: 1_000, costComplete: false, coordinatorEstimateMicros: 0, unattributedMicros: 400,
            byRole: [{ role: 'coder', costMicros: 600, closedHops: 2, openHops: 0, tokens }], bySession: [] }, 1_000)
        const w = await mounted()
        expect(w.find('[data-row="unattributed"]').exists()).toBe(true)
        expect(w.find('[data-testid="lower-bound"]').text()).toBe('Some usage has no price; totals are a lower bound.')
    })

    it('says "no usage" only when the window has none', async () => {
        serve({ totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }, 0)
        const w = await mounted()
        expect(w.find('[data-testid="by-role-empty"]').text()).toBe('No usage in this window.')
        expect(w.find('[data-testid="lower-bound"]').exists()).toBe(false)
    })

    // Tester run 1 T-2: the breakdown failed (T-1's ClassCastException) and the panel printed "no usage"
    // beside a non-zero Total. The failure now shows in the breakdown's place, and the total still reads.
    it('shows why the breakdown could not be read, never "no usage", beside the total', async () => {
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentBoardSpendBreakdown') throw new Error('GraphQL error: Internal server error')
            if (action === 'fetchAgentBoardAgents') return []
            return { ...tokens, derivedCostMicros: 800_000, costComplete: true, byModel: [] }
        })
        const w = await mounted()
        expect(w.find('[data-testid="by-role-error"]').text()).toBe('Could not read the breakdown: Internal server error')
        expect(w.find('[data-testid="by-role-empty"]').exists()).toBe(false)
        expect(w.text()).toContain('$0.80')
    })

    it('clears the error once a later read succeeds', async () => {
        let fail = true
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentBoardSpendBreakdown') {
                if (fail) throw new Error('boom')
                return { totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }
            }
            if (action === 'fetchAgentBoardAgents') return []
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

const empty = { totalMicros: 0, costComplete: true, coordinatorEstimateMicros: 0, unattributedMicros: 0, byRole: [], bySession: [] }
const rows = [
    { session: 'aaaaaaaa-0000-4000-8000-000000000001', agent: 'g1', agentName: 'scully-coder', roles: ['coder'],
        state: { kind: 'WORKING', taskUuid: 't-5', taskKey: 'RD3-5', since: '2026-09-29T08:00:00Z' },
        lastPollAt: '2026-09-29T08:00:00Z', tasksCompleted: 2, costMicros: 1_230_000, cacheShare: 0.6,
        tokens: { inputTokens: 100, outputTokens: 5, cacheReadTokens: 300, cacheWriteTokens: 100 },
        stale: [{ rule: 'hopNoProgress', message: 'RD3-5 stalled: assigned to coder 70 min ago' }] },
    { session: 'bbbbbbbb-0000-4000-8000-000000000002', agent: 'g2', agentName: 'scully-tester', roles: ['tester'],
        state: { kind: 'WAITING', since: '2026-09-29T07:59:00Z' }, lastPollAt: '2026-09-29T07:59:00Z',
        tasksCompleted: 0, costMicros: 0, cacheShare: null, tokens: null, stale: [] },
    { session: 'cccccccc-0000-4000-8000-000000000003', agent: 'g3', agentName: null, roles: ['coder'],
        state: { kind: 'CLOSED', since: '2026-09-28T20:00:00Z', closedBy: { kind: 'USER', name: 'ops' } },
        tasksCompleted: 1, costMicros: 10_000, cacheShare: 0.25,
        tokens: { inputTokens: 300, outputTokens: 1, cacheReadTokens: 100, cacheWriteTokens: 0 }, stale: [] },
]

describe('AgentBoardAgentsPanel: the Agents table', () => {
    beforeEach(() => dispatch.mockReset())

    it('lists the rows in the server\'s order, above the Spend part, with their columns', async () => {
        serve(empty, 0, rows)
        const w = await mounted()
        const table = w.find('[data-testid="agents"]')
        expect(table.exists()).toBe(true)
        expect(table.findAll('[data-session]').map(c => c.attributes('data-session'))).toEqual(rows.map(r => r.session))
        expect(table.findAll('[data-state]').map(c => c.attributes('data-state'))).toEqual(['WORKING', 'WAITING', 'CLOSED'])
        const html = w.html()
        expect(html.indexOf('data-testid="agents"')).toBeLessThan(html.indexOf('data-testid="spend-heading"'))
        expect(w.find('[data-testid="spend-heading"]').text()).toBe('Spend')
        const headers = table.findAll('th').map(h => h.text())
        expect(headers).toEqual(['Agent', 'Roles', 'State', 'Last poll', 'Last offer', 'Done', 'Spend', 'Cache'])
    })

    it('links the agent to its session and a working state to its task', async () => {
        serve(empty, 0, rows)
        const w = await mounted()
        const agentLinks = w.findAll('[data-testid="agent-link"]')
        expect(agentLinks[0].attributes('href')).toBe('/aiAgentSession/aaaaaaaa-0000-4000-8000-000000000001')
        expect(agentLinks[0].text()).toBe('scully-coder')
        expect(agentLinks[2].text()).toBe('cccccccc', )
        const taskLinks = w.findAll('[data-testid="task-link"]')
        expect(taskLinks.length).toBe(1)
        expect(taskLinks[0].attributes('href')).toBe('/aiAgentTask/t-5')
        expect(taskLinks[0].text()).toContain('working RD3-5 since')
    })

    it('marks a stale row with its rule on hover, and shows the cache share with its counts', async () => {
        serve(empty, 0, rows)
        const w = await mounted()
        const marks = w.findAll('[data-testid="stale-mark"]')
        expect(marks.length).toBe(1)
        expect(marks[0].attributes('title')).toBe('hopNoProgress: RD3-5 stalled: assigned to coder 70 min ago')
        const shares = w.findAll('[data-testid="cache-share"]')
        expect(shares.map(s => s.text())).toEqual(['60%', '—', '25%'])
        expect(shares[0].attributes('title')).toBe('300 cache read of 500 (input 100, cache write 100)')
    })

    it('says so when nobody worked or polled the board, and when the rows could not be read', async () => {
        serve(empty, 0, [])
        let w = await mounted()
        expect(w.find('[data-testid="agents-empty"]').text()).toBe('No session has worked or polled this board.')
        dispatch.mockImplementation(async (action: string) => {
            if (action === 'fetchAgentBoardAgents') throw new Error('GraphQL error: Not authorized')
            return action === 'fetchAgentBoardSpendBreakdown' ? empty : { ...tokens, derivedCostMicros: 0, costComplete: true, byModel: [] }
        })
        w = await mounted()
        expect(w.find('[data-testid="agents-error"]').text()).toBe('Could not read the agents: Not authorized')
    })
})
