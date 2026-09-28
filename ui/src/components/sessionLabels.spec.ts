// @vitest-environment happy-dom
//
// Sessions and agents told apart (RD2-11): a session is named role first, by its agent's own name and
// short id, and linked to its page -- in the timeline's lanes, the hop rows, the status history, the
// current assignment and the table -- instead of the key's note every session on one key shares.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SessionRef from './SessionRef.vue'
import TaskHops from './task/TaskHops.vue'
import TaskHistory from './task/TaskHistory.vue'
import TaskAssignment from './task/TaskAssignment.vue'
import Timeline from './AiAgentTaskTimelineView.vue'
import Table from './AiAgentTaskTableView.vue'
import { ref } from 'vue'
import ActorRef from './ActorRef.vue'
import TaskHeader from './task/TaskHeader.vue'
import { AGENT_DIR, agentDirectory } from '@/utils/agentSessionLabel'
import { fixtureRoles, richTask } from './task/taskFixtures'

vi.mock('vue-router', async (orig) => ({ ...(await orig() as any), useRouter: () => ({ push: vi.fn() }) }))

const link = { props: ['to'], template: '<a class="rl" :href="to"><slot/></a>' }
const global = { stubs: { RouterLink: link, 'router-link': link } }

const s1 = '11111111-aaaa-bbbb-cccc-000000000001'
const s2 = '22222222-aaaa-bbbb-cccc-000000000002'
const agents = agentDirectory([{ uuid: 'a1', name: 'claude-code', effectiveDisplayName: 'agent board budget key' }])
/** The page provides its agent directory to every session reference under it. */
const withDir = { ...global, provide: { [AGENT_DIR as symbol]: ref(agents) } }

describe('SessionRef', () => {
    it('links the session\'s page with the role first', () => {
        const w = mount(SessionRef, { props: { session: { uuid: s1, role: 'coder', name: 'claude-code' } }, global })
        expect(w.find('a.rl').attributes('href')).toBe(`/aiAgentSession/${s1}`)
        expect(w.text()).toBe('coder · claude-code · 11111111')
    })
})

function worked () {
    return { uuid: 't1', status: 'ASSIGNED', key: 'RD-1', title: 'work', documents: [], statusHistory: [
        { from: 'QUEUED', to: 'ASSIGNED', at: '2026-09-27T10:00:00Z', trigger: 'ASSIGN', actor: { kind: 'SESSION', uuid: s2, name: 'agent board budget key' } },
    ], sessions: [s1, s2], registeredBySession: s1,
    signOffs: [{ role: 'designer', agent: 'a1', session: s1, outcome: 'PASSED', assignedAt: '2026-09-27T08:00:00Z', signedOffAt: '2026-09-27T09:00:00Z', outputs: [] }],
    returns: [],
    assignment: { role: 'coder', agent: 'a1', session: s2, assignedAt: '2026-09-27T10:00:00Z' } }
}

describe('the task page names sessions', () => {
    it('in the hop rows, role first, linked and named by the agent', () => {
        const w = mount(TaskHops, { props: { task: worked(), agentNames: { a1: 'claude-code' }, agentDir: agents }, global })
        const ref1 = w.find(`a.rl[href="/aiAgentSession/${s1}"]`)
        expect(ref1.text()).toBe('designer · claude-code · 11111111')
        expect(w.text().match(/designer/g)?.length).toBe(1, 'the role once, inside the link')
        expect(w.text()).not.toContain('agent board budget key')
    })

    it('in the current assignment, role first', () => {
        const a = mount(TaskAssignment, { props: { task: worked(), agentNames: {}, agentDir: agents }, global })
        expect(a.find('a.rl').attributes('href')).toBe(`/aiAgentSession/${s2}`)
        expect(a.find('a.rl').text()).toBe('coder · claude-code · 22222222')
    })

    // Tester run 1 T-2: the status history's by, registered by and set by showed a bare uuid8.
    it('in the status history: by, registered by and the sessions worked, each role first', () => {
        const h = mount(TaskHistory, { props: { task: worked() }, global: withDir })
        const by = h.find('.shist__by a.rl')
        expect(by.attributes('href')).toBe(`/aiAgentSession/${s2}`)
        expect(by.text()).toBe('coder · claude-code · 22222222')
        const labels = h.findAll('a.rl').map(r => r.text())
        expect(labels.filter(l => l === 'designer · claude-code · 11111111').length).toBe(2, 'registered by, and a worked chip')
        expect(labels).toContain('coder · claude-code · 22222222')
        expect(labels.some(l => /^[0-9a-f]{8}$/.test(l))).toBe(false, 'no bare uuid8')
    })

    it('for set by and held by: the role the session worked on the task, and its agent', () => {
        const actor = { kind: 'SESSION', uuid: s1, name: 'agent board budget key' }
        const set = mount(ActorRef, { props: { actor, task: worked() }, global: withDir })
        expect(set.text()).toBe('designer · claude-code · 11111111')
        const bare = mount(ActorRef, { props: { actor, task: worked() }, global })
        expect(bare.text()).toBe('designer · 11111111 · agent board budget key', 'without a directory: the role still first')
        const held = mount(TaskHeader, { props: { task: richTask({ ...worked(), status: 'ON_HOLD',
            hold: { level: 'OPERATOR', kind: 'MANUAL', reason: 'wait', heldBy: actor, heldAt: '2026-09-27T11:00:00Z' } }),
        tasks: [], roles: fixtureRoles, priorityLevels: [] }, global: withDir })
        expect(held.find('.holdmeta a.rl').text()).toBe('designer · claude-code · 11111111')
    })
})

describe('the board names sessions', () => {
    it('gives two sessions on one key two lanes, each named apart', () => {
        const tasks = [
            { uuid: 't1', key: 'RD-1', title: 'one', signOffs: [{ role: 'coder', agent: 'a1', session: s1, outcome: 'PASSED',
                assignedAt: '2026-09-27T08:00:00Z', signedOffAt: '2026-09-27T09:00:00Z' }], returns: [] },
            { uuid: 't2', key: 'RD-2', title: 'two', signOffs: [{ role: 'coder', agent: 'a1', session: s2, outcome: 'PASSED',
                assignedAt: '2026-09-27T08:30:00Z', signedOffAt: '2026-09-27T09:30:00Z' }], returns: [] },
        ]
        const w = mount(Timeline, { props: { tasks, agentNames: { a1: 'agent board budget key' }, agentDir: agents } })
        const lanes = w.findAll('text.tl-agent')
        expect(lanes.map(l => l.attributes('data-lane')).sort()).toEqual([s1, s2])
        expect(lanes.map(l => l.text()).sort()).toEqual(['coder · claude-code · 11111111', 'coder · claude-code · 22222222'])
    })

    it('names the working session in the table\'s Agent column', () => {
        const w = mount(Table, { props: { tasks: [{ uuid: 't1', key: 'RD-1', title: 'one', status: 'ASSIGNED',
            assignment: { role: 'coder', agent: 'a1', session: s1 } }], agentNames: {}, agentDir: agents }, global })
        const ref = w.find('a.rl[href^="/aiAgentSession/"]')
        expect(ref.attributes('href')).toBe(`/aiAgentSession/${s1}`)
        expect(ref.text()).toBe('coder · claude-code · 11111111')
    })
})
