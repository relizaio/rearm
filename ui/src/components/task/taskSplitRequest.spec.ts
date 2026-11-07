// @vitest-environment happy-dom
//
// A role's split request in the task header and the hop log (task SPLIT-13, T-18 b' and d): the chip only while the
// request waits at the coordinator, the row after the SPLIT return that wrote it, in the words of its state, with the
// proposed children as a numbered list; every value rendered as text.
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'
import TaskTitle from './TaskTitle.vue'
import TaskHops from './TaskHops.vue'
import { richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const splitAt = '2026-09-23T10:00:00Z'
const description = 'too big: two repositories'
const splitReturn = { role: 'coder', agent: 'a3', session: 's3', reason: 'SPLIT', description, returnedAt: splitAt, outputs: [], usage: null }
const rhythm = { kind: 'USER', uuid: 'u1', name: 'rhythm@example.org' }
function req (over: Record<string, any> = {}) {
    return { requestedAt: splitAt, role: 'coder', roleUuid: 'r-coder', session: 's3', description,
        proposedChildren: [
            { title: 'Backend part', description: 'the server half', dependsOnSiblingIndexes: [], workLevel: null },
            { title: 'UI part', description: null, dependsOnSiblingIndexes: [0], workLevel: 2 },
        ],
        state: 'REQUESTED', settledBy: null, settledAt: null, note: null, ...over }
}
// richTask's own BLOCKED return stays first; the SPLIT return is the newest hop.
function srTask (over: Record<string, any> = {}, rec: any = req()) {
    return richTask({ status: 'AWAITING_COORDINATOR', returns: [...richTask().returns, splitReturn], splitRequest: rec, ...over })
}

const title = (task: any) => mount(TaskTitle, { props: { task } })
const chip = (task: any) => title(task).find('[data-testid="split-request-chip"]')
// The tag's type is a prop naive-ui turns into colours, not a class.
const tagType = (w: any, id: string) => w.findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === id)?.props('type')
const hops = (task: any) => mount(TaskHops, { props: { task, agentNames: {} } })
const rows = (w: any) => w.findAll('.hist__row')
const row = (w: any) => rows(w).find((r: any) => r.find('[data-testid="hop-split-request"]').exists()) ?? w.find('[data-testid="hop-split-request"]')

describe('the header\'s split-request chip', () => {
    it('is absent without a record', () => {
        expect(chip(richTask()).exists()).toBe(false)
        expect(chip(srTask({}, null)).exists()).toBe(false)
    })

    it('says split requested by the role, in warning, while the request waits at the coordinator', () => {
        const task = srTask()
        expect(chip(task).text()).toBe('split requested by coder')
        expect(tagType(title(task), 'split-request-chip')).toBe('warning')
    })

    it('is absent on a hold over the wait, and for a settled or lapsed record back at the coordinator', () => {
        expect(chip(srTask({ status: 'ON_HOLD' })).exists()).toBe(false)
        for (const state of ['DECLINED', 'SPLIT', 'LAPSED']) {
            expect(chip(srTask({}, req({ state }))).exists()).toBe(false)
        }
    })

    it('mounts the redacted shape a blind read returns', () => {
        expect(chip(srTask({}, req({ description: null, session: null, proposedChildren: [] }))).text()).toBe('split requested by coder')
    })
})

