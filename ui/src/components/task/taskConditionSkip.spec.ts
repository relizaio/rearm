// @vitest-environment happy-dom
//
// A role routing passed over because its runWhen was not met (task TPF-156, T-113): one muted row per entry of
// rolesSkippedByCondition in the hop log, at its at, with the literal "by routing", routing's reason and the waived
// outputs; history with "(since ran)" when the server reads the entry as no longer live; no chip in the header.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { resolve } from 'path'
import { mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'
import { compile } from 'sass'
import postcss from 'postcss'
import TaskTitle from './TaskTitle.vue'
import TaskHops from './TaskHops.vue'
import { richTask } from './taskFixtures'
import { ts } from '@/utils/agentTaskFormat'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const skipAt = '2026-11-05T13:00:00Z'
const entry = (over: Record<string, any> = {}) => ({
    role: 'mutant_coder', roleUuid: 'r1', reason: 'runWhen not met: no mutation tag', at: skipAt,
    by: { kind: 'SYSTEM', uuid: null, name: 'routing' }, waives: [] as string[], live: true, ...over,
})
const coder = { role: 'coder', agent: 'a3', session: 's3', assignedAt: '2026-11-05T11:00:00Z', signedOffAt: '2026-11-05T12:00:00Z', outcome: 'PASSED', note: null, outputs: [], usage: null }
const tester = { role: 'tester', agent: 'a4', session: 's4', assignedAt: '2026-11-05T13:30:00Z', signedOffAt: '2026-11-05T14:00:00Z', outcome: 'PASSED', note: null, outputs: [], usage: null }
const mutantSignOff = (signedOffAt: string) => ({ role: 'mutant_coder', agent: 'a5', session: 's5', assignedAt: signedOffAt,
    signedOffAt, outcome: 'PASSED', note: null, outputs: [], usage: null })
const task = (over: Record<string, any> = {}) => structuredClone(richTask({ rolesSkippedByCondition: [entry()], ...over }))

const mounted: any[] = []
afterEach(() => { while (mounted.length) mounted.pop().unmount() })
const hops = (t: any) => { const w = mount(TaskHops, { props: { task: t, agentNames: {} } }); mounted.push(w); return w }
const title = (t: any) => { const w = mount(TaskTitle, { props: { task: t } }); mounted.push(w); return w }
const skipRows = (w: any) => w.findAll('.hist__row').filter((r: any) => r.find('[data-testid="hop-passed-over"]').exists())
const rowIndex = (w: any, pred: (r: any) => boolean) => w.findAll('.hist__row').findIndex(pred)
const isSkip = (r: any) => r.find('[data-testid="hop-passed-over"]').exists()
const hasHistory = (r: any) => r.findAll('.hist__history').length
const tagType = (w: any) => w.findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === 'hop-passed-over')?.props('type')
const words = (r: any) => r.text().replace(/\s+/g, ' ')

