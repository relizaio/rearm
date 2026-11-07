// @vitest-environment happy-dom
//
// A split child's carried design (task TPF-164, 7.4 T-1 to T-5 and T-7): the header chip "design carried from <parentKey>"
// with its tooltip, muted once the server reads the design as no longer standing; the hop log's own carried-design row
// at the record's at; the "Carried from <parentKey>" group above the task's own documents; " [design from parent]" on a
// flagged proposed child. Each carried pass reads "passed by <role> (<when>)", " by <person>" from reviewedBy only,
// ", decided over by a person" from decidedOver; a session uuid is never rendered.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'
import TaskTitle from './TaskTitle.vue'
import TaskHops from './TaskHops.vue'
import TaskDocuments from './TaskDocuments.vue'
import { carriedDesignFixture, richTask } from './taskFixtures'
import { hopHistory, ts } from '@/utils/agentTaskFormat'
import { carriedDesignLine, carriedDesignTag, carriedDesignTooltip, carriedRolePhrase } from '@/utils/agentCarriedDesign'
import { proposedChildTitle, splitRequestTooltip } from '@/utils/agentTaskLabels'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const stubs = { RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }
const archAt = '2026-09-19T10:00:00Z'
const revAt = '2026-09-19T12:00:00Z'
const carried = (over: Record<string, any> = {}) => carriedDesignFixture(over)
const task = (over: Record<string, any> = {}) => structuredClone(richTask({ carriedDesign: carried(), ...over }))

const mounted: any[] = []
afterEach(() => { while (mounted.length) mounted.pop().unmount() })
const keep = (w: any) => { mounted.push(w); return w }
const title = (t: any) => keep(mount(TaskTitle, { props: { task: t } }))
const hops = (t: any) => keep(mount(TaskHops, { props: { task: t, agentNames: {} } }))
const docs = (t: any) => keep(mount(TaskDocuments, { props: { task: t }, global: { stubs } }))
const chip = (w: any) => w.find('[data-testid="carried-design-chip"]')
const chipType = (w: any) => w.findAllComponents(NTag).find((t: any) => t.attributes('data-testid') === 'carried-design-chip')?.props('type')
const words = (el: any) => el.text().replace(/\s+/g, ' ').trim()
const carriedRows = (w: any) => w.findAll('.hist__row').filter((r: any) => r.find('[data-testid="hop-carried-design"]').exists())

const standingLine = `design carried from TPF-12: ARCHITECTURE round 2, THREAT_MODEL round 1; passes carried: passed by architect (${ts(archAt)}); passed by reviewer (${ts(revAt)})`

describe('T-1 a standing carried design', () => {
    it('shows the header chip, in info, with the row\'s words as its tooltip', () => {
        const t = task()
        const w = title(t)
        expect(chip(w).text()).toBe('design carried from TPF-12')
        expect(chipType(w)).toBe('info')
        expect(chip(w).classes()).not.toContain('tag--history')
        expect(carriedDesignTag(t)).toBe('design carried from TPF-12')
        expect(carriedDesignTooltip(t)).toBe(standingLine)
    })

    it('shows one carried-design row in the hop log, first, at the record\'s at, by the seat, with the rounds and the passes', () => {
        const w = hops(task())
        const rows = carriedRows(w)
        expect(rows).toHaveLength(1)
        expect(w.findAll('.hist__row')[0].find('[data-testid="hop-carried-design"]').exists()).toBe(true)
        const row = rows[0]
        expect(row.find('[data-testid="hop-carried-design"]').text()).toBe('design carried')
        expect(row.find('[data-testid="hop-carried-design"]').classes()).not.toContain('hist__history')
        expect(row.find('.hist__role').text()).toBe('from TPF-12')
        expect(words(row.find('.hist__time'))).toContain(ts('2026-09-20T10:30:00Z'))
        // the seat that split, as the checkpoint row names its settledBy
        expect(words(row.find('.hist__time'))).toMatch(/by .*coordinator$/)
        expect(words(row.find('[data-testid="carried-design"]'))).toBe(standingLine)
        expect(words(row)).not.toContain('undefined')
        expect(words(row)).not.toContain('null')
    })

    it('puts the record in the hop history as its own kind, never a sign-off', () => {
        const t = task()
        const h = hopHistory(t)
        const entries = h.filter(e => e.kind === 'carried_design')
        expect(entries).toEqual([{ kind: 'carried_design', rec: t.carriedDesign, at: '2026-09-20T10:30:00Z' }])
        expect(h.filter(e => e.kind === 'signoff')).toHaveLength(t.signOffs.length)
        expect(h[0].kind).toBe('carried_design')
    })
})

