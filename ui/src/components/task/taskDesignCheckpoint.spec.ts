// @vitest-environment happy-dom
//
// The design checkpoint in the task header and the hop log (task SPLIT-8, T-18 b and b'): the chip and the hop
// row say the phase checkpointPhase reads, so a PENDING record says "at the checkpoint" only while the task waits
// there, and the row of a PENDING record is the last line.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
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
const rowTagType = (w: any) => w.findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === 'hop-checkpoint')?.props('type')
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
    // The checkpoint's hop row: the .hist__row whose tag carries the testid.
    const row = (w: any) => rows(w).find((r: any) => r.find('[data-testid="hop-checkpoint"]').exists()) ?? w.find('[data-testid="hop-checkpoint"]')

    it('has no row without a record', () => {
        expect(row(hops(richTask())).exists()).toBe(false)
    })

    it('keeps the tag, the role and the time as the row\'s own children, as a sign-off row does (T-40)', () => {
        const w = hops(richTask({ status: 'ASSIGNED', role: 'coder', assignment: { role: 'coder' }, designCheckpoint: rec() }))
        const r = row(w)
        const children = Array.from(r.element.children) as Element[]
        const tag = children.findIndex(c => c.getAttribute('data-testid') === 'hop-checkpoint')
        const role = children.findIndex(c => c.classList.contains('hist__role'))
        const time = children.findIndex(c => c.classList.contains('hist__time'))
        expect(tag).toBeGreaterThan(-1)
        expect(role).toBe(tag + 1)
        expect(time).toBe(role + 1)
        expect(children[role].textContent).toBe('before coder')
        const signoff = rows(w)[0].element as Element
        expect(signoff.querySelector(':scope > .hist__time')).not.toBeNull()
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
        expect(rows(w).at(-1).find('[data-testid="hop-checkpoint"]').exists()).toBe(true)
        expect(rows(w).at(-1).text()).toContain('now with architect')
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

// What the chip and the row read must be selected, or a real task shows neither while every mount above is green.
describe('the store\'s selections', () => {
    const store = readFileSync(join(process.cwd(), 'src/store.ts'), 'utf8')

    it('selects the record on every task query', () => {
        const start = store.indexOf('const AGENT_TASK_SELECTION = `')
        const selection = store.slice(start, store.indexOf('`', start + 'const AGENT_TASK_SELECTION = `'.length))
        expect(selection).toContain('designCheckpoint { reachedAt afterRole beforeRole design state settledBy { kind uuid name } settledAt note }')
    })

    it('selects the flag on the three role queries, not on presets', () => {
        expect(store.match(/^\s*coordinatorCheckpoint$/gm)).toHaveLength(3)
        const presets = store.slice(store.indexOf('agentTaskRolePresetsOfOrg'))
        expect(presets.slice(0, presets.indexOf('`', presets.indexOf('`') + 1))).not.toContain('coordinatorCheckpoint')
    })
})

// The architect's waiver (task TPF-153, T-10): the chip, the hop row and the sign-off's --no-split tag, for a
// waived pass, a blind reader's copy of it, and a stop where the board's switch made the waiver ignored.
describe('the design checkpoint waiver in the header and the hop log', () => {
    const mounted: any[] = []
    afterEach(() => { while (mounted.length) mounted.pop().unmount() })
    const mountTitle = (task: any) => { const w = mount(TaskTitle, { props: { task } }); mounted.push(w); return w }
    const mountHops = (task: any) => { const w = mount(TaskHops, { props: { task, agentNames: {} } }); mounted.push(w); return w }
    const titleChip = (task: any) => {
        const w = mountTitle(task)
        return { chip: w.find('[data-testid="checkpoint-chip"]'),
            type: w.findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === 'checkpoint-chip')?.props('type') }
    }
    const cpRow = (w: any) => w.findAll('.hist__row').find((r: any) => r.find('[data-testid="hop-checkpoint"]').exists())
    const settledAt = '2026-11-05T03:00:00Z'
    const arch = { kind: 'SESSION', uuid: 's-arch', name: 'architect-seat' }
    const waivedTask = () => richTask({
        status: 'QUEUED', role: 'coder', assignment: null,
        signOffs: [...richTask().signOffs, { role: 'architect', agent: 'ag-1', session: 's-arch', signedOffAt: '2026-11-05T02:59:00Z',
            outcome: 'PASSED', note: 'fits one coder pass', noSplit: true }],
        designCheckpoint: rec({ afterRole: 'architect', design: ['a1'], state: 'SENT_ON', settledBy: arch, settledAt,
            note: 'waived by architect: fits one coder pass' }),
        designCheckpointWaiver: { bySession: 's-arch', byAgent: 'ag-1', role: 'architect', roleUuid: 'r-arch',
            at: '2026-11-05T02:59:00Z', designRound: 'a1', note: 'fits one coder pass', standing: true },
    })
    const blindTask = () => {
        const t = structuredClone(waivedTask())
        t.designCheckpoint.settledBy = null
        t.designCheckpoint.note = 'waived by architect'
        Object.assign(t.designCheckpointWaiver, { bySession: null, byAgent: null, note: null })
        Object.assign(t.signOffs.at(-1), { note: null, session: null, agent: null })
        return t
    }
    const ignoredTask = (standing: boolean) => richTask({ status: 'AWAITING_COORDINATOR', role: 'architect', assignment: null,
        designCheckpoint: rec(),
        designCheckpointWaiver: { bySession: 's-arch', byAgent: 'ag-1', role: 'architect', roleUuid: 'r-arch',
            at: '2026-11-05T02:59:00Z', designRound: 'a1', note: 'fits one coder pass', standing } })

    it('the chip names the architect and the note for a waived pass, in default colour', () => {
        const { chip, type } = titleChip(waivedTask())
        expect(chip.text()).toBe('checkpoint waived by architect: fits one coder pass')
        expect(type).toBe('default')
    })

    it('the chip says the waiver was ignored at a stop, and only when the server says it does not stand', () => {
        const ignored = titleChip(ignoredTask(false))
        expect(ignored.chip.text()).toBe('at the design checkpoint (--no-split ignored)')
        expect(ignored.type).toBe('warning')
        expect(titleChip(ignoredTask(true)).chip.text()).toBe('at the design checkpoint')
        const by = { kind: 'USER', uuid: 'u1', name: 'rhythm@example.org' }
        expect(titleChip(richTask({ status: 'ASSIGNED', designCheckpoint: rec({ state: 'SENT_ON', settledBy: by }) })).chip.text())
            .toBe('sent on by rhythm@example.org')
        expect(titleChip(richTask({ designCheckpoint: null })).chip.exists()).toBe(false)
    })

    it('the hop row says who waived it, with the session and the time, and the note once', () => {
        const w = mountHops(waivedTask())
        const r = cpRow(w)
        expect(r.text()).toContain('checkpoint waived by architect: fits one coder pass')
        // The actor-ref renders the session (role, uuid, name) after the phrase's "by".
        expect(r.text()).toMatch(/fits one coder pass by .*architect-seat/)
        expect(r.find('.hist__time').text()).toMatch(/2026|ago|Nov/)
        expect(r.find('.hist__note').exists()).toBe(false)
        expect(r.findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === 'hop-checkpoint')?.props('type')).toBe('default')
    })

    it('a blind reader\'s copy reads without the note, the actor or a placeholder, and keeps the --no-split tag', () => {
        const task = blindTask()
        expect(titleChip(task).chip.text()).toBe('checkpoint waived by architect')
        const w = mountHops(task)
        const text = cpRow(w).text()
        expect(text).toContain('checkpoint waived by architect')
        expect(text.slice(text.indexOf('checkpoint waived by architect') + 'checkpoint waived by architect'.length)).not.toMatch(/^\s*(:|by )/)
        expect(w.text()).not.toMatch(/null|undefined/)
        expect(w.find('[data-testid="hop-no-split"]').exists()).toBe(true)
    })

    it('the ignored stop\'s row says so, in warning', () => {
        const w = mountHops(ignoredTask(false))
        expect(cpRow(w).text()).toContain('waiting for the coordinator; --no-split by architect ignored: the board\'s architectCheckpointWaiver is off')
        expect(cpRow(w).findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === 'hop-checkpoint')?.props('type')).toBe('warning')
        expect(cpRow(mountHops(ignoredTask(true))).text()).not.toContain('--no-split')
    })

    it('the architect\'s sign-off row carries --no-split, and a sign-off without it none', () => {
        const w = mountHops(waivedTask())
        const tags = w.findAll('[data-testid="hop-no-split"]')
        expect(tags).toHaveLength(1)
        expect(tags[0].text()).toBe('--no-split')
        const without = structuredClone(waivedTask())
        without.signOffs.at(-1).noSplit = null
        expect(mountHops(without).find('[data-testid="hop-no-split"]').exists()).toBe(false)
    })
})

// T-11: the chip and the row read what the store selects.
describe('the store\'s waiver selections', () => {
    const store = readFileSync(join(process.cwd(), 'src/store.ts'), 'utf8')
    const literal = (start: number) => store.slice(start, store.indexOf('`', store.indexOf('`', start) + 1))

    it('selects the waiver and the sign-off\'s noSplit on the task, not on the card', () => {
        const task = literal(store.indexOf('const AGENT_TASK_SELECTION = `'))
        expect(task).toContain('designCheckpointWaiver { bySession byAgent role roleUuid at designRound note standing }')
        expect(task.split('\n').find(l => l.includes('signOffs {'))).toContain(' note noSplit promptVersion')
        const card = literal(store.indexOf('const AGENT_TASK_CARD_SELECTION = `'))
        expect(card).not.toContain('designCheckpointWaiver')
        expect(card).not.toContain('noSplit')
    })

    it('selects the switch on the board, as set and in effect', () => {
        const at = store.indexOf('effectiveCoordinatorStopLift')
        const board = store.slice(store.lastIndexOf('`', at), store.indexOf('`', at))
        expect(board).toContain('architectCheckpointWaiver')
        expect(board).toContain('effectiveArchitectCheckpointWaiver')
    })
})