describe('the hop log\'s passed-over row', () => {
    it('(a) renders one muted row: tag, role, time and routing\'s reason, as text', () => {
        const w = hops(task())
        const rows = skipRows(w)
        expect(rows).toHaveLength(1)
        const row = rows[0]
        expect(row.find('[data-testid="hop-passed-over"]').text()).toBe('passed over')
        const text = words(row)
        expect(text).toContain('mutant_coder')
        expect(text).toContain('by routing: runWhen not met: no mutation tag')
        expect(text).toContain(ts(skipAt))
        expect(text).toContain('\u00b7 by routing')
        expect(text).not.toContain('undefined')
        expect(text).not.toContain('null')
        expect(row.find('.hist__note').exists()).toBe(false)
        expect(tagType(w)).toBe('default')
        expect(row.findAll('.hist__muted')).toHaveLength(3)
        expect(row.find('.hist__role').classes()).toContain('hist__muted')
        expect(row.find('.hist__time').classes()).toContain('hist__muted')
        expect(row.find('[data-testid="hop-passed-over"]').classes()).toContain('hist__muted')
    })

    it('(b) the waived outputs end the words', () => {
        const row = skipRows(hops(task({ rolesSkippedByCondition: [entry({ waives: ['BOARD_TEST_REPORT'] })] })))[0]
        expect(words(row).trim().endsWith('; waives BOARD_TEST_REPORT')).toBe(true)
        const two = skipRows(hops(task({ rolesSkippedByCondition: [entry({ waives: ['BOARD_TEST_REPORT', 'ARCHITECTURE'] })] })))[0]
        expect(words(two).trim().endsWith('; waives BOARD_TEST_REPORT, ARCHITECTURE')).toBe(true)
    })

    it('(c) two entries render two rows at their at, between the coder\'s and the tester\'s sign-offs', () => {
        const w = hops(task({
            signOffs: [coder, tester], returns: [],
            rolesSkippedByCondition: [entry(), entry({ role: 'mutant_tester', waives: ['BOARD_TEST_REPORT'] })],
        }))
        const order = w.findAll('.hist__row').map((r: any) => isSkip(r) ? 'skip:' + r.find('.hist__role').text()
            : 'hop:' + (r.find('.hist__agent').text() || r.text()))
        expect(order).toHaveLength(4)
        expect(order[1]).toBe('skip:mutant_coder')
        expect(order[2]).toBe('skip:mutant_tester')
        expect(order[0]).toContain('coder')
        expect(order[3]).toContain('tester')
    })

    it('(d) no row without entries', () => {
        expect(skipRows(hops(structuredClone(richTask())))).toHaveLength(0)
        expect(skipRows(hops(task({ rolesSkippedByCondition: [] })))).toHaveLength(0)
    })

    it('(e) a null actor and a null role uuid render the same row: the words are the literal by routing', () => {
        const base = words(skipRows(hops(task()))[0])
        const blind = words(skipRows(hops(task({ rolesSkippedByCondition: [entry({ by: null, roleUuid: null })] })))[0])
        expect(blind).toBe(base)
        expect(blind).toContain('by routing: runWhen not met')
    })

    it('(f) the page renders text only, and the shared styles declare the muted and history forms', () => {
        const src = readFileSync(resolve(__dirname, 'TaskHops.vue'), 'utf8')
        expect(src).not.toContain('v-html')
        const css = compile(resolve(__dirname, 'taskSections.scss')).css
        const declarations = (selector: string) => {
            const out: Record<string, string> = {}
            postcss.parse(css).walkRules(rule => {
                if (rule.selectors.includes(selector)) rule.walkDecls(d => { out[d.prop] = d.value })
            })
            return out
        }
        expect(declarations('.hist__muted').opacity).toBe('0.72')
        expect(declarations('.hist__history').opacity).toBe('0.5')
    })

    it('(g) the header gains no chip', () => {
        const count = (w: any) => w.findAll('[data-testid]').length
        expect(count(title(task()))).toBe(count(title(structuredClone(richTask()))))
    })
})

describe('the passed-over row reads live as served', () => {
    it('(h) a live entry renders muted, no history, no marker, though its role signed off after it', () => {
        const t = task({ signOffs: [coder, mutantSignOff('2026-11-05T13:30:00Z')], returns: [] })
        const row = skipRows(hops(t))[0]
        expect(hasHistory(row)).toBe(0)
        expect(words(row)).not.toContain('since ran')
    })

    it('(h) a dead entry renders as history with the marker last, though no sign-off of its role followed it', () => {
        const t = task({ signOffs: [coder, mutantSignOff('2026-11-05T12:30:00Z')], returns: [],
            rolesSkippedByCondition: [entry({ live: false })] })
        const row = skipRows(hops(t))[0]
        expect(row.find('[data-testid="hop-passed-over"]').classes()).toContain('hist__history')
        expect(row.find('.hist__role').classes()).toContain('hist__history')
        expect(row.find('.hist__time').classes()).toContain('hist__history')
        expect(words(row).trim().endsWith('runWhen not met: no mutation tag (since ran)')).toBe(true)
        const both = skipRows(hops(structuredClone({ ...t, rolesSkippedByCondition: [entry({ live: false, waives: ['BOARD_TEST_REPORT'] })] })))[0]
        expect(words(both).trim().endsWith('; waives BOARD_TEST_REPORT (since ran)')).toBe(true)
    })

    it('(h) an entry without a live key renders as live', () => {
        const rec: any = entry()
        delete rec.live
        const row = skipRows(hops(task({ rolesSkippedByCondition: [rec] })))[0]
        expect(hasHistory(row)).toBe(0)
        expect(words(row)).not.toContain('since ran')
    })

    it('(h) the window: a passed-over role now running on an explicit run is still live, above its hop', () => {
        const t = task({
            status: 'ASSIGNED', role: 'mutant_coder', hold: null,
            assignment: { session: 's9', agent: 'a9', role: 'mutant_coder', assignedAt: '2026-11-05T13:10:00Z', promptVersion: null },
            signOffs: [coder, tester], returns: [],
            rolesSkippedByCondition: [entry({ live: true })],
        })
        const w = hops(t)
        const row = skipRows(w)[0]
        expect(row.findAll('.hist__muted')).toHaveLength(3)
        expect(hasHistory(row)).toBe(0)
        expect(words(row)).not.toContain('since ran')
        const skip = rowIndex(w, isSkip)
        const testerRow = rowIndex(w, (r: any) => !isSkip(r) && r.text().includes('tester'))
        expect(skip).toBeGreaterThan(-1)
        expect(skip).toBeLessThan(testerRow)
    })
})