describe('T-2 a carried design that no longer stands', () => {
    const stale = () => task({ carriedDesign: carried({ standing: false,
        roles: [carried().roles[0], { ...carried().roles[1], live: false }] }) })

    it('mutes the chip and names the stale role in the tooltip', () => {
        const t = stale()
        const w = title(t)
        expect(chip(w).text()).toBe('design carried from TPF-12')
        expect(chipType(w)).toBe('default')
        expect(chip(w).classes()).toContain('tag--history')
        const tip = carriedDesignTooltip(t)
        expect(tip).toContain(`passed by reviewer (${ts(revAt)}) (no longer carried)`)
        expect(tip).not.toContain(`passed by architect (${ts(archAt)}) (no longer carried)`)
        expect(tip.endsWith('; the carried design no longer stands: a reopen or a newer round of this task since the split')).toBe(true)
    })

    it('renders the hop row as history with the same per-role phrase', () => {
        const row = carriedRows(hops(stale()))[0]
        expect(row.find('[data-testid="hop-carried-design"]').classes()).toContain('hist__history')
        expect(words(row.find('[data-testid="carried-design"]'))).toContain(`passed by reviewer (${ts(revAt)}) (no longer carried)`)
    })
})

describe('T-3 a task without a carried design', () => {
    it('has no chip, no row and an unchanged Documents section', () => {
        for (const t of [structuredClone(richTask()), structuredClone(richTask({ carriedDesign: null }))]) {
            expect(chip(title(t)).exists()).toBe(false)
            const h = hops(t)
            expect(carriedRows(h)).toHaveLength(0)
            expect(h.find('[data-testid="carried-design"]').exists()).toBe(false)
            expect(hopHistory(t).some(e => e.kind === 'carried_design')).toBe(false)
            const d = docs(t)
            expect(d.find('[data-testid="carried-documents"]').exists()).toBe(false)
            expect(d.html()).toBe(docs(structuredClone(richTask())).html())
            expect(carriedDesignTag(t)).toBeNull()
            expect(carriedDesignTooltip(t)).toBe('')
            expect(carriedDesignLine(t)).toBe('')
        }
    })

    it('keeps the Documents section hidden for a task with no documents and nothing carried', () => {
        expect(docs(structuredClone(richTask({ documents: [] }))).find('.dsec').exists()).toBe(false)
    })
})