describe('the hop log\'s split-request row', () => {
    it('has no row without a record', () => {
        expect(row(hops(richTask())).exists()).toBe(false)
    })

    it('follows the SPLIT return and reads the request, waiting for the coordinator, in warning', () => {
        const w = hops(srTask())
        const all = rows(w)
        expect(all.at(-1).find('[data-testid="hop-split-request"]').exists()).toBe(true)
        expect(all.at(-2).text()).toContain('returned')
        expect(all.at(-2).text()).toContain('split')
        const text = row(w).text()
        expect(text).toContain('split request')
        expect(text).toContain('by coder')
        expect(text).toContain('2 children proposed')
        expect(text).toContain('requested, waiting for the coordinator')
        expect(text).not.toContain(description)
        expect(tagType(w, 'hop-split-request')).toBe('warning')
    })

    it('lists the proposed children, numbered, with their order and level', () => {
        const items = row(hops(srTask())).findAll('[data-testid="split-request-children"] li')
        expect(items).toHaveLength(2)
        expect(items[0].text()).toBe('Backend part')
        expect(items[0].attributes('title')).toBe('the server half')
        expect(items[1].text()).toContain('UI part')
        expect(items[1].text()).toContain('after 1')
        expect(items[1].text()).toContain('level 2')
        expect(items[1].attributes('title')).toBeUndefined()
    })

    it('keeps the tag, the role and the time as the row\'s own children, the list after them (T-40)', () => {
        const children = Array.from(row(hops(srTask())).element.children) as Element[]
        const tag = children.findIndex(c => c.getAttribute('data-testid') === 'hop-split-request')
        const role = children.findIndex(c => c.classList.contains('hist__role'))
        const time = children.findIndex(c => c.classList.contains('hist__time'))
        const list = children.findIndex(c => c.getAttribute('data-testid') === 'split-request-children')
        expect(tag).toBeGreaterThan(-1)
        expect(role).toBe(tag + 1)
        expect(time).toBe(role + 1)
        expect(list).toBeGreaterThan(time)
        expect(children[role].textContent).toBe('by coder')
    })

    it('says requested without the wait once the task left the coordinator on a hold', () => {
        const w = hops(srTask({ status: 'ON_HOLD' }))
        expect(row(w).text()).toContain('requested')
        expect(row(w).text()).not.toContain('waiting for the coordinator')
        expect(tagType(w, 'hop-split-request')).toBe('default')
    })

    it('says who declined it, with the coordinator\'s note, in default colour and with no chip', () => {
        const task = srTask({}, req({ state: 'DECLINED', settledBy: rhythm, settledAt: '2026-09-23T11:00:00Z',
            note: 'one pass is fine: shared fixture lands first' }))
        const w = hops(task)
        expect(row(w).text()).toContain('declined by rhythm@example.org')
        expect(row(w).text()).toContain('one pass is fine: shared fixture lands first')
        expect(tagType(w, 'hop-split-request')).toBe('default')
        expect(chip(task).exists()).toBe(false)
    })

    it('says who split it, with the note, and split alone without an actor', () => {
        const w = hops(srTask({}, req({ state: 'SPLIT', settledBy: rhythm, settledAt: '2026-09-23T11:00:00Z', note: '2 children: X-1, X-2' })))
        expect(row(w).text()).toContain('split by rhythm@example.org')
        expect(row(w).text()).toContain('2 children: X-1, X-2')
        const anon = row(hops(srTask({}, req({ state: 'SPLIT', note: '2 children: X-1, X-2' })))).text()
        expect(anon).toContain('split')
        expect(anon).not.toContain('split by')
    })

    it('reads a lapsed request as history, not a wait, even back at the coordinator (parent F-1)', () => {
        const task = srTask({}, req({ state: 'LAPSED', settledBy: { kind: 'SYSTEM', uuid: null, name: 'routing' },
            settledAt: '2026-09-23T12:00:00Z', note: 'routed to architect: open F-1; back to architect' }))
        const w = hops(task)
        expect(row(w).text()).toContain('lapsed')
        expect(row(w).text()).toContain('routed to architect: open F-1; back to architect')
        expect(row(w).text()).not.toContain('waiting for the coordinator')
        expect(tagType(w, 'hop-split-request')).toBe('default')
        expect(chip(task).exists()).toBe(false)
    })

    it('wraps the writer text of the time line, and names an unknown state by its word, never declined', () => {
        const w = hops(srTask({}, req({ state: 'WITHDRAWN', note: 'x' })))
        expect(row(w).find('.hist__time--wrap').exists()).toBe(true)
        expect(row(w).text()).toContain('withdrawn')
        expect(row(w).text()).not.toContain('declined')
    })

    it('says no children proposed, with no list, for an empty proposal', () => {
        const w = hops(srTask({}, req({ proposedChildren: [] })))
        expect(row(w).text()).toContain('no children proposed')
        expect(w.find('[data-testid="split-request-children"]').exists()).toBe(false)
    })

    it('mounts the redacted shape a blind read returns', () => {
        const w = hops(srTask({}, req({ description: null, session: null, proposedChildren: [] })))
        expect(row(w).text()).toContain('by coder')
        expect(row(w).text()).toContain('no children proposed')
    })
})

// The record is rendered as text only (threat model, parent row "Rendering"), and what the chip and the row read is
// selected on the one task read, not on the board list (architecture 3.2).
describe('the sources', () => {
    const src = (p: string) => readFileSync(join(process.cwd(), p), 'utf8')

    it('render no HTML from a record', () => {
        for (const p of ['src/components/task/TaskHops.vue', 'src/components/task/TaskTitle.vue']) {
            expect(src(p).match(/v-html|innerHTML/g) ?? []).toHaveLength(0)
        }
    })

    it('select the record on the task read and not on the board list', () => {
        const store = src('src/store.ts')
        const start = store.indexOf('const AGENT_TASK_SELECTION = `')
        const selection = store.slice(start, store.indexOf('`', start + 'const AGENT_TASK_SELECTION = `'.length))
        expect(selection).toContain('splitRequest { requestedAt role roleUuid session description proposedChildren { title description dependsOnSiblingIndexes workLevel designFromParent } state settledBy { kind uuid name } settledAt note }')
        const marker = store.indexOf('statusHistory { from to trigger actor { kind } }')
        expect(marker).toBeGreaterThan(-1)
        const open = store.lastIndexOf('`', marker)
        const list = store.slice(open, store.indexOf('`', marker))
        expect(list).not.toContain('splitRequest')
        expect(store.match(/splitRequest \{/g)).toHaveLength(1)
    })
})
