// @vitest-environment happy-dom
//
// The design checkpoint in the task header and the hop log (task SPLIT-8, T-18 b and b'): the chip and the hop
// row say the phase checkpointPhase reads, so a PENDING record says "at the checkpoint" only while the task waits
// there, and the row of a PENDING record is the last line.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'
import TaskTitle from './TaskTitle.vue'
import TaskHops from './TaskHops.vue'
import { richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const reachedAt = '2026-09-23T11:00:00Z'
function rec (over: Record<string, any> = {}) {
    return { reachedAt, afterRole: 'reviewer', beforeRole: 'coder', design: ['a1', 'r3'], state: 'PENDING',
        settledBy: null, settledAt: null, note: null, ...over }
}
const chip = (task: any) => mount(TaskTitle, { props: { task } }).find('[data-testid="checkpoint-chip"]')
// The tag's type is a prop naive-ui turns into colours, not a class.
const chipType = (task: any) => mount(TaskTitle, { props: { task } }).findAllComponents(NTag)
    .find((t: any) => t.attributes('data-testid') === 'checkpoint-chip')?.props('type')
const rowTagType = (w: any) => w.find('[data-testid="hop-checkpoint"]').findComponent(NTag).props('type')
const hops = (task: any) => mount(TaskHops, { props: { task, agentNames: {} } })

describe('the header\'s checkpoint chip', () => {
    it('is absent without a record', () => {
        expect(chip(richTask({ designCheckpoint: null })).exists()).toBe(false)
        expect(chip(richTask()).exists()).toBe(false)
    })

    it('says at the design checkpoint, in warning, while the task waits at the coordinator', () => {
        const task = richTask({ status: 'AWAITING_COORDINATOR', role: 'reviewer', designCheckpoint: rec() })
        expect(chip(task).text()).toBe('at the design checkpoint')
        expect(chipType(task)).toBe('warning')
    })

    it('says design checkpoint ahead for a PENDING record on a task sent back to the architect', () => {
        const task = richTask({ status: 'ASSIGNED', role: 'architect', assignment: { role: 'architect' }, designCheckpoint: rec() })
        expect(chip(task).text()).toBe('design checkpoint ahead')
        expect(chipType(task)).toBe('default')
    })

    it('is absent for a PENDING record on a COMPLETED task, and on a task already with the checkpoint\'s role', () => {
        expect(chip(richTask({ status: 'COMPLETED', designCheckpoint: rec() })).exists()).toBe(false)
        expect(chip(richTask({ status: 'ASSIGNED', role: 'coder', assignment: { role: 'coder' }, designCheckpoint: rec() })).exists()).toBe(false)
        // richTask's default: ON_HOLD with role coder and no assignment -- the same lapsed case.
        expect(chip(richTask({ designCheckpoint: rec() })).exists()).toBe(false)
    })

    it('names who sent the task on and how many children a split made', () => {
        const by = { kind: 'USER', uuid: 'u1', name: 'rhythm@example.org' }
        expect(chip(richTask({ status: 'ASSIGNED', designCheckpoint: rec({ state: 'SENT_ON', settledBy: by }) })).text())
            .toBe('sent on by rhythm@example.org')
        expect(chip(richTask({ status: 'COMPLETED', designCheckpoint: rec({ state: 'SENT_ON' }) })).text()).toBe('sent on')
        expect(chip(richTask({ status: 'QUEUED', childTasks: ['a', 'b'], designCheckpoint: rec({ state: 'SPLIT', settledBy: by }) })).text())
            .toBe('split into 2')
    })
})

describe('the hop log\'s checkpoint row', () => {
    const rows = (w: any) => w.findAll('.hist__row')
    const row = (w: any) => w.find('[data-testid="hop-checkpoint"]')

    it('has no row without a record', () => {
        expect(row(hops(richTask())).exists()).toBe(false)
    })

    it('waits for the coordinator, in warning, as the last line', () => {
        const w = hops(richTask({ status: 'AWAITING_COORDINATOR', role: 'reviewer', designCheckpoint: rec() }))
        expect(w.findAll('[data-testid="hop-checkpoint"]')).toHaveLength(1)
        expect(row(w).text()).toContain('design checkpoint')
        expect(row(w).text()).toContain('before coder')
        expect(row(w).text()).toContain('waiting for the coordinator')
        expect(rowTagType(w)).toBe('warning')
        expect(rows(w).at(-1).find('[data-testid="hop-checkpoint"]').exists()).toBe(true)
    })

    it('renders a re-arrived PENDING record last, now with the architect', () => {
        const task = richTask({
            status: 'ASSIGNED', role: 'architect', assignment: { role: 'architect' },
            signOffs: [...richTask().signOffs,
                { role: 'architect', agent: 'a1', session: 's1', signedOffAt: '2026-09-24T10:00:00Z', outcome: 'PASSED', note: 'round 2' },
                { role: 'reviewer', agent: 'a2', session: 's2', signedOffAt: '2026-09-24T12:00:00Z', outcome: 'PASSED', note: 'ok' }],
            designCheckpoint: rec(),
        })
        const w = hops(task)
        expect(rows(w).at(-1).find('[data-testid="hop-checkpoint"]').text()).toContain('now with architect')
    })

    it('says closed from the checkpoint, in default colour, on a COMPLETED task', () => {
        const w = hops(richTask({ status: 'COMPLETED', designCheckpoint: rec() }))
        expect(row(w).text()).toContain('closed from the checkpoint')
        expect(rowTagType(w)).toBe('default')
    })

    it('says not settled for a lapsed record, as the last line, and not now with coder', () => {
        const w = hops(richTask({ status: 'ASSIGNED', role: 'coder', assignment: { role: 'coder' },
            signOffs: [...richTask().signOffs, { role: 'coder', agent: 'a3', session: 's3', signedOffAt: '2026-09-24T10:00:00Z', outcome: 'PASSED' }],
            designCheckpoint: rec() }))
        expect(row(w).text()).toContain('design checkpoint')
        expect(row(w).text()).toContain('before coder')
        expect(row(w).text()).toContain('not settled; the task went on to coder')
        expect(row(w).text()).not.toContain('now with coder')
        expect(rowTagType(w)).toBe('default')
        expect(rows(w).at(-1).find('[data-testid="hop-checkpoint"]').exists()).toBe(true)
    })

    it('says who sent the task on, with the note, and sent on alone without an actor', () => {
        const by = { kind: 'USER', uuid: 'u1', name: 'rhythm@example.org' }
        const w = hops(richTask({ status: 'ASSIGNED', designCheckpoint: rec({ state: 'SENT_ON', settledBy: by,
            settledAt: '2026-09-23T12:00:00Z', note: 'authorized for coder' }) }))
        expect(row(w).text()).toContain('sent on by rhythm@example.org')
        expect(w.text()).toContain('authorized for coder')
        const anon = hops(richTask({ status: 'ASSIGNED', designCheckpoint: rec({ state: 'SENT_ON' }) }))
        expect(row(anon).text()).toContain('sent on')
        expect(row(anon).text()).not.toContain('sent on by')
        const split = hops(richTask({ status: 'QUEUED', designCheckpoint: rec({ state: 'SPLIT', settledBy: by, note: '2 children: X-1, X-2' }) }))
        expect(row(split).text()).toContain('split by rhythm@example.org')
    })
})