describe('T-4 the Documents section\'s carried group', () => {
    it('lists the carried rounds under "Carried from TPF-12" above the task\'s own documents, linking the parent', () => {
        const w = docs(task())
        const group = w.find('[data-testid="carried-documents"]')
        expect(group.exists()).toBe(true)
        expect(words(group.find('.dcarried__h'))).toBe('Carried from TPF-12')
        expect(group.find('[data-testid="carried-parent"]').attributes('href')).toBe('/aiAgentTask/tp')
        const rounds = group.findAll('[data-testid="carried-round"]')
        expect(rounds.map(r => words(r.find('.drow__label')))).toEqual(['ARCHITECTURE round 2', 'THREAT_MODEL round 1'])
        expect(rounds.map(r => r.find('.drow__label').attributes('href'))).toEqual(['/release/show/pa2', '/release/show/pt1'])
        expect(rounds.map(r => r.find('.drow__path').text()))
            .toEqual(['boards/b/design/TPF-12/architecture-2.md', 'boards/b/design/TPF-12/threat_model-1.md'])
        // above the own documents: the group comes before every row that is not one of its own
        const own = w.findAll('.drow').filter(r => !r.attributes('data-testid'))
        expect(own.length).toBeGreaterThan(0)
        for (const r of own) {
            expect(group.element.compareDocumentPosition(r.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        }
    })

    it('shows the section for a child that has published nothing yet', () => {
        const w = docs(task({ documents: [] }))
        expect(w.find('.dsec__h').text()).toBe('Documents')
        expect(w.findAll('[data-testid="carried-round"]')).toHaveLength(2)
    })
})

describe('T-5 a split request\'s proposed children', () => {
    const proposal = (children: any[]) => ({ requestedAt: '2026-09-23T10:00:00Z', role: 'coder', roleUuid: 'r-coder', session: 's3',
        description: 'too big', proposedChildren: children, state: 'REQUESTED', settledBy: null, settledAt: null, note: null })
    const children = [
        { title: 'Backend part', description: null, dependsOnSiblingIndexes: [], workLevel: null, designFromParent: true },
        { title: 'UI part', description: null, dependsOnSiblingIndexes: [0], workLevel: 2, designFromParent: false },
        { title: 'Docs part', description: null, dependsOnSiblingIndexes: [], workLevel: null, designFromParent: null },
    ]

    it('marks only a flagged title with " [design from parent]"', () => {
        const w = hops(structuredClone(richTask({ status: 'AWAITING_COORDINATOR', splitRequest: proposal(children) })))
        const items = w.find('[data-testid="split-request-children"]').findAll('li').map(li => words(li))
        expect(items).toEqual(['Backend part [design from parent]', 'UI part \u00b7 after 1 \u00b7 level 2', 'Docs part'])
        expect(proposedChildTitle(children[0])).toBe('Backend part [design from parent]')
        expect(proposedChildTitle({ title: 'x', designFromParent: 'true' })).toBe('x')
        expect(proposedChildTitle({ title: 'x' })).toBe('x')
    })

    it('marks the chip\'s tooltip the same way', () => {
        const t = richTask({ status: 'AWAITING_COORDINATOR', splitRequest: proposal(children) })
        expect(splitRequestTooltip(t)).toBe('too big; proposes 3 children: Backend part [design from parent]; UI part; Docs part')
    })
})

describe('T-7 who a carried pass names', () => {
    const agentSession = '5e55a0c1-0000-4000-8000-0000000000a1'
    const roles = [
        // an agent's pass as served: a session, no person
        { role: 'architect', roleUuid: 'rc-arch', signedOffAt: archAt, session: agentSession, reviewedBy: null, decidedOver: false, live: true },
        // a blind read of an agent's pass: session and reviewedBy both null
        { role: 'functional_reviewer', roleUuid: 'rc-fr', signedOffAt: archAt, session: null, reviewedBy: null, decidedOver: false, live: true },
        // an agent's REJECTED a person decided over
        { role: 'security_reviewer', roleUuid: 'rc-sr', signedOffAt: revAt, session: '5e55a0c1-0000-4000-8000-0000000000a3', reviewedBy: null, decidedOver: true, live: true },
        // a person's pass
        { role: 'signoff', roleUuid: 'rc-human', signedOffAt: revAt, session: null, reviewedBy: { kind: 'USER', uuid: 'u1', name: 'pavel' }, decidedOver: true, live: true },
    ]
    const t = () => task({ carriedDesign: carried({ roles }) })
    const phrases = [
        `passed by architect (${ts(archAt)})`,
        `passed by functional_reviewer (${ts(archAt)})`,
        `passed by security_reviewer (${ts(revAt)}), decided over by a person`,
        `passed by signoff (${ts(revAt)}) by pavel`,
    ]

    it('reads each pass from reviewedBy and decidedOver only, the same in the row and the tooltip', () => {
        expect(roles.map(carriedRolePhrase)).toEqual(phrases)
        const passes = `passes carried: ${phrases.join('; ')}`
        expect(carriedDesignTooltip(t()).endsWith(passes)).toBe(true)
        const row = carriedRows(hops(t()))[0]
        expect(words(row.find('[data-testid="carried-design"]')).endsWith(passes)).toBe(true)
    })

    it('renders no session uuid anywhere and no placeholder for a blind null', () => {
        const sessions = roles.map(r => r.session).filter((s): s is string => !!s)
        const rendered = [hops(t()).html(), title(t()).html(), docs(t()).html(), carriedDesignTooltip(t())]
        for (const out of rendered) {
            for (const s of sessions) {
                expect(out).not.toContain(s)
                expect(out).not.toContain(s.slice(0, 8))
            }
        }
        expect(carriedRolePhrase(roles[1])).toBe(`passed by functional_reviewer (${ts(archAt)})`)
    })

    it('drops the time, not the words, when the parent sign-off has none', () => {
        expect(carriedRolePhrase({ role: 'architect', signedOffAt: null, session: null, reviewedBy: null, decidedOver: false, live: true }))
            .toBe('passed by architect')
    })
})

describe('the sources', () => {
    const src = (p: string) => readFileSync(join(process.cwd(), p), 'utf8')

    it('render no HTML from the record and read no session in the carried words', () => {
        for (const p of ['src/components/task/TaskHops.vue', 'src/components/task/TaskTitle.vue', 'src/components/task/TaskDocuments.vue']) {
            expect(src(p).match(/v-html|innerHTML/g) ?? []).toHaveLength(0)
        }
        const code = src('src/utils/agentCarriedDesign.ts').split('\n').filter(l => !l.trim().startsWith('//') && !l.trim().startsWith('*'))
        expect(code.join('\n')).not.toMatch(/\.session\b/)
    })

    it('select the carried design on the task read only, once', () => {
        const store = src('src/store.ts')
        const start = store.indexOf('const AGENT_TASK_SELECTION = `')
        const selection = store.slice(start, store.indexOf('`', start + 'const AGENT_TASK_SELECTION = `'.length))
        expect(selection).toContain('carriedDesign { parentTask parentKey rounds { specification release round path } roles { role roleUuid signedOffAt session reviewedBy { kind uuid name } decidedOver live } at by { kind uuid name } standing }')
        expect(store.match(/carriedDesign \{/g)).toHaveLength(1)
    })
})
